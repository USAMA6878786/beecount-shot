package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

/**
 * 宿主进程 → 模块 App 的遥测通道。
 *
 * <p>为什么不用日志文件 / logcat 把宿主侧的情况带出来：
 * <ul>
 *   <li><b>logcat 不可靠。</b>真机实测 logcat 缓冲几乎不保留我们的日志——每次 dump
 *       只捞到最后一行（`wc -l` 从 3 涨到 6 再到 9，每次只多 1 行）。所以"logcat 里没有
 *       宿主日志"根本说明不了问题。</li>
 *   <li><b>写文件也不可靠。</b>宿主往自己 cache 目录写日志文件，真机上始终没成功过。</li>
 * </ul>
 *
 * <p>而"应用写自己的 SharedPreferences"是 Android 上最不可能失败的持久化路径：它就是
 * 应用自己的正常存储机制，不涉及跨 uid、不涉及权限、不涉及分区存储。所以这里把宿主侧
 * 的关键状态全部写进 {@code beecount_shot.xml}，模块 App 再用 root 把它读出来。
 *
 * <p>这份数据同时解决两个问题：
 * <ol>
 *   <li>确认 hook 到底有没有装进宿主进程（{@code alive_at} / {@code hooks_ok}）；</li>
 *   <li>确认每一次截图是放行了还是被拦了（{@code last_allowed_*} / {@code blocked_*}）。</li>
 * </ol>
 */
final class HostTelemetry {

    private static final String FILE = "beecount_shot";

    private static volatile SharedPreferences sp;

    /**
     * Context 到手之前的暂存区。
     *
     * <p>onPackageReady（写安装摘要的地方）发生在 Application 创建之前，那时还拿不到
     * Context，也就没法写 SharedPreferences。先把这些值攒着，等 HostBridge 拿到 Context
     * 再一次性补写——安装摘要往往是最重要的那一条，不能因为时序丢掉。
     */
    private static final java.util.LinkedHashMap<String, Object> PENDING =
            new java.util.LinkedHashMap<String, Object>();

    static void init(Context context) {
        if (sp != null || context == null) {
            return;
        }
        synchronized (HostTelemetry.class) {
            if (sp != null) {
                return;
            }
            try {
                sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
                Logx.i("[telemetry] channel ready -> shared_prefs/" + FILE + ".xml");
                flushPending();
                put("alive_at", System.currentTimeMillis());
                put("pid", Process.myPid());
                put("host_sdk", android.os.Build.VERSION.SDK_INT);
            } catch (Throwable t) {
                Logx.e("[telemetry] init failed", t);
            }
        }
    }

    private static void flushPending() {
        SharedPreferences p = sp;
        if (p == null || PENDING.isEmpty()) {
            return;
        }
        try {
            SharedPreferences.Editor e = p.edit();
            for (java.util.Map.Entry<String, Object> en : PENDING.entrySet()) {
                Object v = en.getValue();
                if (v instanceof Long) {
                    e.putLong(en.getKey(), ((Long) v).longValue());
                } else {
                    e.putString(en.getKey(), String.valueOf(v));
                }
            }
            e.apply();
            Logx.i("[telemetry] flushed " + PENDING.size() + " buffered entries");
            PENDING.clear();
        } catch (Throwable t) {
            Logx.e("[telemetry] flush failed", t);
        }
    }

    static boolean ready() {
        return sp != null;
    }

    static void put(String key, String value) {
        SharedPreferences p = sp;
        if (p == null) {
            synchronized (HostTelemetry.class) {
                PENDING.put(key, value == null ? "" : value);
            }
            return;
        }
        try {
            p.edit().putString(key, value == null ? "" : value).apply();
        } catch (Throwable ignored) {
        }
    }

    static void put(String key, long value) {
        SharedPreferences p = sp;
        if (p == null) {
            synchronized (HostTelemetry.class) {
                PENDING.put(key, Long.valueOf(value));
            }
            return;
        }
        try {
            p.edit().putLong(key, value).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 自增计数，用来回答"这条链路到底有没有被走到"。 */
    static void bump(String key) {
        SharedPreferences p = sp;
        if (p == null) {
            synchronized (HostTelemetry.class) {
                Object v = PENDING.get(key);
                long n = (v instanceof Long) ? ((Long) v).longValue() : 0L;
                PENDING.put(key, Long.valueOf(n + 1L));
            }
            return;
        }
        try {
            long now = p.getLong(key, 0L);
            p.edit().putLong(key, now + 1L).apply();
        } catch (Throwable ignored) {
        }
    }

    private HostTelemetry() {
    }
}
