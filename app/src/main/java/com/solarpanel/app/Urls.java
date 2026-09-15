package com.solarpanel.app;

import android.net.Uri;

import java.util.Locale;

/**
 * 服务器地址的规范化与校验。
 *
 * 允许用户只输入 "192.168.1.10:8080" 或 "panel.example.com"，
 * 此时自动补齐 http:// 前缀。
 */
public final class Urls {

    private Urls() {
    }

    /**
     * @return 规范化后的地址；若无法解析为合法的 http/https 地址则返回 null。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().replace('\u3000', ' ').trim();
        if (value.isEmpty()) {
            return null;
        }

        // 去掉用户可能粘进来的首尾引号
        if (value.length() > 1
                && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                && value.charAt(value.length() - 1) == value.charAt(0)) {
            value = value.substring(1, value.length() - 1).trim();
        }

        // 缺少协议头时补 http://
        if (!value.matches("(?i)^[a-z][a-z0-9+.\\-]*://.*")) {
            value = "http://" + value;
        }

        Uri uri;
        try {
            uri = Uri.parse(value);
        } catch (Exception e) {
            return null;
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isEmpty()) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        if (host.indexOf(' ') >= 0) {
            return null;
        }

        // 去掉结尾多余的斜杠，避免拼接出 "//admin"
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
