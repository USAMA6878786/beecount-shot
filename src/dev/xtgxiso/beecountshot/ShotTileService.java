package dev.xtgxiso.beecountshot;

import android.app.PendingIntent;
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
 *       有 root 走放行文件（主通道），同时补一条广播（备用）。</li>
 *   <li><b>收面板</b>——有 root 走 `cmd statusbar collapse`；没有则退回
 *       `startActivityAndCollapse` + 透明中转页。</li>
 *   <li><b>再等一会儿</b>——等待时长是**从收起命令发出之后**起算的，这样无论 su 开销多大，
 *       截图时面板都已经收干净了。</li>
 *   <li><b>截屏</b>——交给无障碍服务执行系统级截屏。</li>
 * </ol>
 *
 * <p>v1.1 相对 v1.0 的变化，全部来自真机反馈：
 * <ul>
 *   <li>v1.0 用 `startActivityAndCollapse` 收面板，实测在部分 ROM 上完全不生效，
 *       结果是截到的图里带着控制中心。改用 root 的 shell 命令。</li>
 *   <li>v1.0 的延迟是从"点击磁贴"起算，su / 启动 Activity 的开销会吃掉这段延迟，
 *       所以"截图太快"。改成从收起命令发出后起算。</li>
 *   <li>v1.0 只用广播送放行信号，任一环节断了就静默失败。现在加了不问任何环节的放行文件。</li>
 * </ul>
 */
public class ShotTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();

        // 先查截屏能力：root 模拟组合键是主路径，无障碍只是退路——
        // 所以只有在"既没 root、无障碍又没开"时才提示。注意**每次覆盖安装后
        // 系统都会关掉无障碍服务**，这是最常见的一种"点了没反应"，
        // 直接写在副标题上，用户下拉面板就能看到。
        if (!ShotAccessibilityService.isReady() && !RootShell.isGranted(this)) {
            applyTile("截屏服务未开启 · 点一下去开启", Tile.STATE_UNAVAILABLE);
            return;
        }

        applyTile("检查中…", Tile.STATE_INACTIVE);

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
                        applyTile("蜜蜂记账未运行", Tile.STATE_INACTIVE);
                    } else if (a.activityAlive) {
                        applyTile("已就绪 · 点一下截屏记账", Tile.STATE_INACTIVE);
                    } else {
                        applyTile("未就绪 · 请先打开蜜蜂记账", Tile.STATE_UNAVAILABLE);
                    }
                } catch (Throwable t) {
                    Logx.w("[tile] status probe failed: " + t.getMessage());
                }
            }
        }, "bee-tile-status").start();
    }

    private void applyTile(final String subtitle, final int state) {
        try {
            Tile tile = getQsTile();
            if (tile == null) {
                return;
            }
            tile.setState(state);
            tile.setLabel("记账截图");
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(subtitle);
            }
            tile.updateTile();
        } catch (Throwable t) {
            Logx.w("[tile] update tile failed: " + t.getMessage());
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        final Context app = getApplicationContext();
        Logx.init(Prefs.appLogPath(app));
        Logx.i("[tile] ===== clicked =====");

        // 尽早把接收器注册好：宿主检测到记账成功时会主动广播把我们从冻结中唤醒，
        // 如果这时接收器还没注册，那条唤醒广播就丢了。
        ShotBridge.ensureReceiver(app);

        try {
            final long delay = Prefs.delayMs(app);
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
            // 窗口给得宽松一点：su 开销 + 延迟 + 系统落库 + ContentObserver 回调都要装进去。
            final long until = clickAt + delay + Const.ARM_GRACE_MS + 6000L;

            Logx.i("[tile] delay=" + delay + "ms root=" + root
                    + " armWindow=+" + (until - clickAt) + "ms");

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
                        // 幂等（截止时间是取最大值），所以两条一起发没有副作用。
                        RootShell.broadcast(Const.ACTION_ARM, HostInfo.pkg(),
                                Const.EXTRA_UNTIL, until,
                                Intent.FLAG_RECEIVER_FOREGROUND
                                        | Const.FLAG_RECEIVER_INCLUDE_BACKGROUND);

                        RootShell.Result r = RootShell.collapseStatusBar();
                        long spent = System.currentTimeMillis() - t0;
                        Logx.i("[tile] root stage finished in " + spent + "ms, exit=" + r.exit);

                        if (r.exit != 0) {
                            // root 收起失败（个别 ROM 没有 cmd statusbar）——退回非 root 方案。
                            Logx.w("[tile] root collapse failed, falling back to activity collapse");
                            self.collapseByActivity();
                        }

                        // 延迟从"收起命令已发出"之后起算，保证面板来得及收干净。
                        Logx.i("[tile] shooting in " + delay + "ms");
                        final long shotAt = System.currentTimeMillis();
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ignored) {
                        }

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

                        // 主路径：root 触发系统截屏（内部会自己验证图有没有出来）。
                        // 不依赖无障碍服务，所以覆盖安装后系统关掉它也无所谓。
                        boolean sent = RootShell.takeScreenshotViaRoot();

                        // root 三条命令都没截出图，才退回无障碍服务——双保险，谁成用谁。
                        if (!sent) {
                            Logx.w("[tile] root screenshot produced nothing -> falling back to a11y");
                            if (ShotAccessibilityService.isReady()) {
                                ShotAccessibilityService.scheduleShot(0);
                            } else {
                                Logx.e("[tile] neither root nor a11y produced a screenshot;"
                                        + " see the [root] shot cmd[] lines above");
                            }
                        }

                        // 截屏之后等一会儿，把 logcat 快照落到 Download。
                        // 这一步是为了"失败现场"：logcat 会轮转，等用户发现问题再去导就晚了。
                        // 两个进程的日志都在里面（tag=BeeShot），成没成一目了然。
                        try {
                            Thread.sleep(delay + 4000L);
                        } catch (InterruptedException ignored) {
                        }
                        Logx.i("[tile] dumping logcat snapshot for this attempt");
                        RootShell.appendLogcatToDownload();
                    }
                }, "bee-shot-orchestrator").start();
            } else {
                Logx.i("[tile] no root -> using startActivityAndCollapse");
                collapseByActivity();
                Logx.i("[tile] shooting in " + delay + "ms");
                ShotAccessibilityService.scheduleShot(delay);
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
