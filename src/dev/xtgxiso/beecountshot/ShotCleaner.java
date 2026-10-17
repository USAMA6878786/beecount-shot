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

    /** 等宿主推送的时长。**故意设得很短**：识别一完成就删，不能等宿主慢慢推。 */
    private static final long AWAIT_MS = 2000L;

    /** 主动查询宿主的超时。 */
    private static final long QUERY_TIMEOUT_MS = 3000L;

    /** 总等待上限。 */
    private static final long MAX_WAIT_MS = 60000L;

    /**
     * "按时间认领自己那一张"的窗口上界（相对本次点击时刻）。
     *
     * <p>为什么需要它：<b>蜜蜂记账自己会防抖</b>。真机数据：14 次点击 → 14 张截图，
     * 但宿主只收到 10 次上报（{@code pass granted=14 / pass consumed=10 / live=8}）。
     * <b>被防抖跳过的那几张永远不会出现在宿主的"放行清单"里</b>，于是"只删宿主确认过的"
     * 这条规则对它们永远不成立——它们会一直躺在相册里。用户看到的"还剩一张"就是这么来的。
     *
     * <p>所以再加一条判据：**点下磁贴之后 6 秒内出现的截图，就是我们的**。
     * 无障碍直调约 0.4 秒落盘，最慢的 root 兜底也不到 4 秒，6 秒留足余量，
     * 又不会把用户随手手动截的图卷进来（那得在点磁贴后 6 秒内再手动截一张，概率极低）。
     */
    private static final long MINE_WINDOW_MS = 6000L;

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
                    // 界面被回收 ≠ 监听不在位。v2.32 起宿主开了"引擎保活"：
                    // MainActivity 没了但 Dart isolate 还在，观察者照常收截图。
                    // 所以这里只记一笔，不再弹"本次可能不会被记入"吓人。
                    Logx.i("[clean] MainActivity 已回收；引擎保活生效的话截图监听仍在位");
                    // 让磁贴的副标题也立刻变成"未就绪"——Toast 在部分系统上会被悄悄丢掉，
                    // 而磁贴就在用户手指底下，比 Toast 可靠。requestListeningState 会触发
                    // onStartListening，那里会重新查一次宿主状态并刷新副标题。
                    ShotTileService.requestRefresh(ctx);
                } else {
                    Logx.i("[clean] 蜜蜂记账截图监听在位，正常");
                }
            } catch (Throwable ignored) {
            }

            // 已经处理完的、以及"宿主确认过身份但还没出结果"的。
            // 用集合而不是单个变量：连点多下时一次会有好几张图要管。
            final java.util.Set<String> handled = new java.util.HashSet<String>();
            final java.util.Set<String> confirmed = new java.util.LinkedHashSet<String>();
            // "我们自己截的那一张"——按时间窗认领，见下面的说明。
            final java.util.Set<String> mine = new java.util.LinkedHashSet<String>();
            long lastScan = 0L;
            long deadline = System.currentTimeMillis() + MAX_WAIT_MS;

            while (System.currentTimeMillis() < deadline) {
                // ---- 主路径：等宿主主动推送（会唤醒被冻结的进程）----
                ShotBridge.Answer push = ShotBridge.await(AWAIT_MS);
                if (push.received) {
                    // 宿主主动推了结果 —— 这次就不用再扫盘 + 逐个查询了。
                    // 原来每轮无条件扫盘（每个候选一次 3 秒超时的查询），
                    // 日志里 "no answer from host" 有 20 次，多数是这种自己挤出来的。
                    if (handleIfDecided(ctx, push, "push")) {
                        return;
                    }
                    continue;
                }

                // ---- 兜底：把"比本次点击新的截图"**全部**过一遍 ----
                //
                // 这里以前只取"最新的那一张"。连点多下时，每个清理线程都盯着同一张最新图，
                // 更早的那些谁都不管——真机日志里因此有 5 张放行过的图一直没被删。
                // 现在逐个确认。扫描本身要开一次 root shell，所以限流到 5 秒一次。
                long now = System.currentTimeMillis();
                if (now - lastScan < 5000L) {
                    try {
                        Thread.sleep(300L);
                    } catch (InterruptedException ignored) {
                    }
                    continue;
                }
                lastScan = now;

                // 时间窗：点下磁贴之后几秒内冒出来的图，基本只可能是我们那一张。
                // 上界给 6 秒——最快的无障碍直调约 0.4 秒落盘，最慢的 root 兜底也不到 4 秒。
                java.util.List<String> cands =
                        RootShell.findScreenshotsSince(shotTime, shotTime + MINE_WINDOW_MS);
                for (int i = 0; i < cands.size(); i++) {
                    String c = cands.get(i);
                    if (handled.contains(c)) {
                        continue;
                    }
                    mine.add(c);
                    ShotBridge.Answer answer = ShotBridge.query(ctx, c, QUERY_TIMEOUT_MS);
                    if (!answer.received) {
                        Logx.w("[clean] 宿主无回应（模块可能没被注入 / 作用域没勾对）");
                        continue;
                    }
                    Logx.i("[clean] query " + RootShell.baseName(c)
                            + " SUCCESS=" + answer.success + " processed=" + answer.processed
                            + " deletedByHost=" + answer.deleted);
                    if (handleIfDecided(ctx, answer, "query")) {
                        handled.add(c);
                        continue;
                    }
                    if (answer.processed) {
                        // 宿主确认过身份（这张确实是我们闸门放行给蜜蜂记账的），只是还没出结果
                        confirmed.add(c);
                    }
                }
            }
            // ---- 超时兜底：把"确认过身份"的**全部**删掉 ----
            //
            // 为什么要这样：连点时蜜蜂记账自己会**防抖**（它日志里有"防抖跳过：距离上次处理
            // 仅 21ms"），两次挨着的截图它可能只处理一次、只写一条「落库完成」——另一张
            // 永远等不到结果，于是永远留在相册里。真机日志里 9 次"超时保留"就是这么来的。
            //
            // 等满 60 秒之后，任何该读这些图的动作早就读完了，所以删是安全的。
            // 但仍然**只删宿主确认过身份的那些**（processed=true = 这张确实是我们闸门
            // 放行给蜜蜂记账的），避免误删用户自己手动截的图。
            // 两类都算"确认过是我们自己的"：
            //   ① 宿主明确确认过身份（processed=true，闸门放行过的那张）；
            //   ② 落在本次点击后 6 秒时间窗里的——**宿主没确认也不影响**，
            //      因为蜜蜂记账的防抖会让一部分截图压根不上报。
            java.util.Set<String> allMine = new java.util.LinkedHashSet<String>(confirmed);
            allMine.addAll(mine);

            int swept = 0;
            int sweptByTime = 0;
            for (String c : allMine) {
                if (handled.contains(c) || !RootShell.isSafeScreenshotPath(c)) {
                    continue;
                }
                boolean byTime = !confirmed.contains(c);
                Logx.w("[clean] 等满 " + MAX_WAIT_MS + "ms 仍没等到结果 -> 直接删"
                        + (byTime ? "（按时间窗认领：点磁贴后 6 秒内出现的）" : "（宿主确认过身份）")
                        + "：" + RootShell.baseName(c));
                doDelete(ctx, c, 0L);
                swept++;
                if (byTime) {
                    sweptByTime++;
                }
            }
            Logx.i("[clean] 超时结束（" + MAX_WAIT_MS + "ms）：宿主确认 " + confirmed.size()
                    + " 张、时间窗认领 " + mine.size() + " 张，共补删 " + swept
                    + " 张（其中按时间认领 " + sweptByTime + " 张），其余保留");
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
        // 删除结果不再打扰用户（用户只要知道"截图成没成"），只留日志。
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
