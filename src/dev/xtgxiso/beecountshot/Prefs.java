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
    private static final String KEY_COMPRESS = "compress";
    private static final String KEY_COMPRESS_INITED = "compress_inited";

    // ---------------------------------------------------------------- 关于「等待时长」
    //
    // 收面板之后只等 Const.SHOT_SETTLE_MS（100ms）就抓帧。那 100ms 不是等"命令返回"，
    // 而是等**面板收起动画的第一帧翻过去**——{@code screencap} 抓的是那一刻的真实屏幕，
    // 面板还没退干净就会被拍进去。
    //
    // 早期这里可配（150/600/1300ms），是配合"root 注入按键"那条老路的：注入的按键会被
    // 正在收起的面板吃掉，所以要等更久。现在抓帧走 {@code screencap}，不经过输入系统，
    // 就没有那个顾虑了；配置项 delay_ms 还躺在 SharedPreferences 里，没人读，不用管。

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

    /**
     * 通知权限是否已经问过一次（只问一次，避免反复弹）。
     *
     * <p><b>默认 false</b>：全新安装从来没被问过。以前这里默认 true，
     * 于是"初次打开自动申请"永远不会触发——用户装了模块、点了磁贴，一句提示都没有，
     * 因为在 MIUI 上没通知权限时 Toast 直接被吞掉。
     */
    static boolean notifAsked(Context c) {
        try {
            return c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getBoolean(KEY_NOTIF_ASKED, false);
        } catch (Throwable t) {
            return true;
        }
    }

    static void setNotifAsked(Context c, boolean v) {
        try {
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_NOTIF_ASKED, v).apply();
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

    /**
     * 截图要不要先压缩再交给蜜蜂记账（默认**开**）。
     *
     * <p>为什么默认开：这一项只影响"上传给 AI 的那份图有多大"，不改变截到的内容。
     * 3.2MB 的 PNG 压成 JPEG 之后一般 300~500KB，上传耗时直接按比例下降；
     * 而识别用的是同一张图（同分辨率、质量 85），正文和数字都还清楚。
     *
     * <p>关掉它就完全按原样写相册（PNG 原图），给"觉得识别不准"的用户留一条退路。
     */
    static boolean compress(Context c) {
        try {
            return c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getBoolean(KEY_COMPRESS, true);
        } catch (Throwable t) {
            return true;
        }
    }

    static void setCompress(Context c, boolean v) {
        try {
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_COMPRESS, v).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 「图片压缩」是否已经做过**首次初始化**（即：raw 通道有没有校准过）。
     *
     * <p>为什么需要这个标记：开关默认是**开**的，所以新装的用户根本不会去"打开"它——
     * 而校准是挂在"打开"这个动作上的，结果就是**永远不校准**，一直用 PNG 抓帧那条慢路
     * （能用、也照样压缩，只是每次抓帧多花约 0.2 秒）。
     * 有了这个标记，首次打开设置页时补一次**静默**校准，之后就按开关走。
     */
    static boolean compressInited(Context c) {
        try {
            return c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getBoolean(KEY_COMPRESS_INITED, false);
        } catch (Throwable t) {
            return true;   // 读不到就当已完成，别反复跑
        }
    }

    static void setCompressInited(Context c, boolean v) {
        try {
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_COMPRESS_INITED, v).apply();
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
