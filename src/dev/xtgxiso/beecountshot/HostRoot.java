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
            p.waitFor(15000L, TimeUnit.MILLISECONDS);
            String out = sb.toString();
            boolean ok = out.indexOf("DELETED") >= 0;
            Logx.i("[root] host delete " + path + " -> "
                    + (ok ? "OK" : ("FAILED out=" + out.trim())));
            return ok;
        } catch (Throwable t) {
            Logx.e("[root] host delete failed", t);
            return false;
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
