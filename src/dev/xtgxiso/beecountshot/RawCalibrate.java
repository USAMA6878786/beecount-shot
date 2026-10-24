package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/**
 * **静默校准** raw 的像素通道顺序：不画色块、不切页面、不打扰用户。
 *
 * <h3>怎么做到"不用色块"</h3>
 * 通道顺序只有一个判据：**哪个解出来的颜色更接近真实画面**。而"真实画面"现成就有——
 * 同一帧的 PNG 截图（{@code screencap -p} 走的是标准编码，颜色一定是对的）。
 *
 * <p>所以这里连抓两帧：PNG 当参照物，raw 当待测样本，两种假设各解码一次，
 * 逐点比对。谁跟 PNG 对得上就是谁。整个过程在当前屏幕上完成，
 * 用户看到的只是设置页上那个"正在检测…"的转圈，**没有任何画面跳转**。
 *
 * <h3>为什么只信"彩色点"</h3>
 * 灰白区域 R≈G≈B，交换 R/B 之后数值几乎不变——以前在设置页上用 9 个点校准失败，
 * 就是因为这个（两种假设都"通过"，等于没测）。所以这里：
 * <ul>
 *   <li>只统计**通道差 ≥ {@link #MIN_SPREAD}** 的点（真正带颜色的地方）；</li>
 *   <li>彩色点太少（画面太素）就**判定不了**——这时**不改动**已有设置，
 *       绝不因为一次测不准就把好好的 raw 通道关掉。</li>
 * </ul>
 *
 * <h3>和"四色屏"那种精确校准的关系</h3>
 * 色块法（{@link ColorProbeActivity}）用纯红/绿/蓝/青，判别力是 255 vs 0，最干净；
 * 但要让用户看一眼满屏色块。这里是**默认**的静默方案，判不出来时再让用户选择
 * 要不要用色块法补一次。
 */
final class RawCalibrate {

    /** 采样点里"算得上彩色"的通道差下限：低于它的点（灰、白、黑）一律不参与判定。 */
    private static final int MIN_SPREAD = 40;

    /** 单点算"对得上"的容差（PNG 与 raw 是同一帧，正常误差来自轻微时间差）。 */
    private static final int TOL = 24;

    /** 彩色点少于这个数，画面太素，判定不了。 */
    private static final int MIN_POINTS = 200;

    /** 采样步长（在参照图坐标系里，参照图已被缩到 1/2）。 */
    private static final int STEP = 6;

    /** 一次校准的结果。 */
    static final class Result {
        /** 是否得出了结论。false 时**不要**改动 {@link Prefs#rawSwap}。 */
        final boolean decided;
        /** 结论：0=不交换、1=交换 R/B、-1=未判定。 */
        final int swap;
        /** 一句话结论，直接塞进弹窗。 */
        final String verdict;
        /** 明细（采样点数、两种假设的命中率）。 */
        final String detail;

        Result(boolean decided, int swap, String verdict, String detail) {
            this.decided = decided;
            this.swap = swap;
            this.verdict = verdict;
            this.detail = detail;
        }
    }

