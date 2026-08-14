package com.oai.wechatreadonly;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class WeChatEventSourceService extends WeChatAccessibilityService {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";

    private ChatDbHelper eventDb;
    private final Handler eventHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean eventAuto = new AtomicBoolean(false);
    private volatile boolean scrollScheduled = false;
    private volatile int page = 0;
    private volatile int maxPages = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        eventDb = new ChatDbHelper(this);
        CapturePrefs.setStatus(this, "无障碍服务已连接 · v0.3事件源抓取");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!CapturePrefs.isEnabled(this) || event == null || event.getPackageName() == null) return;
        if (!WECHAT_PACKAGE.contentEquals(event.getPackageName())) return;

        AccessibilityNodeInfo root = rootFromEvent(event);
        if (root == null) {
            CapturePrefs.setStatus(this, "v0.3收到微信事件，但事件节点为空");
            return;
        }

        captureRoot(root);

        if (eventAuto.get() && !scrollScheduled) {
            scheduleNextScroll();
        }
    }

    private AccessibilityNodeInfo rootFromEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo node = event.getSource();
        if (node == null) return null;

        AccessibilityNodeInfo current = node;
        AccessibilityNodeInfo best = node;
        for (int i = 0; i < 24; i++) {
            if (current == null) break;
            CharSequence pkg = current.getPackageName();
            if (pkg != null && WECHAT_PACKAGE.contentEquals(pkg)) {
                best = current;
            }
            AccessibilityNodeInfo parent = current.getParent();
            if (parent == null) break;
            CharSequence parentPkg = parent.getPackageName();
            if (parentPkg == null || !WECHAT_PACKAGE.contentEquals(parentPkg)) break;
            current = parent;
        }
        return best;
    }

    private int captureRoot(AccessibilityNodeInfo root) {
        if (eventDb == null || root == null) return 0;

        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        List<NodeText> all = new ArrayList<>();
        collect(root, all, 0);

        String contact = CapturePrefs.getContact(this);
        if (contact == null || contact.isEmpty()) contact = "未命名联系人";

        int added = 0;
        for (NodeText n : all) {
            if (!candidate(n.text, n.r, h)) continue;
            float cx = n.r.exactCenterX();
            String sender = cx < w * .44f ? "对方" : (cx > w * .56f ? "我" : "系统/未知");
            if (eventDb.insert(contact, sender, n.text, n.r.top, n.cls)) added++;
        }

        CapturePrefs.setStatus(this,
                "v0.3事件源 · 本次新增 " + added + " 条；共 " + eventDb.count(contact) + " 条候选");
        return added;
    }

    @Override
    public int captureVisible() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !WECHAT_PACKAGE.contentEquals(root.getPackageName())) {
            CapturePrefs.setStatus(this, "v0.3请先切到微信聊天窗口再采集");
            return 0;
        }
        return captureRoot(root);
    }

    @Override
    public void startAuto(int pages) {
        CapturePrefs.setEnabled(this, true);
        page = 0;
        maxPages = Math.max(1, Math.min(300, pages));
        eventAuto.set(true);
        scrollScheduled = false;
        CapturePrefs.setStatus(this, "v0.3自动模式已就绪，请切回微信聊天窗口");

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null && WECHAT_PACKAGE.contentEquals(root.getPackageName())) {
            captureRoot(root);
            scheduleNextScroll();
        }
    }

    @Override
    public void stopAuto() {
        eventAuto.set(false);
        scrollScheduled = false;
        CapturePrefs.setStatus(this, "v0.3已停止自动翻页");
    }

    private void scheduleNextScroll() {
        if (!eventAuto.get() || scrollScheduled) return;
        if (page >= maxPages) {
            eventAuto.set(false);
            CapturePrefs.setStatus(this, "v0.3自动翻页完成，共 " + page + " 页");
            return;
        }

        scrollScheduled = true;
        eventHandler.postDelayed(() -> {
            scrollScheduled = false;
            if (!eventAuto.get()) return;

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !WECHAT_PACKAGE.contentEquals(root.getPackageName())) {
                CapturePrefs.setStatus(this, "v0.3自动模式等待微信回到前台");
                return;
            }

            captureRoot(root);
            AccessibilityNodeInfo scroller = findLargestScrollable(root);
            if (scroller == null || !scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                eventAuto.set(false);
                CapturePrefs.setStatus(this, "v0.3无法继续上翻，已停止；当前第 " + page + " 页");
                return;
            }

            page++;
            CapturePrefs.setStatus(this, "v0.3自动采集中 " + page + "/" + maxPages);
            scheduleNextScroll();
        }, 950);
    }

    @Override
    public String dumpTree() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        StringBuilder sb = new StringBuilder("# WeChat Accessibility tree v0.3\n");
        if (root == null || root.getPackageName() == null || !WECHAT_PACKAGE.contentEquals(root.getPackageName())) {
            sb.append("# 请保持微信聊天窗口在前台后再导出\n");
            return sb.toString();
        }
        sb.append("# package=").append(root.getPackageName()).append("\n");
        dump(root, sb, 0);
        return sb.toString();
    }

    private AccessibilityNodeInfo findLargestScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        AccessibilityNodeInfo best = null;
        int bestArea = -1;
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(node);
        for (int i = 0; i < stack.size(); i++) {
            AccessibilityNodeInfo n = stack.get(i);
            if (n.isScrollable()) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                int area = Math.max(0, r.width()) * Math.max(0, r.height());
                if (best == null || area > bestArea) {
                    best = n;
                    bestArea = area;
                }
            }
            for (int j = 0; j < n.getChildCount(); j++) {
                AccessibilityNodeInfo c = n.getChild(j);
                if (c != null) stack.add(c);
            }
        }
        return best;
    }

    private void collect(AccessibilityNodeInfo n, List<NodeText> out, int depth) {
        if (n == null || depth > 18 || out.size() > 8000) return;
        String text = textOf(n);
        if (!text.isEmpty()) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            out.add(new NodeText(text, r, String.valueOf(n.getClassName())));
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) collect(c, out, depth + 1);
        }
    }

    private void dump(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 16 || sb.length() > 700000) return;
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
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) dump(c, sb, depth + 1);
        }
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
        if (r.bottom < h * .08f || r.top > h * .92f) return false;
        String x = s.trim();
        String[] chrome = {"微信", "通讯录", "发现", "我", "视频号", "朋友圈", "按住 说话", "发送", "更多功能", "语音输入", "表情"};
        for (String c : chrome) if (x.equals(c)) return false;
        return true;
    }

    private static final class NodeText {
        final String text;
        final String cls;
        final Rect r;
        NodeText(String text, Rect r, String cls) {
            this.text = text;
            this.r = new Rect(r);
            this.cls = cls;
        }
    }
}
