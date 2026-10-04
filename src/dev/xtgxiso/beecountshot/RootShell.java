package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * root 通道。
 *
 * <p>为什么要用 root：有两件事在纯应用层做不到稳定可靠。
 * <ol>
 *   <li><b>收起控制中心。</b>标准做法 `startActivityAndCollapse` 依赖 ROM 实现，在不少
 *       定制系统上不生效（实测反馈就是面板纹丝不动，于是截到的图里带着控制中心）。
 *       而 `cmd statusbar collapse` 是系统自带的 shell 命令，走 StatusBarManagerService，
 *       行为与下拉手势收起完全一致，最可靠。</li>
 *   <li><b>把"放行这一次"告诉宿主进程。</b>跨进程广播要依赖接收器注册成功 + 广播投递链路
 *       全通，任一环节断了就静默失败。改成 root 直接往宿主自己的目录写一个小文件，
 *       宿主读自己目录里的文件——没有任何环节可以断。</li>
 * </ol>
 *
 * <p>没有 root 也能用：会退回原来的非 root 通道，只是在部分机型上可靠性差一些。
 */
public final class RootShell {

    private static final String PREF = "cfg";
    private static final String KEY_ROOT = "root_state"; // 0=未知 1=已授权 2=已拒绝

    private static volatile Boolean cached;

