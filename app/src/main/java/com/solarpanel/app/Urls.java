package com.solarpanel.app;

import android.net.Uri;
import android.text.TextUtils;

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

    /**
     * 判断两个地址是否指向同一个页面（用于识别"当前是否还在面板首页"）。
     *
     * 比较时忽略查询串与锚点，并把末尾斜杠、默认端口统一掉，
     * 因此 http://ip:8080、http://ip:8080/、http://ip:8080/#section 会被视作同一页。
     */
    public static boolean isSamePage(String left, String right) {
        String a = pageKey(left);
        return a != null && a.equals(pageKey(right));
    }

    private static String pageKey(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Uri uri;
        try {
            uri = Uri.parse(raw);
        } catch (Exception e) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isEmpty()) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);

        int port = uri.getPort();
        if (port < 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }

        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return scheme + "://" + host + ":" + port + path;
    }

    /**
     * 判断 host 是否指向本机回环地址（localhost / 127.0.0.1 / ::1）。
     * 自托管面板在反代、容器端口映射等场景下，返回的卡片链接可能写成 localhost，
     * 手机上的 WebView 去连手机自身的 localhost 当然是连不上的。
     */
    public static boolean isLocalHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(h)
                || "127.0.0.1".equals(h)
                || "::1".equals(h)
                || "0.0.0.0".equals(h);
    }

    /**
     * 如果 uri 的 host 是 localhost / 127.0.0.1 / ::1，把它重写成 baseUrl 的 host + port，
     * 这样自托管面板返回的回环地址卡片链接就能在手机上正确打开。
     *
     * @return 重写后的 Uri；如果不需要重写或无法重写则返回 null。
     */
    public static Uri rewriteLocalHost(Uri uri, String baseUrl) {
        if (uri == null || TextUtils.isEmpty(baseUrl)) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        if (!isLocalHost(host)) {
            return null;
        }

        Uri base;
        try {
            base = Uri.parse(baseUrl);
        } catch (Exception e) {
            return null;
        }
        String baseHost = base.getHost();
        if (baseHost == null || baseHost.isEmpty()) {
            return null;
        }

        // port 处理：uri 自带的 port 保留，否则用 base 的 port
        int port = uri.getPort();
        if (port < 0) {
            port = base.getPort();
        }
        if (port < 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }

        // 手动拼 URI 字符串再 parse 回去，比 Uri.Builder 更可靠
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(baseHost);
        boolean defaultPort = ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
        if (!defaultPort) {
            sb.append(':').append(port);
        }
        String path = uri.getPath();
        if (path != null) {
            sb.append(path);
        }
        if (uri.getQuery() != null) {
            sb.append('?').append(uri.getQuery());
        }
        if (uri.getFragment() != null) {
            sb.append('#').append(uri.getFragment());
        }
        try {
            return Uri.parse(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
