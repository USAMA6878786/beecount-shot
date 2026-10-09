package dev.xtgxiso.beecountshot;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * 控制中心「记账截图」磁贴。
 *
 * <p>点击后的完整时序（顺序不能换）：
 * <ol>
 *   <li><b>先放行</b>——必须早于截图，因为宿主是异步发现新截图的。
 *       有 root 时走两条通道：应用侧定向广播 + root 的 {@code am broadcast} 冗余通道
 *       （宿主退后台会被冻结，普通广播要等它解冻才投得到）。
 *       两条都只是"登记一张限时通行证"，且宿主侧对相同的截止时间做了去重，
 *       所以重复投递没有副作用。</li>
 *   <li><b>收面板</b>——有 root 走 {@code cmd statusbar collapse}；没有则退回
 *       {@code startActivityAndCollapse} + 透明中转页。</li>
 *   <li><b>再等一会儿</b>——等待时长是**从收起命令发出之后**起算的，这样无论 su 开销多大，
 *       截图时面板都已经收干净了。</li>
 *   <li><b>截屏</b>——主路径是 root 模拟"电源键+音量下"（系统真实截图流程）；
 *       root 三条命令都没截出图，才退回无障碍服务。</li>
 * </ol>
 *
 * <p>历史沿革（真机反馈驱动）：
 * <ul>
 *   <li>最初用 {@code startActivityAndCollapse} 收面板，实测在部分 ROM 上完全不生效，
 *       结果是截到的图里带着控制中心。改用 root 的 shell 命令。</li>
 *   <li>最初的延迟是从"点击磁贴"起算，su / 启动 Activity 的开销会吃掉这段延迟，
 *       于是"截图太快"。改成从收起命令发出后起算。</li>
 *   <li>中间版本用过"模块 App 用 root 往宿主目录写一个放行文件"，
 *       实测 {@code su} 跑在全局挂载命名空间、看不到应用数据目录，那条路是静默失效的，
 *       已彻底移除。现在只认广播（见 {@link ArmSignal}）。</li>
 *   <li>截屏原先依赖无障碍服务，但**应用被覆盖安装后系统会自动关掉它**，
 *       于是每次更新模块都会遇到"点了没反应"。改成 root 为主、无障碍为退路。</li>
 * </ul>
 */
public class ShotTileService extends TileService {

    /**
     * 最近一次"点击反馈"写入副标题的时刻。
     *
     * <p>用来挡住状态查询：真机日志里出现过——点下去刚显示"正在截屏…"，
     * 140 毫秒后就被 onStartListening 那次状态查询的结果（"已就绪 · 点一下截屏记账"）
     * 盖掉了，用户等于看不到点击反馈。
     */
    private static volatile long clickFeedbackAt = 0L;

    /** 点击反馈在副标题上保留多久不被状态查询覆盖。 */
    private static final long CLICK_FEEDBACK_HOLD_MS = 8000L;

