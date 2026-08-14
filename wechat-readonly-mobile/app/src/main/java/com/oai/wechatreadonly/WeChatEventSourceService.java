package com.oai.wechatreadonly;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WeChatEventSourceService extends AccessibilityService {
    protected static final String WECHAT_PACKAGE = "com.tencent.mm";
    public static volatile WeChatAccessibilityService INSTANCE;

    private static final Pattern TIME_PATTERN = Pattern.compile(
            "^(?:\\d{1,2}:\\d{2}|昨天(?:\\s*\\d{1,2}:\\d{2})?|前天(?:\\s*\\d{1,2}:\\d{2})?|" +
                    "周[一二三四五六日天](?:\\s*\\d{1,2}:\\d{2})?|" +
                    "\\d{1,2}月\\d{1,2}日(?:\\s*\\d{1,2}:\\d{2})?|" +
                    "\\d{4}年\\d{1,2}月\\d{1,2}日(?:\\s*\\d{1,2}:\\d{2})?)$");
    private static final Pattern CALL_DURATION_PATTERN = Pattern.compile("(\\d{1,3})\\s*[:：]\\s*(\\d{2})");

    protected ChatDbHelper db;
    private TextRecognizer recognizer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private final AtomicBoolean autoRunning = new AtomicBoolean(false);

    private volatile boolean swipeScheduled = false;
    private volatile boolean autoLoopStarted = false;
    private volatile boolean lastScreenVerified = false;
    private volatile int autoPage = 0;
    private volatile int autoMax = 0;
    private volatile long lastWechatEventAt = 0L;
    private volatile long lastScreenshotAt = 0L;
    private volatile String lastVisibleWechatTime = null;
    private String lastScreenSignature = null;
    private int sameScreenCount = 0;
    private Set<String> previousScreenKeys = new HashSet<>();

    private WindowManager windowManager;
    private View privacyOverlay;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        db = new ChatDbHelper(this);
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        if (this instanceof WeChatAccessibilityService) INSTANCE = (WeChatAccessibilityService) this;
        CapturePrefs.setStatus(this, "无障碍服务已连接 · v0.6无人值守OCR");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!WECHAT_PACKAGE.contentEquals(event.getPackageName())) return;
        if (!CapturePrefs.isEnabled(this)) return;

        lastWechatEventAt = System.currentTimeMillis();

        // Once unattended mode has locked onto the requested chat, its own loop drives captures.
        // Ignoring incidental WeChat events prevents duplicate screenshots between programmed swipes.
        if (autoRunning.get() && autoLoopStarted) return;
        requestOcrScreenshot(autoRunning.get() ? "自动开始" : "微信事件");
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        finishAuto(null, false);
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
            CapturePrefs.setStatus(this, "v0.6需要Android 11或以上");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScreenshotAt < 700) return;
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
                        CapturePrefs.setStatus(WeChatEventSourceService.this, "v0.6截图成功但Bitmap为空");
                        return;
                    }
                    Bitmap bitmap = hw.copy(Bitmap.Config.ARGB_8888, false);
                    buffer.close();
                    runOcr(bitmap, source);
                }

                @Override
                public void onFailure(int errorCode) {
                    ocrBusy.set(false);
                    CapturePrefs.setStatus(WeChatEventSourceService.this, "v0.6截图失败 code=" + errorCode);
                    if (autoRunning.get()) finishAuto("截图失败，自动采集已停止", true);
                }
            });
        } catch (Throwable t) {
            ocrBusy.set(false);
            CapturePrefs.setStatus(this, "v0.6截图异常：" + t.getClass().getSimpleName());
            if (autoRunning.get()) finishAuto("截图异常，自动采集已停止", true);
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
                    String contact = safeContact();
                    if (!isTargetChatScreen(result, bitmap.getWidth(), bitmap.getHeight(), contact)) {
                        lastScreenVerified = false;
                        CapturePrefs.setStatus(this,
                                "v0.6等待目标聊天页：请打开「" + contact + "」聊天，识别到标题后才会入库/上翻");
                        return;
                    }

                    lastScreenVerified = true;
                    CaptureSummary summary = storeOcrBlocks(result, bitmap, source);
                    CapturePrefs.setStatus(this,
                            "v0.6 已锁定「" + contact + "」 · 本屏新增 " + summary.addedConversation +
                                    " 条；消息 " + db.countKind(contact, "message") +
                                    " · 通话 " + db.countKind(contact, "call") +
                                    " · 时间锚点 " + db.countKind(contact, "time") +
                                    (summary.sameScreen ? " · 屏幕未变化×" + sameScreenCount : ""));

                    if (autoRunning.get()) {
                        if (!autoLoopStarted) {
                            autoLoopStarted = true;
                            showPrivacyOverlay();
                        }
                        if (sameScreenCount >= 3) {
                            finishAuto("连续3次屏幕不再变化，已判断到达聊天顶部", true);
                        } else {
                            scheduleNextSwipe();
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    CapturePrefs.setStatus(this, "v0.6 OCR失败：" + e.getClass().getSimpleName());
                    if (autoRunning.get()) finishAuto("OCR失败，自动采集已停止", true);
                })
                .addOnCompleteListener(task -> {
                    bitmap.recycle();
                    ocrBusy.set(false);
                });
    }

    private boolean isTargetChatScreen(Text result, int width, int height, String contact) {
        String target = normalizeForMatch(contact);
        if (target.isEmpty()) return false;
        for (Text.TextBlock block : result.getTextBlocks()) {
            Rect box = block.getBoundingBox();
            if (box == null) continue;
            if (box.centerY() > height * 0.115f) continue;
            if (box.centerX() < width * 0.16f || box.centerX() > width * 0.84f) continue;
            String text = normalizeForMatch(block.getText());
            if (!text.isEmpty() && (text.equals(target) || text.contains(target))) return true;
        }
        return false;
    }

    private String normalizeForMatch(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\p{Punct}，。！？、·•（）()【】\\[\\]<>《》]", "").trim();
    }

    private CaptureSummary storeOcrBlocks(Text result, Bitmap bitmap, String source) {
        List<BlockItem> items = new ArrayList<>();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        for (Text.TextBlock block : result.getTextBlocks()) {
            Rect box = block.getBoundingBox();
            String raw = block.getText() == null ? "" : block.getText().trim();
            if (raw.isEmpty() || box == null) continue;
            if (box.bottom < height * 0.075f || box.top > height * 0.92f) continue;

            String normalized = normalizeBlockText(raw);
            if (normalized.isEmpty()) continue;
            if (isAvatarOrEdgeJunk(box, width, height)) continue;

            if (isTimeText(normalized)) {
                items.add(BlockItem.time(normalized, box));
                continue;
            }
            if (isUiJunk(normalized)) continue;

            BubbleClass bc = classifyBubble(bitmap, box);
            if (bc.sender.equals("系统/未知")) continue;

            CallInfo call = parseCall(normalized);
            if (call != null) {
                items.add(BlockItem.call(normalized, box, bc.sender, bc.confidence,
                        call.status, call.durationSeconds));
            } else {
                items.add(BlockItem.message(normalized, box, bc.sender, bc.confidence));
            }
        }

        items.sort(Comparator.comparingInt(a -> a.box.top));
        List<BlockItem> merged = mergeAdjacent(items);
        String signature = makeScreenSignature(merged);
        boolean same = signature.equals(lastScreenSignature);
        if (same) sameScreenCount++; else sameScreenCount = 0;

        // Exact same stable screen is useful for top detection, but not useful as another database copy.
        if (same) return new CaptureSummary(0, true);
        lastScreenSignature = signature;

        String contact = safeContact();
        String captureId = UUID.randomUUID().toString();
        long capturedAt = System.currentTimeMillis();
        int seq = 0;
        int addedConversation = 0;
        String currentWechatTime = null;
        Set<String> currentKeys = new HashSet<>();

        for (BlockItem item : merged) {
            if (item.kind.equals("time")) {
                currentWechatTime = item.text;
                lastVisibleWechatTime = item.text;
                db.insertRecord(contact, "系统", "time", item.text, item.text,
                        capturedAt, captureId, seq++, item.box.left, item.box.top,
                        item.box.right, item.box.bottom, 1.0f, source,
                        null, null, false);
                continue;
            }

            String key = recordKey(item);
            boolean duplicateHint = previousScreenKeys.contains(key);
            currentKeys.add(key);
            if (db.insertRecord(contact, item.sender, item.kind, item.text, currentWechatTime,
                    capturedAt, captureId, seq++, item.box.left, item.box.top,
                    item.box.right, item.box.bottom, item.confidence, source,
                    item.callStatus, item.callDurationSeconds, duplicateHint)) {
                addedConversation++;
            }
        }
        previousScreenKeys = currentKeys;
        return new CaptureSummary(addedConversation, false);
    }

    private boolean isAvatarOrEdgeJunk(Rect box, int width, int height) {
        boolean edge = box.centerX() > width * 0.86f || box.centerX() < width * 0.14f;
        boolean small = box.width() < width * 0.12f && box.height() < height * 0.045f;
        return edge && small;
    }

    private String makeScreenSignature(List<BlockItem> items) {
        StringBuilder sb = new StringBuilder();
        for (BlockItem i : items) {
            if (i.kind.equals("time") || i.kind.equals("call") || i.kind.equals("message")) {
                sb.append(i.kind).append('|').append(i.sender).append('|')
                        .append(normalizeForMatch(i.text)).append('|')
                        .append(i.box.top / 40).append(';');
            }
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private String recordKey(BlockItem item) {
        return item.kind + "|" + item.sender + "|" + normalizeForMatch(item.text);
    }

    private List<BlockItem> mergeAdjacent(List<BlockItem> items) {
        List<BlockItem> out = new ArrayList<>();
        for (BlockItem cur : items) {
            if (!cur.kind.equals("message")) {
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

    private CallInfo parseCall(String text) {
        String x = text.replace(" ", "").replace("　", "");
        if (x.contains("通话时长")) {
            Matcher m = CALL_DURATION_PATTERN.matcher(text);
            Integer seconds = null;
            if (m.find()) {
                try {
                    int mm = Integer.parseInt(m.group(1));
                    int ss = Integer.parseInt(m.group(2));
                    if (ss < 60) seconds = mm * 60 + ss;
                } catch (Exception ignored) {}
            }
            return new CallInfo("completed", seconds);
        }
        if (x.contains("无应答") || x.contains("未接听") || x.contains("无人接听"))
            return new CallInfo("no_answer", null);
        if (x.contains("已取消") || x.contains("取消通话"))
            return new CallInfo("cancelled", null);
        if (x.contains("已拒绝") || x.contains("拒绝通话"))
            return new CallInfo("rejected", null);
        if (x.contains("忙线")) return new CallInfo("busy", null);
        if (x.contains("语音通话") || x.contains("视频通话")) return new CallInfo("call", null);
        return null;
    }

    private boolean isUiJunk(String text) {
        if (text.equals(safeContact())) return true;
        String[] exact = {
                "微信", "通讯录", "发现", "我", "发送", "按住说话", "按住 说话", "语音输入",
                "表情", "搜索", "更多", "视频号", "朋友圈", "返回", "相册", "拍摄", "+", "开"
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
        autoMax = Math.max(1, Math.min(600, pages));
        autoPage = 0;
        swipeScheduled = false;
        autoLoopStarted = false;
        lastScreenVerified = false;
        lastScreenSignature = null;
        sameScreenCount = 0;
        previousScreenKeys = new HashSet<>();
        autoRunning.set(true);
        removePrivacyOverlay();
        CapturePrefs.setStatus(this,
                "v0.6无人值守已待命：现在只需打开「" + safeContact() + "」聊天页，锁定标题后会自动变暗并一直向上采集");
    }

    public void stopAuto() {
        finishAuto("已手动停止无人值守采集", false);
    }

    private void scheduleNextSwipe() {
        if (!autoRunning.get() || swipeScheduled || !lastScreenVerified) return;
        if (autoPage >= autoMax) {
            finishAuto("达到设定最大页数 " + autoMax + "，已停止", true);
            return;
        }
        swipeScheduled = true;
        handler.postDelayed(() -> {
            swipeScheduled = false;
            if (!autoRunning.get() || !lastScreenVerified) return;
            dispatchOlderSwipe();
        }, 1250);
    }

    private void dispatchOlderSwipe() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Path p = new Path();
        p.moveTo(w * 0.50f, h * 0.34f);
        p.lineTo(w * 0.50f, h * 0.80f);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 470);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                autoPage++;
                handler.postDelayed(() -> requestOcrScreenshot("自动第" + autoPage + "页"), 850);
            }

            @Override public void onCancelled(GestureDescription gestureDescription) {
                finishAuto("自动上翻被系统取消", true);
            }
        }, null);
    }

    private void showPrivacyOverlay() {
        if (privacyOverlay != null) return;
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            privacyOverlay = new View(this);
            privacyOverlay.setBackgroundColor(Color.BLACK);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT);
            // Almost transparent in screenshots, while requesting minimum physical display brightness.
            lp.alpha = 0.01f;
            lp.screenBrightness = 0.02f;
            windowManager.addView(privacyOverlay, lp);
        } catch (Throwable ignored) {
            privacyOverlay = null;
        }
    }

    private void removePrivacyOverlay() {
        if (privacyOverlay != null && windowManager != null) {
            try { windowManager.removeView(privacyOverlay); } catch (Throwable ignored) {}
        }
        privacyOverlay = null;
        windowManager = null;
    }

    private void finishAuto(String reason, boolean vibrate) {
        autoRunning.set(false);
        autoLoopStarted = false;
        swipeScheduled = false;
        removePrivacyOverlay();
        if (reason != null) CapturePrefs.setStatus(this, "v0.6 " + reason + "；共自动翻 " + autoPage + " 页");
        if (vibrate) signalDone();
    }

    private void signalDone() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 180, 100, 180}, -1));
            } else {
                v.vibrate(350);
            }
        } catch (Throwable ignored) {}
    }

    public String dumpTree() {
        String contact = safeContact();
        return "# WeChat Readonly v0.6\n"
                + "# mode=verified target chat + unattended screenshot OCR + bubble sender + call parser\n"
                + "# target_contact=" + contact + "\n"
                + "# last_screen_verified=" + lastScreenVerified + "\n"
                + "# auto_running=" + autoRunning.get() + "\n"
                + "# auto_page=" + autoPage + "/" + autoMax + "\n"
                + "# same_screen_count=" + sameScreenCount + "\n"
                + "# last_wechat_event_at=" + lastWechatEventAt + "\n"
                + "# last_visible_wechat_time=" + (lastVisibleWechatTime == null ? "" : lastVisibleWechatTime) + "\n"
                + "# messages=" + (db == null ? 0 : db.countKind(contact, "message")) + "\n"
                + "# calls=" + (db == null ? 0 : db.countKind(contact, "call")) + "\n"
                + "# time_anchors=" + (db == null ? 0 : db.countKind(contact, "time")) + "\n";
    }

    private static final class BubbleClass {
        final String sender;
        final float confidence;
        BubbleClass(String sender, float confidence) {
            this.sender = sender;
            this.confidence = confidence;
        }
    }

    private static final class CallInfo {
        final String status;
        final Integer durationSeconds;
        CallInfo(String status, Integer durationSeconds) {
            this.status = status;
            this.durationSeconds = durationSeconds;
        }
    }

    private static final class CaptureSummary {
        final int addedConversation;
        final boolean sameScreen;
        CaptureSummary(int addedConversation, boolean sameScreen) {
            this.addedConversation = addedConversation;
            this.sameScreen = sameScreen;
        }
    }

    private static final class BlockItem {
        String kind;
        String text;
        Rect box;
        String sender;
        float confidence;
        String callStatus;
        Integer callDurationSeconds;

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

        static BlockItem call(String text, Rect box, String sender, float confidence,
                              String status, Integer durationSeconds) {
            BlockItem i = new BlockItem();
            i.kind = "call";
            i.text = text;
            i.box = new Rect(box);
            i.sender = sender;
            i.confidence = confidence;
            i.callStatus = status;
            i.callDurationSeconds = durationSeconds;
            return i;
        }
    }
}
