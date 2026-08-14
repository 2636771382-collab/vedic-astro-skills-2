package com.oai.wechatreadonly;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class ChatDbHelper extends SQLiteOpenHelper {
    public ChatDbHelper(Context c) { super(c, "wechat_readonly.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,contact TEXT NOT NULL,sender TEXT NOT NULL,text TEXT NOT NULL,captured_at INTEGER NOT NULL,screen_y INTEGER,node_class TEXT,fingerprint TEXT UNIQUE)");
        db.execSQL("CREATE INDEX idx_contact_time ON messages(contact,captured_at)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public boolean insert(String contact, String sender, String text, int y, String cls) {
        if (text == null || text.trim().isEmpty()) return false;
        text = text.trim();
        String fp = sha(contact + "\u001f" + sender + "\u001f" + text);
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("contact", contact); v.put("sender", sender); v.put("text", text);
        v.put("captured_at", System.currentTimeMillis()); v.put("screen_y", y);
        v.put("node_class", cls); v.put("fingerprint", fp);
        return getWritableDatabase().insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public int count(String contact) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages WHERE contact=?", new String[]{contact})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public List<String> search(String contact, String q, int limit) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT sender,text FROM messages WHERE contact=? AND text LIKE ? ORDER BY id DESC LIMIT ?", new String[]{contact, "%" + q + "%", String.valueOf(limit)})) {
            while (c.moveToNext()) out.add("[" + c.getString(0) + "] " + c.getString(1));
        }
        return out;
    }

    public void exportJsonl(String contact, OutputStream os) throws Exception {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT sender,text,captured_at,screen_y,node_class FROM messages WHERE contact=? ORDER BY id", new String[]{contact})) {
            while (c.moveToNext()) {
                String line = "{\"contact\":" + j(contact) + ",\"sender\":" + j(c.getString(0)) + ",\"text\":" + j(c.getString(1)) + ",\"captured_at\":" + c.getLong(2) + ",\"screen_y\":" + c.getInt(3) + ",\"node_class\":" + j(c.getString(4)) + "}\n";
                os.write(line.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static String j(String s) { return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")) + "\""; }
    private static String sha(String s) {
        try { byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); StringBuilder x = new StringBuilder(); for (byte v : b) x.append(String.format("%02x", v)); return x.toString(); }
        catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }
}
