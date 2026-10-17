package dev.xtgxiso.beecountshot;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

/**
 * 模块**自己**监听相册，用来判断"刚才那张截图落盘了没有"。
 *
 * <h3>为什么需要它</h3>
 * 原来确认出图靠 {@code su -c "find ... | stat" } 每 200ms 轮询一次——**每轮都要起一个
 * root shell（实测约 100ms）**，所以确认环节要 200~350ms，占了一次点击总耗时的三分之一。
 *
 * <p>而模块 App 自己就能监听 MediaStore：只要拿到 {@code READ_MEDIA_IMAGES}，
 * 注册一个 {@link ContentObserver}，截图一落盘几十毫秒就知道。**一次点击的 su 次数从 3~5 次
 * 降到 2 次。**
 *
 * <h3>降级</h3>
 * 没给权限、或者系统版本不支持 → {@link #isReady()} 返回 false，
 * {@link RootShell#waitForScreenshot} 自动退回原来的 su 轮询。**功能不会因为缺权限而失效。**
 */
final class ShotWatcher {

    /** 只认这么新的图（秒）。太老的说明是历史图，不是刚才截的。 */
    private static final long FRESH_SECONDS = 15L;

    private static final Object LOCK = new Object();

    private static volatile ContentObserver observer;
    private static volatile boolean ready = false;
    /** 应用级 Context（注册时存下来，观察者回调里要用）。 */
    private static volatile Context appCtx = null;

    /** 最近一次"检测到新截图"的时刻（ms）。等待方用它和起始时刻比较。 */
    private static volatile long lastDetectAt = 0L;
    private static volatile String lastPath = null;

    private ShotWatcher() {
    }

    /**
     * 权限是否已授予。
     *
     * <p>Android 13+ 用 {@code READ_MEDIA_IMAGES}；更老的系统用 {@code READ_EXTERNAL_STORAGE}。
     */
    static boolean hasPermission(Context c) {
        if (c == null) {
            return false;
        }
        try {
            String perm = Build.VERSION.SDK_INT >= 33
                    ? "android.permission.READ_MEDIA_IMAGES"
                    : "android.permission.READ_EXTERNAL_STORAGE";
            return c.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 观察者是否已经挂上（只有挂上了才值得走快路）。 */
    static boolean isReady() {
        return ready;
    }

    /** 挂上观察者。幂等；没权限就什么都不做。 */
    static synchronized void ensureRegistered(Context c) {
        if (ready || c == null || !hasPermission(c)) {
            return;
        }
        try {
            Context app = c.getApplicationContext();
            appCtx = app;
            ContentResolver cr = app.getContentResolver();
            ContentObserver o = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    onChanged(null);
                }

                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    onChanged(uri);
                }
            };
            cr.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o);
            observer = o;
            ready = true;
            missStreak = 0;
            Logx.i("[shotwatch] 相册观察者已注册（快路启用）");
        } catch (Throwable t) {
            Logx.w("[shotwatch] 注册失败，退回 su 轮询：" + t.getMessage());
            ready = false;
        }
    }

    /** 连续几次没等到就判定快路不可用，本进程内退回 su 轮询（避免一直白等 500ms）。 */
    private static volatile int missStreak = 0;

    static void noteMiss() {
        int n = missStreak + 1;
        missStreak = n;
        if (n >= 2 && ready) {
            ready = false;
            Logx.w("[shotwatch] 连续 " + n + " 次没收到相册通知，本进程内退回 su 轮询");
        }
    }

    /** 清掉上一次的检测结果（每次截图前调用）。 */
    static void reset() {
        lastDetectAt = 0L;
        lastPath = null;
    }

    /**
     * 等到一张"在我们开始等之后"才出现的截图。
     *
     * @param sinceMs 起点：只有检测时刻严格晚于它才算数（传点击/发命令的时刻）
     * @return true = 等到了
     */
    static boolean await(long timeoutMs, long sinceMs) {
        if (!ready) {
            return false;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (LOCK) {
            while (true) {
                if (lastDetectAt > sinceMs) {
                    Logx.i("[shotwatch] 检测到新截图（相册通知，未走 su）：" + lastPath);
                    return true;
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0L) {
                    return false;
                }
                try {
                    LOCK.wait(Math.min(left, 300L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    private static void onChanged(Uri uri) {
        try {
            Context app = appCtx;
            if (app == null) {
                return;
            }
            String name = null;
            long dateAdded = 0L;
            ContentResolver cr = app.getContentResolver();
            String[] proj = new String[]{
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
            };
            Cursor cur = null;
            try {
                // uri 有时是"整个集合"，有时是"某一行"；两种都查一次取第一行。
                Uri q = uri;
                if (q == null || !q.toString().contains("/media/")) {
                    q = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }
                cur = cr.query(q, proj, null, null,
                        MediaStore.Images.Media.DATE_ADDED + " DESC");
                if (cur != null && cur.moveToFirst()) {
                    name = cur.getString(0);
                    dateAdded = cur.getLong(1);
                }
            } finally {
                if (cur != null) {
                    cur.close();
                }
            }
            if (name == null) {
                return;
            }
            long nowSec = System.currentTimeMillis() / 1000L;
            if (nowSec - dateAdded > FRESH_SECONDS) {
                return; // 老图，忽略
            }
            if (!looksLikeScreenshot(name)) {
                return;
            }
            synchronized (LOCK) {
                lastDetectAt = System.currentTimeMillis();
                lastPath = name;
                LOCK.notifyAll();
            }
        } catch (Throwable t) {
            Logx.w("[shotwatch] onChange 处理失败：" + t.getMessage());
        }
    }

    private static boolean looksLikeScreenshot(String name) {
        String n = name.toLowerCase();
        return n.contains("screenshot") || n.contains("screen_shot")
                || n.contains("screen-shot") || name.contains("截图") || name.contains("截屏");
    }
}
