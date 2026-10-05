package dev.xtgxiso.beecountshot;

/**
 * 宿主进程（蜜蜂记账）里的"这一次要记账"标记。
 *
 * <p>语义是「限时通行证」：模块 App 点磁贴时通过**定向广播**送来一张通行证的截止时间，
 * 闸门每次判断只看 {@code now < 某张通行证的截止}。
 *
 * <p><b>v2.7 改成多张并存（额度制），这是真机踩出来的：</b>原来只有一份窗口、
 * 且 {@code consume()} 一次性清零。而蜜蜂记账上报一张截图要**延迟约 10 秒**
 * （真机日志：截图 11:26:54 落盘，11:27:04 才上报）。于是连点两次磁贴时：
 *
 * <pre>
 *   点击① → 发通行证（到 +15s）
 *   点击② → 发通行证（到 +22s），arm() 取最大值 → 仍然只有一份
 *   截图 A 上报 → consume() 清零
 *   截图 B 上报 → 窗口已是 0 → 被拦掉，第二张永远不会被记账
 * </pre>
 *
 * 现在每次点击发一张**独立**的通行证，各自带自己的到期时间，互不顶替；
 * 兑现时优先用**最快过期**的那张（先点先兑现），过期的自动作废。
 *
 * <p>为什么只用内存 + 广播，不再走文件：
 * <ul>
 *   <li>早先试过"模块 App 用 root 往宿主目录写一个小文件"，但实测 {@code su} 跑在
 *       全局挂载命名空间，看不到应用的数据目录，那个文件宿主永远读不到——
 *       是**静默失效**，反而让日志看起来正常。</li>
 *   <li>广播这条通道从一开始就是通的，也是唯一真正在生效的。</li>
 * </ul>
 * 所以文件通道已彻底移除，只保留内存窗口。这样"日志说的"和"实际生效的"才一致。
 *
 * <p>用时间窗口而不是永久开关，是为了让状态一定会自动失效——万一某次通行证没被兑现，
 * 也不会把模块永久卡在"什么都放行"的状态上。
 */
final class ArmSignal {

    /** 同时最多存在几张通行证。够覆盖"连点几次"的场景，又不至于放得太开。 */
    private static final int SLOTS = 3;

    private static final long[] armedUntil = new long[SLOTS];

    /** 兑现时对同一张图去重的时间范围（见 {@link #consume(String)}）。 */
    private static final long DEDUPE_MS = 10000L;

    /** 广播送达时发一张通行证。 */
    static synchronized void arm(long untilMs) {
        long now = System.currentTimeMillis();
        int free = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (armedUntil[i] <= now) {
                free = i;
                break;
            }
        }
        if (free < 0) {
            // 全满：挤掉最快过期的那张（它本来也快没用了）。
            int soonest = 0;
            for (int i = 1; i < SLOTS; i++) {
                if (armedUntil[i] < armedUntil[soonest]) {
                    soonest = i;
                }
            }
            free = soonest;
        }
        armedUntil[free] = untilMs;
        Logx.i("[arm] pass#" + free + " granted (+"
                + Math.max(0L, untilMs - now) + "ms)");
    }

    static synchronized boolean isArmed() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < SLOTS; i++) {
            if (armedUntil[i] > now) {
                return true;
            }
        }
        return false;
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
     * <p>兑现时挑**最快过期**的那张：先点的先兑现，保证两张通行证各归各的。
     */
    static synchronized boolean consume(String path) {
        if (path != null && LastShot.allowedRecently(path, DEDUPE_MS)) {
            Logx.i("[arm] duplicate report of an already-allowed screenshot -> blocked"
                    + " (pass kept): " + path);
            return false;
        }
        long now = System.currentTimeMillis();
        int pick = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (armedUntil[i] > now && (pick < 0 || armedUntil[i] < armedUntil[pick])) {
                pick = i;
            }
        }
        if (pick < 0) {
            return false;
        }
        armedUntil[pick] = 0L;
        Logx.i("[arm] pass#" + pick + " consumed");
        return true;
    }

    private ArmSignal() {
    }
}
