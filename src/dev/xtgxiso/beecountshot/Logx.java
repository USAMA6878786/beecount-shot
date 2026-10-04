package dev.xtgxiso.beecountshot;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 会落盘的日志。
 *
 * <p>为什么不用 logcat：用户拿不到。logcat 需要 adb 或者 root 终端，普通用户没法看，
 * 出问题时只能靠猜。所以这里把日志写进进程自己的私有目录，再由设置页一键导出到
 * Download 目录——用户拿文件管理器就能取到并发给我。
 *
 * <p>为什么用"自己的私有目录 + 绝对路径"而不是共享存储：
 * <ul>
 *   <li>宿主进程写自己 cache 目录里的文件，不需要任何权限、不需要 Context、也不受
 *       分区存储限制——这三条都让它在最恶劣的情况下（连 Context 都没拿到）依然能工作，
 *       而"拿不到 Context"恰恰是最需要日志的一种故障。</li>
 *   <li>模块 App 进程也一样写自己的 cache 目录，再由它（有 root）把宿主那份拷出来。</li>
 * </ul>
 *
 * <p>每条日志都立即 open/append/close，不做缓冲。写日志的场合本来就很稀疏，这点开销可以
 * 忽略；换来的是"进程被系统杀掉也不会丢尾巴"。
 */
public final class Logx {

    /** 单文件上限，超过就截断重来，避免无限增长。 */
    private static final long MAX_BYTES = 512L * 1024L;

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static volatile String logPath;
    private static volatile boolean disabled;

    /** 指定日志文件绝对路径。两个进程各写各的。 */
    public static void init(String path) {
        init(new String[]{path});
    }

    /**
     * 依次尝试多个候选路径，取第一个真正能写的。
     *
     * <p>之所以要多个候选：宿主的日志文件在真机上出现了"写不进去"的情况，而写不进去的
     * 原因当时无从得知（能写日志的时候才有日志……）。所以这里把每个候选的失败原因都打到
     * logcat 上——logcat 不依赖任何文件权限，是唯一可靠的兜底通道。
     */
    public static void init(String[] candidates) {
        synchronized (LOCK) {
            if (candidates == null || candidates.length == 0) {
                return;
            }
            for (int i = 0; i < candidates.length; i++) {
                String path = candidates[i];
                if (path == null) {
                    continue;
                }
                if (tryOpen(path)) {
                    logPath = path;
                    disabled = false;
                    Log.i(Const.TAG, "[log] sink ready: " + path);
                    return;
                }
            }
            logPath = null;
            disabled = true;
            Log.e(Const.TAG, "[log] ALL candidate sinks failed; only logcat remains");
        }
    }

    private static boolean tryOpen(String path) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(Const.TAG, "[log] mkdirs failed: " + parent.getAbsolutePath());
                return false;
            }
            if (f.exists() && f.length() > MAX_BYTES && !f.delete()) {
                Log.w(Const.TAG, "[log] truncate failed: " + path);
            }
            if (!f.exists() && !f.createNewFile()) {
                Log.w(Const.TAG, "[log] createNewFile failed: " + path);
                return false;
            }
            if (!f.canWrite()) {
                Log.w(Const.TAG, "[log] not writable: " + path);
                return false;
            }
            // 真写一小段探针，确认不是"看起来能写、实际一写就炸"。
            FileOutputStream probe = null;
            try {
                probe = new FileOutputStream(f, true);
                probe.write(new byte[0]);
                probe.flush();
            } finally {
                if (probe != null) {
                    try {
                        probe.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(Const.TAG, "[log] sink unusable " + path + " -> "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    public static boolean ready() {
        return logPath != null && !disabled;
    }

    public static void i(String msg) {
        write("I", msg, null);
    }

    public static void w(String msg) {
        write("W", msg, null);
    }

    public static void e(String msg, Throwable t) {
        write("E", msg, t);
    }

    private static void write(String level, String msg, Throwable t) {
        // 同时保留 logcat，方便开发时直接看。
        if ("E".equals(level)) {
            Log.e(Const.TAG, msg, t);
        } else if ("W".equals(level)) {
            Log.w(Const.TAG, msg);
        } else {
            Log.i(Const.TAG, msg);
        }

        String path = logPath;
        if (path == null || disabled) {
            return;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append(TS.format(new Date()))
                .append(' ').append(level).append(' ')
                .append('[').append(Thread.currentThread().getName()).append("] ")
                .append(msg).append('\n');
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append(sw);
        }
        synchronized (LOCK) {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(path, true);
                fos.write(sb.toString().getBytes("UTF-8"));
                fos.flush();
            } catch (Throwable x) {
                disabled = true;
                Log.e(Const.TAG, "log write failed: " + x.getMessage());
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private Logx() {
    }
}
