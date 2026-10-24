package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 *
 * <h3>为什么是「一个工人 + 队列」而不是「每次点击一个线程」</h3>
 * 原来每点一次磁贴就起一个线程、各活 60 秒、各自每 5 秒开一次 root shell 扫盘。
 * **连点 10 次就是 10 个线程并发扫同一个目录**——纯浪费，而且连点时正是最需要稳的时候。
 *
 * <p>现在只有一个工人线程，所有点击排队给它：
 * <ul>
 *   <li>宿主的推送（广播）**一次 await 服务所有任务**；</li>
 *   <li>扫盘**每轮只做一次**，覆盖所有任务的时间窗并集；</li>
 *   <li>同一张图在同一轮里**只查一次**宿主（结果共享给各任务）。</li>
 * </ul>
 * 队列空了工人就退出，不常驻占资源。
 */
final class ShotCleaner {

    /** 等宿主推送的时长。**故意设得很短**：识别一完成就删，不能等宿主慢慢推。 */
    private static final long AWAIT_MS = 2000L;

    /** 主动查询宿主的超时。 */
    private static final long QUERY_TIMEOUT_MS = 3000L;

    /** 单个任务的总等待上限。 */
    private static final long MAX_WAIT_MS = 75000L;

    /** 扫盘的全局间隔（一个工人服务所有任务，所以只需要这么一次）。 */
    private static final long SCAN_INTERVAL_MS = 5000L;

    /**
     * "按时间认领自己那一张"的窗口上界（相对本次点击时刻）。
     *
     * <p>为什么需要它：<b>蜜蜂记账自己会防抖</b>。真机数据：14 次点击 → 14 张截图，
     * 但宿主只收到 10 次上报（{@code pass granted=14 / pass consumed=10 / live=8}）。
     * <b>被防抖跳过的那几张永远不会出现在宿主的"放行清单"里</b>，于是"只删宿主确认过的"
     * 这条规则对它们永远不成立——它们会一直躺在相册里。用户看到的"还剩一张"就是这么来的。
     *
     * <p>所以再加一条判据：**点下磁贴之后一小段窗口内出现的截图，就是我们的**。
     *
     * <p><b>窗口原本是 6 秒，太大了</b>：连点（间隔 0.6~1 秒）时，任务 A 的 6 秒窗口
     * 会把后面好几张（B、C…）都圈进来。一旦 A 的兜底先到期，就可能把**还没轮到识别的
     * B/C 的图删掉**——实测日志里"时间窗认领 1 张"就把 233807 删了。
     * root 抓帧实测 200~500ms 落盘，注入按键兜底也 <2 秒，所以收到 2.5 秒足够，
     * 配合"同一轮里一张图只被一个任务认领"，彻底避免误删邻图。
     */
    private static final long MINE_WINDOW_MS = 2500L;

    // ---------------------------------------------------------------- 队列

    private static final Object LOCK = new Object();
    private static final List<Task> TASKS = new ArrayList<Task>();
    private static Thread worker;

    /** 一次点击对应一个任务。集合只在工人线程里读写，不需要额外加锁。 */
    private static final class Task {
        long shotAt;
        long deadline;
        final Set<String> handled = new HashSet<String>();
        final Set<String> confirmed = new LinkedHashSet<String>();
        final Set<String> mine = new LinkedHashSet<String>();
    }

