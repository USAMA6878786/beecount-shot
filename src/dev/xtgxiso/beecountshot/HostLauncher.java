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

    /** 3 秒内只试一次（只挡住同一次点击里的重复调用）。 */
    private static final long DEBOUNCE_MS = 3_000L;

    private static volatile long lastTryAt = 0L;

    /** 记住调用方的 Context，延迟派发时要用。 */

    /** 记住调用方的 Context，延迟派发时要用。 */
    private static volatile Context ctx0;

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
        ctx0 = ctx;
        ctx0 = ctx;
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
            // 一次 su 里做两件事：进程已经活着就 exit 3 直接跳过（省掉一次无用广播），
            // 否则点名发给它的小组件 provider。provider 收到后未必做什么事
            // —— 我们只关心"进程被创建"。
            // 拉起进程之后**立刻**就没窗口可言了：进程刚起来就会被系统当成空进程回收。
            // 所以不再靠"等 1.5 秒再发广播"——改成**留一个标记文件**：
            // 宿主进程的 Application.onCreate 一进来就检查它，存在就直接起引擎。
            // 那里是进程生命的起点，不存在"接收器还没注册"的窗口。
            // 关键：**先写标记，再广播**，而且不判断进程在不在。
            // 因为桌面上的小组件随时可能自己查询宿主、把进程拉起来——
            // 那时代码已经进 Application.onCreate 了，我们后面再写标记就晚了
            // （实测：21:51 和 22:07 两轮都是这么失败的）。
            // 标记里带时间戳，宿主只认 10 秒内的，避免上次残留导致下次冷启动白起一个引擎。
            String mark = "/data/data/" + pkg + "/cache/beeshot_boot";
            // **关键：命令里不能有 sleep**。
            // 之前写了 sleep 0.8 + sleep 2，等于把 su 占住 2.8 秒 ——
            // 而截图自己的 su（收面板 + screencap）要排在后面，
            // 实测 clicked→抓帧 从 200ms 涨到 1.2 秒，连点时还会更糟。
            // 所以这里只做两件瞬时的事：写标记、发拉起广播。
            String cmd = "mkdir -p /data/data/" + pkg + "/cache; "
                    + "date +%s > " + mark + "; "
                    + "chmod 644 " + mark + "; "
                    + "am broadcast -n " + pkg + "/.BeeCountWidgetProvider"
                    + " -a android.appwidget.action.APPWIDGET_UPDATE >/dev/null 2>&1";
            RootShell.Result r = RootShell.exec(cmd, 15000L);
            if (r.exit == 3) {
                Logx.i("[launch] 进程本来就在，跳过");
                return false;
            }
            Logx.i("[launch] 广播已发出 exit=" + r.exit + " out=" + trim(r.out));

            // 进程起来后（约 0.8 秒）再通知它"起无界面引擎"。
            // 这一步用普通 sendBroadcast，不再开 su —— 免得又占住通道。
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            requestHeadlessDispatch(ctx0);
                        }
                    }, 800L);

            return r.exit == 0;
        } catch (Throwable t) {
            Logx.w("[launch] 广播失败：" + t.getMessage());
            return false;
        }
    }

    /**
     * 告诉刚被拉起来的宿主进程："起无界面引擎，把最新那张截图派发进去"。
     *
     * <p>用普通 sendBroadcast 就够：这时宿主进程已经在跑（不是 stopped 状态），
     * 而我们注册的是**动态接收器**，不受后台隐式广播的限制。
     */
    static void requestHeadlessDispatch(android.content.Context ctx) {
        String pkg = HostInfo.pkg(ctx);
        if (pkg == null || pkg.length() == 0) {
            return;
        }
        try {
            android.content.Intent it = new android.content.Intent(Const.ACTION_HEADLESS);
            it.setPackage(pkg);
            ctx.sendBroadcast(it);
            Logx.i("[launch] 已请求无界面派发");
        } catch (Throwable t) {
            Logx.w("[launch] 请求派发失败：" + t.getMessage());
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