    /** 是否已确认拿到 root。未知一律按"没有"处理，避免磁贴点击时突然弹授权框打断操作。 */
    public static boolean isGranted(Context c) {
        Boolean v = cached;
        if (v != null) {
            return v.booleanValue();
        }
        try {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            int s = p.getInt(KEY_ROOT, 0);
            if (s == 1) {
                cached = Boolean.TRUE;
                return true;
            }
            if (s == 2) {
                cached = Boolean.FALSE;
                return false;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 主动申请并验证 root（会弹授权框）。阻塞，必须在子线程调用。 */
    public static boolean request(Context c) {
        Result r = exec("id", 25000L);
        boolean ok = r.ok && r.out != null && r.out.contains("uid=0");
        cached = Boolean.valueOf(ok);
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_ROOT, ok ? 1 : 2).apply();
        } catch (Throwable ignored) {
        }
        Logx.i("[root] request -> " + (ok ? "GRANTED" : "DENIED")
                + " exit=" + r.exit + " out=" + trim(r.out) + " err=" + trim(r.err));
        return ok;
    }

    /**
     * 收起控制中心。
     *
     * <p>标准做法 {@code startActivityAndCollapse} 依赖 ROM 实现，在不少定制系统上完全
     * 不生效（实测面板纹丝不动，结果截到的图里带着控制中心）。{@code cmd statusbar collapse}
     * 是系统自带的 shell 命令，走 StatusBarManagerService，与下拉手势收起完全一致。
     *
     * <p>返回值 exit 即 collapse 的结果，调用方据此判断要不要退回非 root 方案。
     */
    public static Result collapseStatusBar() {
        String cmd = "cmd statusbar collapse; C=$?; echo COLLAPSE_EXIT=$C; exit $C";
        Result r = exec(cmd, 12000L);
        Logx.i("[root] collapse -> exit=" + r.exit
                + " out=" + trim(r.out) + " err=" + trim(r.err));
        return r;
    }

    /** 强制停止宿主（改完模块配置后必须重启它，hook 才生效）。 */
    public static boolean forceStopHost() {
        String pkg = HostInfo.pkg();
        Result r = exec("am force-stop " + pkg + "; echo STOPPED", 15000L);
        boolean ok = r.exit == 0;
        Logx.i("[root] force-stop " + pkg + " -> " + (ok ? "OK" : "FAILED")
                + " out=" + trim(r.out) + " err=" + trim(r.err));
        return ok;
    }

    /**
     * 把模块日志（tag = BeeShot）追加导出到 Download。
     *
     * <p>这是整个排查体系里最关键的一条通道。原因是它<b>不依赖任何写文件权限</b>：
     * 两个进程都用 {@code Log.i} 往 logcat 写，root 再把它捞出来。
     * 宿主进程的私有日志文件万一写不进去（真机上出现过），logcat 依然能拿到全部证据。
     *
     * <p>文件超过 2MB 就先删掉重来，避免无限增长。
     */
    public static boolean appendLogcatToDownload() {
        String f = Const.LOGCAT_PATH;
        String cmd = ""
                + "mkdir -p /sdcard/Download/" + Const.LOG_DIR + "; "
                + "F=" + f + "; "
                + "SZ=$(stat -c %s $F 2>/dev/null || echo 0); "
                + "[ $SZ -gt 2000000 ] && rm -f $F; "
                + "echo \"===== dump @ $(date '+%m-%d %H:%M:%S') =====\" >> $F; "
                + "logcat -d -v threadtime -s BeeShot >> $F; "
                + "chmod 644 $F; "
                + "wc -l $F";
        Result r = exec(cmd, 25000L);
        Logx.i("[root] dump logcat -> exit=" + r.exit
                + " out=" + trim(r.out) + " err=" + trim(r.err));
        return r.exit == 0;
    }

    /** 取路径里的文件名（日志与比对用）。 */
    static String baseName(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        int slash = p.lastIndexOf('/');
        return (slash >= 0) ? p.substring(slash + 1) : p;
    }

    /** 把任意文件拷到 Download/BeecountShot 下，供用户直接取用。 */
    public static boolean exportToDownload(String srcPath, String dstName) {
        String dir = "/sdcard/Download/" + Const.LOG_DIR;
        String cmd = ""
                + "if [ -f " + srcPath + " ]; then "
                + "mkdir -p " + dir + "; "
                + "cp " + srcPath + " " + dir + "/" + dstName + "; "
                + "chmod 644 " + dir + "/" + dstName + "; "
                + "ls -l " + dir + "/" + dstName + "; "
                + "else echo NO_SRC; fi";
        Result r = exec(cmd, 10000L);
        Logx.i("[root] export " + dstName + " -> exit=" + r.exit
                + " out=" + trim(r.out) + " err=" + trim(r.err));
        return r.exit == 0 && r.out != null && r.out.indexOf("NO_SRC") < 0;
    }

    // ---------------------------------------------------------------- 诊断

    /**
     * 一次性把诊断要的信息全部取回来（只开一次 root shell）。
     *
     * <p>以前这里是一条条查，点一次诊断要开十几次 su、还要跑 {@code dumpsys} 和
     * {@code find /data/user}，好几秒才出结果。更糟的是其中大半时间花在探测**宿主的
     * 数据目录**上——而那个目录 root 本来就看不到（{@code su} 在全局挂载命名空间），
     * 结论早就有了，没必要每次再问一遍。
     *
     * <p>现在只剩真正有用的一件事：确认包名、确认 root、确认关键命令可用。
     */
    public static void collectDiagnostics(String hostPkg) {
        String cmd = ""
                + "echo '--- id ---'; id; "
                + "echo '--- packages ---'; "
                + "pm list packages 2>/dev/null | sed 's/package://' | grep -i -E 'bee|tntlikely'; "
                + "echo '--- statusbar cmd ---'; "
                + "command -v cmd >/dev/null && echo 'cmd available' || echo 'cmd MISSING'; "
                + "echo '--- done ---'";
        execLogged(cmd, 20000L, "[diag] root@" + hostPkg + ":");
    }

    /** 检查某个文件是否存在（诊断用）。 */
    public static boolean exists(String path) {
        Result r = exec("[ -e '" + path + "' ] && echo YES", 6000L);
        return r.out != null && r.out.indexOf("YES") >= 0;
    }

    /** 执行命令并把标准输出写进日志（诊断用；exec 本身只在失败时记日志）。 */
    public static void execLogged(String cmd, long timeoutMs, String label) {
        Result r = exec(cmd, timeoutMs);
        String out = (r.out == null || r.out.length() == 0) ? "(empty)" : r.out.trim();
        String err = (r.err == null || r.err.length() == 0) ? "" : (" | err=" + r.err.trim());
        Logx.i(label + " exit=" + r.exit + " " + out.replace('\n', ' ').replace('\r', ' ') + err);
    }

    // ---------------------------------------------------------------- 截图文件定位与删除

    /**
     * 找出最近生成的截图文件（按修改时间倒序取第一个满足条件的）。
     *
     * <p>为什么用"列出目录里最新的文件"而不是别的方式：模块 App 没有相册读权限，
     * 拿不到 MediaStore 里的内容；而宿主进程知道确切路径，但那边没有可靠的回传通道。
     * 我们有 root，直接看文件系统最直接。
     *
     * <p>用 {@code -print0} + {@code xargs -0} 是为了正确处理带空格的文件名——不能因为
     * 一个空格就把路径切错，然后删错文件。
     *
     * @param sinceMs 只接受修改时间不早于这个时刻的文件（毫秒）
     */
    public static String findNewestScreenshot(long sinceMs) {
        StringBuilder dirs = new StringBuilder();
        for (int i = 0; i < Const.SCREENSHOT_DIRS.length; i++) {
            dirs.append('"').append(Const.SCREENSHOT_DIRS[i]).append("\" ");
        }
        String cmd = ""
                + "for D in " + dirs.toString().trim() + "; do "
                + "  [ -d \"$D\" ] && find \"$D\" -maxdepth 1 -type f -print0; "
                + "done | xargs -0 stat -c '%Y|%n' 2>/dev/null | sort -rn | head -20";
        Result r = exec(cmd, 15000L);
        if (r.out == null || r.out.length() == 0) {
            Logx.w("[clean] file scan returned nothing (exit=" + r.exit + ")");
            return null;
        }
        // 只比触发时刻早 1 秒以内的都算，避免把用户刚手动截的另一张算进来。
        long lowerBound = sinceMs - 1000L;
        String[] lines = r.out.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int bar = line.indexOf('|');
            if (bar <= 0) {
                continue;
            }
            long mtime;
            try {
                mtime = Long.parseLong(line.substring(0, bar)) * 1000L;
            } catch (Throwable t) {
                continue;
            }
            if (mtime < lowerBound) {
                continue;
            }
            String path = line.substring(bar + 1);
            if (!isSafeScreenshotPath(path)) {
                Logx.w("[clean] skip unsafe candidate: " + path);
                continue;
            }
            Logx.i("[clean] newest candidate: " + path + " (mtime=" + mtime + ")");
            return path;
        }
        Logx.w("[clean] no candidate newer than " + sinceMs + " among "
                + lines.length + " entries");
        return null;
    }

