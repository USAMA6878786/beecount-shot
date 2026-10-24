package dev.xtgxiso.beecountshot;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * 控制中心「记账截图」磁贴。
 *
 * <p><b>只有一条截屏路径：root。</b>没有 root 就直接引导用户去授权，不再退无障碍——
 * 理由见类尾的「为什么删掉无障碍那条路」。
 *
 * <p>点击后的完整时序（顺序不能换）：
 * <ol>
 *   <li><b>先放行</b>——必须早于截图，因为宿主是异步发现新截图的。
 *       走两条通道：应用侧定向广播 + root 的 {@code am broadcast} 冗余通道
 *       （宿主退后台会被冻结，普通广播要等它解冻才投得到）。
 *       两条都只是"登记一张限时通行证"，且宿主侧对相同的截止时间做了去重，
 *       所以重复投递没有副作用。</li>
 *   <li><b>收面板</b>——{@code cmd statusbar collapse}（和上一步合并成一次 su）。</li>
 *   <li><b>立刻截屏，不等待</b>——收面板命令一返回就发截屏请求。
 *       早先这里有一段"等面板收完"的固定等待（150~1300ms 可选），已整个删掉：
 *       那段等待是配合"root 注入按键"那条老路的，而现在抓帧走 {@code screencap}，
 *       不经过输入系统，跟面板状态无关（只留 100ms 躲开收起动画的第一帧）。</li>
 *   <li><b>截屏</b>——{@code screencap} 直接抓帧（raw 通道校准过就走 raw，
 *       更快也更容易压小）；失败才退回 root 注入"电源键+音量下"。</li>
 * </ol>
 *
 * <p>历史沿革（真机反馈驱动）：
 * <ul>
 *   <li>最初用 {@code startActivityAndCollapse} 收面板，实测在部分 ROM 上完全不生效，
 *       结果是截到的图里带着控制中心。改用 root 的 shell 命令。</li>
 *   <li>中间版本用过"模块 App 用 root 往宿主目录写一个放行文件"，
 *       实测 {@code su} 跑在全局挂载命名空间、看不到应用数据目录，那条路是静默失效的，
 *       已彻底移除。现在只认广播（见 {@link ArmSignal}）。</li>
 *   <li>截屏路径换过三次：无障碍直调 → root 注入按键 → {@code screencap} 抓帧。
 *       无障碍那条最终被整个删掉（见类尾说明）。</li>
 * </ul>
 */
public class ShotTileService extends TileService {

    /**
     * 磁贴唯一的固定外观：**待命**。
     *
     * <p>它是一颗按钮，不是仪表盘。所以标签、副标题、状态三样东西都是常量：
     * 副标题只说明"点一下干什么"，不带任何"能不能用/正在干什么"的判断。
     */
    private static final String TILE_LABEL = "记账截图";
    private static final String TILE_SUBTITLE = "点一下截屏记账";

    @Override
    public void onStartListening() {
        super.onStartListening();
        ensureIdleTile();
    }

