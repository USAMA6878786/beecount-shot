package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/** 模块 App 自己的配置与路径（只在本进程读写）。 */
public final class Prefs {

    private static final String FILE = "cfg";
    private static final String KEY_DELAY = "delay_ms";
    private static final String KEY_AUTO_DELETE = "auto_delete";
    private static final String KEY_PRECISE = "precise_partial";

    /**
     * 默认等待 600ms。
     *
     * <p>这个等待是从"收起面板的命令已经发出"之后才开始算的（见 ShotTileService），
     * 所以它只覆盖面板收起动画本身（约 250~350ms），留了一倍余量。
     */
    public static final long DEFAULT_DELAY_MS = 600L;

    /** 设置页提供的候选值。 */
    public static final long[] DELAY_OPTIONS =
            new long[]{300L, 600L, 900L, 1300L, 2000L};

    public static long delayMs(Context c) {
        try {
            return c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getLong(KEY_DELAY, DEFAULT_DELAY_MS);
        } catch (Throwable t) {
            return DEFAULT_DELAY_MS;
        }
    }

    public static void setDelayMs(Context c, long v) {
        try {
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_DELAY, v).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 模块 App 进程的日志文件（私有目录，再由设置页导出）。 */
    public static String appLogPath(Context c) {
        try {
            return new File(c.getCacheDir(), "beecount_shot_app.log").getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 记账成功后是否自动删除对应的截图。默认开。 */
    public static boolean autoDelete(Context c) {
        try {
            return c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getBoolean(KEY_AUTO_DELETE, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setAutoDelete(Context c, boolean v) {
        try {
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_AUTO_DELETE, v).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 连点多次、其中几张没识别成功时，要不要按"先放行的先出结果"推断着删掉前面那几张。
     *
     * <p>默认开。开了才能做到"删掉成功的、留下没识别的"；代价是推断依赖
     * "蜜蜂记账按上报先后依次处理"这个前提——万一失败的不是最后那几张，就会删错。
     * 在意"绝不误删"就关掉，关掉后只要有失败就全部保留。
     */
    public static boolean precisePartial(Context c) {
        try {
            return c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getBoolean(KEY_PRECISE, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setPrecisePartial(Context c, boolean v) {
        try {
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_PRECISE, v).apply();
        } catch (Throwable ignored) {
        }
    }

    private Prefs() {
    }
}
