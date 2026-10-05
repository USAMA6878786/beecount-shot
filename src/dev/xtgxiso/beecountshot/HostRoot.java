package dev.xtgxiso.beecountshot;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 宿主进程里的 root 能力（用于**由宿主自己**删除截图）。
 *
 * <p>为什么要把删除挪到宿主来做：真机查明模块 App 点完磁贴退回后台会被 Android 的
 * <b>后台应用冻结</b>冻住，而"向缓存态应用发广播来唤醒它"这条路由系统**延迟投递**，
 * 靠不住——日志里所有回包都是 {@code decisive=false}，说明唤醒广播压根没到，
 * 删除只能等应用自己偶然被解冻，于是慢了十几二十秒。
 *
 * <p>而宿主那一刻是**清醒的**（它正在跑 AI 识别、正在写日志）。让它自己动手，
 * 延迟就只剩"蜜蜂记账写日志的节流（约 2 秒）"。
 *
 * <p>宿主删除别的应用写在公共存储里的文件需要 root，所以这里首次调用会触发一次
 * 授权弹窗（用户允许一次即可，之后静默）。用户拒绝就退回"广播唤醒模块 App"的老路。
 */
final class HostRoot {

    private static final int UNKNOWN = 0;
    private static final int GRANTED = 1;
    private static final int DENIED = 2;

    private static volatile int state = UNKNOWN;

    /** 确认/申请 root（会弹一次授权框）。阻塞，需在子线程调用。 */
    static boolean ensure(Context ctx) {
        if (state == GRANTED) {
            return true;
        }
        if (state == DENIED) {
            return false;
        }
        synchronized (HostRoot.class) {
            if (state != UNKNOWN) {
                return state == GRANTED;
            }
            boolean ok = false;
            String out = "";
            try {
                ProcessBuilder pb = new ProcessBuilder("su", "-c", "id");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                br.close();
                boolean done = p.waitFor(25000L, TimeUnit.MILLISECONDS);
                if (!done) {
                    p.destroy();
                }
                out = sb.toString();
                ok = out.contains("uid=0");
            } catch (Throwable t) {
                out = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            state = ok ? GRANTED : DENIED;
            HostTelemetry.put("host_root", ok ? "granted" : "denied");
            Logx.i("[root] host root " + (ok ? "GRANTED" : "DENIED") + " out="
                    + (out == null ? "" : out.trim().replace('\n', ' ')));
            return ok;
        }
    }

    /** 删除一个文件（宿主的 uid 没有公共存储写权限，所以必须走 su）。 */
    static boolean delete(String path) {
        if (path == null || path.length() < 8) {
            return false;
        }
        String cmd = "if [ -f '" + path + "' ]; then rm -f '" + path + "' && echo DELETED; "
                + "else echo MISSING; fi";
        String out = su(cmd, 15000L);
        boolean ok = out != null && out.indexOf("DELETED") >= 0;
        Logx.i("[root] host delete " + path + " -> "
                + (ok ? "OK" : ("FAILED out=" + (out == null ? "" : out.trim()))));
        return ok;
    }

    /**
     * 用 root 发一条广播（普通广播的**冗余通道**）。
     *
     * <p>为什么还要多此一举：模块 App / 宿主都可能在后台被系统冻结，而发给缓存态应用的
     * 广播会被**延迟投递**（真机日志里回包全是 {@code decisive=false}，就是这么来的）。
     * root 的 {@code am broadcast} 走的是 shell 身份，不受应用自己的后台限制；
     * 再加上 {@link Intent#FLAG_RECEIVER_INCLUDE_BACKGROUND}，系统会把广播投给
     * 处于后台的接收器，不再排队等着解冻。
     *
     * <p>这个通道是**幂等**的：放行信号本来就是"设一个截止时间"，收到两次取最大值，
     * 小组件刷新收到两次也只是多刷一次。所以两条通道一起发，谁先到用谁，没有副作用。
     *
     * @param extras 直接拼在命令后面的 extra 段，没有就传 null
     * @param flags  传给 {@code am broadcast -f} 的 Intent flag，传 0 表示不加
     */
    static boolean broadcast(String action, String pkg, String extras, int flags) {
        StringBuilder cmd = new StringBuilder("am broadcast -a ").append(action);
        if (pkg != null && pkg.length() > 0) {
            cmd.append(" -p ").append(pkg);
        }
        if (extras != null && extras.length() > 0) {
            cmd.append(' ').append(extras);
        }
        if (flags != 0) {
            cmd.append(" -f ").append(flags);
        }
        String out = su(cmd.toString(), 15000L);
        boolean ok = out != null && out.indexOf("Error") < 0 && out.indexOf("error") < 0;
        Logx.i("[root] host broadcast '" + cmd + "' -> "
                + (ok ? "sent" : "FAILED") + " out=" + (out == null ? "" : out.trim().replace('\n', ' ')));
        return ok;
    }

    /** 跑一条 su 命令，返回合并后的输出；失败返回 null。阻塞，必须在子线程调用。 */
    private static String su(String cmd, long timeoutMs) {
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            return sb.toString();
        } catch (Throwable t) {
            Logx.e("[root] host su failed: " + cmd, t);
            return null;
        }
    }

    static String stateName() {
        if (state == GRANTED) {
            return "granted";
        }
        if (state == DENIED) {
            return "denied";
        }
        return "unknown";
    }

    private HostRoot() {
    }
}
