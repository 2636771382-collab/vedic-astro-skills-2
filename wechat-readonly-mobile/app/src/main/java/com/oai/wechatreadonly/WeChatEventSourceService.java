package com.oai.wechatreadonly;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class WeChatEventSourceService extends AccessibilityService {
    protected static final String WECHAT_PACKAGE = "com.tencent.mm";
    public static volatile WeChatAccessibilityService INSTANCE;

    protected ChatDbHelper db;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean autoRunning = new AtomicBoolean(false);
    private volatile int autoPage = 0;
    private volatile int autoMax = 0;
    private volatile AccessibilityNodeInfo lastWechatRoot;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        db = new ChatDbHelper(this);
        if (this instanceof WeChatAccessibilityService) {
            INSTANCE = (WeChatAccessibilityService) this;
        }
        CapturePrefs.setStatus(this, "无障碍服务已连接 · v0.3事件源模式");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!WECHAT_PACKAGE.contentEquals(event.getPackageName())) return;
        if (!CapturePrefs.isEnabled(this)) return;

        AccessibilityNodeInfo root = rootFromEvent(event);
        if (root != null) {
            lastWechatRoot = root;
            captureRoot(root, "微信事件");
        } else {
            CapturePrefs.setStatus(this, "v0.3收到微信事件，但没有可读取节点");
        }
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        autoRunning.set(false);
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    private AccessibilityNodeInfo rootFromEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo node = event.getSource();
        if (node == null) return null;
        AccessibilityNodeInfo best = node;
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 32; i++) {
            if (current == null) break;
            CharSequence pkg = current.getPackageName();
            if (pkg == null || !WECHAT_PACKAGE.contentEquals(pkg)) break;
            best = current;
            AccessibilityNodeInfo parent = current.getParent();
            if (parent == null) break;
            current = parent;
        }
        return best;
    }

    public int captureVisible() {
        AccessibilityNodeInfo root = activeWechatRoot();
        if (root == null) root = lastWechatRoot;
        if (root == null) {
            CapturePrefs.setStatus(this, "v0.3暂无微信节点，请切回聊天页滑动一下");
            return 0;
        }
        return captureRoot(root, "手动抓取");
    }

    protected AccessibilityNodeInfo activeWechatRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return null;
        return WECHAT_PACKAGE.contentEquals(root.getPackageName()) ? root : null;
    }

    protected int captureRoot(AccessibilityNodeInfo root, String source) {
        if (root == null || db == null) return 0;
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        List<NodeText> nodes = new ArrayList<>();
        collect(root, nodes, 0);

        String contact = CapturePrefs.getContact(this);
        if (contact == null || contact.trim().isEmpty()) contact = "未命名联系人";

        int added = 0;
        for (NodeText n : nodes) {
            if (!candidate(n.text, n.bounds, h)) continue;
            float cx = n.bounds.exactCenterX();
            String sender = cx < w * 0.44f ? "对方" : (cx > w * 0.56f ? "我" : "系统/未知");
            if (db.insert(contact, sender, n.text, n.bounds.top, n.className)) added++;
        }

        CapturePrefs.setStatus(this,
                "v0.3 " + source + " · 本次新增 " + added + " 条；共 " + db.count(contact) + " 条候选");
        return added;
    }

    public void startAuto(int pages) {
        CapturePrefs.setEnabled(this, true);
        autoMax = Math.max(1, Math.min(300, pages));
        autoPage = 0;
        autoRunning.set(true);
        CapturePrefs.setStatus(this, "v0.3自动模式已开启，请保持微信聊天页在前台");
        handler.postDelayed(this::autoStep, 700);
    }

    public void stopAuto() {
        autoRunning.set(false);
        CapturePrefs.setStatus(this, "v0.3已停止自动翻页");
    }

    private void autoStep() {
        if (!autoRunning.get()) return;
        if (autoPage >= autoMax) {
            autoRunning.set(false);
            CapturePrefs.setStatus(this, "v0.3自动翻页完成，共 " + autoPage + " 页");
            return;
        }

        AccessibilityNodeInfo root = activeWechatRoot();
        if (root == null) {
            CapturePrefs.setStatus(this, "v0.3自动模式等待微信聊天页回到前台");
            handler.postDelayed(this::autoStep, 1000);
            return;
        }

        lastWechatRoot = root;
        captureRoot(root, "自动第" + autoPage + "页");
        AccessibilityNodeInfo scroller = findLargestScrollable(root);
        if (scroller == null || !scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            autoRunning.set(false);
            CapturePrefs.setStatus(this, "v0.3无法继续向上翻，已在第 " + autoPage + " 页停止");
            return;
        }

        autoPage++;
        handler.postDelayed(this::autoStep, 950);
    }

    public String dumpTree() {
        AccessibilityNodeInfo root = activeWechatRoot();
        if (root == null) root = lastWechatRoot;
        StringBuilder sb = new StringBuilder("# WeChat Accessibility tree v0.3\n");
        if (root == null) {
            sb.append("# no cached WeChat root; open chat and scroll first\n");
            return sb.toString();
        }
        sb.append("# package=").append(root.getPackageName()).append("\n");
        dump(root, sb, 0);
        return sb.toString();
    }

    private AccessibilityNodeInfo findLargestScrollable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo best = null;
        int bestArea = -1;
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(root);
        for (int i = 0; i < stack.size(); i++) {
            AccessibilityNodeInfo n = stack.get(i);
            if (n == null) continue;
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

    private void collect(AccessibilityNodeInfo node, List<NodeText> out, int depth) {
        if (node == null || depth > 20 || out.size() > 10000) return;
        String text = textOf(node);
        if (!text.isEmpty()) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            out.add(new NodeText(text, r, String.valueOf(node.getClassName())));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) collect(c, out, depth + 1);
        }
    }

    private void dump(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 18 || sb.length() > 900000) return;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        sb.append("  ".repeat(depth))
                .append("- pkg=").append(node.getPackageName())
                .append(" cls=").append(node.getClassName())
                .append(" viewId=").append(node.getViewIdResourceName())
                .append(" scroll=").append(node.isScrollable())
                .append(" bounds=").append(r)
                .append(" text=").append(textOf(node).replace("\n", "\\n"))
                .append("\n");
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) dump(c, sb, depth + 1);
        }
    }

    protected static String textOf(AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) return t.toString();
        CharSequence d = node.getContentDescription();
        return d == null ? "" : d.toString();
    }

    private static boolean candidate(String s, Rect r, int h) {
        if (s == null || s.trim().isEmpty() || s.length() > 4000) return false;
        if (r.width() <= 0 || r.height() <= 0) return false;
        if (r.bottom < h * 0.06f || r.top > h * 0.94f) return false;
        String x = s.trim();
        String[] chrome = {"微信", "通讯录", "发现", "我", "视频号", "朋友圈", "按住 说话", "发送", "更多功能", "语音输入", "表情"};
        for (String c : chrome) if (x.equals(c)) return false;
        return true;
    }

    protected static final class NodeText {
        final String text;
        final Rect bounds;
        final String className;
        NodeText(String text, Rect bounds, String className) {
            this.text = text;
            this.bounds = new Rect(bounds);
            this.className = className;
        }
    }
}
