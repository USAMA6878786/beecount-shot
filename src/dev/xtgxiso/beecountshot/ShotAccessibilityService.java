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
                long wait = Math.max(150L, delayMs);
                MAIN.postDelayed(pendingShot, wait);
                Logx.i("[a11y] screenshot scheduled in " + wait + "ms");
            }
        });
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
