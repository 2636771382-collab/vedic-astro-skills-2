package com.oai.wechatreadonly;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Color;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class WeChatEventSourceService extends AccessibilityService {
    protected static final String WECHAT_PACKAGE = "com.tencent.mm";
    public static volatile WeChatAccessibilityService INSTANCE;

    private static final Pattern TIME_PATTERN = Pattern.compile(
            "^(?:\\d{1,2}:\\d{2}|昨天(?:\\s*\\d{1,2}:\\d{2})?|前天(?:\\s*\\d{1,2}:\\d{2})?|" +
                    "周[一二三四五六日天](?:\\s*\\d{1,2}:\\d{2})?|" +
                    "\\d{1,2}月\\d{1,2}日(?:\\s*\\d{1,2}:\\d{2})?|" +
                    "\\d{4}年\\d{1,2}月\\d{1,2}日(?:\\s*\\d{1,2}:\\d{2})?)$");

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
    private volatile String lastWechatTime = null;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        db = new ChatDbHelper(this);
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        if (this instanceof WeChatAccessibilityService) {
            INSTANCE = (WeChatAccessibilityService) this;
        }
        CapturePrefs.setStatus(this, "无障碍服务已连接 · v0.5气泡识别OCR");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!WECHAT_PACKAGE.contentEquals(event.getPackageName())) return;
        if (!CapturePrefs.isEnabled(this)) return;

        lastWechatEventAt = System.currentTimeMillis();
        requestOcrScreenshot("微信事件");

        if (autoRunning.get() && !swipeScheduled) scheduleNextSwipe();
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
            CapturePrefs.setStatus(this, "v0.5需要Android 11或以上");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScreenshotAt < 1000) return;
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
                        CapturePrefs.setStatus(WeChatEventSourceService.this, "v0.5截图成功但Bitmap为空");
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
                            "v0.5截图失败 code=" + errorCode);
                }
            });
        } catch (Throwable t) {
            ocrBusy.set(false);
            CapturePrefs.setStatus(this, "v0.5截图异常：" + t.getClass().getSimpleName());
        }
    }

    private void runOcr(Bitmap bitmap, String source) {
        if (recognizer == null) {
            ocrBusy.set(false);
            bitmap.recycle();
            return;
        }
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(image)
                .addOnSuccessListener(result -> {
                    int added = storeOcrBlocks(result, bitmap, source);
                    String contact = safeContact();
                    CapturePrefs.setStatus(this,
                            "v0.5 OCR · 本屏新增 " + added + " 条消息；共 " + db.count(contact) + " 条");
                })
                .addOnFailureListener(e -> CapturePrefs.setStatus(this,
                        "v0.5 OCR失败：" + e.getClass().getSimpleName()))
                .addOnCompleteListener(task -> {
                    bitmap.recycle();
                    ocrBusy.set(false);
                });
    }

    private int storeOcrBlocks(Text result, Bitmap bitmap, String source) {
        List<BlockItem> items = new ArrayList<>();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        for (Text.TextBlock block : result.getTextBlocks()) {
            Rect box = block.getBoundingBox();
            String raw = block.getText() == null ? "" : block.getText().trim();
            if (raw.isEmpty() || box == null) continue;
            if (box.bottom < height * 0.075f || box.top > height * 0.91f) continue;

            String normalized = normalizeBlockText(raw);
            if (normalized.isEmpty()) continue;

            if (isTimeText(normalized)) {
                items.add(BlockItem.time(normalized, box));
                continue;
            }

            BubbleClass bc = classifyBubble(bitmap, box);
            if (bc.sender.equals("系统/未知")) {
                // Strict by design: v0.5 only keeps text that actually sits on a likely chat bubble.
                continue;
            }
            if (isUiJunk(normalized)) continue;
            items.add(BlockItem.message(normalized, box, bc.sender, bc.confidence));
        }

        items.sort(Comparator.comparingInt(a -> a.box.top));
        List<BlockItem> merged = mergeAdjacent(items);

        String contact = safeContact();
        String captureId = UUID.randomUUID().toString();
        long capturedAt = System.currentTimeMillis();
        int seq = 0;
        int addedMessages = 0;

        for (BlockItem item : merged) {
            if (item.kind.equals("time")) {
                lastWechatTime = item.text;
                db.insertRecord(contact, "系统", "time", item.text, item.text,
                        capturedAt, captureId, seq++, item.box.left, item.box.top,
                        item.box.right, item.box.bottom, 1.0f, source);
            } else {
                if (db.insertRecord(contact, item.sender, "message", item.text, lastWechatTime,
                        capturedAt, captureId, seq++, item.box.left, item.box.top,
                        item.box.right, item.box.bottom, item.confidence, source)) {
                    addedMessages++;
                }
            }
        }
        return addedMessages;
    }

    private List<BlockItem> mergeAdjacent(List<BlockItem> items) {
        List<BlockItem> out = new ArrayList<>();
        for (BlockItem cur : items) {
            if (cur.kind.equals("time")) {
                out.add(cur);
                continue;
            }
            if (out.isEmpty()) {
                out.add(cur);
                continue;
            }
            BlockItem prev = out.get(out.size() - 1);
            if (!prev.kind.equals("message") || !prev.sender.equals(cur.sender)) {
                out.add(cur);
                continue;
            }

            int gap = cur.box.top - prev.box.bottom;
            int typicalH = Math.max(24, Math.min(prev.box.height(), cur.box.height()));
            boolean near = gap >= -8 && gap <= typicalH;
            boolean horizontallyRelated = overlapRatio(prev.box, cur.box) > 0.20f
                    || Math.abs(prev.box.left - cur.box.left) < 90
                    || Math.abs(prev.box.right - cur.box.right) < 90;

            if (near && horizontallyRelated) {
                prev.text = smartJoin(prev.text, cur.text);
                prev.box.union(cur.box);
                prev.confidence = Math.min(prev.confidence, cur.confidence);
            } else {
                out.add(cur);
            }
        }
        return out;
    }

    private float overlapRatio(Rect a, Rect b) {
        int left = Math.max(a.left, b.left);
        int right = Math.min(a.right, b.right);
        int overlap = Math.max(0, right - left);
        int denom = Math.max(1, Math.min(a.width(), b.width()));
        return overlap / (float) denom;
    }

    private String smartJoin(String a, String b) {
        if (a.endsWith("-") || a.endsWith("—")) return a + b;
        if (looksLatinTail(a) && looksLatinHead(b)) return a + " " + b;
        return a + b;
    }

    private boolean looksLatinTail(String s) {
        return !s.isEmpty() && Character.toString(s.charAt(s.length() - 1)).matches("[A-Za-z0-9]");
    }

    private boolean looksLatinHead(String s) {
        return !s.isEmpty() && Character.toString(s.charAt(0)).matches("[A-Za-z0-9]");
    }

    private String normalizeBlockText(String raw) {
        String x = raw.replace("\r", "").trim();
        String[] lines = x.split("\\n+");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0 && looksLatinTail(sb.toString()) && looksLatinHead(s)) sb.append(' ');
            sb.append(s);
        }
        return sb.toString();
    }

    private boolean isTimeText(String s) {
        String x = s.replaceAll("\\s+", " ").trim();
        return TIME_PATTERN.matcher(x).matches();
    }

    private boolean isUiJunk(String text) {
        if (text.equals(safeContact())) return true;
        String[] exact = {
                "微信", "通讯录", "发现", "我", "发送", "按住说话", "按住 说话", "语音输入",
                "表情", "搜索", "更多", "视频号", "朋友圈", "返回", "相册", "拍摄"
        };
        for (String x : exact) if (text.equals(x)) return true;
        return text.matches("^(?:[0-9]{1,3}%|[0-9]{1,2}:[0-9]{2}\\s*[A-Z]{0,3})$");
    }

    private BubbleClass classifyBubble(Bitmap bitmap, Rect textBox) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int padX = Math.max(10, Math.min(26, textBox.height() / 2));
        int padY = Math.max(8, Math.min(20, textBox.height() / 3));

        int[][] points = {
                {textBox.left - padX, textBox.centerY()},
                {textBox.right + padX, textBox.centerY()},
                {textBox.centerX(), textBox.top - padY},
                {textBox.centerX(), textBox.bottom + padY},
                {textBox.left - padX, textBox.top - padY},
                {textBox.right + padX, textBox.top - padY},
                {textBox.left - padX, textBox.bottom + padY},
                {textBox.right + padX, textBox.bottom + padY}
        };

        int green = 0, white = 0, usable = 0;
        for (int[] p : points) {
            int x = clamp(p[0], 1, w - 2);
            int y = clamp(p[1], 1, h - 2);
            int c = average3x3(bitmap, x, y);
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            usable++;
            if (isWechatGreen(r, g, b)) green++;
            if (isBubbleWhite(r, g, b)) white++;
        }

        if (green >= 2 && green >= white) return new BubbleClass("我", green / (float) usable);
        if (white >= 3 && white > green) return new BubbleClass("对方", white / (float) usable);

        // Conservative geometric fallback only when the block is strongly lateral.
        if (textBox.left > w * 0.48f) return new BubbleClass("我", 0.45f);
        if (textBox.right < w * 0.58f) return new BubbleClass("对方", 0.45f);
        return new BubbleClass("系统/未知", 0.0f);
    }

    private int average3x3(Bitmap bitmap, int x, int y) {
        long rr = 0, gg = 0, bb = 0;
        int n = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int c = bitmap.getPixel(x + dx, y + dy);
                rr += Color.red(c);
                gg += Color.green(c);
                bb += Color.blue(c);
                n++;
            }
        }
        return Color.rgb((int)(rr / n), (int)(gg / n), (int)(bb / n));
    }

    private boolean isWechatGreen(int r, int g, int b) {
        return g >= 165 && g > r + 15 && g > b + 20 && r >= 85 && r <= 205 && b <= 170;
    }

    private boolean isBubbleWhite(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return min >= 222 && max - min <= 18;
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
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
        CapturePrefs.setStatus(this, "v0.5自动OCR已待命：切回微信聊天页后开始");
        handler.postDelayed(() -> {
            if (System.currentTimeMillis() - lastWechatEventAt < 5000) {
                requestOcrScreenshot("自动开始");
                scheduleNextSwipe();
            }
        }, 1200);
    }

    public void stopAuto() {
        autoRunning.set(false);
        swipeScheduled = false;
        CapturePrefs.setStatus(this, "v0.5已停止自动OCR");
    }

    private void scheduleNextSwipe() {
        if (!autoRunning.get() || swipeScheduled) return;
        if (autoPage >= autoMax) {
            autoRunning.set(false);
            CapturePrefs.setStatus(this, "v0.5自动采集完成，共翻 " + autoPage + " 页");
            return;
        }
        swipeScheduled = true;
        handler.postDelayed(() -> {
            swipeScheduled = false;
            if (!autoRunning.get()) return;
            if (System.currentTimeMillis() - lastWechatEventAt > 6000) {
                CapturePrefs.setStatus(this, "v0.5等待微信聊天页回到前台");
                scheduleNextSwipe();
                return;
            }
            dispatchOlderSwipe();
        }, 1500);
    }

    private void dispatchOlderSwipe() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Path p = new Path();
        p.moveTo(w * 0.50f, h * 0.35f);
        p.lineTo(w * 0.50f, h * 0.78f);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 430);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                autoPage++;
                handler.postDelayed(() -> {
                    requestOcrScreenshot("自动第" + autoPage + "页");
                    scheduleNextSwipe();
                }, 900);
            }

            @Override public void onCancelled(GestureDescription gestureDescription) {
                autoRunning.set(false);
                CapturePrefs.setStatus(WeChatEventSourceService.this, "v0.5自动上翻被系统取消");
            }
        }, null);
    }

    public String dumpTree() {
        return "# WeChat Readonly v0.5\n"
                + "# mode=Accessibility screenshot + ML Kit Chinese OCR + bubble-color sender classification\n"
                + "# last_wechat_event_at=" + lastWechatEventAt + "\n"
                + "# last_wechat_time=" + (lastWechatTime == null ? "" : lastWechatTime) + "\n"
                + "# messages_for_contact=" + (db == null ? 0 : db.count(safeContact())) + "\n"
                + "# all_records_for_contact=" + (db == null ? 0 : db.countAll(safeContact())) + "\n";
    }

    private static final class BubbleClass {
        final String sender;
        final float confidence;
        BubbleClass(String sender, float confidence) {
            this.sender = sender;
            this.confidence = confidence;
        }
    }

    private static final class BlockItem {
        String kind;
        String text;
        Rect box;
        String sender;
        float confidence;

        static BlockItem time(String text, Rect box) {
            BlockItem i = new BlockItem();
            i.kind = "time";
            i.text = text;
            i.box = new Rect(box);
            i.sender = "系统";
            i.confidence = 1.0f;
            return i;
        }

        static BlockItem message(String text, Rect box, String sender, float confidence) {
            BlockItem i = new BlockItem();
            i.kind = "message";
            i.text = text;
            i.box = new Rect(box);
            i.sender = sender;
            i.confidence = confidence;
            return i;
        }
    }
}
