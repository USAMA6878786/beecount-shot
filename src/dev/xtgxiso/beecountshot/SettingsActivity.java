package dev.xtgxiso.beecountshot;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.Executor;

/**
 * 模块设置页。界面用代码搭（不用布局 XML），减少资源依赖，打包链更简单。
 *
 * <p>三块内容：把磁贴/小部件加到系统里、把权限给足、按需调参数；再往下是日志与诊断。
 * 所有"需要问宿主才知道的状态"（hook 有没有装上、截图监听在不在位）都通过广播查询，
 * 因为模块 App 用 root 读不到宿主的私有数据。
 */
public class SettingsActivity extends Activity {

    private TextView statusView;

    /** 来自宿主的实时状态（后台查询后刷新）。 */
    private volatile boolean hostAnswered = false;
    private volatile boolean activityAlive = false;
    private volatile int hookCount = -1;
    private volatile int hookFailed = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("记账截图 · v" + versionName());
        Logx.init(Prefs.appLogPath(this));
        Logx.i("[settings] opened, module " + versionName());

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        // ---------------- 状态 ----------------
        statusView = new TextView(this);
        statusView.setTextSize(14f);
        statusView.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(statusView);
        refreshStatus();

        // ---------------- 使用前提 ----------------
        heading(root, "使用前提");
        body(root, "• 已安装 LSPosed，并在其中启用本模块、作用域勾选「蜜蜂记账」\n"
                + "• 已授予本模块 root（用于收起控制中心、删除截图、重启宿主）\n"
                + "• 已开启「记账截图」的截屏服务（无障碍）\n"
                + "• 蜜蜂记账近期打开过（它的截图监听挂在主界面上，界面被回收就会失效）");

