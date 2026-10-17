package dev.xtgxiso.beecountshot;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
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
 * **验证用**：走 {@code screencap} 截屏 + 自己插一条媒体库记录，看看这条路通不通。
 *
 * <h3>为什么想换这条路</h3>
 * 现在截屏有两条路，都很脆：
 * <ul>
 *   <li><b>root 注入按键</b>（{@code input keycombination}）——注入的按键会被正在收起的
 *       控制中心吃掉，约三成点击第一次没反应；</li>
 *   <li><b>无障碍直调</b>——快，但服务会掉线（掉了就退回上面那条）。</li>
 * </ul>
 * 而 {@code screencap} 是 <b>root 直接让系统抓一帧</b>：同步、不依赖按键、不依赖无障碍、
 * 跟面板状态无关。唯一的问题是"蜜蜂记账感知不到"——它监听的是媒体库，
 * 所以只要我们自己往媒体库插一条 {@code DCIM/Screenshots/Screenshot_*.png}，
 * 它的观察者就会照常收到通知。
 *
 * <h3>这个类只做验证，不改任何现有逻辑</h3>
 * 跑一次、把每一步的结果写进日志、弹个框给用户看。
 * 验证通过了再决定要不要把正式链路切过去。
 */
final class ScreencapProbe {

    static String run(Context c) {
        StringBuilder sb = new StringBuilder();
        File png = new File(c.getCacheDir(), "probe_shot.png");
        //noinspection ResultOfMethodCallIgnored
        png.delete();

        // ---- 第 1 步：root 直接抓屏 ----
        long t0 = System.currentTimeMillis();
        RootShell.Result r = RootShell.exec(
                "screencap -p " + png.getAbsolutePath() + " 2>&1; "
                        + "echo EXIT=$?; ls -l " + png.getAbsolutePath() + " 2>&1", 20000L);
        long screencapMs = System.currentTimeMillis() - t0;
        long size = png.length();
        Logx.i("[probe] screencap " + screencapMs + "ms size=" + size
                + " exit=" + r.exit + " out=" + r.out);
        sb.append("① screencap：").append(screencapMs).append("ms，文件 ")
                .append(size).append(" 字节");
        if (size <= 0) {
            sb.append("\n   ✗ 失败：")
                    .append(safe(r.out)).append(safe(r.err));
            return sb.toString();
        }
        sb.append(" ✓\n");

        // ---- 第 2 步：写进媒体库（DCIM/Screenshots/Screenshot_*.png）----
        String name = "Screenshot_probe_"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".png";
        Uri uri = null;
        String err = null;
        try {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= 29) {
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Screenshots");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                err = "insert 返回 null（多半是没权限）";
            } else {
                OutputStream os = cr.openOutputStream(uri);
                if (os == null) {
                    err = "openOutputStream 返回 null";
                } else {
                    copy(png, os);
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Images.Media.IS_PENDING, 0);
                    cr.update(uri, done, null, null);
                }
            }
        } catch (Throwable t) {
            err = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        Logx.i("[probe] insert uri=" + uri + " err=" + err);
        if (uri == null) {
            sb.append("② 写媒体库：✗ ").append(err);
            return sb.toString();
        }
        sb.append("② 写媒体库：✓ ").append(name).append("\n");

        // ---- 第 3 步：从媒体库把它读回来，确认真的登记上了 ----
        String back = queryBack(c, uri);
        Logx.i("[probe] read back -> " + back);
        sb.append("③ 读回校验：").append(back).append("\n");

        sb.append("\n如果 ①②③ 都 ✓，说明这条路可行：")
                .append("\n· 不用按键、不用无障碍、不受面板状态影响")
                .append("\n· 蜜蜂记账的观察者应该能像系统截图一样收到通知")
                .append("\n\n验证完成后这张测试图会自动删掉。");

        // ---- 收尾：把测试图删掉（媒体库 + 文件）----
        try {
            c.getContentResolver().delete(uri, null, null);
        } catch (Throwable t) {
            Logx.w("[probe] 清理媒体条目失败：" + t.getMessage());
        }
        //noinspection ResultOfMethodCallIgnored
        png.delete();
        return sb.toString();
    }

    private static String queryBack(Context c, Uri uri) {
        android.database.Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri, new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE,
            }, null, null, null);
            if (cur == null || !cur.moveToFirst()) {
                return "✗ 查不到刚插入的记录";
            }
            return "✓ _id=" + cur.getLong(0) + " name=" + cur.getString(1)
                    + " date_added=" + cur.getLong(2) + " size=" + cur.getLong(3);
        } catch (Throwable t) {
            return "✗ " + t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
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

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private ScreencapProbe() {
    }
}
