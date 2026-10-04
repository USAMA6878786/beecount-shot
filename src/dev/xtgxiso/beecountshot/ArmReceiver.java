package dev.xtgxiso.beecountshot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 备用放行通道：接收模块 App 发来的广播。
 *
 * <p>主用通道是 root 写的放行文件（见 {@link ArmSignal}）。这条广播是它的备份——两条都通
 * 就更稳，只通一条也能工作。收到就只记在内存里，不落盘。
 */
public class ArmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        if (!Const.ACTION_ARM.equals(intent.getAction())) {
            return;
        }
        long until;
        try {
            until = intent.getLongExtra(Const.EXTRA_UNTIL, 0L);
        } catch (Throwable t) {
            until = 0L;
        }
        if (until <= 0L) {
            Logx.w("[arm] broadcast arrived but carries no valid deadline, ignored");
            return;
        }
        Logx.i("[arm] broadcast received");
        ArmSignal.arm(until);
    }
}