        // ---------------- 磁贴 ----------------
        heading(root, "控制中心磁贴");
        body(root, "把磁贴加到控制中心后，点一下就会：收起面板 → 等约 0.6 秒 → 截当前屏幕"
                + " → 交给蜜蜂记账识别记账。\n\n"
                + "**平时的普通截图不会再触发识别**，只有点这个磁贴那一次会。");
        button(root, "添加「记账截图」磁贴", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestAddTile();
            }
        });

        // ---------------- root ----------------
        heading(root, "root 权限");
        body(root, "模块用 root 做三件事，都是纯应用层做不到的：收起控制中心、"
                + "在记账成功后删除截图、以及下面的「重启蜜蜂记账」。");
        button(root, "申请 root 权限 / 重新检测", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestRoot();
            }
        });
        button(root, "重启蜜蜂记账（改完配置必做）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartHost();
            }
        });

        // ---------------- 等待时长 ----------------
        heading(root, "等待时长");
        body(root, "点磁贴后先收起面板，再等这么久才截屏（等待从收起命令发出之后起算）。\n"
                + "截到的图里还有控制中心就调大一档；觉得太慢就调小一档。");
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        long saved = Prefs.delayMs(this);
        for (int i = 0; i < Prefs.DELAY_OPTIONS.length; i++) {
            final long value = Prefs.DELAY_OPTIONS[i];
            RadioButton rb = new RadioButton(this);
            rb.setText(value + " 毫秒" + (value == Prefs.DEFAULT_DELAY_MS ? "（推荐）" : ""));
            rb.setChecked(value == saved);
            rb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Prefs.setDelayMs(SettingsActivity.this, value);
                    Logx.i("[settings] delay set to " + value + "ms");
                    Toast.makeText(SettingsActivity.this,
                            "已设为 " + value + " 毫秒", Toast.LENGTH_SHORT).show();
                }
            });
            group.addView(rb);
        }
        root.addView(group);

        // ---------------- 自动删截图 ----------------
        heading(root, "记账成功后删除截图");
        body(root, "打开后：点磁贴截的那张图，如果**被成功识别并记了账**，会自动从相册和存储里删掉。\n\n"
                + "删除前会同时确认两件事，缺一不删：\n"
                + "① 这张图就是交给蜜蜂记账的那一张\n"
                + "② 之后确实有一次「自动记账成功」\n\n"
                + "加上路径必须是图片、必须带截图字样、必须不是相机目录——宁可漏删，绝不误删。"
                + "识别失败（未识别到账单）的截图会原样保留。");
        CheckBox autoDelete = new CheckBox(this);
        autoDelete.setText("记账成功后删除对应的截图");
        autoDelete.setChecked(Prefs.autoDelete(this));
        autoDelete.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setAutoDelete(SettingsActivity.this, isChecked);
                Logx.i("[settings] auto-delete set to " + isChecked);
            }
        });
        root.addView(autoDelete);

        // ---------------- 桌面小组件 ----------------
        heading(root, "桌面小组件");
        body(root, "一个小部件同时显示「今日 / 本周 / 本月支出」，白底，默认 2×1。\n"
                + "横向拉长后会自动从「上下三行」变成「左中右三列」。\n"
                + "点一下小部件会刷新数字并打开蜜蜂记账的记账明细页。\n\n"
                + "数字直接读蜜蜂记账自己的数据库，与它统计页的口径一致"
                + "（跟随账本的自定义每月起始日、周一起算、剔除「不计入收支」的记账）。\n\n"
                + "在蜜蜂记账里手动记一笔，小部件也会立刻跟着更新。");
        button(root, "添加「记账支出」小部件", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestAddWidget();
            }
        });

        final TextView alphaLabel = new TextView(this);
        alphaLabel.setTextSize(14f);
        alphaLabel.setPadding(0, dp(14), 0, dp(2));
        root.addView(alphaLabel);

        SeekBar alphaBar = new SeekBar(this);
        alphaBar.setMax(100);
        alphaBar.setProgress(Math.round(WidgetPrefs.defaultAlpha(this) * 100f / 255f));
        alphaLabel.setText("背景不透明度（统一应用到所有小部件）："
                + alphaBar.getProgress() + "%");
        alphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                alphaLabel.setText("背景不透明度（统一应用到所有小部件）：" + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 松手才写盘 + 刷新，避免拖动过程中反复重绘
                WidgetPrefs.setDefaultAlphaForAll(SettingsActivity.this,
                        Math.round(seekBar.getProgress() * 255f / 100f));
                ExpenseWidgetProvider.refreshAll(getApplicationContext());
                Toast.makeText(SettingsActivity.this, "已应用", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(alphaBar);
        body(root, "只想调某一个？长按桌面上那个小部件 → 重新配置，只改它自己。");

        // ---------------- 日志 ----------------
        heading(root, "日志与诊断");
        body(root, "出问题时点下面两个按钮，然后取 `Download/" + Const.LOG_DIR + "/` 目录里的"
                + "`logcat.txt` 和 `app.log` 即可。");
        button(root, "跑一次诊断（写入日志）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDiagnostics();
            }
        });
        button(root, "导出日志到 Download", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLogs();
            }
        });

        // ---------------- 常见问题 ----------------
        heading(root, "常见问题");
        body(root, "**点了磁贴没反应**\n"
                + "先看本页顶部状态；磁贴副标题也会提示「未就绪」。多半是蜜蜂记账的界面被系统"
                + "回收了——打开一次它即可（后台存活就够，不必留在前台）。\n\n"
                + "**改了配置不生效**\n"
                + "点上面的「重启蜜蜂记账」，hook 只在它启动时注入。\n\n"
                + "**截到的图里带着控制中心**\n"
                + "把等待时长调大一档。\n\n"
                + "**小部件显示灰色**\n"
                + "说明蜜蜂记账的进程没在跑，此时显示的是上次的数值；打开一次它就会恢复实时。");
        button(root, "打开蜜蜂记账", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openHost();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        probeHost();
    }

    // ------------------------------------------------------------ 宿主状态

    /** 后台问一次宿主：hook 装了几条、截图监听在不在位。 */
    private void probeHost() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HostInfo.pkg(SettingsActivity.this);
                    ShotBridge.Answer a = ShotBridge.query(
                            getApplicationContext(), "", 3000L);
                    hostAnswered = a.received;
                    if (a.received) {
                        activityAlive = a.activityAlive;
                        hookCount = a.hookCount;
                        hookFailed = a.hookFailed;
                    }
                } catch (Throwable ignored) {
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                    }
                });
            }
        }, "bee-status-probe").start();
    }

    private void refreshStatus() {
        if (statusView == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        boolean a11y = ShotAccessibilityService.isReady();
        boolean root = RootShell.isGranted(this);

        sb.append(a11y ? "✓ 截屏服务已开启" : "✗ 截屏服务未开启");
        sb.append("　");
        sb.append(root ? "✓ root 已授权" : "✗ root 未授权");
        sb.append('\n');

        if (!hostAnswered) {
            sb.append("? 未连上蜜蜂记账（它没在运行，或模块未注入）");
            statusView.setTextColor(Color.parseColor("#A32D2D"));
        } else {
            if (hookCount >= 0) {
                sb.append("✓ 宿主 hook：已装 ").append(hookCount).append(" 条");
                if (hookFailed > 0) {
                    sb.append("，失败 ").append(hookFailed).append(" 条");
                }
            } else {
                sb.append("? 宿主 hook：未知");
            }
            sb.append('\n');
            sb.append(activityAlive
                    ? "✓ 截图监听在位，可以直接用"
                    : "✗ 截图监听不在位（先打开一次蜜蜂记账）");
            statusView.setTextColor(activityAlive
                    ? Color.parseColor("#1D9E75") : Color.parseColor("#A32D2D"));
        }
        sb.append('\n').append("宿主包名：").append(HostInfo.pkgResolved()
                ? HostInfo.pkg() : "探测中…");
        statusView.setText(sb.toString());
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------------------------------------ 动作

    private void openHost() {
        try {
            String pkg = HostInfo.pkg();
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                Toast.makeText(this, "打不开 " + pkg + "，请手动打开蜜蜂记账",
                        Toast.LENGTH_LONG).show();
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Logx.e("[settings] launch host failed", t);
            Toast.makeText(this, "打开失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestRoot() {
        Toast.makeText(this, "正在申请 root，请在弹出的授权框里允许…", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = RootShell.isGranted(SettingsActivity.this)
                        || RootShell.request(SettingsActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                        Toast.makeText(SettingsActivity.this,
                                ok ? "root 已授权" : "未获得 root，部分功能不可用",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "bee-root-request").start();
    }

    /** 改完模块配置必须重启宿主，hook 才会重新注入。 */
    private void restartHost() {
        if (!RootShell.isGranted(this)) {
            Toast.makeText(this, "需要 root 才能重启蜜蜂记账", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "正在重启蜜蜂记账…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = RootShell.forceStopHost();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(SettingsActivity.this,
                                ok ? "已强制停止，点击下方「打开蜜蜂记账」即可恢复"
                                        : "重启失败，请手动强停蜜蜂记账",
                                Toast.LENGTH_LONG).show();
                        hostAnswered = false;
                        hookCount = -1;
                        refreshStatus();
                    }
                });
            }
        }, "bee-restart-host").start();
    }

    private void exportLogs() {
        Toast.makeText(this, "正在导出日志…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder report = new StringBuilder();
                boolean logcatOk = RootShell.appendLogcatToDownload();
                report.append("logcat.txt：")
                        .append(logcatOk ? "Download/" + Const.LOG_DIR + "/logcat.txt"
                                : "失败（需要 root）")
                        .append('\n');
                String appOut = LogExport.copyToDownload(
                        SettingsActivity.this, Prefs.appLogPath(SettingsActivity.this), "app.log");
                report.append("app.log：").append(appOut == null ? "失败" : appOut);
                final String text = report.toString();
                Logx.i("[settings] export: " + text.replace('\n', ' '));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(SettingsActivity.this, text, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "bee-log-export").start();
    }

    /** 一次性把关键信息写进日志（只开一次 root shell，几秒内完成）。 */
    private void runDiagnostics() {
        Toast.makeText(this, "正在诊断…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Logx.i("[diag] ===== start =====");
                Logx.i("[diag] module=" + versionName()
                        + " android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT
                        + " model=" + Build.MANUFACTURER + " " + Build.MODEL);
                Logx.i("[diag] a11y=" + ShotAccessibilityService.isReady()
                        + " root=" + RootShell.isGranted(SettingsActivity.this));

                String pkg = HostInfo.pkg(SettingsActivity.this);
                RootShell.collectDiagnostics(pkg);

                ShotBridge.Answer a = ShotBridge.query(getApplicationContext(), "", 4000L);
                Logx.i("[diag] host answered=" + a.received
                        + " activityAlive=" + a.activityAlive
                        + " hooks=" + a.hookCount + "/" + a.hookFailed);
                if (a.telemetry != null) {
                    Logx.i("[diag] host telemetry: " + a.telemetry);
                }

                WidgetBridge.Data w = WidgetBridge.query(getApplicationContext(), 4000L);
                Logx.i("[diag] widget data ok=" + w.ok + " day=" + w.day
                        + " week=" + w.week + " month=" + w.month + " " + w.currency
                        + " | " + w.detail);

                Logx.i("[diag] ===== end =====");
                RootShell.appendLogcatToDownload();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                        Toast.makeText(SettingsActivity.this,
                                "诊断完成，请点「导出日志到 Download」",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "bee-diag").start();
    }

    private void requestAddWidget() {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(this);
            if (Build.VERSION.SDK_INT >= 26 && mgr.isRequestPinAppWidgetSupported()) {
                ComponentName cn = new ComponentName(this, ExpenseWidgetProvider.class);
                mgr.requestPinAppWidget(cn, null, null);
                Logx.i("[settings] requestPinAppWidget sent");
                return;
            }
            Toast.makeText(this,
                    "这个桌面不支持自动添加，请长按桌面空白处 → 添加小部件 → 找「记账支出」",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Logx.e("[settings] requestPinAppWidget failed", t);
            Toast.makeText(this,
                    "添加失败，请手动：长按桌面 → 添加小部件 → 「记账支出」",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void requestAddTile() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this,
                    "这个系统版本不支持自动添加，请手动：控制中心 → 编辑 → 找到「记账截图」拖上去",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            android.app.StatusBarManager sbm =
                    (android.app.StatusBarManager) getSystemService(Context.STATUS_BAR_SERVICE);
            if (sbm == null) {
                throw new IllegalStateException("StatusBarManager unavailable");
            }
            ComponentName tile = new ComponentName(this, ShotTileService.class);
            Executor executor = new Executor() {
                @Override
                public void execute(Runnable command) {
                    new Handler(Looper.getMainLooper()).post(command);
                }
            };
            java.util.function.Consumer<Integer> callback =
                    new java.util.function.Consumer<Integer>() {
                        @Override
                        public void accept(Integer resultCode) {
                            Logx.i("[settings] requestAddTileService result=" + resultCode);
                        }
                    };
            sbm.requestAddTileService(tile, "记账截图",
                    Icon.createWithResource(this, android.R.drawable.ic_menu_camera),
                    executor, callback);
        } catch (Throwable t) {
            Logx.e("[settings] requestAddTileService failed", t);
            Toast.makeText(this,
                    "自动添加失败，请手动：控制中心 → 编辑 → 找到「记账截图」拖上去",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ 界面小工具

    private void heading(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16f);
        tv.setPadding(0, dp(20), 0, dp(4));
        root.addView(tv);
    }

    private void body(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13.5f);
        tv.setLineSpacing(dp(3), 1f);
        tv.setTextColor(Color.parseColor("#444441"));
        root.addView(tv);
    }

    private void button(LinearLayout root, String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(b, lp);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
