package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * `screencap` 的**原始像素**路线：比 PNG 快得多。
 *
 * <h3>为什么要做</h3>
 * 现在用 {@code screencap -p} 直接出 PNG。真机实测抓帧耗时**跟画面复杂度强相关**：
 * 3.2MB 的图要 **902ms**，243KB 的图只要 402ms —— 瓶颈是 PNG 的 DEFLATE 编码。
 *
 * <p>而 {@code screencap} 不加 {@code -p} 出的是**原始 RGBA 像素**（约 50~100ms），
 * 自己压成 JPEG 大概再花 100~200ms，总耗时有望压到 **0.3s 量级**。
 *
 * <h3>风险（必须先验证）</h3>
 * 原始像素的字节序是 RGBA，而 {@link Bitmap} 的 {@code ARGB_8888} 在内存里是 BGRA。
 * 不同 ROM 上这个顺序是否一致**没有保证** —— 猜错就是**红蓝互换**。
 * 所以这里同时提供一个**自动颜色比对**：同一帧分别用 PNG 和 raw 出图，
 * 采样若干像素比较 RGB，差值在容差内才算通过。**不靠肉眼判断。**
 */
final class RawShot {

    /** 颜色比对允许的单通道误差（PNG 与 JPEG 有损，90 质量下一般 <10）。 */
    private static final int COLOR_TOLERANCE = 24;

    /**
     * 验证：同一帧分别出 PNG 与 raw→JPEG，比较耗时和**采样像素颜色**。
     *
     * @return 人读的报告
     */
    static String compare(Context c) {
        StringBuilder sb = new StringBuilder();
        File rawF = new File(c.getCacheDir(), "probe.raw");
        File pngF = new File(c.getCacheDir(), "probe.png");
        //noinspection ResultOfMethodCallIgnored
        rawF.delete();
        //noinspection ResultOfMethodCallIgnored
        pngF.delete();

        long t0 = System.currentTimeMillis();
        RootShell.exec("screencap -p " + pngF.getAbsolutePath() + " 2>&1", 20000L);
        long pngMs = System.currentTimeMillis() - t0;
        long pngSize = pngF.length();

        t0 = System.currentTimeMillis();
        RootShell.exec("screencap " + rawF.getAbsolutePath() + " 2>&1", 20000L);
        long rawMs = System.currentTimeMillis() - t0;
        long rawSize = rawF.length();

        Bitmap png = BitmapFactory.decodeFile(pngF.getAbsolutePath());
        Bitmap rawBmp = decodeRaw(rawF);
        sb.append("PNG   ：").append(pngMs).append("ms，").append(pngSize / 1024).append(" KB");
        if (png == null) {
            sb.append("  ✗ 解码失败");
        }
        sb.append("\n");
        sb.append("raw   ：").append(rawMs).append("ms，").append(rawSize / 1024).append(" KB");
        if (rawBmp == null) {
            sb.append("  ✗ 解码失败");
        }
        sb.append("\n");

        if (png != null && rawBmp != null) {
            // raw 再压一次 JPEG，看总共要多久
            long t1 = System.currentTimeMillis();
            try {
                File jpg = new File(c.getCacheDir(), "probe.jpg");
                FileOutputStream os = new FileOutputStream(jpg);
                rawBmp.compress(Bitmap.CompressFormat.JPEG, 90, os);
                os.close();
                sb.append("raw→JPEG 编码：").append(System.currentTimeMillis() - t1)
                        .append("ms，").append(jpg.length() / 1024).append(" KB\n");
                sb.append("raw 总耗时（抓帧+编码）：").append(rawMs + (System.currentTimeMillis() - t1))
                        .append("ms\n");
                //noinspection ResultOfMethodCallIgnored
                jpg.delete();
            } catch (Throwable t) {
                sb.append("JPEG 编码失败：").append(t.getMessage()).append("\n");
            }
            sb.append("\n").append(checkColors(png, rawBmp)).append("\n");
        }

        //noinspection ResultOfMethodCallIgnored
        rawF.delete();
        //noinspection ResultOfMethodCallIgnored
        pngF.delete();
        sb.append("\nPNG 尺寸 ").append(png == null ? "?" : png.getWidth() + "x" + png.getHeight())
                .append("，raw 尺寸 ")
                .append(rawBmp == null ? "?" : rawBmp.getWidth() + "x" + rawBmp.getHeight());
        Logx.i("[raw] compare: " + sb.toString().replace('\n', ' '));
        return sb.toString();
    }

    /** 采样比对两张图的 RGB。差值超容差就判红蓝通道可能互换。 */
    private static String checkColors(Bitmap a, Bitmap b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return "③ 颜色比对：✗ 尺寸不一致，无法比对";
        }
        int w = a.getWidth();
        int h = a.getHeight();
        int bad = 0;
        int max = 0;
        int n = 0;
        int[][] pts = new int[][]{
                {w / 6, h / 6}, {w / 2, h / 6}, {w * 5 / 6, h / 6},
                {w / 6, h / 2}, {w / 2, h / 2}, {w * 5 / 6, h / 2},
                {w / 6, h * 5 / 6}, {w / 2, h * 5 / 6}, {w * 5 / 6, h * 5 / 6},
        };
        for (int[] p : pts) {
            int ca = a.getPixel(p[0], p[1]);
            int cb = b.getPixel(p[0], p[1]);
            int dr = Math.abs(((ca >> 16) & 0xFF) - ((cb >> 16) & 0xFF));
            int dg = Math.abs(((ca >> 8) & 0xFF) - ((cb >> 8) & 0xFF));
            int db = Math.abs((ca & 0xFF) - (cb & 0xFF));
            int d = Math.max(dr, Math.max(dg, db));
            max = Math.max(max, d);
            n++;
            if (d > COLOR_TOLERANCE) {
                bad++;
            }
        }
        Logx.i("[raw] color samples=" + n + " bad=" + bad + " maxDiff=" + max);
        if (bad == 0) {
            return "③ 颜色比对：✓ 通过（采样 " + n + " 点，最大单通道差 " + max
                    + "，容差 " + COLOR_TOLERANCE + "）";
        }
        return "③ 颜色比对：✗ 不通过（采样 " + n + " 点，其中 " + bad
                + " 点超出容差，最大差 " + max
                + "）—— 很可能是 RGBA/BGRA 通道顺序不同，这条不能切";
    }

    /**
     * 解 raw：头 16 字节 = width, height, format, colorspace（小端 uint32），后面是像素。
     *
     * <p>像素是 RGBA，而 {@code ARGB_8888} 的 int 是 0xAARRGGBB。这里用
     * {@link ByteOrder#nativeOrder()} 读成 int 后再把 R/B 换回来（一次位运算，
     * 比逐字节拼快得多）。
     */
    private static Bitmap decodeRaw(File f) {
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
            int[] pixels = new int[w * h];
            byte[] buf = new byte[w * h * 4];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r <= 0) {
                    break;
                }
                off += r;
            }
            IntBuffer ib = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder()).asIntBuffer();
            ib.get(pixels);
            // RGBA(内存序) -> ARGB：把 R 和 B 换回来
            for (int i = 0; i < pixels.length; i++) {
                int v = pixels[i];
                pixels[i] = (v & 0xFF00FF00)
                        | ((v & 0x00FF0000) >>> 16)
                        | ((v & 0x000000FF) << 16);
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            Logx.i("[raw] 解码 " + w + "x" + h + " fmt=" + fmt);
            return bmp;
        } catch (Throwable t) {
            Logx.e("[raw] decodeRaw 异常", t);
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
