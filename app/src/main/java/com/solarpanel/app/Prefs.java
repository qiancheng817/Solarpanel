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
}
