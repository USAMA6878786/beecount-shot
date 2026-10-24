package dev.xtgxiso.beecountshot;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * 颜色校准页：**画已知颜色，再抓自己**。
 *
 * <h3>为什么不能用普通界面校准</h3>
 * 之前在设置页上校准，结果两种通道顺序都"通过"——因为设置页是灰白的，
 * R≈G≈B，交换 R/B 之后数值几乎不变，**测了个寂寞**。
 *
 * <p>所以这里画四块**纯色**（红/绿/蓝/青），抓屏后直接和**已知 RGB** 比。
 * 纯色对通道顺序最敏感：红蓝一换，红块会读成蓝、蓝块读成红，差值直接 255。
 *
 * <h3>怎么用</h3>
 * 不需要出现在正常界面里，adb 拉起即可：
 * <pre>
 *   su -c 'am start -n dev.xtgxiso.beecountshot/.ColorProbeActivity'
 * </pre>
 * 结果同时写进日志（搜 {@code [colorprobe]}），也能在屏幕底部看到。
 */
public class ColorProbeActivity extends Activity {

    /**
     * 自动模式：由设置页在「打开图片压缩」时拉起——进来就画色块、测完自己关掉，
     * 结果通过 {@link #EXTRA_VERDICT} / {@link #EXTRA_DETAIL} 回传，全程不用手动操作。
     *
     * <p>不加这个 extra（比如 adb 直接 {@code am start}）就是原来的手动模式：测完停在页面上。
     */
    static final String EXTRA_AUTO = "auto";
    /** 一句话结论，例如「✓ 采用交换RB（差 0）」。 */
    static final String EXTRA_VERDICT = "verdict";
    /** 完整测量明细（几行文本）。 */
    static final String EXTRA_DETAIL = "detail";

    /** 四块纯色与它们的采样点（相对宽高的比例）。 */
    private static final float[] SX = {0.25f, 0.75f, 0.25f, 0.75f};
    private static final float[] SY = {0.30f, 0.30f, 0.60f, 0.60f};
    private static final int[] EXPECT = {
            Color.rgb(255, 0, 0),     // 红
            Color.rgb(0, 255, 0),     // 绿
            Color.rgb(0, 0, 255),     // 蓝
            Color.rgb(0, 255, 255),   // 青
    };

