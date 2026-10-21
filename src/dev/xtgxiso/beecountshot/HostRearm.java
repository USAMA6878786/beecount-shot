package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Method;

/**
 * 界面销毁之后，把宿主的截图观察者**重新挂回去**。
 *
 * <h3>为什么需要这一步</h3>
 * BeeCount 的观察者是在 {@code MainActivity.onDestroy()} 里被注销的
 * （{@code stopScreenshotObserver}）。而系统回收后台 Activity 很常见，
 * 于是出现这样的链条：
 * <pre>
 *   系统回收 Activity → onDestroy → 观察者注销
 *   → 之后点磁贴，图进了相册但没人接
 *   → 直到它的"兜底扫描"跑到才补上（实测 49 秒）
 *   → imageAge &gt; 30 → 被它当旧图丢掉 → 不记账
 * </pre>
 *
 * <p>但引擎和 Dart 都还活着（实测 {@code FlutterEngine.destroy()} 从未被调用），
 * 所以可以立刻用宿主自己的 {@code startScreenshotObserver(engine)} 把观察者挂回去。
 * 那个方法内部是"先 stop 再 start"，**幂等**，不会产生重复监听。
 *
 * <h3>前置条件</h3>
 * 只有当宿主自己的开关是开着的（{@code flutter.screenshot_monitor_enabled}）才重挂——
 * 用户手动关掉监听时不能替他打开。
 *
 * <h3>失败即放弃</h3>
 * 拿不到 engine、方法找不到、开关是关的：一律什么都不做，行为退回现状。
 */
final class HostRearm {

    private HostRearm() {
    }

    /** 观察者是否曾经成功挂上（用于日志去重，避免每次销毁都刷屏）。 */
    private static volatile boolean armed = false;

    /**
     * 在 {@code MainActivity.onDestroy()} 执行完之后调用。
     *
     * @param activity 那个正在销毁的 MainActivity（同时也是 Context）
     */
    static void afterDestroy(Object activity) {
        try {
            if (!(activity instanceof Context)) {
                return;
            }
            Context ctx = (Context) activity;

            if (!monitorEnabled(ctx)) {
                Logx.i("[rearm] 宿主开关是关的，不重挂");
                return;
            }

            Object engine = HostState.engine();
            if (engine == null) {
                Logx.w("[rearm] 没捕获到 engine，无法重挂");
                return;
            }

            Method start = null;
            try {
                Method[] all = activity.getClass().getDeclaredMethods();
                for (int i = 0; i < all.length; i++) {
                    Method m = all[i];
                    if ("startScreenshotObserver".equals(m.getName())
                            && m.getParameterTypes().length == 1) {
                        m.setAccessible(true);
                        start = m;
                        break;
                    }
                }
            } catch (Throwable t) {
                Logx.w("[rearm] 找 startScreenshotObserver 失败：" + t.getMessage());
            }
            if (start == null) {
                Logx.w("[rearm] 找不到 startScreenshotObserver（上游可能改名），不重挂");
                return;
            }

            start.invoke(activity, engine);
            armed = true;
            Logx.i("[rearm] 界面销毁后已把截图观察者挂回去");
            HostState.onActivityCreated();   // 语义上就是"监听在位"
        } catch (Throwable t) {
            Logx.w("[rearm] 重挂失败（不影响现有行为）：" + t.getMessage());
        }
    }

    /** 宿主自己的开关：Dart 的 enable() 会把它写进 FlutterSharedPreferences。 */
    private static boolean monitorEnabled(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    "FlutterSharedPreferences", Context.MODE_PRIVATE);
            // 默认 false：没读到就当"用户没开"，绝不擅自替他打开
            return sp.getBoolean("flutter.screenshot_monitor_enabled", false);
        } catch (Throwable t) {
            return false;
        }
    }
}
