package dev.xtgxiso.beecountshot;

/**
 * 宿主进程内：记录"闸门最近放行的是哪几张截图"。
 *
 * <p>这是判断"这张图是不是被交给了蜜蜂记账"最直接的证据——比去翻它的
 * {@code processed_screenshots} 列表更可靠：
 * <ul>
 *   <li>它是闸门放行那一刻就地记下的，不依赖蜜蜂记账自己写没写、写在哪、写成什么格式；</li>
 *   <li>它就是方法调用的实参，也就是蜜蜂记账真正拿到手的那个路径。</li>
 * </ul>
 *
 * <p>v2.7 起保留**最近几次**放行（环形缓冲），而不是只留最后一次。原因是连点两次时
 * 两张图都在"已交给蜜蜂记账、还不知道结果"的状态，删图判定需要同时看见它们
 * （见 {@link HostVerdict}）。
 *
 * <p>只放在内存里（同一个宿主进程内共享），不走文件——真机查明 root 看不见应用数据目录，
 * 走文件那条路会静默失效。
 */
final class LastShot {

    /** 环形缓冲容量。够覆盖"连点几次"，也够去重。 */
    private static final int CAP = 4;

    private static final String[] paths = new String[CAP];
    private static final long[] ats = new long[CAP];

    /** 下一个写入位置。 */
    private static int head = 0;
    /** 已写入数量（上限 CAP）。 */
    private static int size = 0;

    /** 闸门放行一张截图时调用。 */
    static synchronized void record(String allowedPath) {
        paths[head] = allowedPath;
        ats[head] = System.currentTimeMillis();
        head = (head + 1) % CAP;
        if (size < CAP) {
            size++;
        }
        Logx.i("[last] gate allowed: " + allowedPath);
    }

    /** 最后放行的截图路径；没有则为 null。 */
    static synchronized String path() {
        if (size == 0) {
            return null;
        }
        return paths[(head - 1 + CAP) % CAP];
    }

    /** 最后放行的时间戳；没有则为 0。 */
    static synchronized long at() {
        if (size == 0) {
            return 0L;
        }
        return ats[(head - 1 + CAP) % CAP];
    }

    /** 环形缓冲里已记录的放行条数（按时间从老到新）。 */
    static synchronized int size() {
        return size;
    }

    /** 第 {@code i} 条放行的时间戳（{@code i=0} 是最老的那条）。 */
    static synchronized long atAt(int i) {
        if (i < 0 || i >= size) {
            return 0L;
        }
        return ats[(head - size + i + CAP) % CAP];
    }

    /** 第 {@code i} 条放行的路径（{@code i=0} 是最老的那条）。 */
    static synchronized String pathAt(int i) {
        if (i < 0 || i >= size) {
            return null;
        }
        return paths[(head - size + i + CAP) % CAP];
    }

    /** 是否在最近 {@code withinMs} 内放行过某个同名文件。 */
    static boolean allowedMostRecently(String candidatePath, long withinMs) {
        String last = path();
        long when = at();
        if (last == null || when <= 0L || candidatePath == null) {
            return false;
        }
        if (System.currentTimeMillis() - when > withinMs) {
            return false;
        }
        return baseName(last).equals(baseName(candidatePath));
    }

    /**
     * 环形缓冲里**任意一条**记录在 {@code withinMs} 内放行过这个同名文件。
     *
     * <p>给闸门去重用：连点两次时，第二次的图和第一次的图不是同一个文件，
     * 只查"最后一条"会漏判，所以要把最近几条都看一遍。
     */
    static synchronized boolean allowedRecently(String candidatePath, long withinMs) {
        if (candidatePath == null) {
            return false;
        }
        String want = baseName(candidatePath);
        if (want.length() == 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < size; i++) {
            long when = ats[(head - size + i + CAP) % CAP];
            if (now - when > withinMs) {
                continue;
            }
            String p = paths[(head - size + i + CAP) % CAP];
            if (p != null && want.equals(baseName(p))) {
                return true;
            }
        }
        return false;
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
