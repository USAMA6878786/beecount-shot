package dev.xtgxiso.beecountshot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机时把常驻服务拉起来。
 *
 * <p>开机广播是 {@code startForegroundService} 的合法豁免场景之一，所以这里能拉起
 * {@link KeepAliveService}（不像在后台点磁贴那样会被 Android 12+ 拒绝）。
 *
 * <p>只认 {@code BOOT_COMPLETED}；用户在设置里关掉常驻开关就什么都不做。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Logx.init(Prefs.appLogPath(context));
        if (!Prefs.keepAlive(context)) {
            Logx.i("[boot] keep-alive is OFF; not starting the service");
            return;
        }
        Logx.i("[boot] starting keep-alive service");
        KeepAliveService.start(context);
    }
}
