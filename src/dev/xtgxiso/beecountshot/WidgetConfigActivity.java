package dev.xtgxiso.beecountshot;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 添加桌面小组件时弹出的配置页。
 *
 * <p>目前只有一件事：用拖动条调背景透明度。默认纯白不透明，往左拖越来越透。
 * 拖动时上方的预览会实时跟着变，所见即所得。
 *
 * <p>点「取消」或返回键就不添加（把结果设成 RESULT_CANCELED，桌面会自动撤掉）。
 */
public class WidgetConfigActivity extends Activity {

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    private LinearLayout previewBox;
    private TextView previewValues;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        widgetId = getIntent().getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        // 必须先给出「取消」这个默认结果：用户中途返回时不应添加小组件
        setResult(RESULT_CANCELED);

        Logx.init(Prefs.appLogPath(this));
        Logx.i("[widget-cfg] opened, widgetId=" + widgetId);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        setContentView(root);
        setTitle("记账小组件设置");

        TextView intro = new TextView(this);
        intro.setText("拖动下面的滑块调整小组件背景的透明度。\n"
                + "小组件显示今日 / 本周 / 本月支出；把它横向拉长后会自动变成左中右三列。");
        intro.setTextSize(13.5f);
        intro.setTextColor(Color.parseColor("#444441"));
        intro.setLineSpacing(dp(3), 1f);
        root.addView(intro);

        // ---------------- 预览 ----------------
        previewBox = new LinearLayout(this);
        previewBox.setOrientation(LinearLayout.VERTICAL);
        previewBox.setGravity(Gravity.CENTER);
        int pv = dp(14);
        previewBox.setPadding(pv, pv, pv, pv);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(96));
        boxLp.topMargin = dp(14);
        root.addView(previewBox, boxLp);

        previewValues = new TextView(this);
        previewValues.setTextSize(13f);
        previewValues.setTextColor(Color.parseColor("#222222"));
        previewValues.setGravity(Gravity.CENTER);
        previewBox.addView(previewValues);

        TextView previewHint = new TextView(this);
        previewHint.setText("预览（深色底衬是为了看清透明度）");
        previewHint.setTextSize(11f);
        previewHint.setTextColor(Color.parseColor("#8A8A8E"));
        previewHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(6);
        root.addView(previewHint, hintLp);

        // ---------------- 透明度拖动条 ----------------
        TextView label = new TextView(this);
        label.setTextSize(14f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(18);
        root.addView(label, labelLp);

        final SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        // 透明度滑到 100 表示完全不透明；初值取"这个小部件自己的"，没设过就用全局默认
        int alpha0 = WidgetPrefs.alpha(this, widgetId);
        bar.setProgress(Math.round(alpha0 * 100f / 255f));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                applyPreview(label, progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        root.addView(bar);
        applyPreview(label, bar.getProgress());

        // ---------------- 按钮 ----------------
        Button ok = new Button(this);
        ok.setText("完成");
        ok.setAllCaps(false);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int alpha = Math.round(bar.getProgress() * 255f / 100f);
                // 只改这一个小组件的透明度，不影响桌面上其它实例
                WidgetPrefs.setAlphaForWidget(WidgetConfigActivity.this, widgetId, alpha);
                Logx.i("[widget-cfg] confirmed alpha=" + alpha + " widgetId=" + widgetId);

                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Intent result = new Intent();
                    result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
                    setResult(RESULT_OK, result);
                }
                // 立刻按新透明度出图，并去宿主那边取一次数
                ExpenseWidgetProvider.refreshAll(getApplicationContext());
                finish();
            }
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(16);
        root.addView(ok, btnLp);

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        root.addView(cancel);

        // 预览用的数字取上次缓存（没有就显示占位），避免在这里卡住等广播
        double[] values = WidgetPrefs.cached(this);
        String currency = WidgetPrefs.cachedCurrency(this);
        boolean has = WidgetPrefs.hasCache(this);
        previewValues.setText("今日 " + (has ? ExpenseWidgetProvider.money(values[0], currency) : "—")
                + "\n本周 " + (has ? ExpenseWidgetProvider.money(values[1], currency) : "—")
                + "\n本月 " + (has ? ExpenseWidgetProvider.money(values[2], currency) : "—"));
    }

    private void applyPreview(TextView label, int progress) {
        int alpha = Math.round(progress * 255f / 100f);
        label.setText("背景不透明度：" + progress + "%");
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(16));
        bg.setColor(ExpenseWidgetProvider.bgColor(alpha));
        previewBox.setBackground(bg);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
