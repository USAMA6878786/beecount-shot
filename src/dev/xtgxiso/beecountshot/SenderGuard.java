package dev.xtgxiso.beecountshot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.lang.reflect.Method;

/**
 * 跨进程广播的"发件人是不是自己人"校验。
 *
 * <h3>为什么要加</h3>
 * 模块的几条跨进程通道（放行信号、查询、结果回包、小组件取数）用的都是**明文常量 action**，
 * 而且接收器必须注册成 {@code RECEIVER_EXPORTED}（因为发件人是另一个应用）。这意味着
 * 手机上任一应用都能往这些 action 上发广播。最要紧的两条后果：
 * <ul>
 *   <li>伪造放行信号 → 闸门被打开，等于临时退回"所有截图都记账并自动删除"；</li>
 *   <li>伪造结果回包 → 模块侧会拿广播里给的路径去 root 删除。</li>
 * </ul>
 *
 * <h3>为什么不能用签名级权限</h3>
 * 最标准的做法是自定义一个 signature 级权限。这里行不通：模块代码是**注入进蜜蜂记账进程**跑的，
 * 两边的"签名身份"天然不同——模块是自己签的，宿主那条链路的身份是蜜蜂记账的签名，
 * 而 root 冗余通道又是 shell 的身份。没有任何一个权限能同时覆盖这三者。
 *
 * <h3>所以：只做"能确认时才拦"</h3>
 * 策略是**单向保守**——只拒绝"已经查清楚发件人是谁、而且确定不是我们的人"的广播：
 * <ol>
 *   <li>取不到发送方 uid（老系统上反射失败）→ <b>放行</b>；</li>
 *   <li>uid 是本进程 / root / system / shell → 放行（root 冗余通道走这里）；</li>
 *   <li>拿 uid 反查它属于哪个包，查出结果里出现模块包名或宿主包名 → 放行；</li>
 *   <li>反查不出任何包名（比如被包可见性挡住）→ <b>放行</b>；</li>
 *   <li>只有"确实查出了包名、但一个都不是我们的人"这一种情况才拦。</li>
 * </ol>
 * 这样即使某个 API 在某个 ROM 上不按预期工作，最坏结果也只是"校验没生效"，
 * **绝不会把正常功能拦死**——这是刻意的取舍。
 */
final class SenderGuard {

    /** 取不到发送方 uid 时的哨兵值。 */
    private static final int UNKNOWN = -10000;

    /** 本条广播能不能收。任何不确定都返回 true。 */
    static boolean allow(BroadcastReceiver receiver, Context ctx) {
        if (receiver == null) {
            return true;
        }
        return allowUid(sendingUid(receiver), ctx);
    }

    static boolean allowUid(int uid, Context ctx) {
        if (uid == UNKNOWN) {
            return true; // 取不到 uid：老系统上反射失败，放行
        }
        // root(0) / system(1000) / shell(2000)：root 冗余通道就是 shell 身份发的。
        if (uid == 0 || uid == 1000 || uid == 2000) {
            return true;
        }
        if (uid == Process.myUid()) {
            return true; // 本进程自己发的（比如小组件的 PendingIntent）
        }
        if (ctx == null) {
            return true;
        }

        String[] pkgs = null;
        try {
            pkgs = ctx.getPackageManager().getPackagesForUid(uid);
        } catch (Throwable t) {
            Logx.w("[guard] getPackagesForUid failed: " + t.getMessage());
        }
        if (pkgs == null || pkgs.length == 0) {
            // 反查不出是谁 → 无法判定 → 放行（宁可弱一点也不要把功能弄坏）
            return true;
        }
        for (int i = 0; i < pkgs.length; i++) {
            if (isPeer(pkgs[i])) {
                return true;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkgs.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(pkgs[i]);
        }
        Logx.w("[guard] rejected broadcast from a foreign app: " + sb + " (uid=" + uid + ")");
        return false;
    }

    /** 包名是不是"我们自己人"：模块 App，或者蜜蜂记账本体（prod / dev）。 */
    private static boolean isPeer(String pkg) {
        if (pkg == null) {
            return false;
        }
        if (Const.MODULE_PKG.equals(pkg)) {
            return true;
        }
        for (int i = 0; i < Const.HOST_PKGS.length; i++) {
            if (Const.HOST_PKGS[i].equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取这条广播的发送方 uid。
     *
     * <p>Android 14（API 34）起有公开的 {@code getSentFromUid()}；更早的版本只有
     * {@code @SystemApi} 的 {@code getSendingUid()}，只能反射。两者都拿不到就返回
     * {@link #UNKNOWN}，调用方据此放行。
     */
    private static int sendingUid(BroadcastReceiver receiver) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                return receiver.getSentFromUid();
            } catch (Throwable ignored) {
                // 落到下面的反射分支再试一次
            }
        }
        try {
            Method m = BroadcastReceiver.class.getDeclaredMethod("getSendingUid");
            m.setAccessible(true);
            Object v = m.invoke(receiver);
            if (v instanceof Integer) {
                return ((Integer) v).intValue();
            }
        } catch (Throwable ignored) {
            // 隐藏 API 被挡 / 方法不存在 —— 正常情况，按"取不到"处理
        }
        return UNKNOWN;
    }

    private SenderGuard() {
    }
}
