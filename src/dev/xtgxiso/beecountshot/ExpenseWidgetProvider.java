package dev.xtgxiso.beecountshot;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.text.DecimalFormat;

/**
 * 桌面小组件：日支出 / 周支出 / 月支出。
 *
 * <p>布局随宽度自适应：
 * <ul>
 *   <li>窄（默认 2×1）→ 三项**上下三行**</li>
 *   <li>横向拉长到约 3 格宽以上 → 三项变成**左中右三列**</li>
 * </ul>
 * 判断依据是系统给的 {@code OPTION_APPWIDGET_MIN_WIDTH}（dp），不是像素，
 * 所以不同密度、不同厂商的桌面都成立。
 *
 * <p>背景是白色圆角矩形，透明度由用户在添加时的配置页用拖动条设定。这里把背景
 * 画成一张位图给 ImageView，而不是用 {@code setBackgroundColor}——后者会把圆角
 * 丢掉（ColorDrawable 是方的），画位图既能圆角又能任意透明度。
 *
 * <p>两段式刷新：先用缓存值立刻渲染（不阻塞主线程，因为 AppWidgetProvider 的回调
 * 跑在主线程，同步等广播会顶到 ANR 边上），再起子线程去宿主那边取新数据后重绘。
 *
 * <p>点一下小组件可以手动刷新。
 */
public class ExpenseWidgetProvider extends AppWidgetProvider {

    /** 点按刷新用的广播 action。 */
    static final String ACTION_REFRESH = "dev.xtgxiso.beecountshot.WIDGET_REFRESH";

    /** 横向布局的宽度阈值（dp）。2 格约 110dp，3 格约 180dp，取中间偏保守。 */
    private static final int HORIZONTAL_MIN_WIDTH_DP = 180;

