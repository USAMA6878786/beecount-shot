package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.Calendar;

/**
 * 宿主进程内：直接读蜜蜂记账自己的 SQLite 库，算日/周/月支出合计。
 *
 * <p>为什么必须由宿主来读：
 * <ul>
 *   <li>模块 App 用 root 读不了别的应用的数据目录（{@code su} 在全局挂载命名空间，
 *       这是前面踩了很久的坑）；</li>
 *   <li>而宿主进程就是蜜蜂记账自己，读自己的库天经地义、权限齐全。</li>
 * </ul>
 *
 * <p>统计口径**完全照抄**蜜蜂记账自己的实现（{@code LocalStatisticsRepository}）：
 * <pre>
 *   SELECT COALESCE(SUM(COALESCE(native_amount, amount)),0) FROM transactions
 *   WHERE ledger_id = ? AND type = 'expense' AND exclude_from_stats = 0
 *     AND happened_at &gt;= ? AND happened_at &lt; ?
 * </pre>
 * 也就是：只看支出、剔除"不计入收支"的记账、金额优先取折算到账本本位币的
 * {@code native_amount}。这样小组件的数字和它自己统计页的数字一致。
 *
 * <p>月周期的口径也照抄（{@code month_range.dart#periodContaining}）：账本可以设置
 * 自定义每月起始日 {@code month_start_day}（1~28），月区间是
 * [本月起始日, 次月起始日)。周按**周一起**（与它自己的周期账单/预算一致）。
 */
final class ExpenseQuery {

    static final class Result {
        boolean ok;
        double day;
        double week;
        double month;
        String currency = "CNY";
        int ledgerId = -1;
        int monthStartDay = 1;
        String detail = "";
    }

    static Result compute(Context ctx) {
        Result r = new Result();
        SQLiteDatabase db = null;
        try {
            File file = findDb(ctx);
            if (file == null) {
                r.detail = "db-not-found under " + safeDataDir(ctx);
                return r;
            }
            // 注意用 READWRITE 而不是 READONLY：蜜蜂记账的 drift 开了 WAL，
            // 只读打开时 SQLite 需要能创建/写 -shm 文件，READONLY 可能直接失败。
            // 我们本来就是它自己，读写权限都有。
            db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);

            long unit = detectUnit(db);
            int[] ledger = resolveLedger(ctx, db);
            r.ledgerId = ledger[0];
            r.monthStartDay = ledger[1];
            if (r.ledgerId <= 0) {
                r.detail = "no-ledger";
                return r;
            }
            r.currency = currencyOf(db, r.ledgerId);

            long[] day = dayRange();
            long[] week = weekRange();
            long[] month = monthRange(r.monthStartDay);

            r.day = sum(db, r.ledgerId, day[0] * unit, day[1] * unit);
            r.week = sum(db, r.ledgerId, week[0] * unit, week[1] * unit);
            r.month = sum(db, r.ledgerId, month[0] * unit, month[1] * unit);
            r.ok = true;
            r.detail = "db=" + file.getAbsolutePath() + " unit=" + unit
                    + " ledger=" + r.ledgerId + " monthStartDay=" + r.monthStartDay
                    + " today=[" + day[0] + "," + day[1] + ")"
                    + " week=[" + week[0] + "," + week[1] + ")"
                    + " month=[" + month[0] + "," + month[1] + ")";
            Logx.i("[widget] " + r.detail + " => day=" + r.day + " week=" + r.week
                    + " month=" + r.month + " " + r.currency);
            return r;
        } catch (Throwable t) {
            r.detail = "error: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            Logx.e("[widget] query failed", t);
            return r;
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- 区间

    private static long[] dayRange() {
        Calendar c = midnightToday();
        long start = c.getTimeInMillis() / 1000L;
        c.add(Calendar.DAY_OF_YEAR, 1);
        return new long[]{start, c.getTimeInMillis() / 1000L};
    }

    /** 本周（**周一起**）。Calendar 里 SUNDAY=1…SATURDAY=7。 */
    private static long[] weekRange() {
        Calendar c = midnightToday();
        int dow = c.get(Calendar.DAY_OF_WEEK);
        int delta = (dow == Calendar.SUNDAY) ? -6 : (Calendar.MONDAY - dow);
        c.add(Calendar.DAY_OF_YEAR, delta);
        long start = c.getTimeInMillis() / 1000L;
        c.add(Calendar.DAY_OF_YEAR, 7);
        return new long[]{start, c.getTimeInMillis() / 1000L};
    }

    /**
     * 账本自定义月周期 [本月起始日, 次月起始日)，与 month_range.dart#periodContaining 一致。
     * 起始日 1 时退化为自然月。
     */
    private static long[] monthRange(int startDay) {
        int d = Math.max(1, Math.min(28, startDay));
        Calendar now = Calendar.getInstance();
        Calendar c = midnightToday();
        if (now.get(Calendar.DAY_OF_MONTH) >= d) {
            c.set(Calendar.DAY_OF_MONTH, d);
        } else {
            c.add(Calendar.MONTH, -1);
            c.set(Calendar.DAY_OF_MONTH, d);
        }
        long start = c.getTimeInMillis() / 1000L;
        c.add(Calendar.MONTH, 1);
        return new long[]{start, c.getTimeInMillis() / 1000L};
    }

    private static Calendar midnightToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    // ---------------------------------------------------------------- SQL

    /** 和蜜蜂记账自己的统计 SQL 一字不差。 */
    private static double sum(SQLiteDatabase db, int ledgerId, long start, long end) {
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT COALESCE(SUM(COALESCE(native_amount, amount)),0) FROM transactions "
                            + "WHERE ledger_id = ? AND type = 'expense' "
                            + "AND exclude_from_stats = 0 "
                            + "AND happened_at >= ? AND happened_at < ?",
                    new String[]{
                            String.valueOf(ledgerId),
                            String.valueOf(start),
                            String.valueOf(end),
                    });
            if (c != null && c.moveToFirst()) {
                return c.getDouble(0);
            }
        } catch (Throwable t) {
            Logx.w("[widget] sum failed: " + t.getMessage());
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return 0d;
    }

