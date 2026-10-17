package dev.xtgxiso.beecountshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

/**
 * 「点一下磁贴之后到底成没成」的**看得见的反馈**。
 *
 * <h3>为什么需要它</h3>
 * 原来唯一的反馈是磁贴副标题（"正在截屏…" → "已截屏"），可**面板一点就收起来了**，
 * 用户根本看不到。于是只能自己去相册翻——"截图生成了"才安心。
 *
 * <p>这条通知解决三件事：
 * <ol>
 *   <li><b>立刻有反应</b>：点下去就出现「正在截屏…」，不用等；</li>
 *   <li><b>能确认内容</b>：把截到的那张图当**大图预览**放进去，不用进相册也知道截到了什么；</li>
 *   <li><b>有结论</b>：识别完成/删除结果/失败原因都会更新到这一条上。</li>
 * </ol>
 *
 * <h3>为什么不吵</h3>
 * 用的是 {@code IMPORTANCE_LOW} 的独立渠道：**状态栏有图标，但不出声、不弹横幅**。
 * 固定通知 id，每次操作原地更新，不会堆一串。
 */
final class ShotNotify {

    private static final String CHANNEL_ID = "beecount_shot_feedback";
    private static final int ID = 1003;

    /** 点下去：立刻告诉用户"收到了"。 */
    static void start(Context c) {
        post(c, "正在截屏…", "稍等，马上就好", null, true, true);
    }

    /**
     * 截屏成功。
     *
     * @param path 截到的图（用于大图预览；取不到就只显示文字）
     */
    static void shotOk(Context c, String path) {
        post(c, "已截屏 · 正在识别…", "蜜蜂记账正在读这张图", loadThumb(path), true, true);
    }

    /** 有结论了。 */
    static void done(Context c, String title, String text) {
        post(c, title, text, null, false, false);
    }

    /** 截屏失败（三条路都没成），把原因写清楚。 */
    static void failed(Context c, String why) {
        post(c, "截屏失败", why == null ? "点开看日志" : why, null, false, false);
    }

    // ---------------------------------------------------------------- 内部

    private static void post(Context c, String title, String text, Bitmap big,
                             boolean ongoing, boolean keepThumb) {
        if (c == null) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            ensureChannel(nm);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CHANNEL_ID)
                    : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_tile_shot);
            b.setContentTitle(title);
            b.setContentText(text);
            b.setOngoing(ongoing);
            b.setAutoCancel(!ongoing);
            b.setShowWhen(false);
            if (big != null) {
                Notification.BigPictureStyle style = new Notification.BigPictureStyle()
                        .bigPicture(big)
                        .setBigContentTitle(title)
                        .setSummaryText(text);
                b.setStyle(style);
                b.setLargeIcon(big);
            }
            // 点通知打开蜜蜂记账（用户想核对有没有记上，正好一步到位）
            Intent open = c.getPackageManager()
                    .getLaunchIntentForPackage(HostInfo.pkg());
            if (open != null) {
                open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                b.setContentIntent(PendingIntent.getActivity(c, ID, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            }
            nm.notify(ID, b.build());
        } catch (Throwable t) {
            Logx.w("[notify] 发通知失败：" + t.getMessage());
        }
    }

    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "操作反馈",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("点磁贴截屏时提示结果（有状态栏图标，不出声）。");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 读一张缩略图。
     *
     * <p>按 2 的幂次降采样到 ~500px 宽就够了——通知里的预览不需要原图分辨率，
     * 而 3MB 的 PNG 直接解码要占二三十兆内存，没必要。
     * 没有相册权限时解码会失败，返回 null（通知退化成纯文字，功能不受影响）。
     */
    private static Bitmap loadThumb(String path) {
        if (path == null) {
            return null;
        }
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, probe);
            int sample = 1;
            while (probe.outWidth > 0 && probe.outWidth / (sample * 2) >= 500) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            Logx.w("[notify] 缩略图解码失败：" + t.getMessage());
            return null;
        }
    }

    private ShotNotify() {
    }
}