    private static final long QUERY_TIMEOUT_MS = 4000L;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null) {
            return;
        }
        // 借这个"每 30 分钟一次"的周期更新当钩子，请系统重新绑定一次控制中心磁贴。
        //
        // 原因：Android 在应用被**覆盖安装**之后经常把磁贴"晾着"——图标还在面板上，
        // 但绑定关系是旧的，点了没反应，过一阵子又自己好。这里是一个天然的定时点，
        // 成本只有一次 requestListeningState（磁贴会重新读到宿主的最新状态）。
        try {
            ShotTileService.requestRefresh(context);
        } catch (Throwable t) {
            Logx.w("[widget] tile refresh request failed: " + t.getMessage());
        }
        for (int i = 0; i < ids.length; i++) {
            render(context, manager, ids[i], manager.getAppWidgetOptions(ids[i]), true);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int id, Bundle newOptions) {
        // 尺寸变了只需要按新尺寸重画，不必再取一次数
        render(context, manager, id, newOptions, false);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        // 只对"我们自己定义的那两个 action"做发件人校验。
        // APPWIDGET_UPDATE 等系统广播一律原样放行，免得校验出岔子把小组件弄成不刷新。
        // 校验只拒"确认识别出是陌生应用"的，任何不确定都放行（见 SenderGuard）。
        if (action != null
                && (ACTION_REFRESH.equals(action) || Const.ACTION_WIDGET_PING.equals(action))
                && !SenderGuard.allow(this, context)) {
            return;
        }
        if (ACTION_REFRESH.equals(action)) {
            Logx.i("[widget] tapped -> refresh + open records");
            // 点一下做两件事：后台刷新数字，并打开记账记录明细页
            refreshAll(context.getApplicationContext());
            openHostRecords(context.getApplicationContext());
        } else if (Const.ACTION_WIDGET_PING.equals(action)) {
            // 宿主那边记账成功后的静默刷新：只更新数字，不打开任何界面。
            // 因为这条是发给我们清单里声明的 receiver，即使本应用进程已被回收也能被唤起。
            Logx.i("[widget] host ping -> silent refresh (data changed)");
            refreshAll(context.getApplicationContext());
        }
    }

    /**
     * 打开蜜蜂记账的「记账记录明细」页。
     *
     * <p>用的是它自己的深链 {@code beecount://open?page=detail}（见它的
     * app_link_service.dart），而不是笼统地拉起主界面。
     */
    static void openHostRecords(Context ctx) {
        String pkg = HostInfo.pkg();
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("beecount://open?page=detail"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.setPackage(pkg);
            ctx.startActivity(i);
            Logx.i("[widget] opened beecount://open?page=detail");
            return;
        } catch (Throwable t) {
            Logx.e("[widget] deep link failed, falling back to launch intent", t);
        }
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                Logx.i("[widget] opened host main activity (fallback)");
            }
        } catch (Throwable t) {
            Logx.e("[widget] open host failed", t);
        }
    }

    /** 刷新所有小组件（记账成功后也可以调）。 */
    static void refreshAll(final Context context) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                    ComponentName cn = new ComponentName(context, ExpenseWidgetProvider.class);
                    int[] ids = mgr.getAppWidgetIds(cn);
                    if (ids == null || ids.length == 0) {
                        return;
                    }
                    WidgetBridge.Data data = WidgetBridge.query(context, QUERY_TIMEOUT_MS);
                    if (data.received && data.ok) {
                        WidgetPrefs.cache(context, data.day, data.week, data.month, data.currency);
                    }
                    for (int i = 0; i < ids.length; i++) {
                        draw(context, mgr, ids[i], mgr.getAppWidgetOptions(ids[i]),
                                !(data.received && data.ok));
                    }
                } catch (Throwable t) {
                    Logx.e("[widget] refreshAll failed", t);
                }
            }
        }, "bee-widget-refresh").start();
    }

    /**
     * @param fetch true = 先按缓存画一版，再起线程去取新数据重画
     */
    private static void render(final Context ctx, final AppWidgetManager manager,
                               final int id, final Bundle options, final boolean fetch) {
        // 第一遍：用已有缓存立刻出图（不阻塞）
        draw(ctx, manager, id, options, !WidgetPrefs.hasCache(ctx));

        if (!fetch) {
            return;
        }
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    WidgetBridge.Data data = WidgetBridge.query(app, QUERY_TIMEOUT_MS);
                    if (data.received && data.ok) {
                        WidgetPrefs.cache(app, data.day, data.week, data.month, data.currency);
                        draw(app, manager, id, options, false);
                    } else {
                        draw(app, manager, id, options, true);
                    }
                } catch (Throwable t) {
                    Logx.e("[widget] fetch failed", t);
                }
            }
        }, "bee-widget-fetch").start();
    }

    /**
     * @param stale true = 显示灰色数字（表示这不是实时数据）
     */
    private static void draw(Context context, AppWidgetManager manager, int id,
                            Bundle options, boolean stale) {
        try {
            int widthDp = (options == null) ? 0
                    : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            // 用当前高度，不要用 OPTION_APPWIDGET_MAX_HEIGHT——那是"最大可能高度"
            // （拉伸过程中桌面可能报出很大的值），按它生成背景位图会顶到
            // RemoteViews 的 Binder 1MB 上限，表现是桌面报"载入窗口小部件时出现问题"。
            int heightDp = (options == null) ? 0
                    : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
            boolean horizontal = widthDp >= HORIZONTAL_MIN_WIDTH_DP;

            double[] values = WidgetPrefs.cached(context);
            String currency = WidgetPrefs.cachedCurrency(context);
            boolean empty = !WidgetPrefs.hasCache(context);

            RemoteViews views = new RemoteViews(context.getPackageName(),
                    horizontal ? R.layout.widget_expense_h : R.layout.widget_expense_v);

            int alpha = WidgetPrefs.alpha(context, id);
            int[] size = bitmapSize(context, widthDp, heightDp);
            Bitmap bg = null;
            try {
                bg = roundedBg(context, size[0], size[1], alpha);
            } catch (Throwable t) {
                Logx.w("[widget] bitmap bg failed, falling back to flat color: " + t.getMessage());
            }
            if (bg != null) {
                views.setImageViewBitmap(R.id.widget_bg, bg);
            } else {
                views.setInt(R.id.widget_bg, "setBackgroundColor",
                        Color.argb(Math.max(0, Math.min(255, alpha)), 255, 255, 255));
            }

            int color = (stale || empty)
                    ? Color.parseColor("#B4B4B8") : Color.parseColor("#222222");
            views.setTextViewText(R.id.w_day_value,
                    empty ? "—" : money(values[0], currency));
            views.setTextViewText(R.id.w_week_value,
                    empty ? "—" : money(values[1], currency));
            views.setTextViewText(R.id.w_month_value,
                    empty ? "—" : money(values[2], currency));
            views.setTextColor(R.id.w_day_value, color);
            views.setTextColor(R.id.w_week_value, color);
            views.setTextColor(R.id.w_month_value, color);

            Intent refresh = new Intent(context, ExpenseWidgetProvider.class);
            refresh.setAction(ACTION_REFRESH);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            views.setOnClickPendingIntent(R.id.widget_root,
                    PendingIntent.getBroadcast(context, id, refresh, flags));

            manager.updateAppWidget(id, views);
            Logx.i("[widget] drawn id=" + id + " " + widthDp + "x" + heightDp + "dp "
                    + (horizontal ? "horizontal" : "vertical")
                    + (empty ? " (no data)" : (stale ? " (stale)" : "")));
        } catch (Throwable t) {
            Logx.e("[widget] draw failed", t);
        }
    }

    // ---------------------------------------------------------------- 绘制与格式化

    /**
     * 背景位图的像素尺寸。
     *
     * <p>两道保险：用**当前**尺寸（不是最大可能尺寸），并做统一的像素上限裁剪——
     * 位图是走 Binder 传给桌面的，超过 1MB 会直接失败，桌面只会甩一句
     * "载入窗口小部件时出现问题"。等比缩小能把圆角变形控制在可接受范围。
     */
    private static int[] bitmapSize(Context context, int wDp, int hDp) {
        int w = dp2px(context, wDp > 0 ? wDp : 110);
        int h = dp2px(context, hDp > 0 ? hDp : 40);
        long maxPixels = 100000L; // 约 400KB，远低于 Binder 上限
        if ((long) w * (long) h > maxPixels) {
            double scale = Math.sqrt((double) maxPixels / ((double) w * (double) h));
            w = Math.max(4, (int) (w * scale));
            h = Math.max(4, (int) (h * scale));
        }
        return new int[]{w, h};
    }

    private static Bitmap roundedBg(Context context, int w, int h, int alpha) {
        int ww = Math.max(4, Math.min(w, 720));
        int hh = Math.max(4, Math.min(h, 720));
        Bitmap bmp = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.argb(Math.max(0, Math.min(255, alpha)), 255, 255, 255));
        float radius = dp2px(context, 16);
        canvas.drawRoundRect(new RectF(0, 0, ww, hh), radius, radius, paint);
        return bmp;
    }

    private static int dp2px(Context context, int dp) {
        try {
            return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
        } catch (Throwable t) {
            return dp;
        }
    }

    /** 金额格式化：小额保留两位小数，上万后省掉角分，避免窄小组件被截断。 */
    static String money(double value, String currency) {
        String symbol = symbolOf(currency);
        String num = (Math.abs(value) >= 10000d)
                ? new DecimalFormat("#,##0").format(value)
                : new DecimalFormat("#,##0.00").format(value);
        return symbol + num;
    }

    private static String symbolOf(String currency) {
        if (currency == null) {
            return "¥";
        }
        String c = currency.toUpperCase();
        if ("CNY".equals(c) || "RMB".equals(c) || "JPY".equals(c)) {
            return "¥";
        }
        if ("USD".equals(c)) {
            return "$";
        }
        if ("EUR".equals(c)) {
            return "€";
        }
        if ("GBP".equals(c)) {
            return "£";
        }
        if ("HKD".equals(c)) {
            return "HK$";
        }
        if ("TWD".equals(c)) {
            return "NT$";
        }
        if ("KRW".equals(c)) {
            return "₩";
        }
        return c + " ";
    }

    /** 供配置页判断横竖版式用（与渲染同一套阈值）。 */
    static boolean isHorizontal(int widthDp) {
        return widthDp >= HORIZONTAL_MIN_WIDTH_DP;
    }

    /** 供配置页预览背景色用。 */
    static int bgColor(int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), 255, 255, 255);
    }
}
