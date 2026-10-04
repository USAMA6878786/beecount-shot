package dev.xtgxiso.beecountshot;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/**
 * 把日志文件放到 Download 目录，方便用户用文件管理器取出来。
 *
 * <p>走 MediaStore 的 Downloads 集合：这是应用可以无权限写入的公共位置（写的是自己贡献的
 * 文件），所以在没有 root 的情况下也能用。名字固定，每次覆盖同一个文件，不会堆出
 * "xxx (1).log" 这种东西。
 */
final class LogExport {

    /** @return 目标文件的展示路径，失败返回 null。 */
    static String copyToDownload(Context ctx, String srcPath, String fileName) {
        if (ctx == null || srcPath == null) {
            return null;
        }
        File src = new File(srcPath);
        if (!src.exists() || src.length() == 0L) {
            Logx.w("[export] source log missing or empty: " + srcPath);
            return null;
        }
        if (Build.VERSION.SDK_INT < 29) {
            Logx.w("[export] MediaStore Downloads needs API 29+");
            return null;
        }
        try {
            ContentResolver cr = ctx.getContentResolver();
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Uri target = findExisting(ctx, collection, fileName);
            if (target == null) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                cv.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + Const.LOG_DIR);
                target = cr.insert(collection, cv);
            }
            if (target == null) {
                Logx.w("[export] insert into Downloads failed");
                return null;
            }
            OutputStream os = cr.openOutputStream(target, "wt");
            if (os == null) {
                Logx.w("[export] openOutputStream failed");
                return null;
            }
            FileInputStream in = null;
            try {
                in = new FileInputStream(src);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
            String shown = "Download/" + Const.LOG_DIR + "/" + fileName;
            Logx.i("[export] wrote " + shown);
            return shown;
        } catch (Throwable t) {
            Logx.e("[export] failed for " + fileName, t);
            return null;
        }
    }

    private static Uri findExisting(Context ctx, Uri collection, String fileName) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(collection,
                    new String[]{MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME + "=?",
                    new String[]{fileName},
                    null);
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(0));
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private LogExport() {
    }
}
