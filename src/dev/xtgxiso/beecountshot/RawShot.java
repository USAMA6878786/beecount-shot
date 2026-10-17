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

    /** 颜色比对允许的单通道误差（PNG 与 JPEG 有损，90 质量下一般 <10）。 */
    private static final int COLOR_TOLERANCE = 24;

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
            String p = ScreencapShot.insertJpeg(c, bmp);
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
     * 验证：同一帧出 PNG 与 raw，**两种通道顺序都比一遍**，用数字选出对的并记住。
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

        // 先把界面等静止。上一版就是在这里翻车的：调用方刚弹了进度框就立刻抓图，
        // 第一张抓在淡入之前、第二张抓在之后，两张本来就不同（最大差 88 就是遮罩层的暗化幅度），
        // 于是"颜色比对不通过"——**测的根本不是同一帧**。
        try {
            Thread.sleep(800L);
        } catch (InterruptedException ignored) {
        }

        long t0 = System.currentTimeMillis();
        RootShell.exec("screencap -p " + pngF.getAbsolutePath() + " 2>&1", 20000L);
        long pngMs = System.currentTimeMillis() - t0;
        long pngSize = pngF.length();

        t0 = System.currentTimeMillis();
        RootShell.exec("screencap " + rawF.getAbsolutePath() + " 2>&1", 20000L);
        long rawMs = System.currentTimeMillis() - t0;
        long rawSize = rawF.length();

        // 强制 ARGB_8888：不透明 PNG 默认可能解成 RGB_565，量化误差会污染比对
        BitmapFactory.Options popts = new BitmapFactory.Options();
        popts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap png = BitmapFactory.decodeFile(pngF.getAbsolutePath(), popts);
        sb.append("PNG  ：").append(pngMs).append("ms，").append(pngSize / 1024).append(" KB\n");
        sb.append("raw  ：").append(rawMs).append("ms，").append(rawSize / 1024).append(" KB\n");
        if (png == null) {
            sb.append("\n✗ PNG 解码失败，无法比对");
            return sb.toString();
        }

        int[] scoreNo = new int[]{-1, -1};
        int[] scoreYes = new int[]{-1, -1};
        long encMs = 0L;
        long encSize = 0L;

        Bitmap b0 = decodeForProbe(rawF, false);
        if (b0 != null) {
            scoreNo = scoreColors(png, b0);
            long t1 = System.currentTimeMillis();
            try {
                File jpg = new File(c.getCacheDir(), "probe.jpg");
                FileOutputStream os = new FileOutputStream(jpg);
                b0.compress(Bitmap.CompressFormat.JPEG, 90, os);
                os.close();
                encMs = System.currentTimeMillis() - t1;
                encSize = jpg.length();
                //noinspection ResultOfMethodCallIgnored
                jpg.delete();
            } catch (Throwable t) {
                Logx.w("[raw] jpeg 编码失败：" + t.getMessage());
            }
            b0.recycle();
        }
        Bitmap b1 = decodeForProbe(rawF, true);
        if (b1 != null) {
            scoreYes = scoreColors(png, b1);
            b1.recycle();
        }

        sb.append("raw→JPEG 编码：").append(encMs).append("ms，").append(encSize / 1024).append(" KB\n");
        sb.append("raw 总耗时：").append(rawMs + encMs).append("ms\n\n");
        sb.append("颜色比对（两种通道顺序都试）：\n");
        sb.append("  不交换：").append(scoreText(scoreNo)).append("\n");
        sb.append("  交换RB：").append(scoreText(scoreYes)).append("\n\n");

        boolean ok0 = scoreNo[0] == 0 && scoreNo[1] >= 0;
        boolean ok1 = scoreYes[0] == 0 && scoreYes[1] >= 0;
        if (!ok0 && !ok1) {
            Prefs.setRawSwap(c, -1);
            sb.append("✗ 两种顺序都不通过 —— 这条不切，生产继续用 PNG。");
        } else {
            int pick = (ok1 && (!ok0 || scoreYes[1] < scoreNo[1])) ? 1 : 0;
            Prefs.setRawSwap(c, pick);
            int gain = pngMs > 0 ? (int) (100 - (rawMs + encMs) * 100 / pngMs) : 0;
            sb.append("✓ 通过，采用「").append(pick == 1 ? "交换RB" : "不交换").append("」。\n")
                    .append("下次点磁贴就走 raw+JPEG，比 PNG 快约 ").append(gain).append("%。\n")
                    .append("以后若觉得颜色不对，重跑一次这个按钮即可重新校准。");
        }

        sb.append("\n\n尺寸：PNG ").append(png.getWidth()).append("x").append(png.getHeight());
        //noinspection ResultOfMethodCallIgnored
        rawF.delete();
        //noinspection ResultOfMethodCallIgnored
        pngF.delete();
        Logx.i("[raw] compare: " + sb.toString().replace('\n', ' '));
        return sb.toString();
    }

    private static String scoreText(int[] s) {
        if (s[1] < 0) {
            return "✗ 解码失败";
        }
        if (s[0] == 0) {
            return "✓ 通过（最大差 " + s[1] + "）";
        }
        return "✗ " + s[0] + "/9 点超容差（最大差 " + s[1] + "）";
    }

    /** @return {超容差点数, 最大单通道差} */
    private static int[] scoreColors(Bitmap a, Bitmap b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return new int[]{9, 255};
        }
        int w = a.getWidth();
        int h = a.getHeight();
        int bad = 0;
        int max = 0;
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
            if (d > COLOR_TOLERANCE) {
                bad++;
            }
        }
        return new int[]{bad, max};
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
