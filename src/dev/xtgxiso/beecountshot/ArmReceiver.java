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
        // 放行信号是全模块最敏感的入口：伪造它等于把"按需截图"临时变回"所有截图都记账"。
        // 校验策略见 SenderGuard —— 只在"确认识别出是陌生应用"时才拒，任何不确定都放行。
        if (!SenderGuard.allow(this, context)) {
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
        // 顺带把"是否允许推断着删"带进宿主进程——这个开关在模块 App 的设置里，
        // 而删图判定在宿主进程，两边够不着对方的 SharedPreferences，只能搭广播的车。
        // 正好每次截屏前都会发一次放行广播，所以总是最新的。
        boolean precise = true;
        try {
            precise = intent.getBooleanExtra(Const.EXTRA_PRECISE, true);
        } catch (Throwable ignored) {
        }
        HostVerdict.setPrecise(precise);

        Logx.i("[arm] broadcast received (precise=" + precise + ")");
        ArmSignal.arm(until);
    }
}
