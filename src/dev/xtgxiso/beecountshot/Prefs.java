package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/** 模块 App 自己的配置与路径（只在本进程读写）。 */
public final class Prefs {

    private static final String FILE = "cfg";
    private static final String KEY_AUTO_DELETE = "auto_delete";
    private static final String KEY_HOST_PKG = "host_pkg";
    private static final String KEY_KEEP_ALIVE = "keep_alive";
    private static final String KEY_RAW_SWAP = "raw_swap";
    private static final String KEY_NOTIF_ASKED = "notif_asked";
    private static final String KEY_MEDIA_ASKED = "media_asked";
    private static final String KEY_WHITELISTED = "host_whitelisted";

    // ---------------------------------------------------------------- 关于「等待时长」
    //
    // v2.18 把「收面板后先等一会儿再截屏」整个去掉了：收面板之后**立刻**截屏。
    //
    // 为什么敢去掉：截屏现在走的是无障碍的 GLOBAL_ACTION_TAKE_SCREENSHOT，
    // 那是**直接调系统的截屏动作**，不经过输入系统的按键注入，所以跟控制中心面板
    // 收没收干净没有关系。原来那段等待是为了配合"注入按键"那条老路径（按键会被
    // 正在收起的面板吃掉），换路径之后它就没有存在理由了。
    //
    // 留个记录免得以后又绕回来：老的 delay_ms 配置项还躺在 SharedPreferences 里没人读，
    // 不用管它。

    /**
     * 记住探测到的宿主包名。
     *
     * <p>为什么值得持久化：探测要开一次 root shell 跑 {@code pm list packages}，
     * 实测 ~100~200ms。而这个值对一台设备来说是**稳定的**（除非用户换装 prod/dev 版）。
     * 冷启动（长时间不用后第一次点磁贴）本来要现跑一次，白等一两百毫秒——
     * 存下来就能直接跳过。见 {@link HostInfo#pkg}。
     */
    static String hostPkg(Context c) {
        try {
            return c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getString(KEY_HOST_PKG, null);
        } catch (Throwable t) {
            return null;
        }
    }

    static void setHostPkg(Context c, String pkg) {
        try {
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putString(KEY_HOST_PKG, pkg).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 是否让模块常驻（前台服务）。
     *
     * <p>默认开。常驻的两个好处：① 点磁贴不必再冷启动进程；② 无障碍服务的连接不会掉
     * （它掉了就只能退回慢的 root 模拟按键）。代价是一条常驻通知——但用的是
     * IMPORTANCE_MIN 的通知渠道，**状态栏不会出现图标**。
     */
    static boolean keepAlive(Context c) {
        try {
            return c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getBoolean(KEY_KEEP_ALIVE, true);
        } catch (Throwable t) {
            return true;
        }
    }

    static void setKeepAlive(Context c, boolean v) {
        try {
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_KEEP_ALIVE, v).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 通知权限问过一次就不再弹（Android 13+）。 */
    static boolean notifAsked(Context c) {
        try {
            return c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getBoolean(KEY_NOTIF_ASKED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    static void setNotifAsked(Context c, boolean v) {
        try {
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_NOTIF_ASKED, v).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 宿主是否已经加过电池优化白名单（只需成功一次）。 */
    static boolean hostWhitelisted(Context c) {
        try {
            return c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getBoolean(KEY_WHITELISTED, false);
        } catch (Throwable t) {
            return true;
        }
    }

    static void setHostWhitelisted(Context c, boolean v) {
        try {
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_WHITELISTED, v).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 相册权限是否已经问过一次（只问一次，不再打扰）。 */
    static boolean mediaAsked(Context c) {
        try {
            return c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getBoolean(KEY_MEDIA_ASKED, false);
        } catch (Throwable t) {
            return true;
        }
    }

    static void setMediaAsked(Context c, boolean v) {
        try {
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_MEDIA_ASKED, v).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * screencap raw 的通道顺序校准结果。
     *
     * <p>raw 出的是 RGBA，而 Bitmap 的 ARGB_8888 在内存里是 BGRA；
     * 顺序**因 ROM 而异**。所以不猜——由验证页两种都试、用像素差值选出对的，记在这里。
     *
     * @return -1 = 还没校准过（**这种情况生产链路不切 raw**）；0 = 不交换；1 = 交换 R/B
     */
    static int rawSwap(Context c) {
        try {
            return c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getInt(KEY_RAW_SWAP, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    static void setRawSwap(Context c, int v) {
        try {
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_RAW_SWAP, v).apply();
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

    private Prefs() {
    }
}
