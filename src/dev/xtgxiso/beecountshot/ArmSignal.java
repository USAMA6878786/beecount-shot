package dev.xtgxiso.beecountshot;

/**
 * 宿主进程（蜜蜂记账）里的"这一次要记账"标记。
 *
 * <p>语义是「限时放行」：模块 App 点磁贴时通过**定向广播**把截止时间送进来，
 * 闸门每次判断只看 {@code now &lt; armedUntil}。
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
 * <p>用时间窗口而不是永久开关，是为了让状态一定会自动失效——万一某次放行没被消费掉，
 * 也不会把模块永久卡在"什么都放行"的状态上。
 */
final class ArmSignal {

    /** 文件内容缓存时长：一次截图事件里会连续查好几次，合并掉重复判断。 */
    private static volatile long armedUntil = 0L;

    /** 广播送达时设置放行窗口。 */
    static void arm(long untilMs) {
        if (untilMs > armedUntil) {
            armedUntil = untilMs;
        }
        Logx.i("[arm] window set (+"
                + Math.max(0L, untilMs - System.currentTimeMillis()) + "ms)");
    }

    static boolean isArmed() {
        return System.currentTimeMillis() < armedUntil;
    }

    /**
     * 精确放行一次：当前在窗口内则消费掉窗口并返回 true。
     *
     * <p>只在最精确的那道闸门（Flutter 通道上报）上使用，避免窗口被一次无关的媒体库变化
     * 消耗掉。
     */
    static boolean consume() {
        if (!isArmed()) {
            return false;
        }
        armedUntil = 0L;
        return true;
    }

    private ArmSignal() {
    }
}
