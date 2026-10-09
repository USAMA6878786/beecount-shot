package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/** 模块 App 自己的配置与路径（只在本进程读写）。 */
public final class Prefs {

    private static final String FILE = "cfg";
    private static final String KEY_AUTO_DELETE = "auto_delete";
    private static final String KEY_PRECISE = "precise_partial";

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