    /**
     * 抓两帧（PNG 当参照、raw 当样本），静默判定通道顺序。
     *
     * <p>判定成功会**写入** {@link Prefs#rawSwap}；判定不了则**原样不动**。
     */
    static Result calibrate(Context c) {
        if (c == null) {
            return new Result(false, -1, "✗ 校准被跳过", "");
        }
        if (!RootShell.isGranted(c)) {
            // 没 root 连 screencap 都跑不了；raw 通道本来也用不上
            return new Result(false, -1, "跳过（没有 root，继续走 PNG）", "");
        }
        File ref0 = new File(c.getCacheDir(), "calib_ref.png");
        File raw = new File(c.getCacheDir(), "calib.raw");
        //noinspection ResultOfMethodCallIgnored
        ref0.delete();
        //noinspection ResultOfMethodCallIgnored
        raw.delete();
        long t0 = System.currentTimeMillis();
        String lastDetail = "";
        // 最多试两次：两次抓帧之间画面一旦变化（刚打开的页面还在入场动画、列表在滚动），
        // 参照图和 raw 就不是同一帧，两种假设都会落到 0% —— 那不代表"判不了"，
        // 而是"这两帧对不上"，隔一会儿重抓一次通常就好了。
        for (int attempt = 1; attempt <= 2; attempt++) {
            Bitmap ref = null;
            try {
                //noinspection ResultOfMethodCallIgnored
                ref0.delete();
                //noinspection ResultOfMethodCallIgnored
                raw.delete();
                RootShell.exec("screencap -p " + ref0.getAbsolutePath() + " 2>&1", 15000L);
                RootShell.exec("screencap " + raw.getAbsolutePath() + " 2>&1", 15000L);
                if (ref0.length() <= 0L || raw.length() <= 0L) {
                    Logx.w("[calib] 抓帧失败 png=" + ref0.length() + " raw=" + raw.length());
                    return new Result(false, -1, "✗ 抓不到帧，这次没校准（继续用 PNG）", "");
                }
                ref = decodeSampled(ref0, 2);
                if (ref == null) {
                    Logx.w("[calib] 参照图解码失败");
                    return new Result(false, -1, "✗ 参照图解码失败，这次没校准", "");
                }
                Score no = score(ref, raw, false);
                Score yes = score(ref, raw, true);
                if (no == null || yes == null) {
                    Logx.w("[calib] raw 解码失败 / 尺寸不符");
                    return new Result(false, -1, "✗ 这台机的 raw 解不开（继续走 PNG）", "");
                }
                float rNo = no.ratio();
                float rYes = yes.ratio();
                lastDetail = "彩色采样点 " + no.colorful + " 个：不交换对上 "
                        + pct(rNo) + "，交换RB 对上 " + pct(rYes)
                        + "（第 " + attempt + " 次，耗时 " + (System.currentTimeMillis() - t0) + "ms）";
                Logx.i("[calib] " + lastDetail);

                if (no.colorful < MIN_POINTS) {
                    return new Result(false, -1,
                            "画面颜色太单一，这次判不了（沿用之前的设置）", lastDetail);
                }
                // 只接受"一边非常对、另一边明显不对"的结论，两边都像就宁可不下判断
                if (rNo >= 0.92f && rYes < 0.60f) {
                    Prefs.setRawSwap(c, 0);
                    return new Result(true, 0, "✓ 已校准：不交换（" + pct(rNo) + " 对上）", lastDetail);
                }
                if (rYes >= 0.92f && rNo < 0.60f) {
                    Prefs.setRawSwap(c, 1);
                    return new Result(true, 1, "✓ 已校准：交换 RB（" + pct(rYes) + " 对上）", lastDetail);
                }
                if (attempt == 1 && rNo < 0.5f && rYes < 0.5f) {
                    Logx.w("[calib] 两帧画面不一致（两种都是 0%），重抓一次");
                    try {
                        Thread.sleep(400L);
                    } catch (InterruptedException ignored) {
                    }
                    continue;
                }
                return new Result(false, -1,
                        "两种顺序都对得上（或都对不上），这次判不了（沿用之前的设置）", lastDetail);
            } catch (Throwable t) {
                Logx.e("[calib] 校准异常", t);
                return new Result(false, -1, "✗ 校准出错：" + t.getMessage(), "");
            } finally {
                if (ref != null) {
                    ref.recycle();
                }
                //noinspection ResultOfMethodCallIgnored
                ref0.delete();
                //noinspection ResultOfMethodCallIgnored
                raw.delete();
            }
        }
        return new Result(false, -1, "两种顺序都对不上，这次判不了（沿用之前的设置）", lastDetail);
    }

    /** 一张参照图（已缩放）对一种假设的命中情况。 */
    private static final class Score {
        int colorful;
        int good;

        float ratio() {
            return colorful <= 0 ? 0f : (float) good / (float) colorful;
        }
    }

    /**
     * 逐点比对：{@code ref} 是 1/2 缩放的 PNG，{@code rawFile} 按 {@code swap} 解码后取样。
     *
     * <p>raw 全图 1080x2400 解码出来就是 10MB，所以**一次只解一种**，比完立刻回收。
     */
    private static Score score(Bitmap ref, File rawFile, boolean swap) {
        Bitmap rawBmp = RawShot.decodeForProbe(rawFile, swap);
        if (rawBmp == null) {
            return null;
        }
        try {
            int rw = ref.getWidth();
            int rh = ref.getHeight();
            float sx = (float) rawBmp.getWidth() / (float) rw;
            float sy = (float) rawBmp.getHeight() / (float) rh;
            Score s = new Score();
            for (int y = STEP / 2; y < rh; y += STEP) {
                int ry = Math.min(rawBmp.getHeight() - 1, Math.round(y * sy));
                for (int x = STEP / 2; x < rw; x += STEP) {
                    int a = ref.getPixel(x, y);
                    int ar = (a >> 16) & 0xFF;
                    int ag = (a >> 8) & 0xFF;
                    int ab = a & 0xFF;
                    int spread = Math.max(ar, Math.max(ag, ab)) - Math.min(ar, Math.min(ag, ab));
                    if (spread < MIN_SPREAD) {
                        continue;   // 灰白色，交换通道也看不出差别，不参与
                    }
                    int rx = Math.min(rawBmp.getWidth() - 1, Math.round(x * sx));
                    int b = rawBmp.getPixel(rx, ry);
                    int d = Math.max(Math.abs(ar - ((b >> 16) & 0xFF)),
                            Math.max(Math.abs(ag - ((b >> 8) & 0xFF)),
                                    Math.abs(ab - (b & 0xFF))));
                    s.colorful++;
                    if (d <= TOL) {
                        s.good++;
                    }
                }
            }
            Logx.i("[calib] swap=" + swap + " 彩色点=" + s.colorful
                    + " 命中=" + s.good + " (" + pct(s.ratio()) + ")");
            return s;
        } finally {
            rawBmp.recycle();
        }
    }

    private static Bitmap decodeSampled(File f, int sample) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String pct(float v) {
        return Math.round(v * 100f) + "%";
    }

    private RawCalibrate() {
    }
}
