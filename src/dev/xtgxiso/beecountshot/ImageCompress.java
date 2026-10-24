package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * 截图上传前的**体积压缩**：同一帧画面，换一种更小的存法。
 *
 * <h3>为什么值得做</h3>
 * 蜜蜂记账是把相册里这张图**整张上传**给视觉模型做识别的。真机上 1080x2400 的
 * PNG 截图，简单画面约 400KB，复杂画面（明细列表、带图表的统计页）能到 3.2MB——
 * 上传体积直接决定"从点磁贴到出结果"里那段等待的长短。
 *
 * <p>同一帧换成 JPEG（质量 85）通常只剩 **1/5 ~ 1/8**，而且**分辨率一点没降**：
 * 识别看的是文字和数字，JPEG 丢掉的是肉眼都不容易察觉的高频细节，字号和笔画都还在。
 *
 * <h3>只缩不放</h3>
 * 长边超过 {@link #MAX_LONG_SIDE} 才等比缩小（1440p / 2K 屏的机器），
 * 1080x2400 这类常见尺寸**原样保留**——不做那种"为了小几 KB 把字缩糊"的事。
 *
 * <h3>失败了怎么办</h3>
 * 解码/编码失败，或者压完反而更大，一律返回 {@code null}，调用方**用原图**。
 * 压缩是加速手段，不是记账的前提，任何情况下都不能因为它丢图。
 */
final class ImageCompress {

    /** 长边上限（像素）。只缩不放：小于它的图原样保留。 */
    static final int MAX_LONG_SIDE = 2400;

    /** JPEG 质量。85 是"文字清楚"和"体积够小"的平衡点。 */
    static final int QUALITY = 85;

    /**
     * 把一个图片文件压成 JPEG 临时文件。
     *
     * @return 压缩后的临时文件（在 cache 目录，调用方用完删掉）；
     *         失败、或者压完没变小，返回 {@code null}（调用方应当继续用原图）
     */
    static File compressFile(Context c, File src) {
        if (c == null || src == null || !src.isFile() || src.length() <= 0L) {
            return null;
        }
        File out = new File(c.getCacheDir(), "prod_shot_c.jpg");
        Bitmap bmp = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Logx.w("[compress] 解不出尺寸，跳过压缩");
                return null;
            }
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sampleSize(Math.max(bounds.outWidth, bounds.outHeight));
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bmp = BitmapFactory.decodeFile(src.getAbsolutePath(), opt);
            if (bmp == null) {
                Logx.w("[compress] 解码失败，跳过压缩");
                return null;
            }

            long t0 = System.currentTimeMillis();
            bmp = fit(bmp);   // 大图等比缩小（会回收旧 bitmap）
            FileOutputStream os = new FileOutputStream(out);
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, os);
                os.flush();
            } finally {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
            long in = src.length();
            long z = out.length();
            if (z <= 0L) {
                Logx.w("[compress] 写出 0 字节，跳过压缩");
                return null;
            }
            if (z >= in) {
                // 原图本来就很省（有些 ROM 的 screencap 直接给 JPEG），压了反而更大
                Logx.i("[compress] 压完更大（" + kb(in) + " -> " + kb(z) + "），用原图");
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                return null;
            }
            Logx.i("[compress] " + bmp.getWidth() + "x" + bmp.getHeight() + " "
                    + kb(in) + " -> " + kb(z) + "（-" + (100L - z * 100L / in) + "%）"
                    + " 耗时 " + (System.currentTimeMillis() - t0) + "ms");
            return out;
        } catch (Throwable t) {
            Logx.e("[compress] 压缩失败，用原图", t);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return null;
        } finally {
            if (bmp != null) {
                bmp.recycle();
            }
        }
    }

    /**
     * 按长边上限等比缩小。
     *
     * <p><b>会回收传进来的 bitmap</b>（需要缩小时），调用方拿返回值用，不要再用原对象。
     */
    static Bitmap fit(Bitmap src) {
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int longSide = Math.max(w, h);
        if (longSide <= MAX_LONG_SIDE) {
            return src;
        }
        float ratio = (float) MAX_LONG_SIDE / (float) longSide;
        int nw = Math.max(1, Math.round(w * ratio));
        int nh = Math.max(1, Math.round(h * ratio));
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
            if (scaled != src) {
                src.recycle();
            }
            return scaled;
        } catch (Throwable t) {
            Logx.w("[compress] 缩放失败，用原尺寸：" + t.getMessage());
            return src;
        }
    }

    /** 解码时先跳着读，省内存：只要最终能落到 MAX_LONG_SIDE 附近即可。 */
    private static int sampleSize(int longSide) {
        int sample = 1;
        while (longSide / (sample * 2) >= MAX_LONG_SIDE) {
            sample *= 2;
        }
        return sample;
    }

    /** 自检结果：抓屏成没成、有没有压小，外加一段给人看的说明。 */
    static final class Check {
        final boolean captureOk;
        final boolean compressed;
        final String text;

        Check(boolean captureOk, boolean compressed, String text) {
            this.captureOk = captureOk;
            this.compressed = compressed;
            this.text = text;
        }
    }

    /**
     * 「图片压缩」打开时的自检：真抓一帧、真压一次，把体积和耗时报出来。
     *
     * <p>**不写相册**——这条路只碰 cache 目录，所以不会惊动蜜蜂记账（它监听的是媒体库），
     * 不会因为它产生一张"假截图"，两个临时文件用完就删。
     *
     * <p>顺便也就验证了截屏链路本身：抓不到屏，这里第一步就会失败。
     */
    static Check check(Context c) {
        File png = new File(c.getCacheDir(), "compress_probe.png");
        //noinspection ResultOfMethodCallIgnored
        png.delete();
        if (!RootShell.isGranted(c)) {
            return new Check(false, false,
                    "✗ 没有 root，抓不到屏。\n\n"
                            + "压缩本身不需要 root，但截图要用。"
                            + "先点「root 权限」授权，再回来打开这个开关。");
        }
        long t0 = System.currentTimeMillis();
        RootShell.Result r = RootShell.exec(
                "screencap -p " + png.getAbsolutePath() + " 2>&1", 20000L);
        long shotMs = System.currentTimeMillis() - t0;
        long raw = png.length();
        if (raw <= 0L) {
            String why = RootShell.trim(r.out);
            //noinspection ResultOfMethodCallIgnored
            png.delete();
            return new Check(false, false,
                    "✗ 抓屏失败（" + shotMs + "ms）" + (why.length() > 0 ? "：" + why : ""));
        }
        long c0 = System.currentTimeMillis();
        File jpg = compressFile(c, png);
        long zipMs = System.currentTimeMillis() - c0;
        Check result;
        if (jpg == null) {
            result = new Check(true, false,
                    "✓ 抓屏 " + shotMs + "ms（原图 " + kb(raw) + "）\n"
                            + "✗ 这张没压小，会按原图上传。\n\n"
                            + "不影响记账，只是这一张省不下上传时间。");
        } else {
            long z = jpg.length();
            result = new Check(true, true,
                    "✓ 抓屏 " + shotMs + "ms，原图 " + kb(raw) + "\n"
                            + "✓ 压缩 " + zipMs + "ms，" + kb(z)
                            + "（省 " + (100L - z * 100L / raw) + "%）\n\n"
                            + "以后每张截图都会先压成这个大小再交给蜜蜂记账，上传更快。");
        }
        //noinspection ResultOfMethodCallIgnored
        png.delete();
        if (jpg != null) {
            //noinspection ResultOfMethodCallIgnored
            jpg.delete();
        }
        return result;
    }

    /** 人类可读的体积。 */
    static String kb(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private ImageCompress() {
    }
}