    /**
     * 判断 happened_at 存的是秒还是毫秒。
     *
     * <p>drift 的 {@code dateTime()} 默认按 Unix **秒** 存（它的 currentDateAndTime 就是
     * {@code strftime('%s','now')}），但为了避免版本差异，这里用最大值量级来自我判断——
     * 大于 1000 亿一定是毫秒。
     */
    private static long detectUnit(SQLiteDatabase db) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COALESCE(MAX(happened_at),0) FROM transactions", null);
            if (c != null && c.moveToFirst()) {
                long max = c.getLong(0);
                return (max > 100000000000L) ? 1000L : 1L;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return 1L;
    }

    /** 解析当前账本 id 与它的自定义月起始日。 */
    private static int[] resolveLedger(Context ctx, SQLiteDatabase db) {
        int fromPrefs = -1;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    "FlutterSharedPreferences", Context.MODE_PRIVATE);
            fromPrefs = sp.getInt("flutter.current_ledger_id", -1);
        } catch (Throwable ignored) {
        }
        int[] out = new int[]{-1, 1};
        Cursor c = null;
        try {
            if (fromPrefs > 0) {
                c = db.rawQuery(
                        "SELECT id, month_start_day FROM ledgers WHERE id = ? LIMIT 1",
                        new String[]{String.valueOf(fromPrefs)});
                if (c != null && c.moveToFirst()) {
                    out[0] = c.getInt(0);
                    out[1] = c.isNull(1) ? 1 : c.getInt(1);
                    return out;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        c = null;
        try {
            c = db.rawQuery("SELECT id, month_start_day FROM ledgers ORDER BY id LIMIT 1", null);
            if (c != null && c.moveToFirst()) {
                out[0] = c.getInt(0);
                out[1] = c.isNull(1) ? 1 : c.getInt(1);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static String currencyOf(SQLiteDatabase db, int ledgerId) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT currency FROM ledgers WHERE id = ? LIMIT 1",
                    new String[]{String.valueOf(ledgerId)});
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                String s = c.getString(0);
                if (s != null && s.length() > 0) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return "CNY";
    }

    // ---------------------------------------------------------------- 找库文件

    private static File findDb(Context ctx) {
        File data = ctx.getDataDir();
        if (data == null) {
            return null;
        }
        File[] candidates = new File[]{
                new File(data, "app_flutter/" + Const.DB_NAME),
                new File(data, "files/" + Const.DB_NAME),
                new File(data, "databases/" + Const.DB_NAME),
                new File(data, "app_flutter/beecount.sqlite3"),
        };
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i].isFile()) {
                return candidates[i];
            }
        }
        return search(data, 0);
    }

    /** 兜底：在自己数据目录里有限深度地找一遍（避免依赖 path_provider 的落盘位置）。 */
    private static File search(File dir, int depth) {
        if (dir == null || depth > 3) {
            return null;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (int i = 0; i < children.length; i++) {
            File f = children[i];
            if (f.isFile() && Const.DB_NAME.equals(f.getName())) {
                return f;
            }
        }
        for (int i = 0; i < children.length; i++) {
            File f = children[i];
            if (f.isDirectory() && !"cache".equals(f.getName())) {
                File hit = search(f, depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static String safeDataDir(Context ctx) {
        try {
            File d = ctx.getDataDir();
            return d == null ? "?" : d.getAbsolutePath();
        } catch (Throwable t) {
            return "?";
        }
    }

    private ExpenseQuery() {
    }
}
