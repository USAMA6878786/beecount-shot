package dev.xtgxiso.beecountshot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 模块 App 侧：通过广播向宿主要"日/周/月支出"三个数。
 *
 * <p>数据在蜜蜂记账自己的 SQLite 库里，而只有**它自己的进程**能读（模块 App 用 root
 * 读别的应用数据目录是行不通的，前面已经踩过）。所以还是老办法：让宿主读自己的库，
 * 用广播送回来。
 */
final class WidgetBridge {

    static final class Data {
        boolean received;
        boolean ok;
        double day;
        double week;
        double month;
        String currency = "CNY";
        String detail = "";
    }

    private static final ArrayBlockingQueue<Data> QUEUE = new ArrayBlockingQueue<Data>(4);
    private static volatile boolean ready = false;

    static void ensureReceiver(Context ctx) {
        if (ready || ctx == null) {
            return;
        }
        synchronized (WidgetBridge.class) {
            if (ready) {
                return;
            }
            try {
                IntentFilter filter = new IntentFilter(Const.ACTION_WIDGET_RESULT);
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent == null
                                || !Const.ACTION_WIDGET_RESULT.equals(intent.getAction())) {
                            return;
                        }
                        Data d = new Data();
                        d.received = true;
                        d.ok = intent.getBooleanExtra(Const.EXTRA_OK, false);
                        d.day = intent.getDoubleExtra(Const.EXTRA_DAY_EXPENSE, 0d);
                        d.week = intent.getDoubleExtra(Const.EXTRA_WEEK_EXPENSE, 0d);
                        d.month = intent.getDoubleExtra(Const.EXTRA_MONTH_EXPENSE, 0d);
                        String cur = intent.getStringExtra(Const.EXTRA_CURRENCY);
                        if (cur != null && cur.length() > 0) {
                            d.currency = cur;
                        }
                        String detail = intent.getStringExtra(Const.EXTRA_DETAIL);
                        if (detail != null) {
                            d.detail = detail;
                        }
                        Logx.i("[widget] host answered ok=" + d.ok + " day=" + d.day
                                + " week=" + d.week + " month=" + d.month
                                + " " + d.currency + " | " + d.detail);
                        QUEUE.offer(d);
                    }
                };
                Context app = ctx.getApplicationContext() != null
                        ? ctx.getApplicationContext() : ctx;
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    app.registerReceiver(receiver, filter);
                }
                ready = true;
            } catch (Throwable t) {
                Logx.e("[widget] register receiver failed", t);
            }
        }
    }

    /** 同步查一次；超时返回 received=false。 */
    static Data query(Context ctx, long timeoutMs) {
        ensureReceiver(ctx);
        Data back = null;
        QUEUE.clear();
        try {
            Intent q = new Intent(Const.ACTION_WIDGET_QUERY);
            q.setPackage(HostInfo.pkg());
            // 顺带告诉宿主桌面上有几个小组件：一个都没有时，它就不用为"账目变动"打扰我们
            q.putExtra(Const.EXTRA_WIDGET_COUNT, countWidgets(ctx));
            ctx.sendBroadcast(q);
        } catch (Throwable t) {
            Logx.e("[widget] send query failed", t);
            return new Data();
        }
        try {
            back = QUEUE.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (back == null) {
            Logx.w("[widget] no answer from host within " + timeoutMs + "ms");
            return new Data();
        }
        return back;
    }

    private static int countWidgets(Context ctx) {
        try {
            android.appwidget.AppWidgetManager mgr =
                    android.appwidget.AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new android.content.ComponentName(
                    ctx, ExpenseWidgetProvider.class));
            return ids == null ? 0 : ids.length;
        } catch (Throwable t) {
            return 0;
        }
    }

    private WidgetBridge() {
    }
}
