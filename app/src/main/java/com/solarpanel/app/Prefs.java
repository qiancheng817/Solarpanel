package com.solarpanel.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * 轻量配置存储：目前只需要记住服务器地址。
 */
public final class Prefs {

    private static final String FILE_NAME = "solarpanel_prefs";
    private static final String KEY_SERVER = "server_url";
    private static final String KEY_DISPLAY_MODE = "display_mode";   // desktop / mobile
    private static final String KEY_NETWORK_MODE = "network_mode";   // wan / lan

    private Prefs() {
    }

    private static SharedPreferences store(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public static String getServer(Context context) {
        String value = store(context).getString(KEY_SERVER, "");
        return TextUtils.isEmpty(value) ? "" : value;
    }

    public static void setServer(Context context, String url) {
        store(context).edit().putString(KEY_SERVER, url).apply();
    }

    public static void clearServer(Context context) {
        store(context).edit().remove(KEY_SERVER).apply();
    }

    /** 桌面/手机屏幕比例模式，默认 desktop */
    public static String getDisplayMode(Context context) {
        String v = store(context).getString(KEY_DISPLAY_MODE, "desktop");
        return "mobile".equals(v) ? "mobile" : "desktop";
    }

    public static void setDisplayMode(Context context, String mode) {
        store(context).edit().putString(KEY_DISPLAY_MODE, mode).apply();
    }

    /** 卡片地址模式：wan（外网，默认）/ lan（内网） */
    public static String getNetworkMode(Context context) {
        String v = store(context).getString(KEY_NETWORK_MODE, "wan");
        return "lan".equals(v) ? "lan" : "wan";
    }

    public static void setNetworkMode(Context context, String mode) {
        store(context).edit().putString(KEY_NETWORK_MODE, mode).apply();
    }
}
