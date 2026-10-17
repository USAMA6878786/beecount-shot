package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

/**
 * 点完磁贴后的**一句话反馈**：成功就"截图成功"，失败就说原因。
 *
 * <h3>为什么需要额外授一个"悬浮窗"权限</h3>
 * Android 12（API 31）起，**后台应用弹出的 Toast 会被系统直接丢掉**——不报错、不显示，
 * 用户什么也看不到。（上一版改用通知，就是因为这个坑。）
 *
 * <p>而磁贴点击时，模块应用处于后台：面板一收起，我们就不是前台了。
 * 解决办法就是拿到"显示在其他应用上层"（悬浮窗）权限——有这个权限的应用
 * **不受后台 Toast 限制**。模块本来就有 root，所以用 root 给自己授一次即可
 * （{@code appops set <包名> SYSTEM_ALERT_WINDOW allow}，跟 ADB 手动授是同一件事）。
 *
 * <p>授不上也不影响功能：Toast 调用了但可能不显示，其它流程一切照旧。
 */
final class ShotToast {

    private static volatile boolean overlayChecked = false;

    /** 授"悬浮窗"权限（幂等，每个进程试一次）。没 root 或失败就安静跳过。 */
    static void ensureOverlayAllowed(Context c) {
        if (c == null || overlayChecked || Build.VERSION.SDK_INT < 31) {
            return;
        }
        overlayChecked = true;
        try {
            if (Settings.canDrawOverlays(c)) {
                return;
            }
            RootShell.Result r = RootShell.exec(
                    "appops set " + c.getPackageName() + " SYSTEM_ALERT_WINDOW allow", 8000L);
            Logx.i("[toast] 申请悬浮窗权限 -> exit=" + r.exit + " out=" + RootShell.trim(r.out)
                    + " err=" + RootShell.trim(r.err));
        } catch (Throwable t) {
            Logx.w("[toast] 申请悬浮窗权限失败：" + t.getMessage());
        }
    }

    /** 截图成功。 */
    static void ok(Context c) {
        show(c, "截图成功");
    }

    /** 截图失败。 */
    static void fail(Context c, String why) {
        show(c, why == null || why.length() == 0 ? "截图失败" : "截图失败 · " + why);
    }

    private static void show(final Context c, final String msg) {
        if (c == null) {
            return;
        }
        try {
            // Toast 必须在有 Looper 的线程上弹；调用方可能是子线程。
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        Logx.w("[toast] 弹出失败：" + t.getMessage());
                    }
                }
            });
            Logx.i("[toast] " + msg);
        } catch (Throwable t) {
            Logx.w("[toast] 投递失败：" + t.getMessage());
        }
    }

    private ShotToast() {
    }
}
