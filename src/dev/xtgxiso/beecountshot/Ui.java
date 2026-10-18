package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Material 3 的视觉规范，用**纯原生 View** 手工实现。
 *
 * <h3>为什么不用 Material Components</h3>
 * 这个模块没有任何 AndroidX 依赖——`build.sh` 用 javac 对着 {@code android.jar} 直接编译，
 * 拉不进 {@code com.google.android.material}。所以这里做的不是"用 M3 组件"，
 * 而是**照 M3 的规范画**：色彩令牌、形状（圆角）、字号层级、触控高度、留白节奏。
 *
 * <h3>色彩</h3>
 * 以"蜜蜂/蜂蜜"为种子色的暖色调（M3 的 tonal palette 思路）：
 * 主色 {@link #PRIMARY}、容器 {@link #PRIMARY_CONTAINER}、表面 {@link #SURFACE_CONTAINER}。
 * 文本一律用 {@link #ON_SURFACE} / {@link #ON_SURFACE_VARIANT}，保证对比度。
 */
final class Ui {

    // ---------------- 色彩令牌（M3 语义命名） ----------------

    /** 页面底色（surface）。 */
    static final int BACKGROUND = Color.parseColor("#FFFBFF");
    /** 卡片底色（surfaceContainer）。 */
    static final int SURFACE_CONTAINER = Color.parseColor("#F7F0E8");
    /** 主色（primary）。 */
    static final int PRIMARY = Color.parseColor("#8A5100");
    static final int ON_PRIMARY = Color.WHITE;
    /** 主色容器（primaryContainer），用于 tonal 按钮与高亮块。 */
    static final int PRIMARY_CONTAINER = Color.parseColor("#FFDDB8");
    static final int ON_PRIMARY_CONTAINER = Color.parseColor("#2B1700");
    /** 主要文字。 */
    static final int ON_SURFACE = Color.parseColor("#1D1B16");
    /** 次要文字。 */
    static final int ON_SURFACE_VARIANT = Color.parseColor("#514539");
    /** 未选中态的描边/轨道。 */
    static final int OUTLINE_VARIANT = Color.parseColor("#D5C3B2");
    /** 状态色。 */
    static final int OK = Color.parseColor("#1D7A5F");
    static final int WARN = Color.parseColor("#A36A00");
    static final int ERROR = Color.parseColor("#B3261E");

    // ---------------- 尺寸令牌（M3 常用档位） ----------------

    /** 页面左右留白。 */
    static final int PAGE_PAD = 16;
    /** 卡片内留白。 */
    static final int CARD_PAD = 16;
    /** 卡片之间。 */
    static final int CARD_GAP = 12;
    /** 卡片圆角（M3 medium 形状）。 */
    static final int CARD_RADIUS = 20;
    /** 按钮圆角（M3 full 形状，40dp 高时取 20）。 */
    static final int BUTTON_RADIUS = 20;
    /** 按钮最小高度（M3 触控目标）。 */
    static final int BUTTON_HEIGHT = 44;

    // ---------------- 基础换算 ----------------

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------- 形状 ----------------

    /** 圆角纯色背景。 */
    static GradientDrawable rounded(int color, int radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    // ---------------- 容器 ----------------

    /**
     * 一张卡片：圆角 + 内留白，返回**内容容器**（往里 addView）。
     *
     * @param title 卡片标题，null 表示不要标题
     */
    static LinearLayout card(Context c, LinearLayout parent, String title) {
        LinearLayout outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackground(rounded(SURFACE_CONTAINER, CARD_RADIUS, c));
        int p = dp(c, CARD_PAD);
        outer.setPadding(p, p, p, p);

        if (title != null && title.length() > 0) {
            TextView t = new TextView(c);
            t.setText(title);
            t.setTextSize(13f);
            t.setTextColor(ON_SURFACE_VARIANT);
            t.setLetterSpacing(0.06f);
            t.setAllCaps(false);
            outer.addView(t, lp(-2, -2, 0, 0, 0, 10));
        }

        parent.addView(outer, lp(-1, -2, 0, CARD_GAP, 0, 0));
        return outer;
    }

    /** 一行：左右两栏（标题在左、控件在右），M3 的 list item 样式。 */
    static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 52));
        return r;
    }

    // ---------------- 文本 ----------------

    /** 页面大标题。 */
    static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(22f);
        t.setTextColor(ON_SURFACE);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    /** 次要说明（一行以内为宜——这是本次"少字"的落点）。 */
    static TextView caption(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTextColor(ON_SURFACE_VARIANT);
        t.setLineSpacing(dp(c, 2), 1f);
        return t;
    }

    /** 行标题。 */
    static TextView rowTitle(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(14.5f);
        t.setTextColor(ON_SURFACE);
        return t;
    }

    // ---------------- 控件 ----------------

    /** M3 filled 按钮：主色底、白字、全圆角、无阴影。 */
    static TextView filledButton(Context c, String text, View.OnClickListener l) {
        TextView b = pillText(c, text, ON_PRIMARY, PRIMARY);
        b.setOnClickListener(l);
        return b;
    }

    /** M3 tonal 按钮：主色容器底、深色字。次级操作用它。 */
    static TextView tonalButton(Context c, String text, View.OnClickListener l) {
        TextView b = pillText(c, text, ON_PRIMARY_CONTAINER, PRIMARY_CONTAINER);
        b.setOnClickListener(l);
        return b;
    }

    /** M3 text 按钮：无底、主色字。 */
    static TextView textButton(Context c, String text, View.OnClickListener l) {
        TextView b = pillText(c, text, PRIMARY, Color.TRANSPARENT);
        b.setOnClickListener(l);
        return b;
    }

    private static TextView pillText(Context c, String text, int textColor, int bg) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextSize(14f);
        b.setTextColor(textColor);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, BUTTON_HEIGHT));
        b.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10));
        b.setClickable(true);
        b.setFocusable(true);
        if (bg == Color.TRANSPARENT) {
            GradientDrawable g = rounded(Color.TRANSPARENT, BUTTON_RADIUS, c);
            b.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x1F8A5100), g, null));
        } else {
            GradientDrawable g = rounded(bg, BUTTON_RADIUS, c);
            b.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF), g, null));
        }
        // 去掉原生 TextView 的默认行距，让高度完全由 minHeight 决定
        b.setIncludeFontPadding(false);
        return b;
    }

    /**
     * 一行开关：左侧标题（+可选一行副标题），右侧 Switch。
     *
     * <p>M3 里这类行**不加卡片内边距以外的东西**——靠留白分组，不画分隔线。
     */
    static void switchRow(Context c, LinearLayout parent, String title, String subtitle,
                          boolean checked, OnToggle l) {
        LinearLayout row = row(c);

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(rowTitle(c, title));
        if (subtitle != null && subtitle.length() > 0) {
            left.addView(caption(c, subtitle));
        }
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(left, llp);

        Toggle sw = new Toggle(c);
        sw.checked = checked;
        sw.listener = l;
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(row);
    }

    /** M3 outlined 按钮：透明底 + 1dp 描边 + 主色字。 */
    static TextView outlinedButton(Context c, String text, View.OnClickListener l) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextSize(14f);
        b.setTextColor(PRIMARY);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, BUTTON_HEIGHT));
        b.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10));
        b.setClickable(true);
        b.setFocusable(true);
        b.setIncludeFontPadding(false);
        GradientDrawable g = rounded(Color.TRANSPARENT, BUTTON_RADIUS, c);
        g.setStroke(Math.max(1, dp(c, 1)), OUTLINE_VARIANT);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1F8A5100), g, null));
        // 曾经漏了这一行：按钮画得很像，但点了没反应。
        b.setOnClickListener(l);
        return b;
    }

    /**
     * M3 列表行：左对齐文字 + 右侧箭头，整行可点。
     *
     * <p>「帮助」那种成组入口用它——比一串居中文字按钮更像 App，也不会显得零散。
     */
    static void listItem(Context c, LinearLayout parent, String title, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 48));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x148A5100), null, null));

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(14.5f);
        t.setTextColor(ON_SURFACE);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chev = new TextView(c);
        chev.setText("›");
        chev.setTextSize(18f);
        chev.setTextColor(ON_SURFACE_VARIANT);
        row.addView(chev);

        row.setOnClickListener(l);
        parent.addView(row);
    }

    /** SeekBar 按 M3 语义色着色。 */
    static void tintSeekBar(Context c, android.widget.SeekBar sb) {
        sb.setProgressTintList(ColorStateList.valueOf(PRIMARY));
        sb.setThumbTintList(ColorStateList.valueOf(PRIMARY));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(PRIMARY_CONTAINER));
    }

    /** 按钮之间留一点空。 */
    static LinearLayout.LayoutParams buttonLp(Context c, boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = first ? 0 : dp(c, 8);
        return lp;
    }

    /** 通用 LayoutParams 构造（width/height 用 -1 = MATCH、-2 = WRAP）。 */
    static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }


    /** 开关回调（不用 CompoundButton，因为下面是自己画的）。 */
    interface OnToggle {
        void onToggle(boolean on);
    }

    /**
     * 自绘的 M3 开关。
     *
     * <h3>为什么不用系统 Switch</h3>
     * 系统 {@code Switch} 在 Android 12+ 的轨道颜色由主题的 {@code switchTrackTint} 决定，
     * 代码里 {@code setTrackTintList} 实测**不生效**（真机：只有圆点变色，轨道始终浅色）。
     * 那样"开/关"只靠圆点位置区分，不是 M3 的样子。
     *
     * <p>自己画就完全可控：开 = 主色轨道 + 白点靠右；关 = 描边色轨道 + 深色点靠左。
     */
    static final class Toggle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int trackW;
        private final int trackH;
        private final int knobD;
        boolean checked;
        OnToggle listener;

        Toggle(Context c) {
            super(c);
            trackW = dp(c, 52);
            trackH = dp(c, 32);
            knobD = dp(c, 24);
            setClickable(true);
            setFocusable(true);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(trackW, trackH);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = trackH / 2f;
            paint.setColor(checked ? PRIMARY : OUTLINE_VARIANT);
            canvas.drawRoundRect(0, 0, trackW, trackH, r, r, paint);

            float cx = checked ? trackW - r : r;
            paint.setColor(checked ? ON_PRIMARY : Color.parseColor("#FFFFFF"));
            canvas.drawCircle(cx, trackH / 2f, knobD / 2f, paint);
        }

        @Override
        public boolean performClick() {
            checked = !checked;
            invalidate();
            if (listener != null) {
                listener.onToggle(checked);
            }
            return true;
        }
    }

    private Ui() {
    }
}
