package com.oai.wechatreadonly;

import android.content.Context;
import android.content.SharedPreferences;

public final class CapturePrefs {
    private static final String PREF = "capture_prefs";
    private CapturePrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static void setEnabled(Context c, boolean v) {
        p(c).edit().putBoolean("enabled", v).apply();
    }
    public static boolean isEnabled(Context c) {
        return p(c).getBoolean("enabled", false);
    }
    public static void setContact(Context c, String s) {
        p(c).edit().putString("contact", s == null ? "" : s.trim()).apply();
    }
    public static String getContact(Context c) {
        return p(c).getString("contact", "");
    }
    public static void setStatus(Context c, String s) {
        p(c).edit().putString("status", s).apply();
    }
    public static String getStatus(Context c) {
        return p(c).getString("status", "未启动");
    }
}
