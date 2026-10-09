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

    /**
     * 把「补发放行广播」和「收起控制中心」合并成**一次 su**。
     *
     * <p>为什么要合并：`su -c` 每调用一次就是起一个新进程，实测一次 150~250ms。
     * 原来这两件事各起一次 su，白白多花两百毫秒——而这段时间是算在"点击到截屏"之间的，
     * 用户能直接感觉到。两条命令本来就没有依赖关系（广播是发给宿主进程的，
     * 收面板是给 SystemUI 的），放进同一个 shell 顺序执行即可。
     *
     * <p>返回值里的 {@code exit} 是 {@code cmd statusbar collapse} 的结果，
     * 调用方据此判断要不要退回非 root 方案（和原来一致）。
     */
    public static Result armAndCollapse(String action, String pkg, String extras, int flags) {
        StringBuilder cmd = new StringBuilder("am broadcast -a ").append(action);
        if (pkg != null && pkg.length() > 0) {
            cmd.append(" -p ").append(pkg);
        }
        if (extras != null && extras.length() > 0) {
            cmd.append(' ').append(extras);
        }
        if (flags != 0) {
            cmd.append(" -f ").append(flags);
        }
        // 广播的输出保留下来（合并成一次 su 之后仍然要能看出它到底发出去没有）
        cmd.append(" 2>&1; ");
        cmd.append("cmd statusbar collapse; C=$?; echo COLLAPSE_EXIT=$C; exit $C");

        Result r = exec(cmd.toString(), 15000L);
        Logx.i("[root] arm+collapse -> exit=" + r.exit
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
     * 用 root 发一条广播（普通广播的**冗余通道**）。
     *
     * <p>为什么要多此一举：宿主退到后台可能被系统冻结，而发给缓存态应用的广播会被
     * **延迟投递**——放行信号晚到就等于"这次不记账"，表现为点了磁贴没反应。
     * root 的 {@code am broadcast} 走 shell 身份，不受应用自己的后台限制；
     * 再加上 {@code FLAG_RECEIVER_INCLUDE_BACKGROUND}，系统会直接投给后台接收器，
     * 不再排队等解冻。
     *
     * <p>重复投递没有副作用：放行信号那边 {@code ArmSignal.arm()} 对相同的截止时间做了去重
     * （两条通道发的是同一个值），小组件刷新那边多刷一次也只是多读一次库。
     *
     * @param extras 直接拼在命令后面的 extra 段，例如 {@code "--el until_ms 123 --ez precise true"}；
     *               没有就传 null
     * @param flags  传给 {@code am broadcast -f} 的 Intent flag，传 0 表示不加
     */
    public static boolean broadcast(String action, String pkg, String extras, int flags) {
        StringBuilder cmd = new StringBuilder("am broadcast -a ").append(action);
        if (pkg != null && pkg.length() > 0) {
            cmd.append(" -p ").append(pkg);
        }
        if (extras != null && extras.length() > 0) {
            cmd.append(' ').append(extras);
        }
        if (flags != 0) {
            cmd.append(" -f ").append(flags);
        }
        Result r = exec(cmd.toString(), 15000L);
        boolean ok = r.exit == 0 && r.out != null && r.out.indexOf("Error") < 0;
        Logx.i("[root] broadcast '" + cmd + "' -> " + (ok ? "sent" : "FAILED")
                + " out=" + trim(r.out) + " err=" + trim(r.err));
        return ok;
    }

    /** 截屏自检用的"时间标记文件"，放在任何截图目录之外，免得把自己算进去。 */
    private static final String SHOT_MARK = "/data/local/tmp/bee_shot_mark";

    /**
     * 候选截屏命令，按可靠性从高到低排，命中一条就停——**绝不连发**，否则会计两笔账。
     *
     * <p>三条路的取舍：
     * <ol>
     *   <li>{@code input keycombination 26 25}——在输入层模拟"电源键+音量下"，走的是
     *       <b>系统真实截图流程</b>：文件由系统命名、正常登记进媒体库，
     *       蜜蜂记账的截图监听器照常触发，下游一条都不用改。
     *       26=电源，25=音量<b>下</b>（24 是音量上，别搞错）。</li>
     *   <li>同样一条，但用符号键名 {@code KEYCODE_POWER KEYCODE_VOLUME_DOWN}。
     *       两条语义完全一样，留着是因为个别 ROM 只认符号写法。</li>
     *   <li>{@code input keyevent 120}（KEYCODE_SYSRQ）——部分 ROM 把 120 接到系统截图，
     *       当作最后的尝试。</li>
     * </ol>
     *
     * <p>为什么不用 {@code screencap}：它只是把一张图写到磁盘，媒体库里没有记录，
     * 蜜蜂记账大概率感知不到。
     */
    private static final String[] SHOT_CMDS = new String[]{
            // v2.15 换序：**数字键码排到前面**。
            //
            // 真机统计（20 次点击）：符号写法成功 14/20，失败的 6 次都退回数字写法，
            // 而数字写法这 6 次**全部成功**。符号写法失败一次要白等 2 秒再换命令，
            // 整体就从 1.3 秒变成 3.5 秒——这就是"点了之后要等好久才反应过来"的来源。
            //
            // 换序的好处是**最坏情况与原来完全相同**（同样是两条命令、各等 2 秒），
            // 但如果数字写法确实更稳，就能把这 30% 的慢case直接省掉。
            "input keycombination 26 25",
            "input keycombination KEYCODE_POWER KEYCODE_VOLUME_DOWN",
            "input keyevent 120",
    };

    /**
     * 用 root 触发系统截屏，并**当场验证图真的出来了**。
     *
     * <p>这是取代无障碍截屏的主路径，关键收益是**不再依赖无障碍服务**：Android 有个
     * 安全机制，应用被覆盖安装后系统会自动关掉它的无障碍服务，以前每次更新模块
     * 用户都会遇到"磁贴点了没反应"，根子就在这儿。
     *
     * <p>因为命令能不能截成功取决于 ROM，这里不猜：每发一条就轮询一次截图目录，
     * 出来了才算数，不出来再换下一条。全部失败返回 false，让调用方退回无障碍。
     */
    public static boolean takeScreenshotViaRoot() {
        for (int i = 0; i < SHOT_CMDS.length; i++) {
            // 第一条命令顺带把"时间标记"立起来——两条命令合成**一次 su**。
            // su 是起一个新进程，每次约 150~250ms，能省就省。
            //
            // 后面的命令**不重立标记**，这一点很重要：所有候选命令共用同一个标记，
            // 万一某条命令其实截到了图、只是我们没在 2 秒内发现，下一轮仍然能把它认出来，
            // 不会因为标记被刷新而误判成"没截到"，然后再多截一张。
            //
            // 标记的立法是 rm + touch，之后用 `find -newer` 判断有没有"比它新"的文件。
            // 用 -newer 而不是 -newermt：前者是 POSIX 标准，toybox / busybox 都认，
            // 后者只有 busybox/GNU 认，小米上是 toybox，用它会永远查不到东西。
            String cmd = (i == 0)
                    ? "rm -f " + SHOT_MARK + "; touch " + SHOT_MARK + " 2>/dev/null; "
                            + SHOT_CMDS[0] + " 2>&1"
                    : SHOT_CMDS[i] + " 2>&1";

            long t0 = System.currentTimeMillis();
            Result r = exec(cmd, 10000L);
            Logx.i("[root] shot cmd[" + i + "] '" + SHOT_CMDS[i] + "' -> exit=" + r.exit
                    + " out=" + trim(r.out)
                    + " (su 用了 " + (System.currentTimeMillis() - t0) + "ms)");

            long t1 = System.currentTimeMillis();
            if (waitForScreenshot(2000L)) {
                Logx.i("[root] screenshot landed via cmd[" + i + "] —— 发命令到确认出图 "
                        + (System.currentTimeMillis() - t1) + "ms，本轮共 "
                        + (System.currentTimeMillis() - t0) + "ms");
                return true;
            }
            Logx.w("[root] cmd[" + i + "] produced no screenshot（等了 "
                    + (System.currentTimeMillis() - t1) + "ms）, trying next");
        }
        Logx.e("[root] all root screenshot methods failed");
        return false;
    }

    /** 立一个"现在"的时间标记；之后用 {@link #waitForNewShot} 判断有没有更新的图出现。 */
    public static void markNow() {
        exec("rm -f " + SHOT_MARK + "; touch " + SHOT_MARK + " 2>/dev/null", 5000L);
    }

    /**
     * 在 {@code timeoutMs} 内等一张"比标记更新"的图出现。
     *
     * <p>给"无障碍直调截屏"那条路做确认用：受理了不代表图一定出来，
     * 没出来就退回 root 模拟按键。
     */
    public static boolean waitForNewShot(long timeoutMs) {
        return waitForScreenshot(timeoutMs);
    }

    /**
     * 轮询等系统把截图写出来（含小米的 {@code .pending-} 临时文件，出现了就算数）。
     *
     * <p>从 400ms 缩到 250ms：每轮要起一个 su 去跑 find，慢是慢在这上面；
     * 缩短间隔能更早发现出图，而单次 find 现在也便宜了（见 {@link #newScreenshotSinceMark()}）。
     */
    private static boolean waitForScreenshot(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                return false;
            }
            if (newScreenshotSinceMark()) {
                return true;
            }
        }
        return false;
    }

    private static boolean newScreenshotSinceMark() {
        // 除了各厂商的 Screenshots 目录，再兜上 Pictures / DCIM 两级：
        // 万一 ROM 把图放到别处（或目录根本不存在），也不至于误判成"没截到"。
        // 窗口只有两秒，这段时间里冒出来的新文件基本只可能是刚截的图。
        //
        // 必须带 -maxdepth 1：不带的话 find 会**递归**扫下去，而 /sdcard/DCIM 底下通常还有
        // Camera、微信、各种 App 的目录，几千个文件扫下来一次就要几百毫秒——而这个 find
        // 每 250ms 跑一次、正是用来判断"图出来了没有"的，慢在这里就等于整个流程慢。
        // 截图从来不会落在子目录里（小米就是 /sdcard/DCIM/Screenshots/，属于本层），
        // 所以深度 1 足够。
        String dirs = "/sdcard/Pictures/Screenshots /sdcard/DCIM/Screenshots"
                + " /sdcard/Screenshots /sdcard/Pictures /sdcard/DCIM";
        Result r = exec("find " + dirs + " -maxdepth 1 -type f -newer " + SHOT_MARK
                + " 2>/dev/null | head -3", 8000L);
        return r.out != null && r.out.trim().length() > 0;
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