    /**
     * 提交一次"这次点击产生的截图要删掉"的任务。**不阻塞**，立刻返回。
     *
     * @param shotTime 本次截图流程的起始时刻（毫秒）
     */
    static void submit(Context ctx, long shotTime) {
        if (ctx == null) {
            return;
        }
        final Context app = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;
        Task t = new Task();
        t.shotAt = shotTime;
        t.deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        synchronized (LOCK) {
            TASKS.add(t);
            if (worker != null) {
                return;
            }
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop(app);
                }
            }, "bee-cleaner");
            worker.setDaemon(true);
            worker.start();
        }
        Logx.i("[clean] 工人线程启动（队列长度 " + TASKS.size() + "）");
    }

    // ---------------------------------------------------------------- 工人

    private static void loop(Context ctx) {
        try {
            // 必须尽早注册好接收器，否则宿主的唤醒广播没人接。
            ShotBridge.ensureReceiver(ctx);
            probeHostOnce(ctx);

            long lastScan = 0L;

            while (true) {
                List<Task> snapshot;
                synchronized (LOCK) {
                    if (TASKS.isEmpty()) {
                        worker = null;
                        Logx.i("[clean] 队列已空，工人线程退出");
                        return;
                    }
                    snapshot = new ArrayList<Task>(TASKS);
                }

                // ---- 1) 宿主推送：一次 await 服务所有任务 ----
                ShotBridge.Answer push = ShotBridge.await(AWAIT_MS);
                if (push.received) {
                    if (push.success && push.path != null) {
                        // 推送里的路径是"可删"的结论，处理一次即可；各任务只需记为已处理，
                        // 避免后面扫盘时重复删。
                        if (handleIfDecided(ctx, push, "push")) {
                            for (Task t : snapshot) {
                                t.handled.add(push.path);
                            }
                        }
                    }
                    continue;
                }

                // ---- 2) 扫盘：**每轮只做一次**，覆盖所有任务时间窗的并集 ----
                long now = System.currentTimeMillis();
                if (now - lastScan < SCAN_INTERVAL_MS) {
                    sleepQuietly(300L);
                    continue;
                }
                lastScan = now;

                long from = Long.MAX_VALUE;
                long to = 0L;
                for (Task t : snapshot) {
                    from = Math.min(from, t.shotAt);
                    to = Math.max(to, t.shotAt + MINE_WINDOW_MS);
                }
                List<String> cands = from == Long.MAX_VALUE
                        ? new ArrayList<String>()
                        : RootShell.findScreenshotsSince(from, to);

                // 窗口里一张图都没有 = 没有东西可删（截屏失败，或者图已经被无界面路径删掉了）。
                // 这种情况没必要再扫到 60 秒：每 5 秒一次 root 扫盘，实测一次点击白扫十来次。
                // 8 秒是"落盘上限"的保守值（实测最慢约 1 秒，留足余量避免误判"还没落盘"）。
                if (cands.isEmpty()) {
                    long t = System.currentTimeMillis();
                    boolean allExpired = true;
                    for (Task task : snapshot) {
                        if (t <= task.shotAt + 8000L) {
                            allExpired = false;
                            break;
                        }
                    }
                    if (allExpired) {
                        synchronized (LOCK) {
                            TASKS.removeAll(snapshot);
                        }
                        Logx.i("[clean] 窗口内已无截图，任务提前结束（不再空转）");
                    }
                    sleepQuietly(300L);
                    continue;
                }

                // 同一张图这一轮只查一次宿主，结果共享
                java.util.Map<String, ShotBridge.Answer> cache =
                        new java.util.HashMap<String, ShotBridge.Answer>();
                // 同一轮里一张图只归一个任务（按任务顺序，最早点击的优先认领它自己那张），
                // 否则连点时 A 的窗口会把 B/C 的图也圈进来、抢着删。
                java.util.Set<String> claimedThisPass =
                        new java.util.HashSet<String>();

                for (int i = 0; i < snapshot.size(); i++) {
                    Task t = snapshot.get(i);
                    for (int j = 0; j < cands.size(); j++) {
                        String c = cands.get(j);
                        if (t.handled.contains(c)) {
                            continue;
                        }
                        // 只认"本次点击之后、且落在认领窗口内"的图。
                        // 时间直接从**文件名**里解析（Screenshot_yyyyMMdd-HHmmss），
                        // 不再为每张图单独开一次 root shell 去 stat。
                        long mtime = timeFromName(c);
                        if (mtime > 0L && (mtime < t.shotAt - 1000L
                                || mtime > t.shotAt + MINE_WINDOW_MS)) {
                            continue;
                        }
                        if (claimedThisPass.contains(c)) {
                            continue;   // 已被更早的任务认领
                        }
                        claimedThisPass.add(c);
                        t.mine.add(c);

                        ShotBridge.Answer answer = cache.get(c);
                        if (answer == null) {
                            answer = ShotBridge.query(ctx, c, QUERY_TIMEOUT_MS);
                            cache.put(c, answer);
                        }
                        if (!answer.received) {
                            continue;
                        }
                        if (handleIfDecided(ctx, answer, "query")) {
                            t.handled.add(c);
                            continue;
                        }
                        if (answer.processed) {
                            t.confirmed.add(c);
                        }
                    }
                }

                // ---- 3) 到期的任务：超时兜底，然后出队 ----
                for (int i = 0; i < snapshot.size(); i++) {
                    Task t = snapshot.get(i);
                    if (System.currentTimeMillis() < t.deadline) {
                        continue;
                    }
                    sweep(ctx, t);
                    synchronized (LOCK) {
                        TASKS.remove(t);
                    }
                }
            }
        } catch (Throwable t) {
            Logx.e("[clean] 工人线程异常退出", t);
            synchronized (LOCK) {
                worker = null;
            }
        }
    }

    /**
     * 超时兜底：把"确认过身份"的**全部**删掉。
     *
     * <p>为什么要这样：连点时蜜蜂记账自己会**防抖**（它日志里有"防抖跳过：距离上次处理
     * 仅 21ms"），两次挨着的截图它可能只处理一次、只写一条「落库完成」——另一张
     * 永远等不到结果，于是永远留在相册里。
     *
     * <p>等满 60 秒之后，任何该读这些图的动作早就读完了，所以删是安全的。
     * 两类都算"确认过是我们自己的"：
     * <ol>
     *   <li>宿主明确确认过身份（processed=true，闸门放行过的那张）；</li>
     *   <li>落在本次点击后 6 秒时间窗里的——**宿主没确认也不影响**，
     *       因为蜜蜂记账的防抖会让一部分截图压根不上报。</li>
     * </ol>
     */
    private static void sweep(Context ctx, Task t) {
        Set<String> allMine = new LinkedHashSet<String>(t.confirmed);
        allMine.addAll(t.mine);
        int swept = 0;
        int byTime = 0;
        for (String c : allMine) {
            if (t.handled.contains(c) || !RootShell.isSafeScreenshotPath(c)) {
                continue;
            }
            boolean isByTime = !t.confirmed.contains(c);
            Logx.w("[clean] 等满 " + MAX_WAIT_MS + "ms 仍没等到结果 -> 直接删"
                    + (isByTime ? "（按时间窗认领）" : "（宿主确认过身份）")
                    + "：" + RootShell.baseName(c));
            doDelete(ctx, c, 0L);
            swept++;
            if (isByTime) {
                byTime++;
            }
        }
        Logx.i("[clean] 任务结束：宿主确认 " + t.confirmed.size() + " 张、时间窗认领 "
                + t.mine.size() + " 张，共补删 " + swept + " 张（按时间 " + byTime + " 张）");
    }

    /** 工人启动时探一次宿主状态。**每个工人只做一次**，不是每次点击都做。 */
    private static void probeHostOnce(Context ctx) {
        try {
            ShotBridge.Answer probe = ShotBridge.query(ctx, "", 2500L);
            if (!probe.received) {
                Logx.w("[clean] 宿主无回应，无法判断截图监听是否在位");
            } else if (!probe.activityAlive) {
                // 界面被回收 ≠ 监听不在位（多数情况下观察者还在，只是界面没了）。
                // 所以只记一笔，不再弹"本次可能不会被记入"吓人。
                Logx.i("[clean] MainActivity 已回收（观察者通常仍在位）");
                ShotTileService.requestRefresh(ctx);
            } else {
                Logx.i("[clean] 蜜蜂记账截图监听在位，正常");
            }
        } catch (Throwable t) {
            Logx.w("[clean] 探测宿主失败：" + t.getMessage());
        }
    }

    /**
     * 从文件名里解析时间戳：{@code Screenshot_20261017-145911_...} → 毫秒。
     *
     * <p>系统截图和我们自己写进相册的图都遵循这个命名，所以能省掉"每张图一次 su stat"。
     * 解析不出来返回 0（调用方会当作"无法判定"，按认领处理）。
     */
    private static long timeFromName(String path) {
        try {
            String base = RootShell.baseName(path);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d{8})-(\\d{6})").matcher(base);
            if (!m.find()) {
                return 0L;
            }
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US);
            f.setLenient(false);
            return f.parse(m.group(1) + "-" + m.group(2)).getTime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 收到宿主的回答后处理。
     *
     * @return true 表示已经处理完（已删、已由宿主删除、或确认不该删）
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

    /**
     * 无界面路径专用：Dart 回执成功之后删掉这张图。
     *
     * <p>为什么需要：headless 下它确实把账记上了，但**没有写我们依赖的那条
     * 「AI 识别 + 落库完成」日志**，所以按日志配对的清理器永远等不到结果、
     * 图就一直留着。这里改成"以 Dart 的回执为准"。
     *
     * <p>仍然尊重设置页的「识别后删除截图」开关。
     */
    static boolean deleteAfterHeadless(Context ctx, String path) {
        try {
            if (ctx == null || path == null || path.length() == 0) {
                return false;
            }
            // 这个函数**两个进程都会跑**：
            //   · 模块 App 进程（普通清理任务）——读自己的 SharedPreferences 就对了；
            //   · 宿主进程（无界面引擎回执）——那里读 Prefs 读到的是**宿主自己的** cfg，
            //     永远是默认值 true，结果就是"不管开关关没关都删"（真机就是这么复现的）。
            // 所以宿主进程里必须用 ARM/派发广播带过来的那个值。
            boolean allow = Const.MODULE_PKG.equals(ctx.getPackageName())
                    ? Prefs.autoDelete(ctx)
                    : HostWatcher.autoDeleteEnabled();
            if (!allow) {
                Logx.i("[clean] 自动删图关着，保留：" + RootShell.baseName(path));
                return false;
            }
            doDelete(ctx, path, 0L);
            return true;
        } catch (Throwable t) {
            Logx.e("[clean] 无界面路径删图失败", t);
            return false;
        }
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
