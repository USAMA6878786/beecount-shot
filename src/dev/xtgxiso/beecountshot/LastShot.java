package dev.xtgxiso.beecountshot;

/**
 * 宿主进程内：记录"闸门最后一次放行的是哪张截图"。
 *
 * <p>这是判断"这张图是不是被交给了蜜蜂记账"最直接的证据——比去翻它的
 * {@code processed_screenshots} 列表更可靠：
 * <ul>
 *   <li>它是闸门放行那一刻就地记下的，不依赖蜜蜂记账自己写没写、写在哪、写成什么格式；</li>
 *   <li>它就是方法调用的实参，也就是蜜蜂记账真正拿到手的那个路径。</li>
 * </ul>
 *
 * <p>只放在内存里（同一个宿主进程内共享），不走文件——真机查明 root 看不见应用数据目录，
 * 走文件那条路会静默失效。
 */
final class LastShot {

    private static volatile String path;
    private static volatile long at;

    /** 闸门放行一张截图时调用。 */
    static void record(String allowedPath) {
        path = allowedPath;
        at = System.currentTimeMillis();
        Logx.i("[last] gate allowed: " + allowedPath);
    }

    /** 最后放行的截图路径；没有则为 null。 */
    static String path() {
        return path;
    }

    /** 最后放行的时间戳；没有则为 0。 */
    static long at() {
        return at;
    }

    /** 是否在最近 {@code withinMs} 内放行过某个同名文件。 */
    static boolean allowedMostRecently(String candidatePath, long withinMs) {
        String last = path;
        long when = at;
        if (last == null || when <= 0L || candidatePath == null) {
            return false;
        }
        if (System.currentTimeMillis() - when > withinMs) {
            return false;
        }
        return baseName(last).equals(baseName(candidatePath));
    }

    private static String baseName(String p) {
        if (p == null) {
            return "";
        }
        String s = p.trim();
        int slash = s.lastIndexOf('/');
        return (slash >= 0) ? s.substring(slash + 1) : s;
    }

    private LastShot() {
    }
}
