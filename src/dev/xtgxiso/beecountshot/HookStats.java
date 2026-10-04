package dev.xtgxiso.beecountshot;

/**
 * 宿主进程里记一笔 hook 安装结果，供设置页直接显示（不必翻日志）。
 *
 * <p>由 {@link BeeShotModule} 在安装完成后写入。
 */
final class HookStats {

    private static volatile int installed = -1;
    private static volatile int failed = -1;

    static void report(int installedCount, int failedCount) {
        installed = installedCount;
        failed = failedCount;
    }

    /** 成功安装的条数；-1 表示还没跑过安装。 */
    static int installedCount() {
        return installed;
    }

    static int failedCount() {
        return failed;
    }

    private HookStats() {
    }
}
