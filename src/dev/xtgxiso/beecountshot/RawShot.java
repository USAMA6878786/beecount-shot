package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * `screencap` 的**原始像素**路线：比 PNG 快得多。
 *
 * <h3>为什么要做</h3>
 * 真机实测（1080x2400）：PNG 抓帧 **403ms / 427KB**；
 * raw 抓帧 **202ms / 10125KB**，自己压 JPEG **32ms / 230KB** —— **合计 234ms**。
 * PNG 的时间全耗在 DEFLATE 编码上，而且**跟画面复杂度强相关**（3.2MB 的图要 902ms）。
 *
 * <h3>通道顺序：不猜，校准</h3>
 * raw 出的是 RGBA，而 {@code Bitmap} 的 {@code ARGB_8888} 在内存里是 BGRA。
 * 这两种顺序**在不同 ROM 上不一样** —— 第一次按"交换 R/B"写，真机采样比对
 * **9 点中 6 点超容差、最大差 41**，说明猜错了。
 *
 * <p>所以现在改成**两种都试、用像素差值选对的**，结果记进 {@link Prefs#rawSwap}。
 * 没校准过（-1）时生产链路**继续用 PNG** —— 不拿没验证过的东西上生产。
 */
final class RawShot {

    /** 抓一帧，raw → JPEG，写进相册。只在通道顺序已校准后由 {@link ScreencapShot#take} 调用。 */
    static String take(Context c) {
        int swap = Prefs.rawSwap(c);
        if (swap < 0) {
            return null;
        }
        File raw = new File(c.getCacheDir(), "prod_shot.raw");
        //noinspection ResultOfMethodCallIgnored
        raw.delete();
        try {
            long t0 = System.currentTimeMillis();
            RootShell.Result r = RootShell.exec(
                    "screencap " + raw.getAbsolutePath() + " 2>&1", 15000L);
            long ms = System.currentTimeMillis() - t0;
            Bitmap bmp = decodeForProbe(raw, swap == 1);
            if (bmp == null) {
                Logx.w("[raw] 解码失败 exit=" + r.exit + " out=" + RootShell.trim(r.out));
                return null;
            }
            Logx.i("[raw] 抓帧 " + ms + "ms size=" + raw.length() + " swap=" + swap);
            // 「图片压缩」开着就把大图缩到长边上限、质量降到 85（省上传时间）；
            // 关着就保持原来的质量 90、原尺寸。
            int quality = 90;
            if (Prefs.compress(c)) {
                bmp = ImageCompress.fit(bmp);   // 需要缩小时会回收旧 bitmap
                quality = ImageCompress.QUALITY;
            }
            String p = ScreencapShot.insertJpeg(c, bmp, quality);
            bmp.recycle();
            return p;
        } catch (Throwable t) {
            Logx.e("[raw] 失败", t);
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    /**
     * 解 raw：头 16 字节 = width, height, format, colorspace（小端 uint32），后面是像素。
     *
     * @param swap true = 交换 R/B（有些 ROM 的 raw 内存序已经是 BGRA，不用换）
     */
    static Bitmap decodeForProbe(File f, boolean swap) {
        FileInputStream in = null;
        try {
            long len = f.length();
            if (len < 16) {
                Logx.w("[raw] 文件太小：" + len);
                return null;
            }
            byte[] head = new byte[16];
            in = new FileInputStream(f);
            if (in.read(head) != 16) {
                return null;
            }
            ByteBuffer hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            int w = hb.getInt();
            int h = hb.getInt();
            int fmt = hb.getInt();
            if (w <= 0 || h <= 0 || w > 4000 || h > 8000) {
                Logx.w("[raw] 尺寸异常 " + w + "x" + h);
                return null;
            }
            long need = 16L + (long) w * h * 4L;
            if (len < need) {
                Logx.w("[raw] 长度不足：需要 " + need + " 实际 " + len);
                return null;
            }
            byte[] buf = new byte[w * h * 4];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r <= 0) {
                    break;
                }
                off += r;
            }
            int[] pixels = new int[w * h];
            IntBuffer ib = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder()).asIntBuffer();
            ib.get(pixels);
            buf = null;
            if (swap) {
                for (int i = 0; i < pixels.length; i++) {
                    int v = pixels[i];
                    pixels[i] = (v & 0xFF00FF00)
                            | ((v & 0x00FF0000) >>> 16)
                            | ((v & 0x000000FF) << 16);
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            Logx.i("[raw] 解码 " + w + "x" + h + " fmt=" + fmt + " swap=" + swap);
            return bmp;
        } catch (Throwable t) {
            Logx.e("[raw] decode 异常", t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private RawShot() {
    }
}
