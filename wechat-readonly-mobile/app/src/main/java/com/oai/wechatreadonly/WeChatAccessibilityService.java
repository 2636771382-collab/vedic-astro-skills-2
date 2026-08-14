package com.oai.wechatreadonly;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class WeChatAccessibilityService extends AccessibilityService {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";

    public static volatile WeChatAccessibilityService INSTANCE;
    private ChatDbHelper db;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override protected void onServiceConnected() {
        INSTANCE = this;
        db = new ChatDbHelper(this);
        CapturePrefs.setStatus(this, "无障碍服务已连接 · v0.2仅抓微信窗口");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!CapturePrefs.isEnabled(this) || event == null || event.getPackageName() == null) return;
        if (!WECHAT_PACKAGE.contentEquals(event.getPackageName())) return;
        int t = event.getEventType();
        if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || t == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            captureVisible();
        }
    }

    @Override public void onInterrupt() {}
    @Override public void onDestroy() {
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    /**
     * v0.2关键修复：不再使用 getRootInActiveWindow()。
     * 浮窗/本App位于微信上方时，active window 可能是我们自己。
     * 这里遍历无障碍窗口，只接受 root.packageName == com.tencent.mm。
     */
    private AccessibilityNodeInfo findWechatRoot() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                AccessibilityNodeInfo best = null;
                int bestArea = -1;
                for (AccessibilityWindowInfo win : windows) {
                    if (win == null) continue;
                    AccessibilityNodeInfo root = win.getRoot();
                    if (root == null || root.getPackageName() == null) continue;
                    if (!WECHAT_PACKAGE.contentEquals(root.getPackageName())) continue;

                    Rect r = new Rect();
                    root.getBoundsInScreen(r);
                    int area = Math.max(0, r.width()) * Math.max(0, r.height());
                    if (best == null || area > bestArea) {
                        best = root;
                        bestArea = area;
                    }
                }
                if (best != null) return best;
            }
        } catch (Exception ignored) {}

        // Safe fallback: only accept active root if it is actually WeChat.
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && active.getPackageName() != null
                && WECHAT_PACKAGE.contentEquals(active.getPackageName())) {
            return active;
        }
        return null;
    }

    public int captureVisible() {
        AccessibilityNodeInfo root = findWechatRoot();
        if (root == null) {
            CapturePrefs.setStatus(this, "没找到微信窗口；请让微信聊天页保持打开");
            return 0;
        }

        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        List<NodeText> all = new ArrayList<>();
        collect(root, all, 0);

        String contact = CapturePrefs.getContact(this);
        if (contact.isEmpty()) contact = "未命名联系人";

        int added = 0;
        for (NodeText n : all) {
            if (!candidate(n.text, n.r, h)) continue;
            float cx = n.r.exactCenterX();
            String sender = cx < w * .44f ? "对方" : (cx > w * .56f ? "我" : "系统/未知");
            if (db.insert(contact, sender, n.text, n.r.top, n.cls)) added++;
        }

        CapturePrefs.setStatus(this,
                "v0.2微信窗口 · 本屏新增 " + added + " 条；共 " + db.count(contact) + " 条候选");
        return added;
    }

    public void startAuto(int pages) {
        if (!running.compareAndSet(false, true)) return;
        CapturePrefs.setEnabled(this, true);
        step(0, Math.max(1, Math.min(300, pages)));
    }

    public void stopAuto() {
        running.set(false);
        CapturePrefs.setStatus(this, "已停止自动翻页");
    }

    private void step(int page, int max) {
        if (!running.get()) return;
        if (page >= max) {
            running.set(false);
            CapturePrefs.setStatus(this, "自动翻页完成");
            return;
        }

        AccessibilityNodeInfo root = findWechatRoot();
        if (root == null) {
            running.set(false);
            CapturePrefs.setStatus(this, "自动翻页停止：微信窗口不可见");
            return;
        }

        captureVisible();
        AccessibilityNodeInfo scroller = findScrollable(root);
        if (scroller == null || !scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            running.set(false);
            CapturePrefs.setStatus(this, "无法继续向上翻；请导出微信UI树给ChatGPT");
            return;
        }

        int next = page + 1;
        CapturePrefs.setStatus(this, "v0.2自动采集中 " + next + "/" + max);
        handler.postDelayed(() -> step(next, max), 900);
    }

    public String dumpTree() {
        AccessibilityNodeInfo root = findWechatRoot();
        StringBuilder sb = new StringBuilder("# WeChat Accessibility tree v0.2\n");
        if (root == null) {
            sb.append("# WeChat root not found\n");
            return sb.toString();
        }
        sb.append("# package=").append(root.getPackageName()).append("\n");
        dump(root, sb, 0);
        return sb.toString();
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isScrollable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            AccessibilityNodeInfo x = findScrollable(c);
            if (x != null) return x;
        }
        return null;
    }

    private void collect(AccessibilityNodeInfo n, List<NodeText> out, int depth) {
        if (n == null || depth > 16 || out.size() > 5000) return;
        String text = textOf(n);
        if (!text.isEmpty()) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            out.add(new NodeText(text, r, String.valueOf(n.getClassName())));
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            collect(n.getChild(i), out, depth + 1);
        }
    }

    private void dump(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 14 || sb.length() > 500000) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        sb.append("  ".repeat(depth))
                .append("- pkg=").append(n.getPackageName())
                .append(" cls=").append(n.getClassName())
                .append(" viewId=").append(n.getViewIdResourceName())
                .append(" scroll=").append(n.isScrollable())
                .append(" bounds=").append(r)
                .append(" text=").append(textOf(n).replace("\n", "\\n"))
                .append("\n");
        for (int i = 0; i < n.getChildCount(); i++) dump(n.getChild(i), sb, depth + 1);
    }

    private static String textOf(AccessibilityNodeInfo n) {
        if (n == null) return "";
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) return t.toString();
        CharSequence d = n.getContentDescription();
        return d == null ? "" : d.toString();
    }

    private static boolean candidate(String s, Rect r, int h) {
        if (s == null || s.trim().isEmpty() || s.length() > 4000 || r.width() <= 0 || r.height() <= 0) return false;
        if (r.bottom < h * .10f || r.top > h * .90f) return false;
        String x = s.trim();
        String[] chrome = {
                "微信", "通讯录", "发现", "我", "视频号", "朋友圈",
                "按住 说话", "发送", "更多功能", "语音输入", "表情"
        };
        for (String c : chrome) if (x.equals(c)) return false;
        return true;
    }

    private static class NodeText {
        String text, cls;
        Rect r;
        NodeText(String t, Rect rr, String c) {
            text = t;
            r = new Rect(rr);
            cls = c;
        }
    }
}
