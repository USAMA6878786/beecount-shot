package dev.xtgxiso.beecountshot;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
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
 *
 * <p>页面**不展示自检结果**。走 headless 之后「进程在不在、hook 装了几条、监听在不在位」
 * 都已经不影响能不能记账（点磁贴时自己拉起进程、自己起引擎派发），把这几项摆在最上面
 * 只会让用户去追一个不影响结果的红点。真正需要用户动手的只有 root，而没 root 时
 * 一按磁贴就会有提示，不需要靠首页状态去提醒。
 */
public class SettingsActivity extends Activity {

    /** 主动重启宿主的窗口期。这期间会反复重探宿主，直到它答复。 */
    private volatile long restartingUntil = 0L;

    /** 上一次探测宿主有没有答复（只用于"重启窗口期内继续重探"这一段逻辑）。 */
    private volatile boolean hostAnswered = false;

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

        // ---------------- 操作 ----------------
        // 这里原来是一张"自检"状态卡：● 就绪 / root ✓ · 无障碍 ✓。
        // 现在整块展示去掉，只留两个会真正改变状态的动作按钮。
        LinearLayout actionCard = Ui.card(this, root, null);
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
        actionCard.addView(statusActions);

        // 让磁贴尽早重新绑定（打开设置页是个自然时机）
        ShotTileService.requestRefresh(this);

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
        tileCard.addView(Ui.caption(this, "截屏走 root 直接抓帧，约 0.25 秒"),
                Ui.lp(-1, -2, 0, 6, 0, 0));

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
                            askNotificationPermission(false);
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

