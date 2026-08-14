package com.oai.wechatreadonly;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_EXPORT = 42, REQ_DUMP = 43, REQ_EXPORT_ZIP = 44;
    private EditText contact, pages, query;
    private TextView status, results;
    private ChatDbHelper db;
    private EvidenceStore evidenceStore;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new ChatDbHelper(this);
        evidenceStore = new EvidenceStore(this);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(24));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("微信只读索引 · v0.7 视觉证据版");
        title.setTextSize(23);
        root.addView(title, lp());

        TextView note = new TextView(this);
        note.setText("只读、不解密数据库、不发消息。v0.7除OCR外，会把每个有效聊天屏幕压缩保存为视觉证据，因此表情包、图片、头像、通话卡片等不会因为OCR读不到就消失。完整导出请用ZIP。");
        note.setTextSize(15);
        root.addView(note, lp());

        Button acc = btn("① 打开系统无障碍设置");
        acc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(acc, lp());

        contact = edit("联系人标签，必须和微信顶部标题一致，例如：韩琪");
        contact.setText(CapturePrefs.getContact(this));
        root.addView(contact, lp());

        Button start = btn("② 开始只读OCR + 视觉保存");
        start.setOnClickListener(v -> {
            String c = currentContact();
            if (c.isEmpty()) return;
            CapturePrefs.setContact(this, c);
            CapturePrefs.setEnabled(this, true);
            toast("已开启。只有识别到微信顶部是「" + c + "」时才会写入数据和视觉页。");
            refresh();
        });
        root.addView(start, lp());

        pages = edit("无人值守最大翻页数，默认500");
        pages.setInputType(InputType.TYPE_CLASS_NUMBER);
        pages.setText("500");
        root.addView(pages, lp());

        Button auto = btn("③ 无人值守：自动抓到聊天顶部");
        auto.setOnClickListener(v -> {
            String c = currentContact();
            if (c.isEmpty()) return;
            CapturePrefs.setContact(this, c);
            CapturePrefs.setEnabled(this, true);
            WeChatAccessibilityService s = WeChatAccessibilityService.INSTANCE;
            if (s == null) {
                toast("先开启无障碍服务");
                return;
            }
            int n = 500;
            try { n = Integer.parseInt(pages.getText().toString()); } catch (Exception ignored) {}
            s.startAuto(n);
            toast("切到「" + c + "」聊天页后放着即可。它会自动保存文字和每屏视觉证据。");
            refresh();
        });
        root.addView(auto, lp());

        Button stop = btn("停止采集 / 恢复屏幕亮度");
        stop.setOnClickListener(v -> {
            CapturePrefs.setEnabled(this, false);
            if (WeChatAccessibilityService.INSTANCE != null) WeChatAccessibilityService.INSTANCE.stopAuto();
            refresh();
        });
        root.addView(stop, lp());

        Button clear = btn("清空当前联系人本地数据 + 视觉页");
        clear.setOnClickListener(v -> {
            String c = currentContact();
            if (c.isEmpty()) return;
            db.clearContact(c);
            evidenceStore.clearContact(c);
            toast("已清空「" + c + "」的v0.7数据和视觉证据");
            refresh();
        });
        root.addView(clear, lp());

        status = new TextView(this);
        root.addView(status, lp());

        query = edit("本地搜索关键词，例如：通话 / 考研 / 寒假");
        root.addView(query, lp());

        Button search = btn("搜索本地聊天");
        search.setOnClickListener(v -> doSearch());
        root.addView(search, lp());

        Button exportZip = btn("★ 导出完整证据 ZIP（推荐给ChatGPT）");
        exportZip.setOnClickListener(v -> {
            String c = currentContact();
            if (c.isEmpty()) return;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, safe(c) + "_wechat_v07_evidence.zip");
            startActivityForResult(i, REQ_EXPORT_ZIP);
        });
        root.addView(exportZip, lp());

        Button export = btn("仅导出结构化 JSONL");
        export.setOnClickListener(v -> {
            String c = currentContact();
            if (c.isEmpty()) return;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.setType("application/x-ndjson");
            i.putExtra(Intent.EXTRA_TITLE, safe(c) + "_wechat_v07.jsonl");
            startActivityForResult(i, REQ_EXPORT);
        });
        root.addView(export, lp());

        Button dump = btn("诊断：导出v0.7状态");
        dump.setOnClickListener(v -> {
            if (WeChatAccessibilityService.INSTANCE == null) {
                toast("先开启无障碍服务");
                return;
            }
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE, "wechat_ocr_status_v07.txt");
            startActivityForResult(i, REQ_DUMP);
        });
        root.addView(dump, lp());

        results = new TextView(this);
        results.setTextIsSelectable(true);
        root.addView(results, lp());

        setContentView(sv);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void doSearch() {
        String c = currentContact();
        String q = query.getText().toString().trim();
        if (c.isEmpty() || q.isEmpty()) {
            toast("先填联系人和关键词");
            return;
        }
        List<String> rows = db.search(c, q, 150);
        StringBuilder sb = new StringBuilder("结果 ").append(rows.size()).append(" 条\n\n");
        for (String s : rows) sb.append(s).append("\n\n");
        results.setText(sb.toString());
    }

    private void refresh() {
        if (status == null) return;
        String c = contact == null ? "" : contact.getText().toString().trim();
        int messages = c.isEmpty() ? 0 : db.countKind(c, "message");
        int calls = c.isEmpty() ? 0 : db.countKind(c, "call");
        int times = c.isEmpty() ? 0 : db.countKind(c, "time");
        int screens = c.isEmpty() ? 0 : evidenceStore.countScreens(c);
        status.setText(
                (CapturePrefs.isEnabled(this) ? "采集开" : "采集关") + " · " +
                        (WeChatAccessibilityService.INSTANCE != null ? "服务已连接" : "服务未连接") +
                        "\n消息 " + messages + " · 通话 " + calls + " · 时间锚点 " + times + " · 视觉页 " + screens +
                        "\n" + CapturePrefs.getStatus(this));
    }

    private String currentContact() {
        String c = contact.getText().toString().trim();
        if (c.isEmpty()) toast("先填联系人标签");
        return c;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (requestCode == REQ_EXPORT) {
                db.exportJsonl(currentContact(), os);
                toast("v0.7 JSONL已导出");
            } else if (requestCode == REQ_EXPORT_ZIP) {
                evidenceStore.exportZip(currentContact(), db, os);
                toast("v0.7完整证据ZIP已导出：里面含JSONL和所有视觉页");
            } else if (requestCode == REQ_DUMP) {
                String d = WeChatAccessibilityService.INSTANCE == null
                        ? "# service unavailable\n"
                        : WeChatAccessibilityService.INSTANCE.dumpTree();
                os.write(d.getBytes(StandardCharsets.UTF_8));
                toast("v0.7状态已导出");
            }
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    private Button btn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    private EditText edit(String h) {
        EditText e = new EditText(this);
        e.setHint(h);
        e.setSingleLine(true);
        return e;
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(8);
        return p;
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private static String safe(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
