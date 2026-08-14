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
    private static final int REQ_EXPORT = 42, REQ_DUMP = 43;
    private EditText contact, pages, query;
    private TextView status, results;
    private ChatDbHelper db;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); db = new ChatDbHelper(this);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(16),dp(18),dp(24)); sv.addView(root);
        TextView title = new TextView(this); title.setText("微信只读索引 · 手机版 v0.1"); title.setTextSize(23); root.addView(title, lp());
        TextView note = new TextView(this); note.setText("只读当前手机微信已显示的聊天界面，不解密数据库，不发消息，不联网。\n第一版先验证你这版微信的UI节点结构。"); note.setTextSize(15); root.addView(note, lp());

        Button acc = btn("① 打开系统无障碍设置"); acc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); root.addView(acc, lp());
        contact = edit("联系人标签，例如：韩琪"); contact.setText(CapturePrefs.getContact(this)); root.addView(contact, lp());
        Button start = btn("② 开始只读采集"); start.setOnClickListener(v -> { String c=currentContact(); if(c.isEmpty())return; CapturePrefs.setContact(this,c); CapturePrefs.setEnabled(this,true); toast("已开启。切到微信聊天窗口并上下滚动。"); refresh(); }); root.addView(start, lp());
        Button stop = btn("停止采集"); stop.setOnClickListener(v -> { CapturePrefs.setEnabled(this,false); if(WeChatAccessibilityService.INSTANCE!=null) WeChatAccessibilityService.INSTANCE.stopAuto(); refresh(); }); root.addView(stop, lp());

        pages = edit("自动向上翻多少页，默认80"); pages.setInputType(InputType.TYPE_CLASS_NUMBER); pages.setText("80"); root.addView(pages, lp());
        Button auto = btn("③ 自动向上抓历史"); auto.setOnClickListener(v -> { String c=currentContact(); if(c.isEmpty())return; CapturePrefs.setContact(this,c); WeChatAccessibilityService s=WeChatAccessibilityService.INSTANCE; if(s==null){toast("先开启无障碍服务");return;} int n=80; try{n=Integer.parseInt(pages.getText().toString());}catch(Exception ignored){} s.startAuto(n); toast("开始自动翻页。请保持微信聊天窗口在前台。"); }); root.addView(auto, lp());

        status = new TextView(this); root.addView(status, lp());
        query = edit("本地搜索关键词，例如：考研"); root.addView(query, lp());
        Button search = btn("搜索本地聊天"); search.setOnClickListener(v -> doSearch()); root.addView(search, lp());
        Button export = btn("导出当前联系人 JSONL"); export.setOnClickListener(v -> { String c=currentContact(); if(c.isEmpty())return; Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.setType("application/x-ndjson"); i.putExtra(Intent.EXTRA_TITLE, safe(c)+"_wechat.jsonl"); startActivityForResult(i,REQ_EXPORT); }); root.addView(export, lp());
        Button dump = btn("诊断：导出当前微信 UI 树"); dump.setOnClickListener(v -> { if(WeChatAccessibilityService.INSTANCE==null){toast("先开启无障碍，并切到微信聊天窗口");return;} Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.setType("text/plain"); i.putExtra(Intent.EXTRA_TITLE,"wechat_ui_dump.txt"); startActivityForResult(i,REQ_DUMP); }); root.addView(dump, lp());
        results = new TextView(this); results.setTextIsSelectable(true); root.addView(results, lp());
        setContentView(sv); refresh();
    }

    @Override protected void onResume(){ super.onResume(); refresh(); }
    private void doSearch(){ String c=currentContact(), q=query.getText().toString().trim(); if(c.isEmpty()||q.isEmpty()){toast("先填联系人和关键词");return;} List<String> rows=db.search(c,q,100); StringBuilder sb=new StringBuilder("结果 "+rows.size()+" 条\n\n"); for(String s:rows) sb.append(s).append("\n\n"); results.setText(sb.toString()); }
    private void refresh(){ if(status==null)return; String c=contact==null?"":contact.getText().toString().trim(); int n=c.isEmpty()?0:db.count(c); status.setText((CapturePrefs.isEnabled(this)?"采集开":"采集关")+" · "+(WeChatAccessibilityService.INSTANCE!=null?"服务已连接":"服务未连接")+" · 当前联系人候选 "+n+" 条\n"+CapturePrefs.getStatus(this)); }
    private String currentContact(){ String c=contact.getText().toString().trim(); if(c.isEmpty())toast("先填联系人标签"); return c; }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){ super.onActivityResult(requestCode,resultCode,data); if(resultCode!=RESULT_OK||data==null||data.getData()==null)return; Uri uri=data.getData(); try(OutputStream os=getContentResolver().openOutputStream(uri)){ if(requestCode==REQ_EXPORT){db.exportJsonl(currentContact(),os); toast("JSONL已导出");} else if(requestCode==REQ_DUMP){String d=WeChatAccessibilityService.INSTANCE==null?"# service unavailable\n":WeChatAccessibilityService.INSTANCE.dumpTree(); os.write(d.getBytes(StandardCharsets.UTF_8)); toast("UI树已导出，把txt发给ChatGPT");} }catch(Exception e){toast("导出失败："+e.getMessage());} }

    private Button btn(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private EditText edit(String h){ EditText e=new EditText(this); e.setHint(h); e.setSingleLine(true); return e; }
    private LinearLayout.LayoutParams lp(){ LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(8); return p; }
    private int dp(int n){ return (int)(n*getResources().getDisplayMetrics().density+.5f); }
    private void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private static String safe(String s){ return s.replaceAll("[\\\\/:*?\"<>|]","_"); }
}
