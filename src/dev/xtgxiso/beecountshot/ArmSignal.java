package dev.xtgxiso.beecountshot;

import java.util.ArrayList;
import java.util.List;

/**
 * 宿主进程（蜜蜂记账）里的"这一次要记账"标记。
 *
 * <p>语义是「限时通行证」：模块 App 点磁贴时通过**定向广播**送来一张通行证的截止时间，
 * 闸门每次判断只看 {@code now < 某张通行证的截止}。
 *
 * <p>用时间窗口而不是永久开关，是为了让状态一定会自动失效——万一某次通行证没被兑现，
 * 也不会把模块永久卡在"什么都放行"的状态上。
 *
 * <h3>v2.7：为什么从"一个窗口"改成"多张并存"</h3>
 * 原来只有一份窗口、且 {@code consume()} 一次性清零。而蜜蜂记账上报一张截图要**延迟约 10 秒**
 * （真机日志：截图 11:26:54 落盘，11:27:04 才上报）。于是连点两次时，第一张上报会把两张的
 * 额度一起吃掉，第二张永远被拦。改成每次点击发一张独立通行证、各带自己的到期时间。
 *
 * <h3>v2.12：修掉"一次点击吃两个额度"和"连点第 4 次挤掉第 1 次"</h3>
 * 查出两个洞，都是"额度数量"上的问题：
 *
 * <ol>
 *   <li><b>同一次点击被登记两次。</b>{@code ShotTileService} 对同一次点击会发**两条** ARM 广播
 *       ——应用侧的普通广播，加一条 root 冗余通道（宿主退后台被冻结时，普通广播会被延迟投递，
 *       所以加了 root 这条）。两条都会被 {@link ArmReceiver} 收到，于是 {@code arm()} 被调两次、
 *       一次点击占掉两个额度。现在 {@code arm()} 对**相同的截止时间做去重**：
 *       重复投递只会登记一张。"两条通道一起发没有副作用"这句话到此才真正成立。</li>
 *   <li><b>固定 3 格 + 满格就挤掉最早的。</b>原来是定长数组，满了以后挤掉"最快过期的那张"；
 *       而所有通行证的时长是一样的（{@code clickAt + delay + 宽限}），
 *       "最快过期"恒等于"最早点击"——于是连点到第 4 次就会把第 1 次的通行证顶掉，
 *       第 1 张截图被闸门拦下、不记账。现在改成按到期时间排的列表，
 *       容量只受"过期清理"约束（外加一个远高于实际的防滥用上限），突发连点不会再互相挤掉。</li>
 * </ol>
 *
 * <p>为什么只用内存 + 广播，不再走文件：
 * <ul>
 *   <li>早先试过"模块 App 用 root 往宿主目录写一个小文件"，但实测 {@code su} 跑在
 *       全局挂载命名空间，看不到应用的数据目录，那个文件宿主永远读不到——
 *       是**静默失效**，反而让日志看起来正常。</li>
 *   <li>广播这条通道从一开始就是通的，也是唯一真正在生效的。</li>
 * </ul>
 * 所以文件通道已彻底移除，只保留内存窗口。这样"日志说的"和"实际生效的"才一致。
 */
final class ArmSignal {

    /**
     * 同时最多保留多少张通行证。
     *
     * <p>纯属防滥用兜底：正常使用下任意时刻的通行证都只有个位数（每张只活二十来秒）。
     * 真正的容量约束是"过期即清理"，不是这个数字。
     */
    private static final int MAX_PASSES = 16;

    /**
     * 单张通行证的有效期上限。
     *
     * <p>实际下发的窗口最长约 21 秒（延迟 1.3s + 宽限 14s + 收尾 6s）。钳到一分钟是为了
     * 防止一条伪造的"远期截止时间"把闸门永久打开——即使真有异常值，也只多开一小会儿。
     */
    private static final long MAX_PASS_LIFETIME_MS = 60000L;

