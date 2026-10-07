package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * 「记账成功后自动删除这张截图」（模块 App 侧的兜底路径）。
 *
 * <p><b>判定权不在本类手里。</b> 是否该删由宿主进程给结论（{@link ShotBridge.Answer#success}），
 * 因为只有宿主同时握有两个事实：
 * <ol>
 *   <li><b>放行的是哪张图</b>：闸门放行时的实参（{@code LastShot}）；</li>
 *   <li><b>成功是否发生在这张图的放行之后</b>：它 Dart 日志里「自动记账成功」的时间戳。</li>
 * </ol>
 *
 * <p>这里曾经自己算判定，只检查"成功时间晚于本次截图"，结果误删了一张
 * **识别失败**（"未识别到账单"）的截图——因为前一次成功的时间恰好满足那个宽松条件。
 * 现在一律以宿主的结论为准，模块 App 只负责执行删除。
 *
 * <p>正常情况下轮不到本类动手：宿主在自己进程里就把删除做完了（它不会被系统冻结，
 * 而模块 App 退回后台会被冻结，见 {@link HostWatcher}）。这里只处理"宿主没 root"的兜底。
 *
 * <p>确认不了就原样保留。宁可漏删，绝不误删。
 */
final class ShotCleaner {

    /** 等宿主主动推送的时长。被唤醒时立即返回。 */
    private static final long AWAIT_MS = 15000L;

    /** 主动查询宿主的超时。 */
    private static final long QUERY_TIMEOUT_MS = 3000L;

    /** 总等待上限。 */
    private static final long MAX_WAIT_MS = 60000L;

    /**
     * @param shotTime 本次截图流程的起始时刻（毫秒）。用来定位"本次触发后新增的成品文件"。
     */
    static void run(final Context ctx, final long shotTime) {
        try {
            // 必须尽早注册好接收器，否则宿主的唤醒广播没人接。
            ShotBridge.ensureReceiver(ctx);

            // 先确认蜜蜂记账的截图监听是否在位。
            //
            // 它的监听器挂在主界面上，主界面被系统回收后就彻底感知不到截图——这时不管
            // 我们放不放行都不会记账。这种情况早点告诉用户，比默默失败好。
            //
            // 注：这里刻意用"检测并提示"而不是"自动把蜜蜂记账拉到前台"——拉起 Activity
            // 必然会让它的界面跳到前台，那正是用户不想要的。
            try {
                ShotBridge.Answer probe = ShotBridge.query(ctx, "", 2500L);
                if (!probe.received) {
                    Logx.w("[clean] 宿主无回应，无法判断截图监听是否在位");
                } else if (!probe.activityAlive) {
                    Logx.w("[clean] 蜜蜂记账的截图监听不在位（MainActivity 已被回收）"
                            + " -> 本次很可能不会被记入");
                    toast(ctx, "蜜蜂记账的截图监听未就绪，本次可能不会被记入（先打开一次它即可）");
                    // 让磁贴的副标题也立刻变成"未就绪"——Toast 在部分系统上会被悄悄丢掉，
                    // 而磁贴就在用户手指底下，比 Toast 可靠。requestListeningState 会触发
                    // onStartListening，那里会重新查一次宿主状态并刷新副标题。
                    ShotTileService.requestRefresh(ctx);
                } else {
                    Logx.i("[clean] 蜜蜂记账截图监听在位，正常");
                }
            } catch (Throwable ignored) {
            }

            String candidate = null;
            long deadline = System.currentTimeMillis() + MAX_WAIT_MS;

            while (System.currentTimeMillis() < deadline) {
                // ---- 主路径：等宿主主动推送（会唤醒被冻结的进程）----
                ShotBridge.Answer push = ShotBridge.await(AWAIT_MS);
                if (handleIfDecided(ctx, push, "push")) {
                    return;
                }

                // ---- 兜底：没等到推送就主动问一次 ----
                if (candidate == null) {
                    candidate = RootShell.findNewestScreenshot(shotTime);
                    if (candidate != null) {
                        Logx.i("[clean] candidate: " + candidate
                                + " (name=" + RootShell.baseName(candidate) + ")");
                    }
                }
                if (candidate != null) {
                    ShotBridge.Answer answer = ShotBridge.query(ctx, candidate, QUERY_TIMEOUT_MS);
                    if (!answer.received) {
                        Logx.w("[clean] 宿主无回应（模块可能没被注入 / 作用域没勾对）");
                    } else {
                        Logx.i("[clean] query SUCCESS=" + answer.success
                                + " deletedByHost=" + answer.deleted
                                + " successTs=" + answer.successTs
                                + " activityAlive=" + answer.activityAlive);
                        if (handleIfDecided(ctx, answer, "query")) {
                            return;
                        }
                    }
                }
            }
            Logx.i("[clean] 超时仍未拿到'可删'结论（" + MAX_WAIT_MS + "ms），截图保留"
                    + "——宁可漏删，绝不误删");
        } catch (Throwable t) {
            Logx.e("[clean] unexpected failure; screenshot kept", t);
        }
    }

    /**
     * 收到宿主的回答后处理。
     *
     * @return true 表示本次流程结束（已删、已由宿主删除、或确认不该删）
     */
    private static boolean handleIfDecided(Context ctx, ShotBridge.Answer a, String from) {
        if (a == null || !a.received || !a.success) {
            return false;
        }
        Logx.i("[clean] 宿主判定可删（来自 " + from + "）：" + a.path);

        if (a.deleted) {
            Logx.i("[clean] 宿主已自行删除，无需重复处理");
            if (a.path != null && a.path.length() > 8) {
                purgeGallery(ctx, a.path);
            }
            return true;
        }

        String path = a.path;
        if (path == null || path.length() < 8 || !RootShell.isSafeScreenshotPath(path)) {
            Logx.w("[clean] 宿主给的路径没通过安全检查，不删: " + path);
            return false;
        }
        doDelete(ctx, path, a.successTs);
        return true;
    }

    private static void doDelete(Context ctx, String path, long successTs) {
        Logx.i("[clean] deleting " + path + " (success ts=" + successTs + ")");
        boolean ok = RootShell.deleteFile(path);
        if (!ok) {
            Logx.w("[clean] deletion failed; file left in place");
            toast(ctx, "记账成功，但截图删除失败（可看日志）");
            return;
        }
        purgeGallery(ctx, path);
        Logx.i("[clean] screenshot deleted and gallery entry dropped");
        toast(ctx, "已记账，截图已自动删除");
    }

    /** 文件被 rm 之后，让 MediaStore 把那张图从相册里去掉。 */
    private static void purgeGallery(Context ctx, String path) {
        try {
            MediaScannerConnection.scanFile(ctx, new String[]{path}, null, null);
            Logx.i("[clean] media scan requested for " + path);
        } catch (Throwable t) {
            Logx.e("[clean] media scan failed", t);
        }
    }

    private static void toast(final Context ctx, final String msg) {
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private ShotCleaner() {
    }
}
