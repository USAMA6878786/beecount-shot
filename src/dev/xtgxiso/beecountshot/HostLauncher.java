package dev.xtgxiso.beecountshot;

import android.content.Context;

/**
 * 用「桌面小组件 provider」的广播把宿主进程拉起来。
 *
 * <h3>为什么可行（真机验证过）</h3>
 * 它带了十几个 {@code AppWidgetProvider}，都是导出的。给其中任意一个**点名**发广播，
 * 系统就会把它的进程拉起来 —— **不需要桌面上真的摆着那个小组件**，也**不会显示任何界面**。
 * 实测（20:56，pid 从空 → 20148）：拉起后模块的 hook 全部初始化，root 也拿到了。
 *
 * <h3>它能做到什么，做不到什么</h3>
 * <b>能做到</b>：宿主进程回来了 → 桌面小组件恢复实时数字、设置页状态点变绿、
 * 后续的探测/判定链路能正常应答。
 *
 * <p><b>做不到</b>：让截图被识别。识别要它的 Flutter/Dart 跑起来，而 Dart 只在
 * 它的界面存在时才运行；广播拉起的是一个**没有界面**的进程。所以这个功能是
 * "把进程叫回来"，不是"让记账变可能"。
 *
 * <h3>细节</h3>
 * - 走 root 发（普通应用向"已停止"状态的包发广播会被系统拦掉；shell 不受这个限制）
 * - 60 秒防抖：避免状态刷新时反复开 su
 * - 只在调用方已经确认"宿主没应答"时才试，不做多余的 pid 检查（少一次 su）
 */
final class HostLauncher {

    /** 60 秒内只试一次。 */
    private static final long DEBOUNCE_MS = 60_000L;

    private static volatile long lastTryAt = 0L;

    private HostLauncher() {
    }

    /**
     * 尝试把宿主进程拉起来。**必须在后台线程调用**（会开 su）。
     *
     * @return true = 广播已发出（不代表一定拉起来了，调用方需要自己再探一次确认）
     */
    static boolean pullUp(Context ctx) {
        if (ctx == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastTryAt < DEBOUNCE_MS) {
            return false;
        }
        lastTryAt = now;

        String pkg = HostInfo.pkg(ctx);
        if (pkg == null || pkg.length() == 0) {
            Logx.w("[launch] 不知道宿主包名，放弃");
            return false;
        }
        if (!RootShell.isGranted(ctx)) {
            Logx.w("[launch] 没有 root，无法发广播拉起");
            return false;
        }
        try {
            // 点名发给它的小组件 provider。action 用标准的 APPWIDGET_UPDATE；
            // provider 收到后未必做什么事（我们只关心"进程被创建"）。
            String cmd = "am broadcast -n " + pkg + "/.BeeCountWidgetProvider"
                    + " -a android.appwidget.action.APPWIDGET_UPDATE";
            RootShell.Result r = RootShell.exec(cmd, 8000L);
            Logx.i("[launch] 广播已发出 exit=" + r.exit + " out=" + trim(r.out));
            return r.exit == 0;
        } catch (Throwable t) {
            Logx.w("[launch] 广播失败：" + t.getMessage());
            return false;
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }
}
