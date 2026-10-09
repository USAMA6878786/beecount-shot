package dev.xtgxiso.beecountshot;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

/**
 * 只干一件事的无障碍服务：在收到指令后触发一次系统级截屏。
 *
 * <p>为什么必须有无障碍服务：Android 不允许普通 App 主动截屏。可行的通道只有两条——
 * MediaProjection（每次都要用户授权，体验很差）和 AccessibilityService 的
 * GLOBAL_ACTION_TAKE_SCREENSHOT（一次授权，长期可用）。这里选后者，并且走的是系统原生
 * 截屏管线，所以截图会正常进相册、行为与按截图键完全一致。
 *
 * <p>权限最小化：不读窗口内容（canRetrieveWindowContent=false）、不注册任何事件回调
 * （onAccessibilityEvent 是空实现），只保留"执行全局动作"这一个能力。
 */
public class ShotAccessibilityService extends AccessibilityService {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile ShotAccessibilityService instance;
    private static Runnable pendingShot;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Logx.init(Prefs.appLogPath(this));
        Logx.i("[a11y] service connected and ready");
        // 提前把宿主包名解析出来（要开 root shell，放子线程），省得点磁贴时多等一次。
        new Thread(new Runnable() {
            @Override
            public void run() {
                HostInfo.pkg(ShotAccessibilityService.this);
            }
        }, "bee-pkg-probe").start();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        Logx.i("[a11y] unbound (service disabled?)");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 刻意留空：本服务不需要任何界面事件。
    }

    @Override
    public void onInterrupt() {
        // 刻意留空。
    }

    /** 服务是否已连接。用于磁贴判断"现在能不能截屏"。 */
    public static boolean isReady() {
        return instance != null;
    }

    /**
     * 排一次延迟截屏。
     *
     * @param delayMs 从调用时刻算起的延迟毫秒数
     */
    public static void scheduleShot(final long delayMs) {
        final ShotAccessibilityService service = instance;
        if (service == null) {
            Logx.w("[a11y] scheduleShot ignored: service not connected");
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (pendingShot != null) {
                    MAIN.removeCallbacks(pendingShot);
                }
                pendingShot = new Runnable() {
                    @Override
                    public void run() {
                        performShot(service);
                    }
                };
                // 不再设下限（原来有个 150ms 的地板）：这条是"直调系统截屏动作"，
                // 跟控制中心面板收没收干净无关，没有任何等待的理由。
                long wait = Math.max(0L, delayMs);
                MAIN.postDelayed(pendingShot, wait);
                Logx.i("[a11y] screenshot scheduled in " + wait + "ms");
            }
        });
    }

    /**
     * **立刻**触发一次系统截屏，并等系统受理（同步返回）。
     *
     * <p>为什么这是比"root 模拟按键"更好的主路径，原因很具体：
     * {@code input keycombination 26 25} 是往输入系统里注入"电源键+音量下"，
     * 而**控制中心面板刚收起的那一瞬间，注入的按键会被面板吃掉**。真机日志里表现为
     * "第一次发命令毫无反应、2 秒后换一条命令就成了"，一天里约三成的点击会踩到。
     *
     * <p>这里走的 {@link AccessibilityService#GLOBAL_ACTION_TAKE_SCREENSHOT} 是
     * <b>直接调系统的截屏动作</b>，不经过按键注入，跟面板状态完全无关；也没有
     * {@code su} 和 {@code input} 的进程启动开销。它同样是系统真实截图流程
     * （有动画、有缩略图、正常进相册），所以蜜蜂记账的截图监听照常触发。
     *
     * <p>覆盖安装模块后系统会关掉无障碍服务——那种情况下 {@link #isReady()} 为 false，
     * 调用方会自动退回 root 模拟按键，功能不会断。
     *
     * @return true = 系统受理了这次请求（不代表图一定已经落盘）
     */
    public static boolean shootNow() {
        final ShotAccessibilityService service = instance;
        if (service == null || Build.VERSION.SDK_INT < 28) {
            return false;
        }
        // 放到主线程执行再等结果：performGlobalAction 是连着系统服务的调用，
        // 在主线程上调最稳妥（个别 ROM 在别的线程上会直接返回 false）。
        final java.util.concurrent.atomic.AtomicBoolean ok =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ok.set(service.performGlobalAction(
                            AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT));
                } catch (Throwable t) {
                    Logx.e("[a11y] direct take-screenshot failed", t);
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(1500L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Logx.i("[a11y] direct take-screenshot dispatched, accepted=" + ok.get());
        return ok.get();
    }

    private static void performShot(ShotAccessibilityService service) {
        try {
            if (Build.VERSION.SDK_INT < 28) {
                Logx.w("[a11y] GLOBAL_ACTION_TAKE_SCREENSHOT requires API 28+; this device is API "
                        + Build.VERSION.SDK_INT);
                return;
            }
            boolean ok = service.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
            Logx.i("[a11y] take-screenshot dispatched, accepted=" + ok);
        } catch (Throwable t) {
            Logx.e("[a11y] take screenshot failed", t);
        }
    }
}
