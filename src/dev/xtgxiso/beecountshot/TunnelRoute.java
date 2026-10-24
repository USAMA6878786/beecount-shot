package dev.xtgxiso.beecountshot;

/**
 * 把蜜蜂记账的流量"塞回"VPN 隧道。
 *
 * <h3>为什么需要</h3>
 * 用户开着 Clash 这类 fake-ip 代理时，域名会被解析成 {@code 198.18.x.x} 这种**假地址**——
 * 它只有走隧道才有意义。但 MIUI 的「智能限制」会给后台应用打上网络标记，
 * 于是它的 SYN 绕开隧道跑去公网找一个不存在的地址，**永远停在 SYN_SENT**，
 * 表现就是"识别卡在正在识别截图"。
 *
 * <p>实测（2026-10-23）：
 * <pre>
 *   加规则前：🔍 [GLM] 请求 → 40 秒无响应（443 连接 state=02 SYN_SENT）
 *   加规则后：🔍 [GLM] 请求 → 9.2 秒后 📦 响应数据（正常识别出 3 笔）
 * </pre>
 *
 * <h3>做法</h3>
 * 加一条**优先级更高**的按 UID 路由规则，强制这个 UID 走 VPN 的路由表：
 * <pre>ip rule add uidrange &lt;uid&gt;-&lt;uid&gt; lookup &lt;tun表名&gt; pref 14000</pre>
 *
 * <p>表名不硬编码——不同代理用的表名不一样（Clash 是 {@code tun0}，别的可能是数字）。
 * 这里从 {@code ip route show table all} 里找出"走在 tun 设备上的那些表"。
 *
 * <h3>用完就撤</h3>
 * 识别结束（或空闲）后会把规则删掉，避免长期改变用户的网络走向。
 */
final class TunnelRoute {

    /** 我们加的规则优先级。比 Android 的 fwmark 规则（14999/15040）更靠前。 */
    private static final int PREF = 14000;

    private static volatile String activeTable;

    private TunnelRoute() {
    }

    /**
     * 让某个 UID 走隧道。**必须后台线程**（会开 su）。
     *
     * @param uid 目标应用 uid
     * @return true = 规则已就位
     */
    static boolean ensure(android.content.Context ctx, final int uid) {
        if (uid <= 0) {
            return false;
        }
        if (ctx == null || !RootShell.isGranted(ctx)) {
            return false;
        }
        String table = tunTable();
        if (table == null) {
            Logx.i("[route] 没检测到 VPN 路由表，跳过（可能没开代理）");
            return false;
        }
        try {
            // 先删后加，避免重复规则
            String cmd = "ip rule del uidrange " + uid + "-" + uid
                    + " lookup " + table + " pref " + PREF + " 2>/dev/null; "
                    + "ip rule add uidrange " + uid + "-" + uid
                    + " lookup " + table + " pref " + PREF;
            RootShell.Result r = RootShell.exec(cmd, 6000L);
            if (r.exit == 0) {
                activeTable = table;
                Logx.i("[route] 已把 uid " + uid + " 强制走 " + table + "（修复假 IP 绕隧道）");
                return true;
            }
            Logx.w("[route] 加规则失败 exit=" + r.exit + " " + r.err);
        } catch (Throwable t) {
            Logx.w("[route] 加规则异常：" + t.getMessage());
        }
        return false;
    }

    /** 把规则撤掉，恢复用户原本的路由。 */
    static void release(int uid) {
        String table = activeTable;
        if (table == null || uid <= 0) {
            return;
        }
        try {
            RootShell.exec("ip rule del uidrange " + uid + "-" + uid
                    + " lookup " + table + " pref " + PREF + " 2>/dev/null", 5000L);
            activeTable = null;
            Logx.i("[route] 已撤销 uid " + uid + " 的强制路由");
        } catch (Throwable t) {
            Logx.w("[route] 撤规则失败：" + t.getMessage());
        }
    }

    /**
     * 找出 VPN 的隧道路由表名。
     *
     * <p>从 {@code ip route show table all} 的输出里取第一张"路由走在 tun 设备上"的表，
     * 例如 {@code 1.0.0.0/8 dev tun0 table tun0 proto static} → 返回 {@code tun0}。
     */
    private static String tunTable() {
        try {
            RootShell.Result r = RootShell.exec(
                    "ip route show table all 2>/dev/null | grep -m1 ' dev tun' "
                            + "| sed -n 's/.* table \\([^ ]*\\).*/\\1/p'", 5000L);
            if (r.out != null) {
                String t = r.out.trim();
                if (t.length() > 0 && t.length() < 24) {
                    return t;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
