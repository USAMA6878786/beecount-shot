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

    /** 来自宿主的实时状态（后台查询后刷新）。 */
    private volatile boolean hostAnswered = false;
    private volatile boolean activityAlive = false;
    private volatile int hookCount = -1;
    private volatile int hookFailed = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 设置页用 NoActionBar 主题（见 AndroidManifest），所以这个标题**不会**显示在界面上，
        // 它只影响"最近任务"里那张卡片的名字。版本号改写在状态行的最后一行。
        setTitle("记账截图 · v" + versionName());
        Logx.init(Prefs.appLogPath(this));
        Logx.i("[settings] opened, module " + versionName());

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        // 别把正文画到系统栏底下。
        //
        // Android 15（API 35）起，targetSdk 35 的应用被**强制"边到边"**显示：内容会一直
        // 画到状态栏、导航栏下面。本模块 targetSdk 就是 35，所以在较新的系统上，正文第一行
        // 会被顶到状态栏/标题栏后面，看起来就像"被横幅盖住了"。
        //
        // setFitsSystemWindows(true) 让这层视图自动把系统栏的高度让出来（补成内边距）。
        // 在还没强制边到边的老系统上，系统本来就已经让好了位置、剩余 inset 是 0，这行无副作用。
        scroll.setFitsSystemWindows(true);

        setContentView(scroll);

        // ---------------- 状态 ----------------
        statusView = new TextView(this);
        statusView.setTextSize(14f);
        statusView.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(statusView);
        refreshStatus();

        // 顺手请系统重新绑定一次磁贴。原因：应用被覆盖安装后磁贴常被"晾着"，
        // 图标还在、点了没反应，过一阵子又自己好——主动请求一次能让它早点恢复，
        // 也让副标题立刻刷新成最新状态。（打开设置页是个很自然的时机。）
        ShotTileService.requestRefresh(this);

        // 无障碍服务没开的话，用 root 帮用户打开。原因见 RootShell.ensureAccessibilityEnabled()：
        // 它是截屏又快又稳的那条主路径，而覆盖安装模块后系统会自动把它关掉——
        // 用户往往不会再去手动开一次，于是一直退在慢的 root 模拟按键上。
        if (!ShotAccessibilityService.isReady()) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    RootShell.ensureAccessibilityEnabled();
                    ShotTileService.requestRefresh(SettingsActivity.this);
                }
            }, "bee-ensure-a11y").start();
        }

        // ---------------- 使用前提 ----------------
        heading(root, "使用前提");
        body(root, "• 已安装 LSPosed，并在其中启用本模块、作用域勾选「蜜蜂记账」\n"
                + "• 已授予本模块 root（截屏、收起控制中心、补发放行信号、重启宿主）\n"
                + "• 已授予蜜蜂记账本体 root（只有删截图要用）\n"
                + "• **建议开启「记账截图」的截屏服务（无障碍）**：它是截屏的主路径，\n"
                + "  不走按键注入，最快也最稳。没开也能用，会自动退回 root 模拟按键。");

        // ---------------- 磁贴 ----------------
        heading(root, "控制中心磁贴");
        body(root, "把磁贴加到控制中心后，点一下就会：收起面板 → **立刻**截当前屏幕"
                + " → 交给蜜蜂记账识别记账（不等待，收面板命令一发出就截）。\n\n"
                + "截屏有两条路，都走系统真实截图流程（有动画、进相册）：\n"
                + "① 「截屏服务」（无障碍）直调系统截屏动作——**主路径，最快最稳**；\n"
                + "② 没开的话退回 root 模拟「电源键 + 音量下」。这条偶尔会有一条命令不生效、"
                + "要重试一次，所以建议把①开着（模块会自己帮你开）。\n\n"
                + "**平时的普通截图不会再触发识别**，只有点这个磁贴那一次会。\n"
                + "连着点几次也能一张不漏地分别记账。");
        button(root, "添加「记账截图」磁贴", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestAddTile();
            }
        });

        // ---------------- root ----------------
        heading(root, "root 权限");
        body(root, "模块用 root 做四件事，都是纯应用层做不稳的：\n"
                + "① 模拟组合键截屏（不用依赖无障碍服务）\n"
                + "② 收起控制中心（不少定制系统上普通做法不生效）\n"
                + "③ 补发一条放行广播（宿主退后台被冻结时，普通广播会被延迟投递）\n"
                + "④ 下面的「重启蜜蜂记账」");
        button(root, "申请 root 权限 / 重新检测", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestRoot();
            }
        });
        button(root, "重启蜜蜂记账", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartHost();
            }
        });
        body(root, "强制停止并重新打开。改完模块配置后 hook 需要重新注入，点它一次就够；"
                + "没给 root 时只会帮你打开它（无法强制停止）。");

        // ---------------- 等待时长（v2.18 已移除） ----------------
        //
        // 原来这里有一组「收面板后等多久才截屏」的档位（150/300/600/900/1300ms）。
        // 已整个去掉：截屏现在走无障碍直调系统截屏动作，不经过按键注入，
        // 跟控制中心面板收没收干净没有关系——那段等待是配合老路径的，没有存在理由了。
        // 「收起面板」这个动作本身**保留**（不收的话控制面板会出现在截图里）。

        // ---------------- 自动删截图 ----------------
        heading(root, "记账成功后删除截图");
        body(root, "打开后：点磁贴截的那张图，如果**被成功识别并记了账**，会自动从相册和存储里删掉。\n\n"
                + "怎么判断有没有识别成功：蜜蜂记账每处理完一张图会写一条"
                + "「AI 识别 + 落库完成 … 成功=N 笔」，模块拿它跟放行顺序一一对上——"
                + "N>0 的删掉，N=0（未识别到账单）的保留。连点几次、成功失败交替也没问题。\n\n"
                + "另外还有几道硬门槛，缺一不删：\n"
                + "① 这张图确实是闸门放行给蜜蜂记账的那一张\n"
                + "② 路径必须是图片、名字带截图字样、不在相机目录、不含特殊字符\n\n"
                + "拿不准时一律保留——宁可漏删，绝不误删。");
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

        CheckBox precise = new CheckBox(this);
        precise.setText("连点多次时，区分「识别成功」和「未识别到账单」分别处理");
        precise.setChecked(Prefs.precisePartial(this));
        precise.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setPrecisePartial(SettingsActivity.this, isChecked);
                Logx.i("[settings] precise-partial set to " + isChecked);
            }
        });
        root.addView(precise);
        body(root, "正常情况下用不到这个开关：蜜蜂记账每处理完一张图会写一条"
                + "「AI 识别 + 落库完成 … 成功=N 笔」的日志，模块拿它跟放行的先后顺序一一对上，"
                + "**精确知道哪张有账目、哪张没识别**，N=0 的那张直接保留。\n\n"
                + "万一以后它改了日志措辞、这条读不到了，才会退回推断：\n\n"
                + "**打开**——按「先截的先出结果」推断，删前面那几张。"
                + "这依赖蜜蜂记账按顺序依次处理；万一失败的是中间那张（比如第 2 张失败、"
                + "第 3 张成功），就会删错。\n\n"
                + "**关掉**——最保守：只要这一批里有一张没成功，整批都保留。"
                + "想要「绝不误删」就关掉它。");

        // ---------------- 桌面小组件 ----------------
        heading(root, "桌面小组件");
        body(root, "一个小部件同时显示「今日 / 本周 / 本月支出」，白底，默认 2×1。\n"
                + "横向拉长后会自动从「上下三行」变成「左中右三列」。\n"
                + "点一下小部件会刷新数字并打开蜜蜂记账的记账明细页。\n\n"
                + "数字直接读蜜蜂记账自己的数据库，与它统计页的口径一致"
                + "（跟随账本的自定义每月起始日、周一起算、剔除「不计入收支」的记账）。\n\n"
                + "在蜜蜂记账里增删改账目，或手动记一笔，小部件都会在几秒内跟着更新。");
        // v2.20：把原来那个「把「记账支出」放到桌面」按钮**去掉了**。
        //
        // 原因：那个按钮调的是 AppWidgetManager.requestPinAppWidget，前提是桌面实现了
        // "钉住小组件"这套接口。小米桌面（以及不少第三方桌面）会返回"支持"，
        // 但**点了完全没反应**——属于系统的坑，不是我们没调对。
        //
        // 而"把小组件放到桌面"这件事 **root 也做不到**：桌面是另一个应用（启动器），
        // 往它桌面上摆东西归它自己的 AppWidgetHost 管，没有任何 shell / root 接口
        // （`cmd appwidget` 只管绑定权限，管不了摆放）。
        //
        // 所以干脆不给按钮了——留一个点了没反应的按钮比不给更糟。
        body(root, "**添加方法**（应用没法自己放，root 也不行）：\n"
                + "回到桌面 → 长按空白处 → 点「添加小部件」（小米里可能叫「添加工具」）→ "
                + "找到「记账支出」→ 按住拖到桌面上。\n\n"
                + "之所以要手动，是因为往桌面摆小组件是**桌面应用自己的事**，"
                + "Android 没有给任何应用（包括有 root 的）提供这个接口。");

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
                + "先看本页顶部状态；磁贴副标题也会提示。常见三种：\n"
                + "① 既没 root、无障碍服务也没开 → 副标题会直接写「截屏服务未开启 · 点一下去开启」\n"
                + "② 蜜蜂记账的界面被系统回收了 → 打开一次它即可（后台存活就够，不必留在前台），"
                + "或用上面的「重启蜜蜂记账」\n"
                + "③ root 没授权 → 点上面的「申请 root 权限 / 重新检测」\n\n"
                + "**改了配置不生效**\n"
                + "点上面的「重启蜜蜂记账」，hook 只在它启动时注入。\n\n"
                + "**截到的图里带着控制中心**\n"
                + "理论上不该出现：收面板一发出就截屏，而且截屏走的是系统动作、不挑面板状态。\n"
                + "真拍到了说明这台机器收面板比截屏慢——那就得改成「等系统确认面板已收起再截」，"
                + "而不是拍脑袋等一个固定毫秒数。请把截图发我。\n\n"
                + "**小部件显示灰色**\n"
                + "说明蜜蜂记账的进程没在跑，此时显示的是上次的数值；打开一次它就会恢复实时。");
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

        sb.append(a11y ? "✓ 截屏服务已开启（主路径）"
                : (root ? "○ 截屏服务未开启 · 走 root 兜底，建议开启" : "✗ 截屏服务未开启"));
        if (!a11y) {
            sb.append("　← 到「无障碍」里打开「记账截图」会更快");
        }
        sb.append("　");
        sb.append(root ? "✓ root 已授权" : "✗ root 未授权");
        sb.append('\n');

        if (!hostAnswered) {
            sb.append("? 未连上蜜蜂记账（它没在运行，或模块未注入）");
            statusView.setTextColor(Color.parseColor("#A32D2D"));
        } else if (!a11y && !root) {
            // 截屏服务没开是最要紧的问题，优先让它红着
            sb.append("✓ 宿主 hook：").append(hookCount >= 0 ? hookCount + " 条" : "未知");
            sb.append('\n');
            sb.append(activityAlive ? "✓ 截图监听在位" : "✗ 截图监听不在位（先打开一次蜜蜂记账）");
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
        // 版本号挂在最后一行：标题栏去掉了，不写在这里就再也看不到自己装的是哪一版。
        sb.append("　·　模块 v").append(versionName());
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
                        refreshStatus();
                        // 等强停真正落地再启动，否则可能被系统忽略
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                openHost();
                                if (stopped) {
                                    Logx.i("[settings] host restarted");
                                }
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
