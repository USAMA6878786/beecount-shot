package dev.xtgxiso.beecountshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;

/**
 * 常驻服务：让模块进程不被系统回收。
 *
 * <p><b>为什么需要它。</b>「记账截图」磁贴的服务跑在模块 App 的进程里，而这个进程闲置时
 * 会被系统回收。于是冷启动点上磁贴要付两份代价：
 * <ol>
 *   <li><b>进程冷启动</b>——发生在我们的第一行日志之前，用户能直接感觉到；</li>
 *   <li><b>无障碍服务连接不上</b>——无障碍是我们最快的截屏路径（直调系统截屏动作，
 *       不走按键注入）。进程刚起来时它还没绑定好，只能用慢的 root 模拟按键兜底；
 *       更糟的是真机日志里出现过"服务一旦掉线就再也连不回来"（几十次点击全程
 *       {@code a11y=false}）。</li>
 * </ol>
 * 常驻之后这两个问题一起消失。系统自己的截图磁贴之所以一点就响应，本质上也是这个原因
 * ——它跑在 SystemUI 这个永不被回收的进程里。
 *
 * <h3>关于那条通知</h3>
 * 通知渠道用 {@link NotificationManager#IMPORTANCE_MIN}，所以**状态栏不会出现图标**，
 * 只在通知栏里有一条静默条目（点它能打开设置页）。如果连这一条也不想要，
 * 可以在系统设置 → 通知里把「常驻运行」这个渠道关掉，服务本身照常运行。
 *
 * <h3>什么时候会被拉起来</h3>
 * <ul>
 *   <li>打开设置页时（应用在前台，启动前台服务是允许的）；</li>
 *   <li>开机时（{@link BootReceiver}）；</li>
 *   <li>被系统杀掉之后由 {@code START_STICKY} 自动重建；</li>
 *   <li>点磁贴时也会试着拉一次——但那时应用在后台，Android 12+ 多半会拒，
 *       包在 try 里没有副作用。</li>
 * </ul>
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "beecount_shot_keepalive";
    /** 提醒型通知（无障碍掉线）。跟常驻那条分开，可以对它单独设静默。 */
    private static final String ALERT_CHANNEL_ID = "beecount_shot_alert";
    private static final int ALERT_ID = 1002;
    private static final int NOTIF_ID = 1001;

    /** 通知权限的请求码（设置页用）。 */
    static final int REQ_NOTIF = 1001;

    private static volatile boolean channelReady = false;

    /**
     * 确保通知渠道存在。**幂等、很便宜，可以在任何入口随手调**。
     *
     * <p>为什么不能只在 {@link #onCreate()} 里建：Android 的系统设置里，
     * 一个应用**一个通知渠道都没有**的时候，"允许通知"那个总开关是灰的、点不动。
     * 而渠道原来只在服务真的启动后才建——服务没起来（比如后台启动被系统拒了），
     * 用户去系统设置里就会看到一个点不动的开关，像是模块坏了。
     *
     * <p>所以改成：设置页、点磁贴、开机广播、服务本身，谁的入口先到谁就把它建好。
     */
    static void ensureChannel(Context c) {
        if (c == null || Build.VERSION.SDK_INT < 26 || channelReady) {
            return;
        }
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "常驻运行",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("让「记账截图」随时保持响应（点磁贴不再冷启动）。"
                    + "设为「静默」后状态栏不会有图标。");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(ch);
            channelReady = true;
            Logx.i("[keepalive] notification channel ready");
        } catch (Throwable t) {
            Logx.w("[keepalive] ensureChannel failed: " + t.getMessage());
        }
    }

    /**
     * Android 13+ 需要运行时授予通知权限。
     *
     * <p>**不授予也不影响常驻**——按系统规定，用户拒绝通知权限后前台服务的提示只是
     * 不再出现在通知栏（只在「任务管理器」里可见）。所以这个权限是"要不要看见那条提示"
     * 的选择，不是功能必需。
     */
    static boolean hasNotifPermission(Context c) {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        try {
            return c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 拉起服务。可以在应用处于前台时调用（设置页 / 开机广播）。 */
    static void start(Context c) {
        if (c == null) {
            return;
        }
        try {
            Intent i = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable t) {
            // 后台启动前台服务会被 Android 12+ 拒绝，这是预期内的，不当错误。
            Logx.i("[keepalive] start rejected (" + t.getClass().getSimpleName() + ")");
        }
    }

    static void stop(Context c) {
        if (c == null) {
            return;
        }
        try {
            c.stopService(new Intent(c, KeepAliveService.class));
        } catch (Throwable t) {
            Logx.w("[keepalive] stop failed: " + t.getMessage());
        }
    }

    /**
     * 提醒用户「截屏服务掉线了」。
     *
     * <p>为什么值得打扰一次：无障碍是截屏最快的那条路，掉线之后每次点击都会退回
     * root 模拟按键（慢一倍、还可能重试）。原来这件事**只写在日志里**，用户完全不知道。
     */
    static void notifyA11yLost(Context c) {
        if (c == null) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(ALERT_CHANNEL_ID,
                        "截屏服务提醒", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("截屏服务（无障碍）掉线时提醒你重新开启。");
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, ALERT_CHANNEL_ID)
                    : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_tile_shot);
            b.setContentTitle("截屏服务已掉线");
            b.setContentText("点这里重新开启。不开启也能用，但每次点磁贴会慢一倍。");
            b.setAutoCancel(true);
            Intent open = new Intent(c, SettingsActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            b.setContentIntent(PendingIntent.getActivity(c, ALERT_ID, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            nm.notify(ALERT_ID, b.build());
            Logx.w("[keepalive] 已发出「截屏服务掉线」提醒");
        } catch (Throwable t) {
            Logx.w("[keepalive] notifyA11yLost failed: " + t.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, buildNotification());
            Logx.i("[keepalive] 常驻服务已启动");
        } catch (Throwable t) {
            Logx.e("[keepalive] startForeground failed", t);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Logx.i("[keepalive] 常驻服务被销毁");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_tile_shot);
        b.setContentTitle("记账截图");
        b.setContentText("常驻中：点磁贴立刻响应");
        b.setOngoing(true);
        b.setShowWhen(false);
        b.setPriority(Notification.PRIORITY_MIN);
        try {
            Intent open = new Intent(this, SettingsActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.setContentIntent(pi);
        } catch (Throwable ignored) {
        }
        return b.build();
    }
}
