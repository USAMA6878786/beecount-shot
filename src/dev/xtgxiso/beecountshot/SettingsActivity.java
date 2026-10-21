package dev.xtgxiso.beecountshot;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
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
    /** 状态卡第二行：一行冰冷的事实（root / hook / 无障碍 / 监听）。 */
    private TextView statusFacts;

    /** 主动重启宿主的窗口期。这期间状态显示"正在重启"而不是"连不上"。 */
    private volatile long restartingUntil = 0L;

    /** 来自宿主的实时状态（后台查询后刷新）。 */
    private volatile boolean hostAnswered = false;
    private volatile boolean activityAlive = false;
    private volatile int hookCount = -1;
    private volatile int hookFailed = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("记账截图");
        Logx.init(Prefs.appLogPath(this));
        Logx.i("[settings] opened, module " + versionName());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BACKGROUND);
        scroll.setFitsSystemWindows(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, Ui.PAGE_PAD);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        // ---------------- 标题 ----------------
        root.addView(Ui.title(this, "记账截图"), Ui.lp(-1, -2, 0, 4, 0, 2));
        root.addView(Ui.caption(this, "v" + versionName() + " · 点磁贴才记账"),
                Ui.lp(-1, -2, 0, 0, 0, 14));

        // ---------------- 状态 ----------------
        LinearLayout statusCard = Ui.card(this, root, null);
        statusView = new TextView(this);
        statusView.setTextSize(14.5f);
        statusView.setLineSpacing(Ui.dp(this, 3), 1f);
        statusCard.addView(statusView);
        statusFacts = Ui.caption(this, "");
        statusCard.addView(statusFacts, Ui.lp(-1, -2, 0, 4, 0, 0));

        LinearLayout statusActions = new LinearLayout(this);
        statusActions.setOrientation(LinearLayout.HORIZONTAL);
        TextView bRestart = Ui.filledButton(this, "重启蜜蜂记账", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartHost();
            }
        });
        TextView bRoot = Ui.tonalButton(this, "root 权限", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestRoot();
            }
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statusActions.addView(bRestart, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.leftMargin = Ui.dp(this, 8);
        statusActions.addView(bRoot, half2);
        statusCard.addView(statusActions, Ui.lp(-1, -2, 0, 12, 0, 0));
        // 先渲染一次，避免探测回调到达前状态卡是空白
        refreshStatus();

        // 让磁贴尽早重新绑定（打开设置页是个自然时机）
        ShotTileService.requestRefresh(this);

        // 把宿主加进电池优化白名单：唯一能"让它活得久一点"的温和手段。
        // 只做一次（成功过一次就不再重复开 su）。
        if (!Prefs.hostWhitelisted(this)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String pkg = HostInfo.pkg(SettingsActivity.this);
                    if (pkg != null && RootShell.whitelistHost(pkg)) {
                        Prefs.setHostWhitelisted(SettingsActivity.this, true);
                    }
                }
            }, "bee-whitelist-host").start();
        }

        // 无障碍没开就用 root 帮用户打开（它是第二截屏路径）
        if (!ShotAccessibilityService.isReady()) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    RootShell.ensureAccessibilityEnabled();
                    ShotTileService.requestRefresh(SettingsActivity.this);
                }
            }, "bee-ensure-a11y").start();
        }

        // 先把通知渠道建出来，否则系统设置里"允许通知"是灰的
        KeepAliveService.ensureChannel(this);

        // 后台 Toast 需要悬浮窗权限，顺手授一次
        new Thread(new Runnable() {
            @Override
            public void run() {
                ShotToast.ensureOverlayAllowed(SettingsActivity.this);
            }
        }, "bee-overlay-grant").start();

        // ---------------- 磁贴 ----------------
        LinearLayout tileCard = Ui.card(this, root, null);
        LinearLayout tileRow = Ui.row(this);
        LinearLayout tileLeft = new LinearLayout(this);
        tileLeft.setOrientation(LinearLayout.VERTICAL);
        tileLeft.addView(Ui.rowTitle(this, "控制中心磁贴"));
        tileLeft.addView(Ui.caption(this, "收起面板 → 截屏 → 交给蜜蜂记账"));
        tileRow.addView(tileLeft, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tileRow.addView(Ui.filledButton(this, "添加", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestAddTile();
            }
        }));
        tileCard.addView(tileRow);
        if (ShotAccessibilityService.isReady()) {
            tileCard.addView(Ui.caption(this, "截屏走 root 抓帧；无障碍已开启，作为第二条路径"),
                    Ui.lp(-1, -2, 0, 6, 0, 0));
        }

        // ---------------- 行为 ----------------
        LinearLayout behaviorCard = Ui.card(this, root, "行为");

        Ui.switchRow(this, behaviorCard, "常驻运行", "进程不被回收，点磁贴立刻响应",
                Prefs.keepAlive(this), new Ui.OnToggle() {
                    @Override
                    public void onToggle(boolean on) {
                        Prefs.setKeepAlive(SettingsActivity.this, on);
                        Logx.i("[settings] keep-alive set to " + on);
                        if (on) {
                            KeepAliveService.ensureChannel(SettingsActivity.this);
                            KeepAliveService.start(SettingsActivity.this);
                            requestNotifPermissionIfNeeded();
                        } else {
                            KeepAliveService.stop(SettingsActivity.this);
                        }
                    }
                });

        Ui.switchRow(this, behaviorCard, "识别后删除截图", "蜜蜂记账读完就清理相册",
                Prefs.autoDelete(this), new Ui.OnToggle() {
                    @Override
                    public void onToggle(boolean on) {
                        Prefs.setAutoDelete(SettingsActivity.this, on);
                        Logx.i("[settings] auto-delete set to " + on);
                    }
                });


        // ---------------- 提速 ----------------
        LinearLayout speedCard = Ui.card(this, root, "提速");
        // 相册监听不再是开关：有权限就用快路，没权限自动退回扫描。
        // 权限只问一次（问过一次就不再打扰），因为用户根本无从判断这个开关该开该关。
        final boolean hasMedia = ShotWatcher.hasPermission(this);
        if (hasMedia) {
            ShotWatcher.ensureRegistered(this);
        } else if (!Prefs.mediaAsked(this)) {
            Prefs.setMediaAsked(this, true);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    requestMediaPermissionIfNeeded();
                }
            }, 600L);
        }

        TextView bColor = Ui.tonalButton(this, "颜色校准", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, ColorProbeActivity.class));
            }
        });
        speedCard.addView(bColor, Ui.buttonLp(this, false));

        TextView bProbe = Ui.outlinedButton(this, "检查截屏链路", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runScreencapProbe();
            }
        });
        speedCard.addView(bProbe, Ui.buttonLp(this, false));

        // ---------------- 小组件 ----------------
        LinearLayout widgetCard = Ui.card(this, root, "桌面小组件");
        final TextView alphaLabel = Ui.rowTitle(this, "背景不透明度  85%");
        widgetCard.addView(alphaLabel);
        SeekBar alphaBar = new SeekBar(this);
        Ui.tintSeekBar(this, alphaBar);
        alphaBar.setMax(100);
        alphaBar.setProgress(Math.round(WidgetPrefs.defaultAlpha(this) * 100f / 255f));
        alphaLabel.setText("背景不透明度  " + alphaBar.getProgress() + "%");
        alphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                alphaLabel.setText("背景不透明度  " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                WidgetPrefs.setDefaultAlphaForAll(SettingsActivity.this,
                        Math.round(sb.getProgress() * 255f / 100f));
                ExpenseWidgetProvider.refreshAll(getApplicationContext());
            }
        });
        widgetCard.addView(alphaBar, Ui.lp(-1, -2, 0, 8, 0, 0));
        widgetCard.addView(Ui.caption(this, "只改某一个小部件：长按桌面上的它 → 重新配置"),
                Ui.lp(-1, -2, 0, 4, 0, 0));

        // ---------------- 帮助 ----------------
        LinearLayout helpCard = Ui.card(this, root, "帮助");
        Ui.listItem(this, helpCard, "使用说明", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showText("使用说明", GUIDE);
            }
        });
        Ui.listItem(this, helpCard, "常见问题", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showText("常见问题", FAQ);
            }
        });
        Ui.listItem(this, helpCard, "导出日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLogs();
            }
        });
        Ui.listItem(this, helpCard, "跑一次诊断", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDiagnostics();
            }
        });

        // 打开后立刻探一次宿主状态
        probeHost();
    }

    /** 统一的文本弹窗（把长说明从页面上挪走，保持界面清爽）。 */
    private void showText(String title, String text) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 使用说明（点「使用说明」才看，不再占页面）。 */
    private static final String GUIDE =
            "前提\n"
            + "• LSPosed 里启用本模块，作用域勾选「蜜蜂记账」\n"
            + "• 给本模块 root（截屏、收面板、删图都要用）\n"
            + "• 进一次蜜蜂记账的「智能记账」，确认截图监听已开启\n\n"
            + "截屏是三级降级：\n"
            + "① root 抓帧（主路径，约 0.25 秒）\n"
            + "② 无障碍直调系统截屏\n"
            + "③ root 模拟按键（兜底）\n\n"
            + "整个过程：收起面板 → 等 0.15 秒 → 截屏 → 弹「截图成功」→ 蜜蜂记账识别 → 识别完成后删图。\n\n"
            + "从点击到删图实测约 10 秒，大头在蜜蜂记账自己的检测与 AI 识别（3~5 秒）。";

    /** 常见问题。 */
    private static final String FAQ =
            "点了磁贴没反应？\n"
            + "① 看顶部状态和磁贴副标题；② root 是否授权；"
            + "③ 蜜蜂记账的截图监听是否开着（进「智能记账」确认）。\n\n"
            + "改了配置不生效？\n"
            + "hook 只在蜜蜂记账启动时注入，点「重启蜜蜂记账」。\n\n"
            + "截到的图里带控制中心？\n"
            + "说明这台机器收面板比 0.15 秒慢，把 Const.SHOT_SETTLE_MS 调大。\n\n"
            + "小部件数字是灰色的？\n"
            + "蜜蜂记账进程没在跑，显示的是上次的值；打开一次它即可。\n\n"
            + "能不用打开蜜蜂记账就自动记账吗？\n"
            + "做不到。它的截图监听挂在界面上，界面被回收就得重新打开一次。"
            + "（推消息、分享、深链三条路都实测证否。）";

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
                        // 还在重启窗口里、又没答复 -> 过一会儿自己再来一次，
                        // 免得用户盯着"正在重启"不动。
                        if (!hostAnswered && System.currentTimeMillis() < restartingUntil) {
                            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    probeHost();
                                }
                            }, 1800L);
                        }
                    }
                });
            }
        }, "bee-status-probe").start();
    }

    /**
     * 刷新顶部状态。
     *
     * <p>只给结论 + 一行事实，不再堆三行解释——细节都进日志和「常见问题」。
     * 状态点用 M3 语义色：就绪绿、部分可用琥珀、未就绪红。
     */
    private void refreshStatus() {
        if (statusView == null) {
            return;
        }
        boolean a11y = ShotAccessibilityService.isReady();
        boolean root = RootShell.isGranted(this);

        String head;
        int color;
        String hint = null;
        if (!root) {
            head = "缺少 root";
            color = Ui.ERROR;
            hint = "点下面「root 权限」授权后才能截屏";
        } else if (System.currentTimeMillis() < restartingUntil) {
            head = "正在重启蜜蜂记账";
            color = Ui.WARN;
            hint = "几秒后自动刷新";
        } else if (!hostAnswered) {
            head = "未连上蜜蜂记账";
            color = Ui.ERROR;
            hint = "确认 LSPosed 作用域勾了它，再重启一次";
        } else if (!activityAlive) {
            head = "部分可用";
            color = Ui.WARN;
            hint = "截图监听不在位，打开一次蜜蜂记账";
        } else {
            head = "就绪";
            color = Ui.OK;
        }

        statusView.setText("● " + head + (hint == null ? "" : "\n" + hint));
        statusView.setTextColor(color);

        if (statusFacts != null) {
            StringBuilder f = new StringBuilder();
            f.append("root ").append(root ? "✓" : "✗");
            f.append(" · hook ").append(hookCount >= 0 ? String.valueOf(hookCount) : "?");
            if (hookFailed > 0) {
                f.append("（失败 ").append(hookFailed).append("）");
            }
            f.append(" · 无障碍 ").append(a11y ? "✓" : "—");
            f.append(" · 监听 ").append(activityAlive ? "✓" : "✗");
            statusFacts.setText(f.toString());
        }
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

    /**
     * 申请 / 重新检测 root。
     *
     * <h3>为什么要分情况说</h3>
     * 原来不管有没有授权，先弹一句"请在弹出的授权框里允许…"。
     * 但 root **已经授权**时 {@code su} 直接成功，压根不会有弹窗 ——
     * 用户（尤其 KernelSU / APatch，有些配置下只在通知栏提示、不弹窗）会以为按钮没反应。
     *
     * <p>现在：已授权就直接说"已授权"；没授权才提示去管理器里看，
     * 并且能一键打开 root 管理器。
     */
    private void requestRoot() {
        if (RootShell.isGranted(this)) {
            showToast("root 已授权");
            refreshStatus();
            return;
        }
        final String mgr = rootManagerLabel();
        Toast.makeText(this, "正在请求 root…（若没看到弹窗，请到 " + mgr + " 里授权）",
                Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = RootShell.request(SettingsActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                        if (ok) {
                            showToast("root 已授权");
                        } else {
                            showRootDenied(mgr);
                        }
                    }
                });
            }
        }, "bee-root-request").start();
    }

    /**
     * 没拿到 root 时的说明。
     *
     * <p>不写"请在弹出的框里允许"——KernelSU / APatch 在部分配置下**不弹窗**，
     * 只往通知栏丢一条，甚至完全静默。所以直接告诉用户去管理器里点。
     */
    private void showRootDenied(String mgr) {
        final String pkg = rootManagerPackage();
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("还没拿到 root")
                .setMessage("请打开「" + mgr + "」，在「超级用户 / 权限」列表里找到"
                        + "「记账截图」并允许。\n\n"
                        + "有些管理器（KernelSU / APatch）在这种情况下不弹窗，"
                        + "只在通知栏提示一句，或者需要你自己去列表里加。\n\n"
                        + "授权完回到这里点一次「root 权限」即可重新检测。")
                .setPositiveButton("知道了", null);
        if (pkg != null) {
            b.setNeutralButton("打开 " + mgr, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    openRootManager(pkg);
                }
            });
        }
        b.show();
    }

    /** root 管理器的展示名（按已安装的包判断）。 */
    private String rootManagerLabel() {
        String pkg = rootManagerPackage();
        if ("me.weishu.kernelsu".equals(pkg)) {
            return "KernelSU";
        }
        if ("com.topjohnwu.magisk".equals(pkg)) {
            return "Magisk";
        }
        if ("me.bmax.apatch".equals(pkg)) {
            return "APatch";
        }
        return "root 管理器";
    }

    /** 已安装的 root 管理器包名（没装则 null）。 */
    private String rootManagerPackage() {
        String[] cands = {"me.weishu.kernelsu", "com.topjohnwu.magisk", "me.bmax.apatch"};
        for (String p : cands) {
            try {
                getPackageManager().getPackageInfo(p, 0);
                return p;
            } catch (Throwable ignored) {
                // 没装，继续看下一个
            }
        }
        return null;
    }

    /** 打开 root 管理器（用它的启动入口，不硬编码 Activity 名）。 */
    private void openRootManager(String pkg) {
        try {
            android.content.Intent it = getPackageManager().getLaunchIntentForPackage(pkg);
            if (it == null) {
                showToast("打不开 " + pkg);
                return;
            }
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Throwable t) {
            Logx.e("[settings] open root manager failed", t);
            showToast("打开失败：" + t.getMessage());
        }
    }

    /**
     * 一键重启蜜蜂记账：强制停止（需要 root）→ 稍等 → 重新打开。
     *
     * <p>以前这里只做强制停止，用户还得再点一次「打开蜜蜂记账」才能用——"重启"这个词
     * 本来就该包含重新打开，所以合并成一个按钮。
     */
    private void restartHost() {
        final boolean root = RootShell.isGranted(this);
        if (!root) {
            // 没有 root 就退化成"帮你打开它"，至少不用自己找图标
            Logx.w("[settings] no root, only launching host");
            Toast.makeText(this, "未授权 root，只能帮你打开蜜蜂记账（无法强制停止）",
                    Toast.LENGTH_LONG).show();
            openHost();
            return;
        }
        Toast.makeText(this, "正在重启蜜蜂记账…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean stopped = RootShell.forceStopHost();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!stopped) {
                            Toast.makeText(SettingsActivity.this,
                                    "强制停止失败，改为直接打开蜜蜂记账", Toast.LENGTH_LONG).show();
                        }
                        hostAnswered = false;
                        hookCount = -1;
                        // 强制停止后宿主进程确实没了，但这是"我们主动干的"，
                        // 不能报成"连不上"——给它一个重启窗口，期间显示"正在重启"。
                        restartingUntil = System.currentTimeMillis() + 8000L;
                        refreshStatus();
                        // 等强停真正落地再启动，否则可能被系统忽略
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                openHost();
                                if (stopped) {
                                    Logx.i("[settings] host restarted");
                                }
                                // 它起来还需要一点时间，过几秒自动再探一次
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        probeHost();
                                    }
                                }, 2500L);
                            }
                        }, 700L);
                    }
                });
            }
        }, "bee-restart-host").start();

        // 重启宿主之后磁贴的状态也要跟着重算一次（副标题里带着"截图监听是否在位"）。
        ShotTileService.requestRefresh(this);
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

    // v2.20 删除：requestAddWidget() / showManualAddHint()
    //
    // 它们调的是 AppWidgetManager.requestPinAppWidget。小米桌面会返回"支持"，
    // 但点了**完全没反应**；而 root 也没有接口能把小组件摆到桌面（那是桌面应用自己的
    // AppWidgetHost 的职责，`cmd appwidget` 只管绑定权限）。留一个点了没反应的按钮
    // 比不给更糟，所以按钮和这两个方法一起删掉，改成在正文里直接写清楚手动步骤。

    /**
     * 请系统把磁贴加到控制中心。
     *
     * <h3>为什么以前"点了没反应"</h3>
     * 系统接口 {@code requestAddTileService} 有几种返回：已添加 / 新增成功 / 被取消 / 未添加。
     * 原实现**只写日志**，界面上什么都不显示——磁贴早就加过的时候，系统连弹窗都不会给，
     * 用户看到的就是一个"死按钮"。
     *
     * <p>现在：每种结果都明确告诉用户；并且加一个看门狗——有些 ROM 压根不回调，
     * 1.2 秒没动静就直接给出手动添加的步骤。
     */
    private void requestAddTile() {
        if (Build.VERSION.SDK_INT < 33) {
            showManualAddHint("这个系统版本不支持自动添加");
            return;
        }
        final boolean[] answered = new boolean[]{false};
        try {
            android.app.StatusBarManager sbm =
                    (android.app.StatusBarManager) getSystemService(Context.STATUS_BAR_SERVICE);
            if (sbm == null) {
                showManualAddHint("系统没有提供 StatusBarManager");
                return;
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
                        public void accept(Integer code) {
                            answered[0] = true;
                            int c = code == null ? -1 : code;
                            Logx.i("[settings] requestAddTileService result=" + c);
                            if (c == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                                showToast("已加到控制中心");
                            } else if (c == android.app.StatusBarManager
                                    .TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                                showToast("磁贴已经在控制中心里了，下拉就能看到");
                            } else {
                                // 注意：Android 只定义了两个"结果"码（已添加 / 新增成功），
                                // 其余都是 TILE_ADD_REQUEST_ERROR_* 之类的失败码。
                                showManualAddHint("系统没有自动添加（返回码 " + c + "）");
                            }
                        }
                    };
            sbm.requestAddTileService(tile, "记账截图",
                    Icon.createWithResource(this, android.R.drawable.ic_menu_camera),
                    executor, callback);

            // 看门狗：部分 ROM 不回调，不能让用户对着没反应的按钮发呆
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!answered[0]) {
                        Logx.w("[settings] requestAddTileService 无回调，给手动指引");
                        showManualAddHint("系统没有响应添加请求");
                    }
                }
            }, 1200L);
        } catch (Throwable t) {
            Logx.e("[settings] requestAddTileService failed", t);
            showManualAddHint("自动添加失败：" + t.getMessage());
        }
    }

    /** 手动添加磁贴的步骤（自动添加不可用时兜底）。 */
    private void showManualAddHint(String why) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("手动添加磁贴")
                .setMessage(why + "\n\n请按这个步骤手动加：\n"
                        + "① 下拉控制中心 → 点右上角「编辑」（铅笔图标）\n"
                        + "② 在下方「可用磁贴 / 未添加」里找到「记账截图」\n"
                        + "③ 把它拖到上面 → 点完成\n\n"
                        + "加过一次之后就不用再加了。")
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 申请「读取照片」权限的请求码（跟通知权限的 1001 区分开）。 */
    private static final int REQ_MEDIA = 1002;

    /** 申请「读取照片」权限（用于 MediaStore 快路）。拒绝不影响功能，只是退回 su 轮询。 */
    private void requestMediaPermissionIfNeeded() {
        try {
            String perm = Build.VERSION.SDK_INT >= 33
                    ? "android.permission.READ_MEDIA_IMAGES"
                    : "android.permission.READ_EXTERNAL_STORAGE";
            if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ShotWatcher.ensureRegistered(this);
                showToast("相册监听已开启");
                return;
            }
            requestPermissions(new String[]{perm}, REQ_MEDIA);
        } catch (Throwable t) {
            Logx.w("[settings] request media permission failed: " + t.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(code, perms, granted);
        if (code == REQ_MEDIA) {
            boolean ok = granted.length > 0
                    && granted[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            Logx.i("[settings] media permission granted=" + ok);
            if (ok) {
                ShotWatcher.ensureRegistered(this);
                showToast("相册监听已开启，截屏确认会更快");
            } else {
                showToast("没给权限也没关系，会自动退回原来的方式");
            }
            recreate();
        } else if (code == KeepAliveService.REQ_NOTIF) {
            recreate();
        }
    }

    private void showToast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 跑一次 screencap 验证（子线程，完成后弹框）。 */
    private void runScreencapProbe() {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在验证 screencap…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String report = ScreencapProbe.run(SettingsActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Throwable ignored) {
                        }
                        new android.app.AlertDialog.Builder(SettingsActivity.this)
                                .setTitle("screencap 验证结果")
                                .setMessage(report)
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            }
        }, "bee-screencap-probe").start();
    }

    /**
     * Android 13+ 运行时申请通知权限。
     *
     * <p>只申请一次，而且**拒绝完全不影响常驻**——按系统规定，用户拒绝之后前台服务的提示
     * 只是不再出现在通知栏（只在「任务管理器」里可见）。所以这里是"要不要看见那条提示"
     * 的选择题。
     */
    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33 || KeepAliveService.hasNotifPermission(this)) {
            return;
        }
        if (Prefs.notifAsked(this)) {
            return; // 问过一次就别再烦人，让用户自己去系统设置里开
        }
        Prefs.setNotifAsked(this, true);
        try {
            requestPermissions(
                    new String[]{"android.permission.POST_NOTIFICATIONS"},
                    KeepAliveService.REQ_NOTIF);
        } catch (Throwable t) {
            Logx.w("[settings] request notif permission failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------ 界面小工具

}
