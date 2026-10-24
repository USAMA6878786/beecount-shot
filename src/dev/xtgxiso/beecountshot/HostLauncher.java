package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.content.Intent;

/**
 * 用「桌面小组件 provider」的广播把宿主进程拉起来。
 *
 * <h3>为什么可行（真机验证过）</h3>
 * 它带了十几个 {@code AppWidgetProvider}，都是导出的。给其中任意一个**点名**发广播，
 * 系统就会把它的进程拉起来 —— **不需要桌面上真的摆着那个小组件**，也**不会显示任何界面**。
 * 实测（20:56，pid 从空 → 20148）：拉起后模块的 hook 全部初始化，root 也拿到了。
 *
 * <h3>它能做到什么，做不到什么</h3>
 * <b>能做到</b>：宿主进程回来了 → 桌面小组件恢复实时数字、
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
            // 3 秒内刚拉过一次：进程已经在了，不用再广播拉起。
            // 派发这一张由截图成功后的"指定路径派发"负责（见 ShotTileService），
            // 这里不再发无路径派发——那只会让宿主空等、和带路径派发打架。
            Logx.i("[launch] 3 秒内拉过，跳过广播（截图完成后会带路径派发）");
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

            // 不再在这里"延迟派发"了：以前截图路径还没确定，只能先拉起宿主、
            // 让它自己 newestShot 取图。现在截图成功后一定会走"指定路径派发"
            // （见 ShotTileService），这里再发一条无路径派发只会和它打架——
            // 宿主收到无路径派发只能空等（"还没有明确路径"），还多占一次广播。

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
        requestHeadlessDispatch(ctx, null);
    }

    /**
     * 告诉刚被拉起来的宿主进程："起无界面引擎，把这张截图派发进去"。
     *
     * <p>用普通 sendBroadcast 就够：这时宿主进程已经在跑（不是 stopped 状态），
     * 而我们注册的是**动态接收器**，不受后台隐式广播的限制。
     *
     * @param path 本次截图的精确路径；null 表示让宿主自己取最新（冷启动兜底用）
     */
    static void requestHeadlessDispatch(android.content.Context ctx, String path) {
        String pkg = HostInfo.pkg(ctx);
        if (pkg == null || pkg.length() == 0) {
            return;
        }
        try {
            // 先起解冻续命线程（异步，不阻塞）：它内部第一下会立即戳，
            // 尽早把可能已被冻结的进程解冻，让紧接着的广播更容易送达。
            // 自适应校准（异步、只跑一次）：按本机实测的冻结周期算出该 sleep 多久，
            // 不同 ROM/机型的冻结周期可能不是 5 秒，硬编码会失效。
            calibrateAsync(ctx, pkg);
            startThawLoop(ctx, pkg);

            // 用 root 的 am broadcast 发派发请求：普通 sendBroadcast 在被冻结的
            //    宿主上投不到（动态接收器虽在，但进程冻结着收不到），root 的 shell
            //    广播带 FLAG_RECEIVER_INCLUDE_BACKGROUND，能直达后台/冻结进程。
            StringBuilder extras = new StringBuilder();
            extras.append("--ez ").append(Const.EXTRA_AUTO_DELETE).append(' ')
                    .append(Prefs.autoDelete(ctx) ? "true" : "false");
            if (path != null && path.length() > 0) {
                extras.append(" --es ").append(Const.EXTRA_PATH).append(' ').append(path);
            }
            RootShell.broadcast(Const.ACTION_HEADLESS, pkg, extras.toString(),
                    Intent.FLAG_RECEIVER_FOREGROUND | Const.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            Logx.i("[launch] 已请求无界面派发" + (path != null ? "（指定路径）" : ""));

            // 补发三次：广播若正好落在约 0.2 秒的冻结窗口里，会被 Greezer Denial 拒掉
            // （实测连点时约 1/3 的派发被拒）。同一张图重发是安全的——宿主按路径去重
            // （DISPATCHED_HISTORY / 队列 contains），重复不会重复记账。
            final String fpkg = pkg;
            final String fextras = extras.toString();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 1; i <= 3; i++) {
                        try {
                            Thread.sleep(700L);
                        } catch (InterruptedException e) {
                            return;
                        }
                        RootShell.broadcast(Const.ACTION_HEADLESS, fpkg, fextras,
                                Intent.FLAG_RECEIVER_FOREGROUND
                                        | Const.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                        Logx.i("[launch] 补发派发请求 #" + i);
                    }
                }
            }, "bee-dispatch-retry").start();
        } catch (Throwable t) {
            Logx.w("[launch] 请求派发失败：" + t.getMessage());
        }
    }

    // ---------------- MIUI 冻结解冻（GreezeManager） ----------------
    //
    // 背景（实测）：
    //   · 蜜蜂记账被无界面拉起后，MIUI 的 GreezeManager 周期性 freezeUid 冻结它
    //     （logcat `FZ uid ... reason=from system`，解冻后约 5 秒必冻结一次）。
    //   · 冻结会掐断它到智谱 AI 的 TCP 连接 → 解冻后死连接报 DioException
    //     → 弹「识别失败」（单点约 40~50%，连点更糟）；严重时卡在「正在识别截图」。
    //
    // 无效方案（都已实测排除）：
    //   · cpuset 保前台：阻止不了 freezeUid
    //   · oom_score_adj=0：同样阻止不了 freezeUid
    //   · `am unfreeze --sticky`：命令报成功，但 cgroup.freeze 仍是 1——只对
    //     `am freeze` 冻的进程有效，对 GreezeManager 的私有冻结无效
    //   · 前台化 Activity：有效但会闪屏
    //
    // 唯一有效：binder 调用（content query）戳 provider —— 送达即触发 THAW 解冻。
    //
    // ★ 关键发现（logcat 实测）：GreezeManager 的冻结是**固定定时器**——
    //   每次真正 THAW 之后，硬等 **5.01 秒**就再冻结一次，与进程有没有 binder 流量无关。
    //   （在"未冻结"时戳它没用：content query 不产生 THAW，计时器不会重置。）
    //
    //   于是冻结窗口长度 = 戳周期 − 5.01 秒：
    //     · 周期 4.0s（原来"3s sleep + 1s 戳"）→ 窗口 3s+，识别请求大概率被掐断
    //     · 周期 4.9s（略小于冻结点）→ 最差：戳永远落在冻结前，窗口 4.8s
    //     · 周期 5.15s（略大于）→ **窗口 0.15s** ✅ 实测 0.145~0.177s
    //
    //   所以戳必须写成"一次 su 里的循环"（每次 content query 约 1.0 秒；
    //   若每下都新开 su 会多 0.2 秒开销、破坏对齐），sleep 4.15 秒凑成 5.15 周期。

    /** 循环里两次戳之间的 sleep：4.15s + 戳本身约 1.0s ≈ 5.15s 周期。 */
    private static final long ALIGN_SLEEP_MS = 4_150L;

    /** 实际使用的 sleep（自适应校准后覆盖）。 */
    private static volatile long alignSleepMs = ALIGN_SLEEP_MS;

    /** 是否已校准（只跑一次）。 */
    private static volatile boolean alignCalibrated = false;

    /** 校准结果缓存键（避免每次启动都重新测）。 */
    private static final String PREF_ALIGN_INTERVAL = "align_interval_ms";

    /** 本机实测的「解冻→再冻结」间隔；默认 5010 是这台 HyperOS 机器的值。 */
    private static volatile long freezeIntervalMs = 5_010L;

    /**
     * 自适应校准：实测**本机**的「解冻 → 再冻结」间隔，反推对齐 sleep。
     *
     * <p>为什么必须自适应：5.01 秒只是这台机器的值，别的 ROM 可能是 10 秒、30 秒。
     * 硬编码 4.15 秒换台机器就完全对不齐（要么每次戳空、要么窗口极长）。
     *
     * <p>做法：
     * <ol>
     *   <li>读 {@code logcat -v epoch -s GreezeManager}，取本 uid 的 THAW/FZ 时间戳，
     *       算相邻 THAW→FZ 的**中位数**（避开偶发异常样本）；</li>
     *   <li>实测一次 content query 的耗时（= 周期里的固定开销）；</li>
     *   <li>sleep = 间隔 − 开销 + 目标窗口(150ms)，并夹在合理区间。</li>
     * </ol>
     * 结果写进 SharedPreferences，下次启动直接用。
     */
    static void calibrateAsync(final Context ctx, final String pkg) {
        if (alignCalibrated) {
            return;
        }
        alignCalibrated = true;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long cached = app.getSharedPreferences("beeshot", 0)
                            .getLong(PREF_ALIGN_INTERVAL, 0L);
                    if (cached > 1_000L) {
                        freezeIntervalMs = cached;
                        Logx.i("[launch] 用缓存的冻结间隔=" + cached + "ms");
                        return;
                    }
                    int uid;
                    try {
                        uid = app.getPackageManager().getApplicationInfo(pkg, 0).uid;
                    } catch (Throwable t) {
                        Logx.w("[launch] 拿不到宿主 uid，跳过校准");
                        alignCalibrated = false;   // 允许下次重试
                        return;
                    }
                    RootShell.Result r = RootShell.exec(
                            "logcat -d -v epoch -t 3000 2>/dev/null | grep -E 'FZ uid = " + uid
                                    + " |THAW uid = " + uid + " ' | tail -60", 15_000L);
                    long interval = medianInterval(r.out == null ? "" : r.out);
                    if (interval <= 0) {
                        Logx.w("[launch] 校准失败：logcat 没有本机冻结记录，沿用默认 "
                                + alignSleepMs + "ms");
                        alignCalibrated = false;   // 允许下次点击再试
                        return;
                    }
                    // 只记"本机冻结间隔"；每次戳的耗时抖动由循环内**闭环自校正**吸收
                    // （每轮用 目标周期 − 本次耗时 当 sleep，周期恒定）。
                    freezeIntervalMs = interval;
                    app.getSharedPreferences("beeshot", 0).edit()
                            .putLong(PREF_ALIGN_INTERVAL, interval).apply();
                    Logx.i("[launch] 自适应校准完成：本机冻结间隔=" + interval
                            + "ms → 目标周期=" + (interval + 150L) + "ms（窗口目标 150ms）");
                } catch (Throwable t) {
                    Logx.w("[launch] 校准异常：" + t.getMessage());
                    alignCalibrated = false;   // 允许下次重试
                }
            }
        }, "bee-align-calib").start();
    }

    /**
     * 从 logcat 文本里算 THAW→FZ 间隔的中位数（毫秒）。
     *
     * @return 中位数；没有有效样本返回 -1
     */
    private static long medianInterval(String out) {
        java.util.ArrayList<Long> v = new java.util.ArrayList<Long>();
        long lastThaw = -1;
        for (String line : out.split("\n")) {
            String s = line.trim();
            int sp = s.indexOf(' ');
            if (sp <= 0) {
                continue;
            }
            long ts;
            try {
                ts = (long) (Double.parseDouble(s.substring(0, sp)) * 1000);
            } catch (Throwable t) {
                continue;
            }
            if (s.contains("THAW uid = ")) {
                lastThaw = ts;
            } else if (s.contains("FZ uid = ") && lastThaw > 0) {
                long d = ts - lastThaw;
                if (d > 500 && d < 120_000) {
                    v.add(d);
                }
                lastThaw = -1;
            }
        }
        if (v.isEmpty()) {
            return -1;
        }
        java.util.Collections.sort(v);
        return v.get(v.size() / 2);
    }

    /** 一次循环戳几下（5 下 ≈ 26 秒，覆盖一次识别的整个周期还有余量）。 */
    private static final int ALIGN_TICKS = 10;

    /** 只有一条对齐循环（连点时复用，避免两条相位互踩）。 */
    private static volatile boolean thawLoopRunning = false;

    /**
     * 起一条"对齐式"解冻循环：一次 su 里循环戳，周期约 5.15 秒，把冻结窗口压到 0.15 秒。
     *
     * <p>必须跑在**模块自己的进程**里（本类就是）：宿主被冻结时它内部所有线程一起被冻，
     * 在宿主里起的解冻线程自己也发不出 binder（实测只戳 1 次就停）。
     */
    private static void startThawLoop(final Context ctx, final String pkg) {
        if (thawLoopRunning) {
            return;
        }
        thawLoopRunning = true;
        String uri = "content://" + pkg + ".androidx-startup/";
        // timeout 兜底：即使某次 content 卡住，也不会留下永久循环
        // 目标周期 = 本机冻结间隔 + 150ms 窗口。
        // 循环内每轮实测本次戳耗时 d，用 T−d 当 sleep —— 这样周期恒定等于 T，
        // 戳耗时的抖动被自动吸收，不会因为某次戳变快就"戳空"（那会导致 5 秒窗口）。
        final long target = freezeIntervalMs + 150L;
        String cmd = "timeout " + (ALIGN_TICKS * 8) + " sh -c 'for i in $(seq 1 "
                + ALIGN_TICKS + "); do "
                + "a=$(date +%s%3N); "
                + "content query --uri " + uri + " >/dev/null 2>&1; "
                + "b=$(date +%s%3N); d=$((b-a)); sl=$(( " + target + " - d )); "
                + "[ $sl -lt 500 ] && sl=500; "
                + "sleep $((sl/1000)).$(printf %03d $((sl%1000))); "
                + "done'";
        final Process p = RootShell.startAsync(cmd);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (p != null) {
                        p.waitFor();
                    }
                } catch (Throwable ignored) {
                }
                thawLoopRunning = false;
                Logx.i("[launch] 对齐解冻循环结束");
            }
        }, "bee-thaw-watch").start();
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }
}
