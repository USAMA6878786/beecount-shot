package dev.xtgxiso.beecountshot;

/**
 * 宿主进程内的运行状态快照（内存态，供查询回答使用）。
 *
 * <p>目前只跟踪一件事：<b>蜜蜂记账的截图监听能力是否还在</b>。
 *
 * <p>为什么需要它：蜜蜂记账的截图监听器是在 {@code MainActivity} 起来之后注册、
 * 在 {@code MainActivity.onDestroy} 里注销的。也就是说主界面一旦被系统回收，
 * 它就彻底感知不到任何截图——不管我们的闸门放不放行。这个状态没法由模块 App
 * 自己判断（它看不到别的应用的生命周期），只能由宿主机就地记下来再如实回报。
 */
final class HostState {

    /**
     * 截图监听能力是否在位。默认 false（"不知道"按不在位处理，避免误报"已就绪"）。
     * 我们的 hook 在 Application.onCreate 阶段就装好了，早于任何 Activity 创建，
     * 所以不会漏掉第一次 onCreate。
     */
    private static volatile boolean activityAlive = false;

    private static volatile long aliveSince = 0L;
    private static volatile long downSince = 0L;

    static void onActivityCreated() {
        activityAlive = true;
        aliveSince = System.currentTimeMillis();
        HostTelemetry.put("activity_alive", "true");
        HostTelemetry.put("activity_created_at", aliveSince);
    }

    static void onActivityDestroyed() {
        activityAlive = false;
        downSince = System.currentTimeMillis();
        HostTelemetry.put("activity_alive", "false");
        HostTelemetry.put("activity_destroyed_at", downSince);
    }

    static boolean isActivityAlive() {
        return activityAlive;
    }

    static long aliveSince() {
        return aliveSince;
    }

    static long downSince() {
        return downSince;
    }

    private HostState() {
    }
}
