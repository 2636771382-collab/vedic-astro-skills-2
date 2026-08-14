package com.oai.wechatreadonly;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ChatDbHelper extends SQLiteOpenHelper {
    private static final int DB_VERSION = 2;

    public ChatDbHelper(Context c) {
        super(c, "wechat_readonly_v05.db", null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE messages(" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "contact TEXT NOT NULL," +
                        "sender TEXT NOT NULL," +
                        "kind TEXT NOT NULL," +
                        "text TEXT NOT NULL," +
                        "wechat_time TEXT," +
                        "captured_at INTEGER NOT NULL," +
                        "capture_id TEXT NOT NULL," +
                        "seq_in_capture INTEGER NOT NULL," +
                        "left_px INTEGER,top_px INTEGER,right_px INTEGER,bottom_px INTEGER," +
                        "confidence REAL," +
                        "source TEXT" +
                        ")"
        );
        db.execSQL("CREATE INDEX idx_contact_capture ON messages(contact,captured_at,capture_id,seq_in_capture)");
        db.execSQL("CREATE INDEX idx_contact_text ON messages(contact,text)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS messages");
        onCreate(db);
    }

    public boolean insertRecord(String contact, String sender, String kind, String text,
                                String wechatTime, long capturedAt, String captureId, int seq,
                                int left, int top, int right, int bottom,
                                float confidence, String source) {
        if (text == null || text.trim().isEmpty()) return false;
        ContentValues v = new ContentValues();
        v.put("contact", contact == null ? "" : contact.trim());
        v.put("sender", sender == null ? "系统/未知" : sender);
        v.put("kind", kind == null ? "message" : kind);
        v.put("text", text.trim());
        if (wechatTime != null && !wechatTime.isEmpty()) v.put("wechat_time", wechatTime);
        v.put("captured_at", capturedAt);
        v.put("capture_id", captureId);
        v.put("seq_in_capture", seq);
        v.put("left_px", left);
        v.put("top_px", top);
        v.put("right_px", right);
        v.put("bottom_px", bottom);
        v.put("confidence", confidence);
        v.put("source", source == null ? "OCR" : source);
        return getWritableDatabase().insert("messages", null, v) != -1;
    }

    public int count(String contact) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM messages WHERE contact=? AND kind='message'",
                new String[]{contact})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int countAll(String contact) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM messages WHERE contact=?",
                new String[]{contact})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public void clearContact(String contact) {
        getWritableDatabase().delete("messages", "contact=?", new String[]{contact});
    }

    public List<String> search(String contact, String q, int limit) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT sender,text,wechat_time FROM messages " +
                        "WHERE contact=? AND kind='message' AND text LIKE ? " +
                        "ORDER BY id DESC LIMIT ?",
                new String[]{contact, "%" + q + "%", String.valueOf(limit)})) {
            while (c.moveToNext()) {
                String t = c.isNull(2) ? "" : (" @" + c.getString(2));
                out.add("[" + c.getString(0) + t + "] " + c.getString(1));
            }
        }
        return out;
    }

    public void exportJsonl(String contact, OutputStream os) throws Exception {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT sender,kind,text,wechat_time,captured_at,capture_id,seq_in_capture," +
                        "left_px,top_px,right_px,bottom_px,confidence,source " +
                        "FROM messages WHERE contact=? ORDER BY id",
                new String[]{contact})) {
            while (c.moveToNext()) {
                String line = "{" +
                        "\"contact\":" + j(contact) + "," +
                        "\"sender\":" + j(c.getString(0)) + "," +
                        "\"kind\":" + j(c.getString(1)) + "," +
                        "\"text\":" + j(c.getString(2)) + "," +
                        "\"wechat_time\":" + (c.isNull(3) ? "null" : j(c.getString(3))) + "," +
                        "\"captured_at\":" + c.getLong(4) + "," +
                        "\"capture_id\":" + j(c.getString(5)) + "," +
                        "\"seq_in_capture\":" + c.getInt(6) + "," +
                        "\"bbox\":{" +
                        "\"left\":" + c.getInt(7) + "," +
                        "\"top\":" + c.getInt(8) + "," +
                        "\"right\":" + c.getInt(9) + "," +
                        "\"bottom\":" + c.getInt(10) + "}," +
                        "\"confidence\":" + c.getFloat(11) + "," +
                        "\"source\":" + j(c.getString(12)) +
                        "}\n";
                os.write(line.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static String j(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
