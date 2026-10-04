package dev.xtgxiso.beecountshot;

import android.content.Context;

import java.io.File;

/**
 * 宿主进程内：盯着蜜蜂记账的数据库文件，数据一变就通知桌面小组件刷新。
 *
 * <p>为什么需要它：小组件原来的刷新触发点只有三个——30 分钟周期、用户点一下、
 * 以及"自动记账成功"。**在蜜蜂记账里删掉或修改一笔账不会写那条成功日志**，
 * 于是没有任何东西通知小组件去重读，数字就一直停在旧值上。
 *
 * <p>做法是只看文件的修改时间（`stat`，几乎不耗电），不看内容：
 * 蜜蜂记账用 drift/SQLite，任何一次写入都会更新 `-wal` 文件（或库文件本身），
 * 所以我们能把"新增 / 修改 / 删除"全都覆盖到。
 *
 * <p>两个节流：轮询间隔 1.5 秒；真正发通知的间隔不小于 2 秒。即使触发被节流吞掉，
 * 下一次通知读到的也是最新数据，所以不会丢状态。
 *
 * <p>只在"确实有小组件存在"时才工作（由模块 App 在查询时把数量带过来），
 * 没有小组件时不产生任何唤醒。
 */
final class DbWatcher {

    /** 轮询间隔。 */
    private static final long POLL_MS = 1500L;

    /** 两次通知之间的最小间隔。 */
    private static final long DEBOUNCE_MS = 2000L;

    private static volatile boolean running = false;

    /** 模块 App 最近一次报告的、桌面上的小组件个数。 */
    private static volatile int widgetCount = 0;

    static void setWidgetCount(int count) {
        widgetCount = Math.max(0, count);
    }

    static int widgetCount() {
        return widgetCount;
    }

    static synchronized void start(final Context ctx) {
        if (running) {
            return;
        }
        running = true;
        final Context app = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Logx.i("[dbwatch] watching " + Const.DB_NAME + " for changes");
                long lastStamp = -1L;
                long lastNotify = 0L;
                while (true) {
                    try {
                        long stamp = stampOf(app);
                        if (lastStamp >= 0L && stamp > lastStamp) {
                            long now = System.currentTimeMillis();
                            if (widgetCount > 0 && now - lastNotify >= DEBOUNCE_MS) {
                                lastNotify = now;
                                Logx.i("[dbwatch] database changed -> notify widget");
                                HostWatcher.pingWidget(app);
                            } else {
                                Logx.i("[dbwatch] database changed (throttled, widgets="
                                        + widgetCount + ")");
                            }
                        }
                        if (stamp > lastStamp) {
                            lastStamp = stamp;
                        }
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        Logx.w("[dbwatch] loop error: " + t.getMessage());
                        try {
                            Thread.sleep(POLL_MS);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        }, "bee-db-watch").start();
    }

    /** 库文件与 -wal 文件里较新的那个修改时间。 */
    private static long stampOf(Context ctx) {
        File db = ExpenseQuery.findDb(ctx);
        if (db == null) {
            return 0L;
        }
        long stamp = db.lastModified();
        try {
            File wal = new File(db.getAbsolutePath() + "-wal");
            if (wal.isFile()) {
                long w = wal.lastModified();
                if (w > stamp) {
                    stamp = w;
                }
            }
        } catch (Throwable ignored) {
        }
        return stamp;
    }

    private DbWatcher() {
    }
}
