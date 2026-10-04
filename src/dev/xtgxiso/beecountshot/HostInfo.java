package dev.xtgxiso.beecountshot;

import android.content.Context;

/**
 * 探测"蜜蜂记账装在哪个包名下"。
 *
 * <p>为什么要探测：模块所有跨进程动作（广播定向、root 命令）都要用到包名。
 * 蜜蜂记账有 prod / dev 两个构建风味（dev 的 applicationId 带 {@code .dev} 后缀），
 * 用户实际装哪个不确定。一旦包名写错，表现是**静默全失效**——广播发不到、
 * root 命令打错目标，而且不报任何错。
 *
 * <p>所以不猜：用 root 跑一次 {@code pm list packages} 找出真实的包名，结果缓存起来。
 *
 * <h3>关于"数据目录"（历史教训，别再走弯路）</h3>
 * 早期版本还探测过"宿主的数据目录在哪"，想用 root 去读写宿主的私有数据。
 * 实测发现这条路**根本走不通**：{@code su} 跑在**全局挂载命名空间**，而应用的数据
 * 目录（CE 存储）挂载在应用自己的命名空间里，所以 root 眼里那个目录"不存在"
 * （连系统 {@code dumpsys package} 给出的路径都验证不过）。
 *
 * <p>结论：凡是需要宿主的私有数据，一律**让宿主自己读、再通过广播送回来**
 * （见 HostProbe / HostWatcher / ExpenseQuery），不要尝试从外面用 root 拿。
 * 相关探测代码已全部删除——留着只会让日志看起来在工作。
 */
final class HostInfo {

    /** 宿主候选包名，顺序即优先级。 */
    static final String[] PKG_CANDIDATES = new String[]{
            "com.tntlikely.beecount",
            "com.tntlikely.beecount.dev",
    };

    private static volatile String pkg;

    /** 解析并缓存包名。会开 root shell，请在子线程调用。 */
    static String pkg(Context context) {
        String v = pkg;
        if (v != null) {
            return v;
        }
        synchronized (HostInfo.class) {
            if (pkg != null) {
                return pkg;
            }
            String found = probePkg();
            if (found == null) {
                found = PKG_CANDIDATES[0];
                Logx.w("[host] 未能探测到蜜蜂记账的包名，退回默认值 " + found);
            } else {
                Logx.i("[host] package resolved to: " + found);
            }
            pkg = found;
            return found;
        }
    }

    /** 已解析的包名；没解析过时返回默认值。 */
    static String pkg() {
        String v = pkg;
        return v != null ? v : PKG_CANDIDATES[0];
    }

    static boolean pkgResolved() {
        return pkg != null;
    }

    private static String probePkg() {
        try {
            RootShell.Result r = RootShell.exec(
                    "pm list packages 2>/dev/null | sed 's/package://' | grep -i beecount", 15000L);
            String[] lines = (r.out == null) ? new String[0] : r.out.split("\n");
            for (int i = 0; i < PKG_CANDIDATES.length; i++) {
                for (int j = 0; j < lines.length; j++) {
                    if (PKG_CANDIDATES[i].equals(lines[j].trim())) {
                        return PKG_CANDIDATES[i];
                    }
                }
            }
        } catch (Throwable t) {
            Logx.e("[host] probe package failed", t);
        }
        return null;
    }

    private HostInfo() {
    }
}