    /** 兑现时对同一张图去重的时间范围（见 {@link #consume(String)}）。 */
    private static final long DEDUPE_MS = 10000L;

    /** 未过期的通行证的截止时间（毫秒），无序，只在读的时候挑最早的那张。 */
    private static final List<Long> passes = new ArrayList<Long>(4);

    /** 广播送达时发一张通行证。重复投递同一个截止时间只会登记一张。 */
    static synchronized void arm(long untilMs) {
        long now = System.currentTimeMillis();
        prune(now);

        // 超过上限就钳一下（正常路径永远碰不到），避免伪造值把窗口开太久。
        long until = Math.min(untilMs, now + MAX_PASS_LIFETIME_MS);
        if (until <= now) {
            Logx.w("[arm] pass rejected: deadline already in the past (+" + (until - now) + "ms)");
            return;
        }

        // 去重：同一次点击会经由两条通道各送一次，截止时间完全相同。
        if (passes.contains(Long.valueOf(until))) {
            Logx.i("[arm] duplicate pass ignored (+" + (until - now)
                    + "ms), live=" + passes.size());
            return;
        }

        if (passes.size() >= MAX_PASSES) {
            int soonest = soonestIndex();
            long dropped = passes.remove(soonest).longValue();
            Logx.w("[arm] too many live passes (" + (passes.size() + 1)
                    + "), dropping the soonest one (+" + (dropped - now) + "ms)");
        }

        passes.add(Long.valueOf(until));
        Logx.i("[arm] pass granted (+" + (until - now) + "ms), live=" + passes.size());
    }

    /** 现在是否有可用的通行证。 */
    static synchronized boolean isArmed() {
        prune(System.currentTimeMillis());
        return !passes.isEmpty();
    }

    /**
     * 精确兑现一次：有未过期的通行证则消耗一张并返回 true。
     *
     * <p>只用在最精确的那道闸门（Flutter 通道上报）上，避免通行证被一次无关的媒体库变化
     * 消耗掉。
     *
     * <p><b>同图去重：</b>刚刚放行过的那张图如果又上报一次，直接拦掉、且**不消耗**通行证。
     * 以前"放行即清零"顺带起到了这个作用，改成额度制后必须显式补上，否则一次重复上报
     * 会白吃掉后面那张的额度。
     *
     * <p>兑现时挑**最快过期**的那张：先点的先兑现，保证每次点击的通行证各归各的。
     */
    static synchronized boolean consume(String path) {
        if (path != null && LastShot.allowedRecently(path, DEDUPE_MS)) {
            Logx.i("[arm] duplicate report of an already-allowed screenshot -> blocked"
                    + " (pass kept): " + path);
            return false;
        }
        long now = System.currentTimeMillis();
        prune(now);
        if (passes.isEmpty()) {
            return false;
        }
        int pick = soonestIndex();
        long picked = passes.remove(pick).longValue();
        Logx.i("[arm] pass consumed (+" + (picked - now) + "ms), live=" + passes.size());
        return true;
    }

    /** 当前可用通行证张数（诊断用）。 */
    static synchronized int liveCount() {
        prune(System.currentTimeMillis());
        return passes.size();
    }

    /** 清掉已过期的通行证。所有入口都先过一遍，容量就自然只受"实际未兑现的点击次数"约束。 */
    private static void prune(long now) {
        for (int i = passes.size() - 1; i >= 0; i--) {
            if (passes.get(i).longValue() <= now) {
                passes.remove(i);
            }
        }
    }

    /** 最快过期的那张的下标；空列表时返回 -1。 */
    private static int soonestIndex() {
        int best = -1;
        for (int i = 0; i < passes.size(); i++) {
            if (best < 0 || passes.get(i).longValue() < passes.get(best).longValue()) {
                best = i;
            }
        }
        return best;
    }

    private ArmSignal() {
    }
}