    /**
     * 删除前的硬性安全检查。这是"不要误删"的最后一道闸门。
     *
     * <p>规则刻意保守：只接受绝对路径、只接受图片扩展名、路径里必须出现"截图/screenshot"
     * 字样、且必须排除相机目录。
     */
    static boolean isSafeScreenshotPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.trim();
        if (p.length() < 24 || !p.startsWith("/")) {
            return false;
        }
        int slash = p.lastIndexOf('/');
        String name = (slash >= 0) ? p.substring(slash + 1) : p;
        if (name.length() == 0 || name.startsWith(".")) {
            // 隐藏文件。小米在写图过程中会产生 .pending-* 临时文件，蜜蜂记账自己也明确跳过它们，
            // 我们更不能去删——那个名字是临时的，删了没有意义还可能打断系统写图。
            return false;
        }
        String nameLower = name.toLowerCase();
        if (nameLower.contains(".pending-") || nameLower.startsWith(".pending")) {
            return false;
        }
        // 拒绝任何可能干扰 shell 解析的字符。真删之前还要在 shell 里再引一次，
        // 但源头就挡住更安全。
        char[] forbidden = {'\'', '"', '`', ';', '$', '\\', '\n', '\r', '|', '&', '<', '>'};
        for (int i = 0; i < forbidden.length; i++) {
            if (p.indexOf(forbidden[i]) >= 0) {
                return false;
            }
        }
        String lower = p.toLowerCase();
        if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp"))) {
            return false;
        }
        if (lower.contains("/dcim/camera/")) {
            return false;
        }
        boolean looksLikeScreenshot = lower.contains("screenshot")
                || p.contains("截图") || p.contains("截屏")
                || lower.contains("screen_shot") || lower.contains("screen-shot");
        return looksLikeScreenshot;
    }

    /** 真的去删。成功返回 true。 */
    public static boolean deleteFile(String path) {
        if (!isSafeScreenshotPath(path)) {
            Logx.w("[clean] REFUSED to delete (failed safety check): " + path);
            return false;
        }
        String cmd = "if [ -f '" + path + "' ]; then rm -f '" + path + "' && echo DELETED; "
                + "else echo MISSING; fi";
        Result r = exec(cmd, 10000L);
        boolean ok = r.exit == 0 && r.out != null && r.out.indexOf("DELETED") >= 0;
        Logx.i("[clean] delete " + path + " -> " + (ok ? "OK" : "FAILED")
                + " out=" + trim(r.out) + " err=" + trim(r.err));
        return ok;
    }

    // ---------------------------------------------------------------- 执行器

    public static final class Result {
        public boolean ok;
        public int exit = -1;
        public String out = "";
        public String err = "";
    }

    public static Result exec(String cmd, long timeoutMs) {
        Result r = new Result();
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(false);
            p = pb.start();
            final Process target = p;
            final StringBuilder out = new StringBuilder();
            final StringBuilder err = new StringBuilder();
            Thread tOut = drain(target.getInputStream(), out);
            Thread tErr = drain(target.getErrorStream(), err);
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroy();
                r.err = "timeout after " + timeoutMs + "ms";
                return r;
            }
            tOut.join(1000L);
            tErr.join(1000L);
            r.exit = p.exitValue();
            r.out = out.toString();
            r.err = err.toString();
            r.ok = true;
        } catch (Throwable t) {
            r.err = t.getClass().getSimpleName() + ": " + t.getMessage();
            Logx.e("[root] exec failed: " + cmd, t);
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
        return r;
    }

    private static Thread drain(final java.io.InputStream in, final StringBuilder sink) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        sink.append(line).append('\n');
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (br != null) {
                        try {
                            br.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private RootShell() {
    }
}
