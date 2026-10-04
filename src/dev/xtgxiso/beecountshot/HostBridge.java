package dev.xtgxiso.beecountshot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.io.File;

/**
 * 宿主进程侧的桥接：一旦拿到宿主 Context，就把日志落盘目标和广播接收器准备好。
 *
 * <p>Context 的来源有两条，互为备份（见 {@link BeeShotModule}）：
 * <ol>
 *   <li>Application 的 onCreate —— 最早、最稳，因为 Application 一定先于任何界面创建。</li>
 *   <li>MainActivity 的 onCreate —— 兜底。宿主已显式 keep 了这个类名，可以放心按名字 hook。</li>
 * </ol>
 *
 * <p>注意：日志落盘（{@link Logx#init}）不依赖 Context，在 onPackageReady 里就已经指向了
 * 宿主自己的 cache 目录。这是刻意的——万一 Context 一直拿不到，我们至少还有日志能看。
 */
final class HostBridge {

    private static volatile boolean attached = false;

    static void attach(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        synchronized (HostBridge.class) {
            if (attached) {
                return;
            }
            attached = true;
            Logx.i("[bridge] host context acquired: " + app.getClass().getName());
            try {
                Logx.i("[bridge] host cacheDir = " + app.getCacheDir().getAbsolutePath());
            } catch (Throwable ignored) {
            }

            // 遥测通道：把宿主侧状态写进宿主自己的 SharedPreferences。
            // 这是唯一一条"保证能写成功"的回传路径——应用写自己的 prefs 不涉及任何权限问题。
            HostTelemetry.init(app);

            // 再试一次日志落盘。用 Context 取到的 cacheDir 是"官方认可"的路径，
            // 比 onPackageReady 时期的绝对路径更可靠——早期那次可能因为目录尚未就绪而失败。
            try {
                String pkg = app.getPackageName();
                String viaContext = new File(app.getCacheDir(), "beecount_shot_host.log")
                        .getAbsolutePath();
                String[] candidates = Const.hostLogCandidates(pkg);
                Logx.init(new String[]{
                        viaContext,
                        candidates[0],
                        candidates[1],
                        candidates[2],
                });
                Logx.i("[bridge] file log sink after context: "
                        + (Logx.ready() ? "OK" : "STILL UNAVAILABLE (logcat only)"));
            } catch (Throwable t) {
                Logx.e("[bridge] log re-init failed", t);
            }

            registerArmReceiver(app);
            registerQueryReceiver(app);
            registerWidgetQueryReceiver(app);

            // 宿主主动推送：盯成功日志，一出现就由宿主自己删（模块 App 会被系统冻结，靠不住）。
            HostWatcher.start(app);

            // 提前确认/申请一次 root：删除公共存储里的文件需要它。
            // 放在这里（应用启动时）是为了让授权弹窗出现在可预期的时刻，
            // 而不是在"刚记完账"的中间突然弹出来。拒绝也不会重复弹。
            final Context appRef = app;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    HostRoot.ensure(appRef);
                }
            }, "bee-host-root").start();
        }
    }

    private static void registerArmReceiver(Context app) {
        try {
            IntentFilter filter = new IntentFilter(Const.ACTION_ARM);
            ArmReceiver receiver = new ArmReceiver();
            if (Build.VERSION.SDK_INT >= 33) {
                // 信号来自另一个 App（模块 App），所以必须是 EXPORTED；
                // 用 setPackage 定向发送，实际只有宿主能收到。
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            Logx.i("[bridge] arm receiver (backup channel) registered");
        } catch (Throwable t) {
            Logx.e("[bridge] register arm receiver failed", t);
        }
    }

    /**
     * 接收模块 App 的查询广播，在本进程里读自己的 SharedPreferences 并把答案回过去。
     *
     * <p>这是跨进程通信的主通道。之前用"模块 App 拿 root 去读宿主的文件"是行不通的：
     * su 在全局挂载命名空间，看不到应用的数据目录。
     */
    private static void registerQueryReceiver(Context app) {
        try {
            IntentFilter filter = new IntentFilter(Const.ACTION_QUERY);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    HostProbe.handleQuery(context, intent);
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            Logx.i("[bridge] query receiver registered (host -> app channel ready)");
        } catch (Throwable t) {
            Logx.e("[bridge] register query receiver failed", t);
        }
    }

    /**
     * 桌面小组件的取数请求：宿主读**自己的** SQLite 库算出日/周/月支出再回广播。
     *
     * <p>读库是阻塞操作，所以丢到子线程做，别拖住宿主的主线程。
     */
    private static void registerWidgetQueryReceiver(Context app) {
        try {
            IntentFilter filter = new IntentFilter(Const.ACTION_WIDGET_QUERY);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final Context c = context.getApplicationContext() != null
                            ? context.getApplicationContext() : context;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            replyWidgetData(c);
                        }
                    }, "bee-widget-query").start();
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            Logx.i("[bridge] widget query receiver registered");
        } catch (Throwable t) {
            Logx.e("[bridge] register widget query receiver failed", t);
        }
    }

    private static void replyWidgetData(Context ctx) {
        try {
            ExpenseQuery.Result r = ExpenseQuery.compute(ctx);
            Intent i = new Intent(Const.ACTION_WIDGET_RESULT);
            i.setPackage(Const.MODULE_PKG);
            i.putExtra(Const.EXTRA_OK, r.ok);
            i.putExtra(Const.EXTRA_DAY_EXPENSE, r.day);
            i.putExtra(Const.EXTRA_WEEK_EXPENSE, r.week);
            i.putExtra(Const.EXTRA_MONTH_EXPENSE, r.month);
            i.putExtra(Const.EXTRA_CURRENCY, r.currency);
            i.putExtra(Const.EXTRA_DETAIL, r.detail);
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            Logx.e("[bridge] replyWidgetData failed", t);
        }
    }

    private HostBridge() {
    }
}