        // 原来的「颜色校准」「检查截屏链路」两个按钮合并成这一个开关：
        // 打开时自动跑一次真抓帧 + 真压缩的自检（抓不到屏/压不动都会直说），
        // 跑通之后每张截图都先压再交出去。关掉就完全按原图。
        Ui.switchRow(this, speedCard, "图片压缩",
                "截图先压成 JPEG 再交给蜜蜂记账，上传识别更快",
                Prefs.compress(this), new Ui.OnToggle() {
                    @Override
                    public void onToggle(boolean on) {
                        Prefs.setCompress(SettingsActivity.this, on);
                        Logx.i("[settings] compress set to " + on);
                        if (on) {
                            runCompressCheck();
                        }
                    }
                });

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
        Ui.listItem(this, helpCard, "检查更新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate();
            }
        });
        // 首次打开就申请通知权限（没有它，截图成功/失败的 Toast 会被系统吞掉）。
        // 「帮助」里不再放那一行状态：授权本身只在第一次打开时需要，之后它在不在
        // 都不影响截图和记账，摆在页面上只会让人去研究一个不影响结果的开关。
        askNotificationPermission(true);

        // 压缩默认是开的，但"校准 raw 通道"挂在"打开开关"这个动作上——
        // 默认开就意味着新装用户永远不会触发它。所以这里补一次（只跑一次，静默）。
        maybeInitCompress();

        // 打开后立刻探一次宿主状态
        probeHost();
    }

    /**
     * 首次打开的收尾：如果「图片压缩」开着但 raw 通道从没校准过，**静默**校准一次。
     *
     * <p>为什么需要它：{@link Prefs#compress} 默认 true，用户不会去拨这个开关，
     * 于是 {@link #runCompressCheck()} 永远不触发，`rawSwap` 一直是 -1，
     * 截屏就一直走 PNG 抓帧（照样压缩、照样记账，只是每次抓帧多花约 0.2 秒）。
     *
     * <p>这里只做校准、不弹框：刚装完模块的人不需要看一份"检测报告"；
     * 真要看，他自己关一次再打开开关就会走到 {@link #runCompressCheck()}。
     * 没 root 时**不置位**，下次打开设置页再试。
     */
    private void maybeInitCompress() {
        if (!Prefs.compress(this) || Prefs.compressInited(this)) {
            return;
        }
        if (Prefs.rawSwap(this) >= 0) {
            // 已经校准过（老用户升级上来），直接记为已初始化
            Prefs.setCompressInited(this, true);
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (!RootShell.isGranted(SettingsActivity.this)) {
                            Logx.i("[settings] 压缩首次初始化：还没 root，等下次打开再试");
                            return;   // 不置位，下次再试
                        }
                        RawCalibrate.Result r = RawCalibrate.calibrate(SettingsActivity.this);
                        Logx.i("[settings] 压缩首次初始化：" + r.verdict
                                + (r.detail.length() > 0 ? "｜" + r.detail : ""));
                        if (r.decided) {
                            Prefs.setCompressInited(SettingsActivity.this, true);
                            ShotTileService.requestRefresh(SettingsActivity.this);
                        }
                    }
                }, "bee-compress-init").start();
            }
        }, 1800L);   // 等页面入场动画走完再抓帧：否则参照图和 raw 不是同一画面
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
            + "• 给本模块 root（截屏、收面板、删图都要用）——蜜蜂记账本体不需要 root\n"
            + "• 打开一次蜜蜂记账，进「智能记账」确认「截图自动记账」是开着的\n"
            + "• 点上面的「添加」，把「记账截图」磁贴放进控制中心\n\n"
            + "截屏只有一条路：root 直接抓帧（约 0.25 秒）；抓帧失败才退回 root 注入按键。\n\n"
            + "整个过程：收起面板 → 等 0.1 秒 → 截屏 → 弹「截图成功」→ 蜜蜂记账识别 → 识别完成后删图。\n\n"
            + "从点击到删图实测约 4~7 秒，大头在蜜蜂记账自己的检测与 AI 识别（3~5 秒）。";

    /** 常见问题。 */
    private static final String FAQ =
            "点了磁贴没反应？\n"
            + "① 点「root 权限」看是否已授权；② 有没有看到「截图成功 / 失败」的提示"
            + "（没给通知权限时这个提示会被系统吞掉，去系统设置里给「记账截图」打开通知即可）；"
            + "③ 蜜蜂记账的「截图自动记账」是否开着（进「智能记账」确认）。\n\n"
            + "偶尔提示「识别失败」？\n"
            + "蜜蜂记账调 AI 时网络连接被打断（多见于会冻结后台应用的机型）。"
            + "本模块会实测本机冻结节奏并对齐解冻时机，把打断窗口从几秒压到约 0.2 秒；"
            + "单点基本不出现，极限连点仍可能偶发。把蜜蜂记账的省电策略设成「无限制」可进一步降低。\n\n"
            + "改了配置不生效？\n"
            + "hook 只在蜜蜂记账启动时注入，点「重启蜜蜂记账」。\n\n"
            + "截到的图里带控制中心？\n"
            + "说明这台机器收面板特别慢。这个等待是编译期常量（Const.SHOT_SETTLE_MS），"
            + "要改得重新打包——把日志发我即可。\n\n"
            + "小部件数字是灰色的？\n"
            + "蜜蜂记账进程没在跑，显示的是上次的值；打开一次它即可。\n\n"
            + "要打开蜜蜂记账才能记账吗？\n"
            + "不用。点磁贴时模块会自己把它的进程拉起来、起一个**无界面引擎**把截图送进去识别，"
            + "全程不显示它的界面、也不会把它弹到前台。";

    @Override
    protected void onResume() {
        super.onResume();
        probeHost();
    }

    // ------------------------------------------------------------ 宿主

    /**
     * 问一次宿主还在不在；不在就顺手把它叫回来。
     *
     * <p>这个结果**不上屏**了（页面不再展示自检），它只干一件事：
     * 宿主进程没在跑时，用小组件的广播把进程拉起来——这样桌面小组件的数字是实时的，
     * 点磁贴也不用再等一次冷启动。整个过程不显示任何界面。
     */
    private void probeHost() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HostInfo.pkg(SettingsActivity.this);
                    ShotBridge.Answer a = ShotBridge.query(
                            getApplicationContext(), "", 3000L);
                    hostAnswered = a.received;
                    if (!a.received && HostLauncher.pullUp(SettingsActivity.this)) {
                        // 宿主进程不在 —— 用它的"桌面小组件 provider"广播叫回来。
                        // 这不显示任何界面，只是让进程活着（小组件恢复实时数字）。
                        try {
                            Thread.sleep(2500L);
                        } catch (InterruptedException ignored) {
                        }
                        ShotBridge.Answer a2 = ShotBridge.query(
                                getApplicationContext(), "", 3000L);
                        if (a2.received) {
                            hostAnswered = true;
                            Logx.i("[launch] 进程已被广播拉起");
                        }
                    }
                } catch (Throwable ignored) {
                }
                // 还在重启窗口里、又没答复 -> 过一会儿自己再来一次
                if (!hostAnswered && System.currentTimeMillis() < restartingUntil) {
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            probeHost();
                        }
                    }, 1800L);
                }
            }
        }, "bee-host-probe").start();
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
                        // 强制停止后宿主进程确实没了，但这是"我们主动干的"，
                        // 给它一个重启窗口，这期间探测会自己重试，而不是一次问不到就放弃。
                        restartingUntil = System.currentTimeMillis() + 8000L;
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

        // 重启宿主后请系统重新绑定一次磁贴：覆盖安装/强停之后磁贴容易"被晾着"（点了没反应）。
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

    /**
     * 「检查更新」：问一次 GitHub 上的最新发布，比版本号。
     *
     * <p>两条通道（API / update.json）见 {@link UpdateCheck}；这里只负责"后台问 → 前台说"。
     * 不自动检查，只在用户点的时候查一次——模块平时不联网，这一点别破坏。
     */
    private void checkUpdate() {
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final UpdateCheck.Info info = UpdateCheck.fetch();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showUpdateResult(info);
                    }
                });
            }
        }, "bee-update-check").start();
    }

    /** 把检查结果说清楚：有新版本给下载入口，没有就一句话。 */
    private void showUpdateResult(final UpdateCheck.Info info) {
        final String cur = UpdateCheck.current();
        if (!info.ok) {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                    .setTitle("检查失败")
                    .setMessage("没拿到最新版本信息：\n" + info.error
                            + "\n\n当前版本 v" + cur + "。稍后再试，或者直接去发布页看。")
                    .setPositiveButton("知道了", null);
            b.setNeutralButton("打开发布页", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    openUrl("https://github.com/USAMA6878786/beecount-shot/releases");
                }
            });
            b.show();
            return;
        }

        if (UpdateCheck.compare(info.latest, cur) > 0) {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                    .setTitle("发现新版本 v" + info.latest)
                    .setMessage("当前 v" + cur + "，远端 v" + info.latest + "。\n\n"
                            + "签名是一致的，下载后直接覆盖安装即可，不用卸载"
                            + "（卸载会清掉 root 授权和 LSPosed 作用域）。")
                    .setPositiveButton("下载 APK", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which) {
                            openUrl(info.apkUrl);
                        }
                    })
                    .setNegativeButton("以后再说", null);
            if (info.pageUrl != null) {
                b.setNeutralButton("更新说明", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        openUrl(info.pageUrl);
                    }
                });
            }
            b.show();
            return;
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("已是最新版本")
                .setMessage("当前 v" + cur + "，远端 v" + info.latest + "。")
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 用浏览器打开链接（下载 APK / 看说明都用它，不在应用内做下载器）。 */
    private void openUrl(String url) {
        if (url == null || url.length() == 0) {
            showToast("没有可用的下载地址");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Logx.w("[update] 打不开链接：" + t.getMessage());
            showToast("打不开链接：" + t.getMessage());
        }
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
                Logx.i("[diag] root=" + RootShell.isGranted(SettingsActivity.this));

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

    /** 自动颜色校准页的请求码（1001 通知权限、1002 相册权限，接着往后排）。 */
    private static final int REQ_COLOR = 1003;

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
            boolean ok = granted.length > 0
                    && granted[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            Logx.i("[settings] notif permission granted=" + ok);
            if (!ok) {
                showNotifDeniedDialog();
            }
        }
    }

    private void showToast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /**
     * 「图片压缩」打开时的必要检测：**抓屏 + 压缩 + 颜色通道校准**，一次跑完。
     *
     * <p>为什么必须真抓：压缩本身不会失败，**抓屏会**（没 root、ROM 屏蔽 screencap、
     * 安全窗口）。用户打开这个开关的动机是"让上传快点"，如果链路根本不通，
     * 开关摆在那里就是骗人的——所以抓不到屏就直接把它拨回关闭，并说明原因。
     *
     * <p>为什么顺带校准颜色：压得再小，也快不过 raw 抓帧那条路（PNG 走 DEFLATE，
     * 复杂画面能到 3MB / 900ms；raw 只要 200ms）。而 raw 出来的像素通道顺序
     * 因 ROM 而异，不校准就**不敢用**。这两件事本来就是同一个诉求（快）。
     *
     * <p>校准是**静默**的（见 {@link RawCalibrate}）：只多抓一帧 PNG 当参照物，
     * 在当前屏幕上逐点比对，不跳页面、不闪色块。只有在画面太素判不出来时，
     * 才由用户自己决定要不要用"四色屏"那种精确办法补一次。
     */
    private void runCompressCheck() {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在检测抓屏与压缩…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ImageCompress.Check check =
                        ImageCompress.check(SettingsActivity.this);
                // 抓屏都不通的话，校色也没有意义，直接跳过
                final RawCalibrate.Result calib =
                        check.captureOk ? RawCalibrate.calibrate(SettingsActivity.this) : null;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Throwable ignored) {
                        }
                        if (!check.captureOk) {
                            new android.app.AlertDialog.Builder(SettingsActivity.this)
                                    .setTitle("压缩没能开启")
                                    .setMessage(check.text)
                                    .setPositiveButton("知道了", null)
                                    .show();
                            // 抓屏都不通，压缩开着毫无意义：拨回关闭，别留一个假开关。
                            Prefs.setCompress(SettingsActivity.this, false);
                            recreate();
                            return;
                        }
                        pendingCheck = check;
                        showCheckResult(calib == null ? "跳过" : calib.verdict,
                                calib == null || !calib.decided);
                    }
                });
            }
        }, "bee-compress-check").start();
    }

    /** 校验阶段的结果先存着，等颜色校准回来一起展示。 */
    private ImageCompress.Check pendingCheck;

    /**
     * 四色屏精确校准（**只在静默校准判不出来时**由用户点按钮触发）。
     *
     * <p>色块法的判别力最干净（纯色，错的那个差 255），代价是要看两秒满屏色块。
     * 所以它退居"补一刀"的位置，不再默认弹出来。
     */
    private void startColorCalibration() {
        try {
            Intent i = new Intent(this, ColorProbeActivity.class);
            i.putExtra(ColorProbeActivity.EXTRA_AUTO, true);
            startActivityForResult(i, REQ_COLOR);
        } catch (Throwable t) {
            Logx.w("[settings] 拉起颜色校准失败：" + t.getMessage());
            showCheckResult("精确校准没跑起来（" + t.getMessage() + "）", false);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_COLOR) {
            return;
        }
        String verdict = null;
        if (result == RESULT_OK && data != null) {
            verdict = data.getStringExtra(ColorProbeActivity.EXTRA_VERDICT);
        }
        if (verdict == null) {
            verdict = "精确校准被跳过（raw 通道保持原样）";
        }
        showCheckResult(verdict, false);
    }

    /**
     * 把"压缩 + 颜色"两段结果合成一个弹框。
     *
     * @param colorVerdict 颜色通道那一行的结论
     * @param offerPrecise true = 弹窗里多给一个「精确校准」按钮（静默校准没判出来时）
     */
    private void showCheckResult(String colorVerdict, boolean offerPrecise) {
        ImageCompress.Check check = pendingCheck;
        pendingCheck = null;
        if (check == null) {
            return;
        }
        String title = check.compressed ? "压缩已生效" : "压缩开着，但这张没省下体积";
        String msg = check.text
                + (colorVerdict == null ? "" : "\n\n颜色通道：" + colorVerdict)
                + (offerPrecise ? "\n\n想用最准的办法（纯色四色屏，约 2 秒）再测一次，点下面的按钮。" : "");
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("知道了", null);
        if (offerPrecise) {
            b.setNeutralButton("精确校准", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    startColorCalibration();
                }
            });
        }
        b.show();
    }

    /**
     * 申请通知权限（Android 13+）。
     *
     * <h3>为什么这件事必须在"第一次打开设置页"就做</h3>
     * 磁贴点完只有两条反馈：截图成功的 Toast、截图失败的 Toast。而这台机器上
     * **没有通知权限时 Toast 会被系统直接吞掉**——用户看到的就是"点了没反应"，
     * 既不知道成功也不知道失败。
     *
     * <p>模块是 LSPosed 模块，没有常驻界面、没有系统入口会替它弹这个框。
     * 用户主动点开设置页，可能就是唯一的机会；错过这次，就只能靠他自己去
     * 系统设置里翻。所以默认值也一起改了（见 {@link Prefs#notifAsked}）。
     *
     * @param firstOpen true = 初次打开自动触发，问过就不再问；
     *                  false = 用户自己动了「常驻运行」开关，这时即使问过也再试一次
     */
    private void askNotificationPermission(boolean firstOpen) {
        if (Build.VERSION.SDK_INT < 33 || KeepAliveService.hasNotifPermission(this)) {
            return;
        }
        if (firstOpen && Prefs.notifAsked(this)) {
            return; // 问过一次就别再烦人，让用户自己去系统设置里开
        }
        Prefs.setNotifAsked(this, true);
        // 等页面先画出来再弹框：Android 的权限框是盖在 Activity 上的，
        // 太早弹会看到"白屏 + 权限框"。
        requestNotifPermission(450L);
    }

    /**
     * 真正去要权限。{@code delayMs} 给页面留出绘制时间。
     *
     * <p>系统无论如何都会回调 {@link #onRequestPermissionsResult}：用户点了"拒绝"、
     * 或者系统因为"拒过两次"直接返回拒绝，都会落到同一个分支里，由那里给出说明和入口。
     */
    private void requestNotifPermission(long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    requestPermissions(
                            new String[]{"android.permission.POST_NOTIFICATIONS"},
                            KeepAliveService.REQ_NOTIF);
                } catch (Throwable t) {
                    Logx.w("[settings] request notif permission failed: " + t.getMessage());
                }
            }
        }, delayMs);
    }

    /** 跳到本应用的通知设置页（拒绝过之后系统不再弹框，只能这样找回来）。 */
    private void openNotifSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Logx.e("[settings] open notif settings failed", t);
            showToast("打不开系统通知设置：" + t.getMessage());
        }
    }

    /** 用户拒绝了通知权限时的说明（对话框，不走 Toast——此刻 Toast 正好是坏的）。 */
    private void showNotifDeniedDialog() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("没给通知权限")
                .setMessage("没有它，点磁贴之后「截图成功 / 截图失败」的提示会被系统吞掉，"
                        + "看起来就像点了没反应。\n\n"
                        + "截图和记账本身不受影响。想要提示的话，"
                        + "在系统设置里把「记账截图」的通知打开即可。")
                .setPositiveButton("去打开", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        openNotifSettings();
                    }
                })
                .setNegativeButton("不用了", null)
                .show();
    }

    // ------------------------------------------------------------ 界面小工具

}
