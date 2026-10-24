package dev.xtgxiso.beecountshot;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「检查更新」：问一次 GitHub，比较版本号。
 *
 * <h3>为什么两条路</h3>
 * <ul>
 *   <li><b>GitHub Releases API</b>——给的是发布页里那一版，信息全（tag、下载地址、说明页）。
 *       缺点是未登录调用**限速 60 次/小时/IP**，而且必须带 {@code User-Agent}，
 *       否则直接 403。</li>
 *   <li><b>仓库里的 {@code update.json}</b>——一个几十字节的文件，走 raw.githubusercontent，
 *       没有限速。它同时是 LSPosed 自带的更新检查用的那份，所以两边口径一致。</li>
 * </ul>
 * 先走 API（信息更准），失败再走 update.json；两条都不通才算"检查失败"，
 * 而不是把限速当成"网络坏了"。
 *
 * <h3>为什么不用 JSON 库</h3>
 * 这个模块是 javac 直接对着 android.jar 编译的，没有任何第三方依赖。
 * 这里只需要三个字段，用正则从原文里抠出来比引入 gson 划算得多
 * （而且 GitHub 的 returns 结构稳定，字段名不会变）。
 */
final class UpdateCheck {

    private static final String API =
            "https://api.github.com/repos/USAMA6878786/beecount-shot/releases/latest";
    private static final String FALLBACK =
            "https://raw.githubusercontent.com/USAMA6878786/beecount-shot/main/update.json";

    private static final int TIMEOUT_MS = 9000;

    /** 检查结果。 */
    static final class Info {
        /** 是否拿到了远端版本信息。false 时只有 {@link #error} 有意义。 */
        final boolean ok;
        /** 远端版本名，形如 "2.73"（去掉了 tag 的 v 前缀）。 */
        final String latest;
        /** APK 直链（没有就是 null）。 */
        final String apkUrl;
        /** 发布页/说明页（没有就是 null）。 */
        final String pageUrl;
        /** 失败原因（给人看的）。 */
        final String error;
        /** 用的是哪条通道，写日志用。 */
        final String source;

        Info(boolean ok, String latest, String apkUrl, String pageUrl, String error, String source) {
            this.ok = ok;
            this.latest = latest;
            this.apkUrl = apkUrl;
            this.pageUrl = pageUrl;
            this.error = error;
            this.source = source;
        }
    }

    /** 当前安装的版本名（构建时注入的，见 build.sh 的 BuildInfo）。 */
    static String current() {
        return BuildInfo.VERSION_NAME;
    }

    /** 拉一次远端版本信息。**阻塞**，调用方放子线程。 */
    static Info fetch() {
        Info api = tryApi();
        if (api.ok) {
            return api;
        }
        Logx.w("[update] API 失败：" + api.error + "，改用 update.json");
        Info json = tryJson();
        if (json.ok) {
            return json;
        }
        // 两条都失败时，优先报 API 的原因（更可能是"网络"而不是"文件不存在"）
        return new Info(false, null, null, null,
                api.error != null ? api.error : json.error, "none");
    }

    private static Info tryApi() {
        try {
            String body = http(API);
            if (body == null) {
                return new Info(false, null, null, null, "连不上 GitHub", "api");
            }
            String tag = pick(body, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
            if (tag == null || tag.length() == 0) {
                return new Info(false, null, null, null, "GitHub 返回里没有版本号", "api");
            }
            String apk = pick(body, "\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"");
            String page = pick(body, "\"html_url\"\\s*:\\s*\"(https://github\\.com/[^\"]+/releases/tag/[^\"]+)\"");
            String version = normalize(tag);
            Logx.i("[update] API: tag=" + tag + " apk=" + apk);
            return new Info(true, version, apk, page, null, "api");
        } catch (Throwable t) {
            return new Info(false, null, null, null, shortMsg(t), "api");
        }
    }

    private static Info tryJson() {
        try {
            String body = http(FALLBACK);
            if (body == null) {
                return new Info(false, null, null, null, "连不上 GitHub", "json");
            }
            String tag = pick(body, "\"version\"\\s*:\\s*\"([^\"]+)\"");
            String apk = pick(body, "\"zipUrl\"\\s*:\\s*\"([^\"]+)\"");
            if (tag == null || tag.length() == 0) {
                return new Info(false, null, null, null, "update.json 里没有版本号", "json");
            }
            String version = normalize(tag);
            Logx.i("[update] update.json: version=" + tag + " apk=" + apk);
            return new Info(true, version, apk, null, null, "json");
        } catch (Throwable t) {
            return new Info(false, null, null, null, shortMsg(t), "json");
        }
    }

    /** {@code v2.73} → {@code 2.73}。 */
    private static String normalize(String tag) {
        String s = tag.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        return s;
    }

    /**
     * 版本号比较：{@code remote > current} 返回正数，相等 0，更旧返回负数。
     *
     * <p>按点分段逐段比数字（2.72 vs 2.9 要判对，不能按字符串比）。
     * 段数不同时短的一方补 0，于是 "2.72" == "2.72.0"。
     */
    static int compare(String remote, String current) {
        if (remote == null || current == null) {
            return 0;
        }
        String[] a = remote.split("\\.");
        String[] b = current.split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? num(a[i]) : 0;
            int y = i < b.length ? num(b[i]) : 0;
            if (x != y) {
                return x > y ? 1 : -1;
            }
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String pick(String body, String regex) {
        Matcher m = Pattern.compile(regex).matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** 极简 GET：只认 200，超时短，失败返回 null。 */
    private static String http(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            // GitHub API 强制要求 UA，不给就是 403
            conn.setRequestProperty("User-Agent", "beecount-shot/" + BuildInfo.VERSION_NAME);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                Logx.w("[update] HTTP " + code + " for " + url);
                return null;
            }
            InputStream in = conn.getInputStream();
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                    if (sb.length() > 200 * 1024) {
                        break;   // 只需要前面这些字段，别把整页读完
                    }
                }
                return sb.toString();
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.length() == 0) {
            return t.getClass().getSimpleName();
        }
        return m.length() > 80 ? m.substring(0, 80) : m;
    }

    private UpdateCheck() {
    }
}