    /**
     * 把磁贴摆回待命态；**已经对了就一个字节都不写**。
     *
     * <p>为什么必须"没变就不调 {@code updateTile()} "：MIUI/HyperOS 的磁贴每次
     * {@code updateTile()} 都会重绘并跑一遍状态过渡动画，哪怕内容一样。以前这里
     * 先写「检查中…」（INACTIVE）再写「点一下截屏记账」（ACTIVE），下拉面板就能看到
     * 图标先暗一下、再亮起来——两帧之间还夹着一次查询广播。
     *
     * <p>现在只写一次，而且只在真的不对的时候写（比如刚覆盖安装、系统缓存了旧外观）。
     */
    private void ensureIdleTile() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Tile tile = getQsTile();
                    if (tile == null) {
                        return;
                    }
                    boolean changed = false;
                    if (tile.getState() != Tile.STATE_INACTIVE) {
                        tile.setState(Tile.STATE_INACTIVE);
                        changed = true;
                    }
                    CharSequence label = tile.getLabel();
                    if (label == null || !TILE_LABEL.contentEquals(label)) {
                        tile.setLabel(TILE_LABEL);
                        changed = true;
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        CharSequence sub = tile.getSubtitle();
                        if (sub == null || !TILE_SUBTITLE.contentEquals(sub)) {
                            tile.setSubtitle(TILE_SUBTITLE);
                            changed = true;
                        }
                    }
                    if (!changed) {
                        Logx.i("[tile] appearance already idle, nothing to redraw");
                        return;
                    }
                    tile.updateTile();
                    Logx.i("[tile] idle appearance applied");
                } catch (Throwable t) {
                    Logx.w("[tile] update tile failed: " + t.getMessage());
                }
            }
        });
    }

    /**
     * 请系统重新绑定磁贴并回调 {@link #onStartListening()}。
     *
     * <p>为什么需要它：Android 在应用被**覆盖安装**之后，磁贴往往会被"晾着"——图标还在面板上，
     * 但绑定关系是旧的，表现出来就是"点了没反应"，过一阵子又自己好了。
     * 主动请求一次重新监听，能让它早点恢复成可点的状态。
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

        // 顺手确认宿主进程还在：不在就用它的 widget provider 广播把它叫回来。
        // **并行**做，不挡截图；等图片写进相册、宿主检测到的时候，进程通常已经起来了。
        final Context launchCtx = app;
        if (launchCtx != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    // pullUp 内部已经把"拉起 → 等接收器就绪 → 派发（含补发）"串起来了
                    HostLauncher.pullUp(launchCtx);
                }
            }, "bee-launch-host").start();
        }

        // 点下去**不动磁贴**：不切成 ACTIVE、不写"正在截屏…"。
        // 切成 ACTIVE 会让整块磁贴亮起来（看起来像在"处理中"，也像不能点了），
        // 处理完再变回去又是一次动画。结果反馈只走 Toast（"截图成功/失败"），
        // 磁贴自始至终是同一副待命的样子，随时可以再点。
        // 注意 Android 12+ 会丢掉后台 Toast，所以要先授悬浮窗权限，见 ShotToast。

        // 尽早把接收器注册好：宿主检测到记账成功时会主动广播把我们从冻结中唤醒，
        // 如果这时接收器还没注册，那条唤醒广播就丢了。
        ShotBridge.ensureReceiver(app);

        // 顺手确保常驻服务在跑（用户在后台点了磁贴时这里多半会被系统拒掉，
        // 但包在 try 里没副作用；真正能把服务拉起来的时机是打开设置页）。
        if (Prefs.keepAlive(app)) {
            KeepAliveService.ensureChannel(app);   // 幂等、一次进程只做一次
            KeepAliveService.start(app);
        }

        try {
            final boolean root = RootShell.isGranted(app);

            // 只有 root 一条路。没有 root 就直接把用户送到设置页去授权——
            // 不再退无障碍：那条路要用户手动开服务、覆盖安装后还会被系统关掉，
            // 而且它和 root 抓帧的产物完全不同（系统截图 vs 我们自己写相册）。
            if (!root) {
                Logx.w("[tile] no root -> guiding user to grant it");
                guideToRootPermission();
                return;
            }
            final long clickAt = System.currentTimeMillis();
            // 窗口给得宽松一点：su 开销 + 系统落库 + ContentObserver 回调都要装进去。
            final long until = clickAt + Const.ARM_GRACE_MS + 6000L;

            Logx.i("[tile] root=true armWindow=+" + (until - clickAt) + "ms");

            // 通道 B：广播（很快，先在主线程发出去）
            sendArmBroadcast(app, until);

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
                    // 注意：这里不再立"标记文件"了。它原来是给无障碍那条路
                    // （waitForNewShot）判断"有没有出新图"用的；那条路删掉之后，
                    // 标记就没人读了，白多一次 touch。
                    RootShell.Result r = RootShell.armAndCollapse(Const.ACTION_ARM, HostInfo.pkg(),
                            "--el " + Const.EXTRA_UNTIL + " " + until
                                    + " --ez " + Const.EXTRA_PRECISE + " "
                                    + "true"
                                    + " --ez " + Const.EXTRA_AUTO_DELETE + " "
                                    + (Prefs.autoDelete(app) ? "true" : "false"),
                            Intent.FLAG_RECEIVER_FOREGROUND
                                    | Const.FLAG_RECEIVER_INCLUDE_BACKGROUND, null);
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
                    // 收面板命令返回后问一下要不要再等一下（见 Const.SHOT_SETTLE_MS）。
                    // 现在是 0 —— 收面板命令一返回就抓帧，最快。
                    if (Const.SHOT_SETTLE_MS > 0L) {
                        try {
                            Thread.sleep(Const.SHOT_SETTLE_MS);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    Logx.i("[tile] 截屏（收面板后等了 " + Const.SHOT_SETTLE_MS + "ms）");
                    final long shotAt = System.currentTimeMillis();

                    // 记账成功后自动删截图（独立的观察线程，不阻塞截屏）。
                    // 必须在**截屏之前**就起好，否则可能错过删除窗口。
                    if (Prefs.autoDelete(app)) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                ShotCleaner.submit(app, shotAt);
                            }
                        }, "bee-shot-cleaner").start();
                    } else {
                        Logx.i("[tile] auto-delete is OFF; screenshot will be kept");
                    }

                    // ---- 截屏 ----
                    //
                    // 主路径：root 直接抓帧（raw 通道校准过走 raw，否则 PNG）。
                    // 抓完自己往相册插一条截图，蜜蜂记账的观察者照常收到通知。
                    // 失败只有一种兜底：root 注入"电源键+音量下"（系统真实截图流程）。
                    boolean sent = false;
                    String via = "none";

                    if (ScreencapShot.isAvailable(app)) {
                        long sc0 = System.currentTimeMillis();
                        String p = ScreencapShot.take(app);
                        if (p != null) {
                            sent = true;
                            via = "screencap";
                            Logx.i("[tile] 截图路径=screencap 成功（"
                                    + (System.currentTimeMillis() - sc0) + "ms）");
                        } else {
                            Logx.w("[tile] screencap 失败 -> 退回 root 注入按键");
                        }
                    }

                    if (!sent) {
                        sent = RootShell.takeScreenshotViaRoot();
                        if (sent) {
                            via = "root-input";
                        }
                    }
                    Logx.i("[tile] 截图结果：路径=" + via + " 成功=" + sent);

                    // 用户只要知道"截图成没成"——一句话 Toast 就够。
                    if (sent) {
                        ShotToast.ok(app);
                    } else {
                        ShotToast.fail(app, "截屏失败");
                    }

                    // 结果不再写回磁贴：成功有 Toast「截图成功」，失败有「截图失败」。
                    // 磁贴保持待命外观，不给它任何"状态"含义，也就没有可点不了的时候。

                    // 只有**这次没截到图**才落 logcat 快照。
                    // 原来是每次点击都等 4 秒再开一次 su 把 logcat 写进 Download——
                    // 成功的点击（绝大多数）纯属白费：多一次 su、多 4 秒线程、多一次写盘。
                    // 失败现场才是真正需要它的地方，日志当场落盘，等用户发现问题再看也还在。
                    if (!sent) {
                        try {
                            Thread.sleep(2000L);
                        } catch (InterruptedException ignored) {
                        }
                        Logx.i("[tile] capture failed, dumping logcat snapshot");
                        RootShell.appendLogcatToDownload();
                    }
                }
            }, "bee-shot-orchestrator").start();
        } catch (Throwable t) {
            Logx.e("[tile] onClick failed", t);
        }
    }

    /**
     * 没有 root 时，把用户送到设置页去授权。
     *
     * <p>为什么不是直接弹 root 请求：{@code su} 的授权框属于 root 管理器，
     * 从磁贴（后台）发起时机很不稳，而且新装的模块还没被 LSPosed 作用域信任时更容易被静默拒绝。
     * 送到设置页，用户点一下「root 权限」按钮，路径最确定。
     *
     * <p><b>两个坑必须绕开</b>（都踩过）：
     * <ol>
     *   <li>Android 14（API 34）起**禁止 TileService 用 Intent 调
     *       {@code startActivityAndCollapse}**，会抛
     *       {@code UnsupportedOperationException}。必须用 {@code PendingIntent} 版本。</li>
     *   <li>兜底的 Toast 靠不住：Android 12 起后台应用发的 Toast 会被丢，
     *       而磁贴点击未必算"前台"。所以磁贴外观一律不动，只做"送到设置页"这一件事。</li>
     * </ol>
     */
    private void guideToRootPermission() {
        Intent settings = new Intent(this, SettingsActivity.class);
        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(this, 1001, settings,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(settings);
            }
            Logx.i("[tile] no root, guided user to settings");
            return;
        } catch (Throwable t) {
            Logx.e("[tile] startActivityAndCollapse failed, fallback to toast", t);
        }
        try {
            Toast.makeText(this, "点一下磁贴记账需要 root：请打开「记账截图」并点「root 权限」",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private void sendArmBroadcast(Context app, long until) {
        try {
            Intent arm = new Intent(Const.ACTION_ARM);
            arm.setPackage(HostInfo.pkg());
            arm.putExtra(Const.EXTRA_UNTIL, until);
            // 把设置页那个"推断着删"的开关搭这趟车送进宿主进程（见 Const.EXTRA_PRECISE）。
            arm.putExtra(Const.EXTRA_PRECISE, true);
            arm.putExtra(Const.EXTRA_AUTO_DELETE, Prefs.autoDelete(app));
            arm.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            app.sendBroadcast(arm);
            Logx.i("[tile] arm broadcast sent (backup channel)");
        } catch (Throwable t) {
            Logx.e("[tile] arm broadcast failed", t);
        }
    }

    /**
     * 收面板的兜底方案：root 的 {@code cmd statusbar collapse} 失败时（个别 ROM 没有这个命令），
     * 借 {@code startActivityAndCollapse} 启动一个透明页把面板顶掉。
     *
     * <p>和 {@link #guideToRootPermission()} 一样受 Android 14 的限制：
     * API 34 起 {@code startActivityAndCollapse(Intent)} 直接抛
     * {@code UnsupportedOperationException}，必须用 {@code PendingIntent} 版本。
     * 这里如果漏改，root 收面板失败的那次点击会**静默无反应**。
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