    private TextView tv;
    private boolean auto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        auto = getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTO, false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        root.addView(new PatternView(this),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tv = new TextView(this);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13f);
        tv.setPadding(24, 16, 24, 24);
        tv.setText(auto ? "正在校准颜色通道…（几秒后自动返回）" : "测量中…");
        root.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        // 等界面稳定（布局、导航栏动画）再抓，否则抓到的是过渡帧。
        // 自动模式下少等一点：这个页面只有一块自绘色板，没有要加载的东西。
        new Handler(getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final Result r = measure();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                tv.setText(r.detail);
                                if (!auto) {
                                    return;
                                }
                                // 自动模式：把结论回传给设置页，停一下让人看清色块再退。
                                android.content.Intent data = new android.content.Intent();
                                data.putExtra(EXTRA_VERDICT, r.verdict);
                                data.putExtra(EXTRA_DETAIL, r.detail);
                                setResult(RESULT_OK, data);
                                new Handler(getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        finish();
                                    }
                                }, 700L);
                            }
                        });
                    }
                }, "bee-colorprobe").start();
            }
        }, auto ? 700L : 1500L);
    }

    /** 一次测量的结论：一句话 + 明细。 */
    private static final class Result {
        final String verdict;
        final String detail;

        Result(String verdict, String detail) {
            this.verdict = verdict;
            this.detail = detail;
        }
    }

    /** 抓一帧，按已知色值判定两种通道顺序哪个对。 */
    private Result measure() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        int w = dm.widthPixels;
        int h = dm.heightPixels;

        File raw = new File(getCacheDir(), "color.raw");
        //noinspection ResultOfMethodCallIgnored
        raw.delete();
        RootShell.exec("screencap " + raw.getAbsolutePath() + " 2>&1", 20000L);

        int[] noSwap = sample(raw, w, h, false);
        int[] yesSwap = sample(raw, w, h, true);
        //noinspection ResultOfMethodCallIgnored
        raw.delete();

        if (noSwap == null || yesSwap == null) {
            String msg = "✗ raw 解码失败，无法校准（继续用 PNG）";
            Logx.w("[colorprobe] " + msg);
            return new Result(msg, msg);
        }

        int dNo = maxDiff(noSwap);
        int dYes = maxDiff(yesSwap);
        StringBuilder sb = new StringBuilder();
        sb.append("屏幕 ").append(w).append("x").append(h).append("\n");
        sb.append("期望：红 绿 蓝 青\n");
        sb.append("不交换：").append(colorList(noSwap)).append(" → 最大差 ").append(dNo).append("\n");
        sb.append("交换RB：").append(colorList(yesSwap)).append(" → 最大差 ").append(dYes).append("\n");

        // 纯色对通道最敏感：错的那个差 255，对的接近 0
        boolean okNo = dNo <= 20;
        boolean okYes = dYes <= 20;
        String verdict;
        if (okNo && !okYes) {
            Prefs.setRawSwap(this, 0);
            verdict = "✓ 采用「不交换」（差 " + dNo + "）";
        } else if (okYes && !okNo) {
            Prefs.setRawSwap(this, 1);
            verdict = "✓ 采用「交换RB」（差 " + dYes + "）";
        } else if (okNo && okYes) {
            Prefs.setRawSwap(this, -1);
            verdict = "✗ 两种都接近期望，无法区分 —— 不采信，继续用 PNG";
        } else {
            Prefs.setRawSwap(this, -1);
            verdict = "✗ 两种都不对（" + dNo + " / " + dYes + "）—— 这台机的 raw 可能不是 RGBA_8888，不切";
        }
        sb.append("\n").append(verdict);
        Logx.i("[colorprobe] " + sb.toString().replace('\n', ' '));
        return new Result(verdict, sb.toString());
    }

    private static String colorList(int[] cs) {
        StringBuilder b = new StringBuilder();
        for (int c : cs) {
            b.append(String.format("#%02X%02X%02X ", (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF));
        }
        return b.toString().trim();
    }

    private int maxDiff(int[] got) {
        int m = 0;
        for (int i = 0; i < EXPECT.length && i < got.length; i++) {
            int a = EXPECT[i];
            int b = got[i];
            m = Math.max(m, Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)));
            m = Math.max(m, Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)));
            m = Math.max(m, Math.abs((a & 0xFF) - (b & 0xFF)));
        }
        return m;
    }

    /** 从 raw 里采样四个点。 */
    private int[] sample(File raw, int w, int h, boolean swap) {
        Bitmap bmp = RawShot.decodeForProbe(raw, swap);
        if (bmp == null) {
            return null;
        }
        try {
            if (bmp.getWidth() != w || bmp.getHeight() != h) {
                Logx.w("[colorprobe] 尺寸不符 raw=" + bmp.getWidth() + "x" + bmp.getHeight()
                        + " screen=" + w + "x" + h);
            }
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            int[] out = new int[4];
            for (int i = 0; i < 4; i++) {
                int x = Math.min(bw - 1, Math.max(0, (int) (SX[i] * bw)));
                int y = Math.min(bh - 1, Math.max(0, (int) (SY[i] * bh)));
                out[i] = bmp.getPixel(x, y);
            }
            return out;
        } finally {
            bmp.recycle();
        }
    }

    /** 画四块纯色。 */
    private static final class PatternView extends View {
        private final Paint p = new Paint();

        PatternView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            p.setStyle(Paint.Style.FILL);
            // 左半：红（上）/ 蓝（下）；右半：绿（上）/ 青（下）
            p.setColor(EXPECT[0]);
            canvas.drawRect(0, 0, w * 0.5f, h * 0.5f, p);
            p.setColor(EXPECT[1]);
            canvas.drawRect(w * 0.5f, 0, w, h * 0.5f, p);
            p.setColor(EXPECT[2]);
            canvas.drawRect(0, h * 0.5f, w * 0.5f, h, p);
            p.setColor(EXPECT[3]);
            canvas.drawRect(w * 0.5f, h * 0.5f, w, h, p);
        }
    }
}
