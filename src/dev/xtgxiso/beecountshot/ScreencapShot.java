package dev.xtgxiso.beecountshot;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 正式截屏主路径：**root 直接抓帧 + 自己写进相册**。
 *
 * <h3>为什么换掉原来的两条路</h3>
 * <ul>
 *   <li><b>root 注入按键</b>（{@code input keycombination}）——注入的按键会被正在收起的
 *       控制中心吃掉，约三成点击第一次没反应，要白等再换命令；</li>
 *   <li><b>无障碍直调</b>——快，但服务会掉线（真机上出现过整天连不上）。</li>
 * </ul>
 * {@code screencap} 是 <b>root 让系统直接抓一帧</b>：同步、不依赖按键、不依赖无障碍、
 * 跟面板状态无关。唯一的难点是"蜜蜂记账感知不到"——它监听的是媒体库，
 * 所以这里抓完自己往相册插一条 {@code DCIM/Screenshots/Screenshot_*.png} 就行。
 *
 * <h3>已验证</h3>
 * 真机（Android 16）实测三步全通：{@code screencap} 约 400ms / 415KB；
 * 写入媒体库拿到 uri；读回 {@code _id} / {@code DISPLAY_NAME} / {@code DATE_ADDED} / {@code SIZE} 都正常。
 *
 * <h3>失败时</h3>
 * 任何一步失败都返回 {@code null}，调用方自动退回无障碍 / 按键注入那条老路。
 * **换路径不等于去掉退路。**
 */
final class ScreencapShot {

    /** 小于这个体积基本可以断定是"黑屏"（安全窗口不让截）——只记日志，不当失败。 */
    private static final long SUSPICIOUS_SIZE = 60L * 1024L;

    static boolean isAvailable(Context c) {
        // RELATIVE_PATH 需要 API 29+；root 是硬前提。
        return Build.VERSION.SDK_INT >= 29 && RootShell.isGranted(c);
    }

    /**
     * 抓一帧并写进相册。
     *
     * @return 成功时返回媒体库里的**绝对路径**（后续删除/日志要用）；失败返回 null
     */
    static String take(Context c) {
        File tmp = new File(c.getCacheDir(), "prod_shot.png");
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        try {
            long t0 = System.currentTimeMillis();
            RootShell.Result r = RootShell.exec(
                    "screencap -p " + tmp.getAbsolutePath() + " 2>&1", 15000L);
            long ms = System.currentTimeMillis() - t0;
            long size = tmp.length();
            Logx.i("[screencap] 抓帧 " + ms + "ms size=" + size
                    + " exit=" + r.exit + " out=" + RootShell.trim(r.out));
            if (size <= 0L) {
                return null;
            }
            if (size < SUSPICIOUS_SIZE) {
                // 多半是 FLAG_SECURE 窗口（银行/支付）导致的整屏黑，仍按成功处理，
                // 让蜜蜂记账照常跑一遍识别——它识别不出来就会按"未识别"处理。
                Logx.w("[screencap] 体积偏小（" + size + " 字节），可能是安全窗口截出的黑图");
            }
            return insertIntoGallery(c, tmp);
        } catch (Throwable t) {
            Logx.e("[screencap] 失败", t);
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** 把抓到的 png 作为"一张截图"写进 DCIM/Screenshots，并返回它在媒体库里的路径。 */
    private static String insertIntoGallery(Context c, File png) {
        String name = "Screenshot_" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date()) + "_beecountshot.png";
        ContentResolver cr = c.getContentResolver();
        Uri uri = null;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Screenshots");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                Logx.w("[screencap] 写媒体库失败：insert 返回 null");
                return null;
            }
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) {
                Logx.w("[screencap] 写媒体库失败：openOutputStream 返回 null");
                return null;
            }
            copy(png, os);
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);

            String path = queryData(cr, uri);
            Logx.i("[screencap] 已写入相册 name=" + name + " path=" + path);
            return path != null ? path : guessPath(name);
        } catch (Throwable t) {
            Logx.e("[screencap] 写媒体库异常", t);
            return null;
        }
    }

    private static String queryData(ContentResolver cr, Uri uri) {
        Cursor cur = null;
        try {
            cur = cr.query(uri, new String[]{MediaStore.Images.Media.DATA}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                return cur.getString(0);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
        return null;
    }

    /** DATA 列取不到时（个别 ROM）按约定拼一个，供日志和删除用。 */
    private static String guessPath(String name) {
        return "/storage/emulated/0/DCIM/Screenshots/" + name;
    }

    private static void copy(File src, OutputStream os) throws Exception {
        FileInputStream in = new FileInputStream(src);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            try {
                os.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private ScreencapShot() {
    }
}