    @Override
    public void onStartListening() {
        super.onStartListening();

        // 先查截屏能力：root 模拟组合键是主路径，无障碍只是退路——
        // 所以只有在"既没 root、无障碍又没开"时才提示。注意**每次覆盖安装后
        // 系统都会关掉无障碍服务**，这是最常见的一种"点了没反应"，
        // 直接写在副标题上，用户下拉面板就能看到。
        if (!ShotAccessibilityService.isReady() && !RootShell.isGranted(this)) {
            applyTileFromProbe("截屏服务未开启 · 点一下去开启", Tile.STATE_UNAVAILABLE);
            return;
        }

        applyTileFromProbe("检查中…", Tile.STATE_INACTIVE);

        // 副标题显示"现在能不能用"：蜜蜂记账的主界面被系统回收后它的截图监听会失效，
        // 这时提前在下拉面板里就告诉用户，比点完再弹提示有用。
        // 查询要走广播，不能在主线程等，所以放子线程拿到结果再刷新一次磁贴。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ShotBridge.Answer a = ShotBridge.query(
                            getApplicationContext(), "", 2500L);
                    if (!a.received) {
                        applyTileFromProbe("蜜蜂记账未运行", Tile.STATE_INACTIVE);
                    } else if (a.activityAlive) {
                        applyTileFromProbe("已就绪 · 点一下截屏记账", Tile.STATE_INACTIVE);
                    } else {
                        applyTileFromProbe("未就绪 · 先打开一次蜜蜂记账", Tile.STATE_UNAVAILABLE);
                    }
                } catch (Throwable t) {
                    Logx.w("[tile] status probe failed: " + t.getMessage());
                }
            }
        }, "bee-tile-status").start();
    }

    /**
     * 状态查询专用的副标题更新：**点击反馈还在展示期内就不覆盖**。
     *
     * <p>否则会出现"刚点完、反馈一闪就变回已就绪"，用户以为没反应。
     */
    private void applyTileFromProbe(String subtitle, int state) {
        long age = System.currentTimeMillis() - clickFeedbackAt;
        if (age < CLICK_FEEDBACK_HOLD_MS) {
            Logx.i("[tile] probe result not shown (click feedback still on, " + age
                    + "ms ago): " + subtitle);
            return;
        }
        applyTile(subtitle, state);
    }

    /**
     * 更新磁贴外观。**可以从任意线程调**（内部会切到主线程再碰 Tile）。
     *
     * <p>顺手把副标题写进日志：这是"那一下点击到底有没有送到磁贴服务"最直接的凭据。
     */
    private void applyTile(final String subtitle, final int state) {
        final ShotTileService self = this;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Tile tile = self.getQsTile();
                    if (tile == null) {
                        return;
                    }
                    tile.setState(state);
                    tile.setLabel("记账截图");
                    if (Build.VERSION.SDK_INT >= 29) {
                        tile.setSubtitle(subtitle);
                    }
                    tile.updateTile();
                    Logx.i("[tile] subtitle -> " + subtitle);
                } catch (Throwable t) {
                    Logx.w("[tile] update tile failed: " + t.getMessage());
                }
            }
        });
    }

    /**
     * 请系统重新绑定磁贴并回调 {@link #onStartListening()}，副标题因此会立刻刷新成最新状态。
     *
     * <p>为什么需要它：Android 在应用被**覆盖安装**之后，磁贴往往会被"晾着"——图标还在面板上，
     * 但绑定关系是旧的，表现出来就是"点了没反应"，过一阵子又自己好了。
     * 主动请求一次重新监听，能让它早点恢复，也让用户下拉面板时看到的是最新状态。
     *
     * <p>额外好处：即使磁贴不重新绑定，{@code onStartListening} 里那次对宿主的查询也会跑一遍，
     * 于是"蜜蜂记账截图监听不在位"这类问题会立刻显示在副标题上，而不是等用户点完才发现。
     */
    static void requestRefresh(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            TileService.requestListeningState(ctx,
                    new ComponentName(ctx, ShotTileService.class));
            Logx.i("[tile] asked the system to re-bind the tile");
        } catch (Throwable t) {
            Logx.w("[tile] requestListeningState failed: " + t.getMessage());
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        final Context app = getApplicationContext();
        Logx.init(Prefs.appLogPath(app));
        Logx.i("[tile] ===== clicked =====");

        // 立刻把"收到了"画在磁贴上。这一步专门用来把两种"点了没反应"区分开：
        //   副标题变了     → 点击送到了磁贴服务，问题在后面的截屏/记账环节；
        //   副标题纹丝不动 → 这一下压根没送到（应用刚覆盖安装完、磁贴还没重新绑定时会这样，
        //                    属于系统层行为，不是模块逻辑）。
        // 上一次"点了没反应"就是后一种：日志里连一条 clicked 都没有，无从下手。
        clickFeedbackAt = System.currentTimeMillis();
        applyTile("正在截屏…", Tile.STATE_ACTIVE);

        // 尽早把接收器注册好：宿主检测到记账成功时会主动广播把我们从冻结中唤醒，
        // 如果这时接收器还没注册，那条唤醒广播就丢了。
        ShotBridge.ensureReceiver(app);

        try {
            final boolean root = RootShell.isGranted(app);

            // 截屏的两条路：root（主）或无障碍服务（退路）。
            //
            // root 用 input keycombination 模拟"电源键+音量下"，走系统真实截图流程。
            // 之所以把它做成主路径：无障碍服务在**应用被覆盖安装后会被系统自动关闭**
            // （服务代码变了，之前那次授权作废）——以前每次更新模块，用户都会遇到
            // "磁贴点了没反应"。有 root 就完全不依赖无障碍；两条都没有才引导去开。
            // 引导逻辑本身也要绝对可靠，见 guideToAccessibilitySettings()。
            if (!ShotAccessibilityService.isReady() && !root) {
                Logx.w("[tile] no root and a11y not ready -> guiding user to enable it");
                guideToAccessibilitySettings();
                return;
            }
            final long clickAt = System.currentTimeMillis();
            // 窗口给得宽松一点：su 开销 + 系统落库 + ContentObserver 回调都要装进去。
            final long until = clickAt + Const.ARM_GRACE_MS + 6000L;

            Logx.i("[tile] root=" + root + " armWindow=+" + (until - clickAt) + "ms");

            // 通道 B：广播（很快，先在主线程发出去）
            sendArmBroadcast(app, until);

            if (root) {
                // 通道 A + 收面板：都在子线程做，避免阻塞主线程（su 是阻塞调用）。
                final ShotTileService self = this;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        long t0 = System.currentTimeMillis();
                        // 先解析出宿主真实包名（广播定向要用它；root 命令也用它）。
                        HostInfo.pkg(app);

                        // 冗余通道：再让 root 发一次放行信号。宿主退后台可能被冻结，
                        // 普通广播要等它解冻才投得到；root 这条不受应用后台限制。
                        // 宿主侧对相同的截止时间做了去重，所以两条一起发没有副作用。
                        //
                        // 这一步和下面的"收面板"合并成**一次 su**：每调一次 su 就是起一个
                        // 新进程（实测 150~250ms），分开做等于白等两百毫秒，而这段时间
                        // 直接算在"点击→截屏"的延迟里。
                        RootShell.Result r = RootShell.armAndCollapse(Const.ACTION_ARM, HostInfo.pkg(),
                                "--el " + Const.EXTRA_UNTIL + " " + until
                                        + " --ez " + Const.EXTRA_PRECISE + " "
                                        + (Prefs.precisePartial(app) ? "true" : "false"),
                                Intent.FLAG_RECEIVER_FOREGROUND
                                        | Const.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                        long spent = System.currentTimeMillis() - t0;
                        Logx.i("[tile] root stage finished in " + spent + "ms, exit=" + r.exit);

                        if (r.exit != 0) {
                            // root 收起失败（个别 ROM 没有 cmd statusbar）——退回非 root 方案。
                            Logx.w("[tile] root collapse failed, falling back to activity collapse");
                            self.collapseByActivity();
                        }

                        // **不再等待**：收起面板的命令一返回就立刻截屏。
                        //
                        // v2.18 把「收面板后先等一会儿再截屏」整个去掉了。那段等待原本是配合
                        // "root 注入按键"那条老路径的——注入的按键会被正在收起的面板吃掉，
                        // 所以要等面板收干净。现在截屏走无障碍直调系统的截屏动作，不经过
                        // 按键注入、跟面板状态无关，等待就没有存在理由了。
                        //
                        // 「收起面板」这个动作本身**保留**：不收的话控制面板会出现在截图里。
                        final boolean a11yReady = ShotAccessibilityService.isReady();
                        if (!a11yReady) {
                            // 无障碍没开 —— 用 root 帮用户把它打开。
                            //
                            // 这是**唯一**能让整个功能又快又稳的办法：无障碍直调系统截屏动作
                            // 不走按键注入，而 root 注入按键偶尔会完全没生效、白等 1~2 秒
                            // （就是"中间某一次卡住"的原因）。
                            //
                            // 覆盖安装模块后系统会自动关掉无障碍服务，用户往往不会再去开一次，
                            // 于是一直退在慢的路上。模块有 root，就自己把它办了。
                            //
                            // 本次点击仍然走 root；服务连上之后，**下一次点击**就走快路了。
                            try {
                                RootShell.ensureAccessibilityEnabled();
                            } catch (Throwable t) {
                                Logx.w("[tile] ensure a11y failed: " + t.getMessage());
                            }
                        }
                        Logx.i("[tile] 立刻截屏（不等待）a11y=" + a11yReady);
                        final long shotAt = System.currentTimeMillis();

                        // 记账成功后自动删截图（独立的观察线程，不阻塞截屏）。
                        // 必须在**截屏之前**就起好，否则可能错过删除窗口。
                        if (Prefs.autoDelete(app)) {
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    ShotCleaner.run(app, shotAt);
                                }
                            }, "bee-shot-cleaner").start();
                        } else {
                            Logx.i("[tile] auto-delete is OFF; screenshot will be kept");
                        }

                        // ---- 截屏 ----
                        //
                        // 主路径：**无障碍直调系统截屏动作**。它不走输入系统的按键注入，
                        // 所以不会和控制中心面板的收起动画抢时间——真机上这是唯一稳定的那条路：
                        // 原先用 root 模拟按键时，"第一次发命令毫无反应、白等 2 秒再换一条"
                        // 大约三成点击会踩到，表现就是"点了要等好久才开始截图"。
                        //
                        // 退路：root 模拟"电源键+音量下"（原来的做法，保留兜底）。
                        boolean sent = false;
                        if (a11yReady) {
                            RootShell.markNow();   // 一次 su，用来判断"图到底出来了没有"
                            sent = ShotAccessibilityService.shootNow();
                            if (sent && !RootShell.waitForNewShot(1500L)) {
                                // 系统受理了但没等到图 —— 退回 root，双保险
                                Logx.w("[tile] a11y 触发了但没等到新图 -> 退回 root 模拟按键");
                                sent = false;
                            }
                        } else {
                            Logx.i("[tile] 无障碍未就绪 -> 走 root 模拟按键");
                        }
                        if (!sent) {
                            sent = RootShell.takeScreenshotViaRoot();
                        }

                        // 把结果写回磁贴副标题。这样"点了没反应"永远有一个看得见的落点：
                        // 副标题停在"正在截屏…"说明流程中途断了，日志里对应位置会有报错。
                        clickFeedbackAt = System.currentTimeMillis();
                        if (sent) {
                            applyTile("已截屏 · 等蜜蜂记账处理", Tile.STATE_INACTIVE);
                        } else {
                            applyTile("截屏失败 · 请导出日志", Tile.STATE_UNAVAILABLE);
                        }

                        // 截屏之后等一会儿，把 logcat 快照落到 Download。
                        // 这一步是为了"失败现场"：logcat 会轮转，等用户发现问题再去导就晚了。
                        // 两个进程的日志都在里面（tag=BeeShot），成没成一目了然。
                        try {
                            Thread.sleep(4000L);
                        } catch (InterruptedException ignored) {
                        }
                        Logx.i("[tile] dumping logcat snapshot for this attempt");
                        RootShell.appendLogcatToDownload();
                    }
                }, "bee-shot-orchestrator").start();
            } else {
                Logx.i("[tile] no root -> using startActivityAndCollapse");
                collapseByActivity();
                Logx.i("[tile] 立刻截屏（不等待）");
                ShotAccessibilityService.scheduleShot(0);
            }
        } catch (Throwable t) {
            Logx.e("[tile] onClick failed", t);
        }
    }

    /**
     * 截屏服务没开时，把用户送到"无障碍设置"页。
     *
     * <p><b>这段代码曾经把用户坑惨了</b>，所以两条保障缺一不可：
     *
     * <ol>
     *   <li>Android 14（API 34）起**禁止 TileService 用 Intent 调
     *       {@code startActivityAndCollapse}**，会抛
     *       {@code UnsupportedOperationException: Starting activity from TileService
     *       using an Intent is not allowed}。必须改用 {@code PendingIntent} 版本。</li>
     *   <li>兜底的 Toast 也靠不住：Android 12 起后台应用发的 Toast 会被系统直接丢掉，
     *       而磁贴点击未必算"前台"。所以兜底改成**直接改磁贴自己的副标题**
     *       （此刻面板就开着，用户一定看得见），不依赖任何权限。</li>
     * </ol>
     */
    private void guideToAccessibilitySettings() {
        Intent settings = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(this, 1001, settings,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(settings);
            }
            Logx.i("[tile] guided user to accessibility settings");
            return;
        } catch (Throwable t) {
            Logx.e("[tile] startActivityAndCollapse failed, fallback to tile text", t);
        }
        // 最后一道保险：把状态写回磁贴自己，面板此刻就开着，用户一定看得见。
        applyTile("截屏服务未开启 · 点一下去开启", Tile.STATE_UNAVAILABLE);
        try {
            Toast.makeText(this, "请先开启「记账截图」的截屏服务", Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private void sendArmBroadcast(Context app, long until) {
        try {
            Intent arm = new Intent(Const.ACTION_ARM);
            arm.setPackage(HostInfo.pkg());
            arm.putExtra(Const.EXTRA_UNTIL, until);
            // 把设置页那个"推断着删"的开关搭这趟车送进宿主进程（见 Const.EXTRA_PRECISE）。
            arm.putExtra(Const.EXTRA_PRECISE, Prefs.precisePartial(app));
            arm.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            app.sendBroadcast(arm);
            Logx.i("[tile] arm broadcast sent (backup channel)");
        } catch (Throwable t) {
            Logx.e("[tile] arm broadcast failed", t);
        }
    }

    /**
     * 非 root 的收面板方案：借 {@code startActivityAndCollapse} 启动一个透明页。
     *
     * <p>和 {@link #guideToAccessibilitySettings()} 一样受 Android 14 的限制：
     * API 34 起 {@code startActivityAndCollapse(Intent)} 直接抛
     * {@code UnsupportedOperationException}，必须用 {@code PendingIntent} 版本。
     * 这里如果漏改，无 root 的用户点击磁贴会**静默无反应**。
     */
    private void collapseByActivity() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent proxy = new Intent(ShotTileService.this, CollapseProxyActivity.class);
                    proxy.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (Build.VERSION.SDK_INT >= 34) {
                        PendingIntent pi = PendingIntent.getActivity(
                                ShotTileService.this, 1002, proxy,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                        startActivityAndCollapse(pi);
                    } else {
                        startActivityAndCollapse(proxy);
                    }
                    Logx.i("[tile] startActivityAndCollapse fired");
                } catch (Throwable t) {
                    Logx.e("[tile] startActivityAndCollapse failed", t);
                }
            }
        });
    }
}
