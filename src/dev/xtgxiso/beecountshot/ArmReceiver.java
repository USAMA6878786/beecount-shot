package dev.xtgxiso.beecountshot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 宿主进程内接收"放行这一次"的定向广播。
 *
 * <p><b>这是唯一的放行通道。</b>由 {@code HostBridge} 动态注册（不是清单里的静态接收器）。
 *
 * <p>曾经还有一条"模块 App 用 root 往宿主目录写一个小文件"的通道，但实测行不通：
 * {@code su} 跑在全局挂载命名空间，看不到应用的数据目录，那个文件宿主永远读不到——
 * 属于静默失效。文件通道已彻底移除，现在只认这条广播，这样"日志说的"和"实际生效的"
 * 才是一回事。
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
