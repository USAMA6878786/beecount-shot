package dev.xtgxiso.beecountshot;

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
            // 前提：截屏能力已就绪。没开无障碍就没有任何办法截屏，直接引导。
            if (!ShotAccessibilityService.isReady()) {
                Logx.w("[tile] accessibility service NOT ready -> guiding user to enable it");
                Toast.makeText(app, "请先开启「记账截图」的截屏服务", Toast.LENGTH_LONG).show();
                Intent settings = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivityAndCollapse(settings);
                return;
            }

            final long delay = Prefs.delayMs(app);
            final boolean root = RootShell.isGranted(app);
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
                        ShotAccessibilityService.scheduleShot(delay);

                        // 记账成功后自动删截图（独立的观察线程，不阻塞这里的收尾工作）。
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

    /** 非 root 的收面板方案：借 startActivityAndCollapse 启动一个透明页。 */
    private void collapseByActivity() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent proxy = new Intent(ShotTileService.this, CollapseProxyActivity.class);
                    proxy.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivityAndCollapse(proxy);
                    Logx.i("[tile] startActivityAndCollapse fired");
                } catch (Throwable t) {
                    Logx.e("[tile] startActivityAndCollapse failed", t);
                }
            }
        });
    }
}
