package com.oai.wechatreadonly;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.concurrent.atomic.AtomicBoolean;

public class WeChatEventSourceService extends AccessibilityService {
    protected static final String WECHAT_PACKAGE = "com.tencent.mm";
    public static volatile WeChatAccessibilityService INSTANCE;

    protected ChatDbHelper db;
    private TextRecognizer recognizer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private final AtomicBoolean autoRunning = new AtomicBoolean(false);
    private volatile boolean swipeScheduled = false;
    private volatile int autoPage = 0;
    private volatile int autoMax = 0;
    private volatile long lastWechatEventAt = 0L;
    private volatile long lastScreenshotAt = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        db = new ChatDbHelper(this);
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        if (this instanceof WeChatAccessibilityService) {
            INSTANCE = (WeChatAccessibilityService) this;
        }
        CapturePrefs.setStatus(this, "无障碍服务已连接 · v0.4屏幕OCR模式");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!WECHAT_PACKAGE.contentEquals(event.getPackageName())) return;
        if (!CapturePrefs.isEnabled(this)) return;

        lastWechatEventAt = System.currentTimeMillis();
        requestOcrScreenshot("微信事件");

        if (autoRunning.get() && !swipeScheduled) {
            scheduleNextSwipe();
        }
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        autoRunning.set(false);
        if (recognizer != null) recognizer.close();
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    public int captureVisible() {
        requestOcrScreenshot("手动OCR");
        return 0;
    }

    private void requestOcrScreenshot(String source) {
        if (Build.VERSION.SDK_INT < 30) {
            CapturePrefs.setStatus(this, "v0.4需要Android 11或以上");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScreenshotAt < 1050) return;
        if (!ocrBusy.compareAndSet(false, true)) return;
        lastScreenshotAt = now;

        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult screenshot) {
                    HardwareBuffer buffer = screenshot.getHardwareBuffer();
                    ColorSpace colorSpace = screenshot.getColorSpace();
                    Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                    if (hw == null) {
                        buffer.close();
                        ocrBusy.set(false);
                        CapturePrefs.setStatus(WeChatEventSourceService.this, "v0.4截图成功但Bitmap为空");
                        return;
                    }
                    Bitmap bitmap = hw.copy(Bitmap.Config.ARGB_8888, false);
                    buffer.close();
                    runOcr(bitmap, source);
                }

                @Override
                public void onFailure(int errorCode) {
                    ocrBusy.set(false);
                    CapturePrefs.setStatus(WeChatEventSourceService.this,
                            "v0.4截图失败 code=" + errorCode + "；确认无障碍截图权限已开启");
                }
            });
        } catch (Throwable t) {
            ocrBusy.set(false);
            CapturePrefs.setStatus(this, "v0.4截图异常：" + t.getClass().getSimpleName());
        }
    }

    private void runOcr(Bitmap bitmap, String source) {
        if (recognizer == null) {
            ocrBusy.set(false);
            return;
        }
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(image)
                .addOnSuccessListener(result -> {
                    int added = storeOcrLines(result, bitmap.getWidth(), bitmap.getHeight());
                    String contact = safeContact();
                    CapturePrefs.setStatus(this,
                            "v0.4 OCR · 本屏新增 " + added + " 条；共 " + db.count(contact) + " 条候选");
                })
                .addOnFailureListener(e -> CapturePrefs.setStatus(this,
                        "v0.4 OCR失败：" + e.getClass().getSimpleName()))
                .addOnCompleteListener(task -> {
                    bitmap.recycle();
                    ocrBusy.set(false);
                });
    }

    private int storeOcrLines(Text result, int width, int height) {
        int added = 0;
        String contact = safeContact();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String text = line.getText() == null ? "" : line.getText().trim();
                Rect box = line.getBoundingBox();
                if (!isChatCandidate(text, box, width, height)) continue;

                String sender = classifySender(box, width);
                if (db.insert(contact, sender, text, box == null ? 0 : box.top, "OCR")) {
                    added++;
                }
            }
        }
        return added;
    }

    private String classifySender(Rect box, int width) {
        if (box == null) return "系统/未知";
        if (box.left < width * 0.30f && box.right < width * 0.72f) return "对方";
        if (box.right > width * 0.70f && box.left > width * 0.28f) return "我";
        float cx = box.exactCenterX();
        if (cx < width * 0.40f) return "对方";
        if (cx > width * 0.60f) return "我";
        return "系统/未知";
    }

    private boolean isChatCandidate(String text, Rect box, int width, int height) {
        if (text == null || text.isEmpty() || text.length() > 3000 || box == null) return false;
        if (box.bottom < height * 0.075f || box.top > height * 0.90f) return false;
        String[] ignoreExact = {
                "微信", "通讯录", "发现", "我", "发送", "按住 说话", "语音输入", "表情",
                "搜索", "更多", "视频号", "朋友圈"
        };
        for (String x : ignoreExact) if (text.equals(x)) return false;
        if (text.equals(safeContact())) return false;
        if (text.matches("^(\\d{1,2}:\\d{2}|\\d{1,2}月\\d{1,2}日.*|昨天.*|周[一二三四五六日].*)$")) {
            return false;
        }
        if (box.height() < 12 || box.width() < 10) return false;
        return true;
    }

    private String safeContact() {
        String contact = CapturePrefs.getContact(this);
        return contact == null || contact.trim().isEmpty() ? "未命名联系人" : contact.trim();
    }

    public void startAuto(int pages) {
        CapturePrefs.setEnabled(this, true);
        autoMax = Math.max(1, Math.min(300, pages));
        autoPage = 0;
        swipeScheduled = false;
        autoRunning.set(true);
        CapturePrefs.setStatus(this, "v0.4自动OCR已待命：切回微信聊天页后会自动截图+上翻");
        handler.postDelayed(() -> {
            if (System.currentTimeMillis() - lastWechatEventAt < 4000) {
                requestOcrScreenshot("自动开始");
                scheduleNextSwipe();
            }
        }, 1200);
    }

    public void stopAuto() {
        autoRunning.set(false);
        swipeScheduled = false;
        CapturePrefs.setStatus(this, "v0.4已停止自动OCR");
    }

    private void scheduleNextSwipe() {
        if (!autoRunning.get() || swipeScheduled) return;
        if (autoPage >= autoMax) {
            autoRunning.set(false);
            CapturePrefs.setStatus(this, "v0.4自动采集完成，共翻 " + autoPage + " 页");
            return;
        }
        swipeScheduled = true;
        handler.postDelayed(() -> {
            swipeScheduled = false;
            if (!autoRunning.get()) return;
            if (System.currentTimeMillis() - lastWechatEventAt > 5000) {
                CapturePrefs.setStatus(this, "v0.4等待微信聊天页回到前台");
                scheduleNextSwipe();
                return;
            }
            dispatchOlderSwipe();
        }, 1450);
    }

    private void dispatchOlderSwipe() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Path p = new Path();
        p.moveTo(w * 0.50f, h * 0.34f);
        p.lineTo(w * 0.50f, h * 0.78f);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 420);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                autoPage++;
                handler.postDelayed(() -> {
                    requestOcrScreenshot("自动第" + autoPage + "页");
                    scheduleNextSwipe();
                }, 850);
            }

            @Override public void onCancelled(GestureDescription gestureDescription) {
                autoRunning.set(false);
                CapturePrefs.setStatus(WeChatEventSourceService.this, "v0.4自动上翻被系统取消");
            }
        }, null);
    }

    public String dumpTree() {
        return "# v0.4 no longer depends on WeChat text nodes\n"
                + "# capture mode: AccessibilityService.takeScreenshot + ML Kit Chinese OCR\n"
                + "# last_wechat_event_at=" + lastWechatEventAt + "\n"
                + "# total_for_contact=" + (db == null ? 0 : db.count(safeContact())) + "\n";
    }
}
