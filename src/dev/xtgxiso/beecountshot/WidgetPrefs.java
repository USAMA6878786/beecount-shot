package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * 桌面小组件的本地配置与数据缓存。
 *
 * <p><b>透明度是按小部件分别保存的</b>：每个实例有自己的 {@code alpha_&lt;widgetId&gt;}。
 * 没单独设过的用全局默认值 {@code bg_alpha}。
 *
 * <p>为什么要分开：早先只有一个全局值，桌面上放两个小组件时，调其中一个会把另一个
 * 也改掉——用户在配置页明明只调了这一个。现在设置页的滑块表示"统一设为"（会清掉
 * 各自的自定义值），而添加/重新配置某个小组件时只改它自己的。
 *
 * <p>另外缓存上一次成功取到的三个数值：宿主进程没在跑的时候（比如刚重启手机、
 * 蜜蜂记账还没被打开过）拿不到新数据，这时显示上次的值总比显示空白好。
 */
final class WidgetPrefs {

    private static final String FILE = "widget";

    private static final String K_ALPHA_DEFAULT = "bg_alpha";   // 全局默认 0~255
    private static final String K_ALPHA_PREFIX = "alpha_";      // 按小部件
    private static final String K_DAY = "day";
    private static final String K_WEEK = "week";
    private static final String K_MONTH = "month";
    private static final String K_CUR = "currency";
    private static final String K_TS = "cached_at";
    private static final String K_HAS = "has_cache";

    /** 默认完全不透明（用户要求"白底"，需要透明再自己往下调）。 */
    static final int DEFAULT_ALPHA = 255;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 全局默认透明度（设置页滑块显示的就是它）。 */
    static int defaultAlpha(Context c) {
        try {
            return sp(c).getInt(K_ALPHA_DEFAULT, DEFAULT_ALPHA);
        } catch (Throwable t) {
            return DEFAULT_ALPHA;
        }
    }

    /** 取某个小部件的透明度：它自己的优先，没有就用全局默认。 */
    static int alpha(Context c, int widgetId) {
        try {
            SharedPreferences p = sp(c);
            return p.getInt(K_ALPHA_PREFIX + widgetId,
                    p.getInt(K_ALPHA_DEFAULT, DEFAULT_ALPHA));
        } catch (Throwable t) {
            return DEFAULT_ALPHA;
        }
    }

    /** 只改这一个小组件的透明度。 */
    static void setAlphaForWidget(Context c, int widgetId, int a) {
        try {
            sp(c).edit()
                    .putInt(K_ALPHA_PREFIX + widgetId, clamp(a))
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 设置页的"统一设为"：改默认值，并清掉各小部件的自定义值。 */
    static void setDefaultAlphaForAll(Context c, int a) {
        try {
            SharedPreferences p = sp(c);
            SharedPreferences.Editor e = p.edit().putInt(K_ALPHA_DEFAULT, clamp(a));
            for (Map.Entry<String, ?> en : p.getAll().entrySet()) {
                if (en.getKey().startsWith(K_ALPHA_PREFIX)) {
                    e.remove(en.getKey());
                }
            }
            e.apply();
        } catch (Throwable ignored) {
        }
    }

    private static int clamp(int a) {
        return Math.max(0, Math.min(255, a));
    }

    static void cache(Context c, double day, double week, double month, String currency) {
        try {
            sp(c).edit()
                    .putBoolean(K_HAS, true)
                    .putLong(K_DAY, Double.doubleToRawLongBits(day))
                    .putLong(K_WEEK, Double.doubleToRawLongBits(week))
                    .putLong(K_MONTH, Double.doubleToRawLongBits(month))
                    .putString(K_CUR, currency)
                    .putLong(K_TS, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    static boolean hasCache(Context c) {
        try {
            return sp(c).getBoolean(K_HAS, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** @return {day, week, month} */
    static double[] cached(Context c) {
        try {
            SharedPreferences p = sp(c);
            return new double[]{
                    Double.longBitsToDouble(p.getLong(K_DAY, Double.doubleToRawLongBits(0d))),
                    Double.longBitsToDouble(p.getLong(K_WEEK, Double.doubleToRawLongBits(0d))),
                    Double.longBitsToDouble(p.getLong(K_MONTH, Double.doubleToRawLongBits(0d))),
            };
        } catch (Throwable t) {
            return new double[]{0d, 0d, 0d};
        }
    }

    static String cachedCurrency(Context c) {
        try {
            String s = sp(c).getString(K_CUR, "CNY");
            return (s == null || s.length() == 0) ? "CNY" : s;
        } catch (Throwable t) {
            return "CNY";
        }
    }

    static long cachedAt(Context c) {
        try {
            return sp(c).getLong(K_TS, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private WidgetPrefs() {
    }
}
