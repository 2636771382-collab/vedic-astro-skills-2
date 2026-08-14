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
    public ChatDbHelper(Context c) {
        super(c, "wechat_readonly_v06.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE records(" +
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
                        "source TEXT," +
                        "call_status TEXT," +
                        "call_duration_seconds INTEGER," +
                        "duplicate_hint INTEGER NOT NULL DEFAULT 0" +
                        ")"
        );
        db.execSQL("CREATE INDEX idx_contact_capture ON records(contact,captured_at,capture_id,seq_in_capture)");
        db.execSQL("CREATE INDEX idx_contact_kind ON records(contact,kind)");
        db.execSQL("CREATE INDEX idx_contact_text ON records(contact,text)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public boolean insertRecord(String contact, String sender, String kind, String text,
                                String wechatTime, long capturedAt, String captureId, int seq,
                                int left, int top, int right, int bottom,
                                float confidence, String source,
                                String callStatus, Integer callDurationSeconds,
                                boolean duplicateHint) {
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
        if (callStatus != null) v.put("call_status", callStatus);
        if (callDurationSeconds != null) v.put("call_duration_seconds", callDurationSeconds);
        v.put("duplicate_hint", duplicateHint ? 1 : 0);
        return getWritableDatabase().insert("records", null, v) != -1;
    }

    public int countKind(String contact, String kind) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM records WHERE contact=? AND kind=?",
                new String[]{contact, kind})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int countConversation(String contact) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM records WHERE contact=? AND kind IN ('message','call')",
                new String[]{contact})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int countAll(String contact) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM records WHERE contact=?",
                new String[]{contact})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public void clearContact(String contact) {
        getWritableDatabase().delete("records", "contact=?", new String[]{contact});
    }

    public List<String> search(String contact, String q, int limit) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT sender,kind,text,wechat_time,call_duration_seconds FROM records " +
                        "WHERE contact=? AND kind IN ('message','call') AND text LIKE ? " +
                        "ORDER BY id DESC LIMIT ?",
                new String[]{contact, "%" + q + "%", String.valueOf(limit)})) {
            while (c.moveToNext()) {
                String t = c.isNull(3) ? "" : (" @" + c.getString(3));
                String k = "call".equals(c.getString(1)) ? "☎" : "";
                String d = c.isNull(4) ? "" : (" " + c.getInt(4) + "s");
                out.add("[" + c.getString(0) + t + "] " + k + c.getString(2) + d);
            }
        }
        return out;
    }

    public void exportJsonl(String contact, OutputStream os) throws Exception {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT sender,kind,text,wechat_time,captured_at,capture_id,seq_in_capture," +
                        "left_px,top_px,right_px,bottom_px,confidence,source," +
                        "call_status,call_duration_seconds,duplicate_hint " +
                        "FROM records WHERE contact=? ORDER BY id",
                new String[]{contact})) {
            while (c.moveToNext()) {
                String line = "{" +
                        "\"contact\":" + j(contact) + "," +
                        "\"sender\":" + j(c.getString(0)) + "," +
                        "\"kind\":" + j(c.getString(1)) + "," +
                        "\"text\":" + j(c.getString(2)) + "," +
                        "\"wechat_time\":" + nullableString(c, 3) + "," +
                        "\"captured_at\":" + c.getLong(4) + "," +
                        "\"capture_id\":" + j(c.getString(5)) + "," +
                        "\"seq_in_capture\":" + c.getInt(6) + "," +
                        "\"bbox\":{" +
                        "\"left\":" + c.getInt(7) + "," +
                        "\"top\":" + c.getInt(8) + "," +
                        "\"right\":" + c.getInt(9) + "," +
                        "\"bottom\":" + c.getInt(10) + "}," +
                        "\"confidence\":" + c.getFloat(11) + "," +
                        "\"source\":" + j(c.getString(12)) + "," +
                        "\"call_status\":" + nullableString(c, 13) + "," +
                        "\"call_duration_seconds\":" + (c.isNull(14) ? "null" : String.valueOf(c.getInt(14))) + "," +
                        "\"duplicate_hint\":" + (c.getInt(15) == 1 ? "true" : "false") +
                        "}\n";
                os.write(line.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static String nullableString(Cursor c, int idx) {
        return c.isNull(idx) ? "null" : j(c.getString(idx));
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
