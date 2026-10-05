package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;

/**
 * 宿主进程侧：盯着蜜蜂记账的成功日志，**由宿主自己**把截图删掉。
 *
 * <p>为什么删除动作挪到宿主来做：模块 App 点完磁贴退回后台会被 Android 的
 * <b>后台应用冻结</b>冻住，而"往缓存态应用发广播唤它起来"这条路会被系统**延迟投递**
 * （真机日志里所有回包都是 {@code decisive=false}，说明唤醒广播压根没到），
 * 删除只能等应用偶然被解冻，于是慢了十几二十秒。
 *
 * <p>而宿主在那一刻是醒着的（正在跑 AI、正在写日志），让它自己动手，延迟就只剩
 * "蜜蜂记账写日志的节流（约 2 秒）"。宿主删除公共存储里的文件需要 root，见 {@link HostRoot}。
 *
 * <p>监听用 {@code OnSharedPreferenceChangeListener}，且**必须用静态强引用**持有，
 * 因为 SharedPreferences 只持有监听器的弱引用。
 */
final class HostWatcher {

    private static final String FLUTTER_PREFS = "FlutterSharedPreferences";

    /** 强引用，防止监听器被回收。 */
    private static volatile SharedPreferences watched;
    private static volatile SharedPreferences.OnSharedPreferenceChangeListener listener;
    private static volatile Context appCtx;

    /**
     * 已经为哪一次放行处理过，避免重复。
     *
     * <p>这只是**快路径去重**（同一个放行时间戳不再走一遍完整判定），
     * 真正的防误删判据在 {@link HostVerdict} 里——那边用的是"成功时间戳有没有被用过"，
     * 比这里更严格，能挡住"隔几秒再点一次、拿上一次的成功删这一次的图"。
     */
    private static volatile long handledForAt = -1L;

    // ---------------- 诊断计数（会随广播回传给模块 App，便于排查） ----------------
    private static volatile int watchEvents = 0;
    private static volatile int pushCount = 0;
    private static volatile int hostDeleteOk = 0;
    private static volatile String lastAction = "<none>";
    private static volatile String deletedPath = "";
    private static volatile long deletedAt = 0L;

    static void start(Context ctx) {
        if (watched != null || ctx == null) {
            return;
        }
        synchronized (HostWatcher.class) {
            if (watched != null) {
                return;
            }
            try {
                appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
                final SharedPreferences sp = appCtx.getSharedPreferences(
                        FLUTTER_PREFS, Context.MODE_PRIVATE);
                listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                    @Override
                    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                        if (!Const.K_APP_LOGS.equals(key)) {
                            return;
                        }
                        watchEvents++;
                        try {
                            final long successTs = HostProbe.latestSuccessTs(
                                    prefs.getString(Const.K_APP_LOGS, null));

                            // 任何一次成功记账都顺手让桌面小组件刷新——不限于"走磁贴那一次"。
                            // 你在蜜蜂记账里手动记的账也能立刻反映到小组件上，不用等 30 分钟周期。
                            if (successTs > 0L && successTs != lastPingedTs) {
                                lastPingedTs = successTs;
                                pingWidget(appCtx);
                            }

                            // 删不删由 HostVerdict 一家说了算（三条判据缺一不可）。
                            // 判定必须在**起线程之前**同步做完：否则同一次成功可能被
                            // 两条并行的回调各用一次，各删一张图。
                            final String path = LastShot.path();
                            if (!HostVerdict.shouldDelete(path, successTs)) {
                                return;
                            }
                            // 先占位再删：占位的那一刻起，同一次成功就不能再兑换第二张图了。
                            HostVerdict.consume(path, successTs);
                            handledForAt = LastShot.at();
                            Logx.i("[watch] success confirmed (ts=" + successTs
                                    + ", allowedAt=" + LastShot.at() + ") path=" + path);

                            // 一定要放到子线程：首次确认 root 会弹授权框、可能阻塞十几秒，
                            // 而这里回调跑在主线程（插件 apply() 的线程），阻塞会 ANR。
                            final Context ctx2 = appCtx;
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    handleSuccess(ctx2, path, successTs);
                                }
                            }, "bee-host-delete").start();
                        } catch (Throwable t) {
                            Logx.e("[watch] onSharedPreferenceChanged failed", t);
                        }
                    }
                };
                sp.registerOnSharedPreferenceChangeListener(listener);
                watched = sp;
                Logx.i("[watch] watching BeeCount success log (host-side deletion enabled)");
            } catch (Throwable t) {
                Logx.e("[watch] start failed", t);
            }
        }
    }

    /**
     * 成功后的处理：优先由宿主自己删（快且不受冻结影响）；做不到再广播让模块 App 删。
     */
    private static void handleSuccess(Context ctx, String path, long successTs) {
        try {
            boolean safe = RootShell.isSafeScreenshotPath(path);
            if (!safe) {
                lastAction = "unsafe-path";
                Logx.w("[watch] path failed safety check, not deleting: " + path);
            } else if (HostRoot.ensure(ctx)) {
                if (HostRoot.delete(path)) {
                    hostDeleteOk++;
                    lastAction = "host-deleted";
                    deletedPath = path;
                    deletedAt = System.currentTimeMillis();
                    HostTelemetry.put("deleted_path", path);
                    HostTelemetry.put("deleted_at", deletedAt);
                    scanGallery(ctx, path);
                    // 告知模块 App（只为日志/提示，它删不删都不影响结果）
                    broadcast(ctx, path, successTs, true, true);
                    return;
                }
                lastAction = "host-delete-failed";
            } else {
                lastAction = "no-host-root";
            }

            // 兜底：让模块 App 自己删。系统可能延迟投递，所以这只是保底。
            pushCount++;
            broadcast(ctx, path, successTs, true, false);
            lastAction = lastAction + "+pushed";
        } catch (Throwable t) {
            Logx.e("[watch] handleSuccess failed", t);
        }
    }

    private static void scanGallery(Context ctx, String path) {
        try {
            MediaScannerConnection.scanFile(ctx, new String[]{path}, null, null);
            Logx.i("[watch] gallery entry purge requested for " + path);
        } catch (Throwable t) {
            Logx.e("[watch] media scan failed", t);
        }
    }

    private static void broadcast(Context ctx, String path, long successTs,
                                  boolean processed, boolean deleted) {
        try {
            Intent r = new Intent(Const.ACTION_RESULT);
            r.setPackage(Const.MODULE_PKG);
            r.putExtra(Const.EXTRA_DECISIVE, true);
            r.putExtra(Const.EXTRA_PATH, path == null ? "" : path);
            r.putExtra(Const.EXTRA_PROCESSED, processed);
            r.putExtra(Const.EXTRA_SUCCESS, processed);
            r.putExtra(Const.EXTRA_SUCCESS_TS, successTs);
            r.putExtra(Const.EXTRA_DELETED, deleted);
            r.putExtra(Const.EXTRA_ACTIVITY_ALIVE, HostState.isActivityAlive());
            r.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            ctx.sendBroadcast(r);
            Logx.i("[watch] result broadcast sent (deleted=" + deleted + ")");
        } catch (Throwable t) {
            Logx.e("[watch] broadcast failed", t);
        }
    }

    /** 宿主是否已经删掉过这个文件（按文件名比）。 */
    static boolean wasDeleted(String path) {
        String d = deletedPath;
        if (d == null || d.length() == 0 || path == null) {
            return false;
        }
        String a = baseName(d);
        String b = baseName(path);
        return a.length() > 0 && a.equals(b);
    }

    private static String baseName(String p) {
        if (p == null) {
            return "";
        }
        String s = p.trim();
        int slash = s.lastIndexOf('/');
        return (slash >= 0) ? s.substring(slash + 1) : s;
    }

    /**
     * 通知模块 App 的桌面小组件刷新数据。
     *
     * <p>这条广播发的是模块 App **清单里声明**的 widget provider，所以即使它的进程已经
     * 被系统回收，也能被唤起来完成刷新——这比"等它自己轮询"可靠得多。
     */
    static void pingWidget(final Context ctx) {
        try {
            Intent i = new Intent(Const.ACTION_WIDGET_PING);
            i.setPackage(Const.MODULE_PKG);
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            ctx.sendBroadcast(i);
            Logx.i("[watch] widget refresh ping sent");
        } catch (Throwable t) {
            Logx.e("[watch] ping widget failed", t);
        }

        // 冗余通道：root 的 am broadcast。模块 App 退后台会被冻结，普通广播要等它解冻
        // 才投得到；root 这条不受应用后台限制，还带 FLAG_RECEIVER_INCLUDE_BACKGROUND，
        // 系统会直接投给后台接收器。幂等——多刷一次没有副作用。
        // 必须在子线程：HostRoot.ensure 首次会弹授权框并阻塞。
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (HostRoot.ensure(ctx)) {
                            HostRoot.broadcast(Const.ACTION_WIDGET_PING, Const.MODULE_PKG,
                                    null, 0L,
                                    Intent.FLAG_RECEIVER_FOREGROUND
                                            | Const.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                        }
                    } catch (Throwable t) {
                        Logx.w("[watch] root widget ping failed: " + t.getMessage());
                    }
                }
            }, "bee-widget-ping-root").start();
        } catch (Throwable t) {
            Logx.w("[watch] root widget ping thread failed: " + t.getMessage());
        }
    }

    /** 已经为哪一次成功推过小组件刷新，避免重复。 */
    private static volatile long lastPingedTs = -1L;

    /** 诊断快照，随查询回包带给模块 App。 */
    static String diag() {
        return "watch_started=" + (watched != null)
                + "|watch_events=" + watchEvents
                + "|push_count=" + pushCount
                + "|host_delete_ok=" + hostDeleteOk
                + "|host_root=" + HostRoot.stateName()
                + "|last_watch_action=" + lastAction
                + "|deleted_path=" + deletedPath
                + "|deleted_at=" + deletedAt;
    }

    private HostWatcher() {
    }
}
