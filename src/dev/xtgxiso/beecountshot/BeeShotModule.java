package dev.xtgxiso.beecountshot;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口（现代 libxposed API 102，运行在 BeeCount 进程内）。
 *
 * <p>整套方案只做一件事：在「宿主发现截图」和「宿主真的去记账」之间插一道闸门。
 * BeeCount 的链路是：
 * <pre>
 *   MediaStore 变化
 *     -> ScreenshotObserver.onChange(boolean, Uri)          // ContentObserver 子类
 *     -> 判定是不是截图（文件名含 screenshot/截图/截屏，且 30 秒内）
 *     -> new MethodChannel(messenger, "com.tntlikely.beecount/screenshot")
 *            .invokeMethod("onScreenshotDetected", path)
 *     -> Dart ScreenshotMonitorService._handleScreenshot
 *     -> AutoBillingService.processScreenshot(path)         // OCR + 记账
 * </pre>
 *
 * <p>闸门装在最精确的那一步（Flutter 通道上报）。之所以不用类名去 hook ScreenshotObserver：
 * 宿主的 release 版开了 R8，那个类的名字会被混淆掉。而宿主自己的 proguard-rules.pro 里显式
 * 保留了这些：
 * <pre>
 *   -keep class io.flutter.plugin.common.** { *; }
 *   -keep class io.flutter.** { *; }
 *   -keep class com.tntlikely.beecount.MainActivity { *; }
 * </pre>
 * 所以"库类 + 字符串常量"这两个锚点天然免疫混淆。
 *
 * <p>一共装四道：
 * <ol>
 *   <li>gate-flutter-channel —— 精确闸门，命中即"消费"掉放行窗口。决定成败的那一道。</li>
 *   <li>gate-media-observer —— 兜底闸门，直接拦媒体库变化回调。不消费窗口。靠"先抓到
 *       observer 实例、再挂它的 onChange"实现，同样不依赖类名。</li>
 *   <li>host-context-app —— hook 宿主 Application.onCreate 拿 Context（最早最稳）。</li>
 *   <li>host-context-activity —— 兜底，hook MainActivity.onCreate 再拿一次。</li>
 * </ol>
 * 每一道都独立 try/catch，安装结果打一行摘要，并写进宿主的日志文件。
 */
public class BeeShotModule extends XposedModule {

    /**
     * 日志里打的版本号。
     *
     * <p>由 {@code build.sh} 在构建时生成的 {@code BuildInfo} 提供——之前这里写死成 "v1.3"，
     * 一直没跟着模块版本走，排查问题时看日志会被带偏。现在改成构建期注入，
     * 版本号只有一个来源（build.sh 顶部的 VERSION_NAME）。
     */
    private static final String VERSION_TAG = "v" + BuildInfo.VERSION_NAME;

    private final List<XposedInterface.HookHandle> handles =
            new ArrayList<XposedInterface.HookHandle>();
    private final List<String> installed = new ArrayList<String>();
    private final List<String> failedList = new ArrayList<String>();

    private volatile boolean observerGateInstalled = false;
    private volatile int observerBlocked = 0;
    private volatile boolean installedThisProcess = false;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Logx.i("[module] " + VERSION_TAG + " loaded in process=" + param.getProcessName()
                + " api=" + getApiVersion()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        try {
            final String pkg = param.getPackageName();
            if (!isTargetPackage(pkg)) {
                return;
            }

            // 这一步还没有 Context，只能先按"标准用户目录"猜一个日志位置——真机上这个猜测
            // 可能是不存在的路径（应用数据目录未必在 /data/user/0）。所以 HostBridge 拿到
            // Context 后会再用真正的 cacheDir 重试一次。
            Logx.init(Const.hostLogCandidates("/data/user/0/" + pkg));

            Logx.i("[module] " + VERSION_TAG + " onPackageReady pkg=" + pkg
                    + " isFirstPackage=" + param.isFirstPackage()
                    + " targetSdk=" + safeTargetSdk(param));

            // 用自己进程内的标记保证只装一次。
            //
            // 刻意不再依赖 isFirstPackage()：那是个"本进程内第一个被加载的包"的判断，
            // 在 Flutter 应用上未必是我们期望的值；一旦它为 false 就整段跳过，表现是
            // "模块像没装一样"且毫无提示。反正包名已经过滤过，重复安装用本地标记挡住就够。
            if (installedThisProcess) {
                Logx.i("[module] hooks already installed in this process, skipping");
                return;
            }
            installedThisProcess = true;

            Logx.i("================================================");
            Logx.i("[module] file log sink: " + (Logx.ready() ? "OK" : "UNAVAILABLE"));

            ClassLoader cl = param.getClassLoader();

            installFlutterChannelGate(cl);
            installMediaObserverGate(cl);
            installApplicationContextHook(cl, param);
            installActivityContextHook(cl);
            installActivityLifecycleProbe(cl);
            installEngineCapture(cl);
            installDartLogTap(cl);

            String summary = "ok=" + installed + " failed=" + failedList;
            Logx.i("[module] install summary: " + summary);
            if (!failedList.isEmpty()) {
                Logx.w("[module] some hooks were not installed: " + failedList);
            }
            Logx.i("================================================");

            // 把安装结果写进遥测（此时还没有 Context，会先暂存，拿到 Context 后补写）。
            // 这是回答"hook 到底装上没有"的唯一可靠凭据；同时给设置页一份计数。
            HookStats.report(installed.size(), failedList.size());
            HostTelemetry.put("host_pkg", pkg);
            HostTelemetry.put("hooks_ok", installed.toString());
            HostTelemetry.put("hooks_failed", failedList.toString());
            HostTelemetry.put("hooks_ready_at", System.currentTimeMillis());
        } catch (Throwable t) {
            Logx.e("[module] onPackageReady failed", t);
        }
    }

    private static boolean isTargetPackage(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (int i = 0; i < Const.HOST_PKGS.length; i++) {
            if (Const.HOST_PKGS[i].equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 闸门 1

    /**
     * 主闸门：拦 `MethodChannel.invokeMethod(String, Object)`。
     *
     * <p>只挂一个重载。两参版内部会调用三参版，如果两个都挂，已被消费掉窗口的这一次
     * 会在第二道闸门上被自己拦掉，等于放行失败。
     */
    private void installFlutterChannelGate(ClassLoader cl) {
        Method twoArg = null;
        Method threeArg = null;
        try {
            Class<?> channel = Class.forName("io.flutter.plugin.common.MethodChannel", false, cl);
            twoArg = findMethod(channel, "invokeMethod",
                    new Class<?>[]{String.class, Object.class});
            try {
                Class<?> result = Class.forName(
                        "io.flutter.plugin.common.MethodChannel$Result", false, cl);
                threeArg = findMethod(channel, "invokeMethod",
                        new Class<?>[]{String.class, Object.class, result});
            } catch (Throwable ignored) {
                // 旧版 Flutter 可能没有 Result；两参版才是宿主实际调用的那个。
            }
        } catch (Throwable t) {
            Logx.e("[hook] Flutter MethodChannel lookup failed", t);
        }

        Method target = (twoArg != null) ? twoArg : threeArg;
        if (target == null) {
            failedList.add("gate-flutter-channel:not-found");
            Logx.w("[hook] gate-flutter-channel target not found");
            return;
        }
        add("gate-flutter-channel", target, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object method = chain.getArg(0);
                if (!Const.TARGET_METHOD.equals(method)) {
                    return chain.proceed();
                }
                String path = String.valueOf(chain.getArg(1));
                // 纯 headless 模式：只有**我们自己**（无界面引擎）发的派发放行。
                // 宿主自己的观察者也走这个方法，但那条路会让"谁先派发谁记账"变得不确定，
                // 所以一律拦掉 —— 记账只由我们这一条路完成。
                if (!HostHeadlessEngine.isOurChannel(chain.getThisObject())) {
                    HostTelemetry.bump("channel_blocked");
                    Logx.i("[gate-channel] BLOCKED host observer report (headless-only mode)");
                    return null;
                }
                // 就地记下"闸门放行了哪个文件"——这是后续判断"这张图被交给蜜蜂记账了"
                // 最直接的证据，比去读蜜蜂记账自己的列表可靠得多。
                LastShot.record(path);
                HostTelemetry.bump("channel_allowed");
                HostTelemetry.put("last_allowed_path", path);
                HostTelemetry.put("last_allowed_at", System.currentTimeMillis());
                Logx.i("[gate-channel] ALLOWED a screenshot report -> BeeCount will book it");
                return chain.proceed();
            }
        });
    }

    // ---------------------------------------------------------------- 闸门 2

    /**
     * 兜底闸门：宿主注册截图监听器时抓到实例，再挂它自己的 `onChange(boolean, Uri)`。
     *
     * <p>完全不依赖类名，所以即使 Flutter 通道那条路因为版本变化失效，这一道仍然有效。
     */
    private void installMediaObserverGate(ClassLoader cl) {
        Method register = findMethod(ContentResolver.class, "registerContentObserver",
                new Class<?>[]{Uri.class, boolean.class, ContentObserver.class});
        add("capture-media-observer", register, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object uri = chain.getArg(0);
                    Object observer = chain.getArg(2);
                    if (uri instanceof Uri && observer != null && isMediaImages((Uri) uri)) {
                        hookObserverOnChange(observer.getClass());
                    }
                } catch (Throwable t) {
                    Logx.e("[hook] capture media observer failed", t);
                }
                return chain.proceed();
            }
        });
    }

    private static boolean isMediaImages(Uri uri) {
        String path = uri.getPath();
        return "content".equals(uri.getScheme())
                && "media".equals(uri.getAuthority())
                && path != null
                && path.contains("images/media");
    }

    private void hookObserverOnChange(final Class<?> observerClass) {
        if (observerGateInstalled) {
            return;
        }
        Method probe = findMethod(observerClass, "onChange",
                new Class<?>[]{boolean.class, Uri.class});
        // 必须是子类自己覆写的实现。否则会挂到 framework 的 ContentObserver.onChange 上，
        // 那会把宿主进程里所有 ContentObserver 全部波及。
        if (probe == null || probe.getDeclaringClass() == ContentObserver.class) {
            Logx.w("[hook] media observer does not override onChange: " + observerClass.getName());
            return;
        }
        Logx.i("[hook] screenshot observer located: " + observerClass.getName());
        // 关键：绝不在当前这条 hook 回调里再安装 hook。框架分发 hook 时可能持锁，
        // 重入安装有死锁风险。丢到主线程消息队列尾部执行，那时本次回调已经返回。
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (observerGateInstalled) {
                    return;
                }
                Method onChange = findMethod(observerClass, "onChange",
                        new Class<?>[]{boolean.class, Uri.class});
                if (onChange == null) {
                    return;
                }
                boolean ok = add("gate-media-observer", onChange, new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (!ArmSignal.isArmed()) {
                            // 未放行：吞掉这次媒体库变化回调，宿主连"有新截图"都感知不到。
                            // 日志做个限流，避免相册批量变化时刷屏。
                            int n = ++observerBlocked;
                            HostTelemetry.bump("observer_blocked");
                            if (n <= 3 || n % 20 == 0) {
                                Logx.i("[gate-observer] BLOCKED media-change callback (#" + n + ")");
                            }
                            return null;
                        }
                        return chain.proceed();
                    }
                });
                if (ok) {
                    observerGateInstalled = true;
                    Logx.i("[hook] media observer gate armed on " + observerClass.getName());
                }
            }
        });
    }

    // ---------------------------------------------------------------- 上下文

    /**
     * 从 Application.onCreate 拿 Context。比 MainActivity 更早更稳。
     *
     * <p>宿主的 Application 类名从 applicationInfo 里拿，是明面上的信息（Flutter 应用一般是
     * io.flutter.app.FlutterApplication，也可能被换成自定义类），不需要猜。
     */
    private void installApplicationContextHook(final ClassLoader cl,
                                               XposedModuleInterface.PackageReadyParam param) {
        Method onCreate = null;
        try {
            String className = param.getApplicationInfo().className;
            Logx.i("[hook] host Application class = " + className);
            if (className == null || className.length() == 0) {
                className = "android.app.Application";
            }
            Class<?> appClass = Class.forName(className, false, cl);
            onCreate = findMethod(appClass, "onCreate", new Class<?>[0]);
        } catch (Throwable t) {
            Logx.e("[hook] Application lookup failed", t);
        }
        add("host-context-app", onCreate, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Context) {
                        HostBridge.attach((Context) self);
                        registerHeadlessReceiver((Context) self, cl);
                        // 模块侧刚把我们拉起来？（拉起前会留一个标记文件）
                        // 有标记就说明"这次启动是来干活的"——立刻起无界面引擎。
                        // 放在 Application.onCreate 里检查，**没有时序窗口**。
                        Context appCtx = (Context) self;
                        java.io.File mark = new java.io.File(appCtx.getCacheDir(), "beeshot_boot");
                        if (mark.exists()) {
                            // 界面引擎已经在的话就别再起第二个 —— 那时它的观察者会处理这张图，
                            // 我们再起一个就是双份内存 + 有重复记账的风险。
                            if (HostState.engine() != null) {
                                Logx.i("[headless] 界面引擎已在，跳过无界面引擎");
                                //noinspection ResultOfMethodCallIgnored
                                mark.delete();
                                return chain.proceed();
                            }
                            // 界面引擎已经在的话就别再起第二个 —— 那时它的观察者会处理这张图，
                            // 我们再起一个就是双份内存 + 有重复记账的风险。
                            if (HostState.engine() != null) {
                                Logx.i("[headless] 界面引擎已在，跳过无界面引擎");
                                //noinspection ResultOfMethodCallIgnored
                                mark.delete();
                                return chain.proceed();
                            }
                            // 只认 10 秒内的标记：桌面小组件随时会唤醒进程，
                            // 一个过期标记会让用户正常打开 App 时也白起一个引擎。
                            long ageMs = System.currentTimeMillis() - mark.lastModified();
                            //noinspection ResultOfMethodCallIgnored
                            mark.delete();
                            if (ageMs <= 10_000L) {
                                Logx.i("[headless] 发现拉起标记（" + ageMs + "ms），开始起无界面引擎");
                                HostHeadlessEngine.boot(appCtx, cl, null);
                            } else {
                                Logx.i("[headless] 标记已过期（" + ageMs + "ms），忽略");
                            }
                        }
                    }
                } catch (Throwable t) {
                    Logx.e("[hook] attach application context failed", t);
                }
                return chain.proceed();
            }
        });
    }

    /** 兜底：MainActivity.onCreate 再拿一次 Context（宿主已 keep 该类名，可安全按名 hook）。 */
    private void installActivityContextHook(ClassLoader cl) {
        Method onCreate = null;
        try {
            Class<?> activity = Class.forName(Const.HOST_PKG + ".MainActivity", false, cl);
            onCreate = findMethod(activity, "onCreate", new Class<?>[]{Bundle.class});
        } catch (Throwable t) {
            Logx.e("[hook] MainActivity lookup failed", t);
        }
        add("host-context-activity", onCreate, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Context) {
                        // MainActivity 起来了 → 蜜蜂记账的截图监听能力在场。
                        HostState.onActivityCreated();
                        HostBridge.attach((Context) self);
                    }
                } catch (Throwable t) {
                    Logx.e("[hook] attach activity context failed", t);
                }
                return chain.proceed();
            }
        });
    }

    // ---------------------------------------------------------------- 宿主存活探针

    /**
     * 记录宿主"截图监听能力"的存活区间。
     *
     * <p>BeeCount 的截图监听器是在 MainActivity 起来之后注册、在 MainActivity.onDestroy 里
     * 注销的（见它的 MainActivity.startContentObserverMonitor / stopScreenshotObserver）。
     * 也就是说：<b>MainActivity 不在了，BeeCount 就彻底感知不到任何截图</b>——不管我们放行不放行。
     *
     * <p>这正是"偶尔没反应"最可疑的原因之一（比如你从别的应用拉下控制中心时，BeeCount
     * 的界面早已被系统回收）。所以这里把 onDestroy 也记一笔，日志里就能直接看出来
     * 失败的那一次，宿主的监听到底还在不在。
     */
                    private void installActivityLifecycleProbe(ClassLoader cl) {
        Method onDestroy = null;
        try {
            Class<?> activity = Class.forName(Const.HOST_PKG + ".MainActivity", false, cl);
            onDestroy = findMethod(activity, "onDestroy", new Class<?>[0]);
        } catch (Throwable t) {
            Logx.e("[hook] MainActivity.onDestroy lookup failed", t);
        }
        add("host-lifecycle-probe", onDestroy, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                // 注意：这是在原始 onDestroy 之前。宿主即将注销它的截图监听器，
                // 从这一刻起任何截图它都不会再感知到。
                HostState.onActivityDestroyed();
                // 注意：装了"引擎保活"之后，界面销毁**不等于**监听失效——
                // Dart isolate 还在，观察者继续工作。所以这行只说明"界面没了"，不再断言监听掉了。
                Logx.w("[host] MainActivity.onDestroy -> 界面销毁");
                Object r = chain.proceed();
                // 宿主刚刚在 onDestroy 里把观察者注销了。但引擎和 Dart 都还活着
                // （实测 FlutterEngine.destroy 从未被调用），所以立刻用它的
                // startScreenshotObserver(engine) 挂回去 —— 这样下一次点磁贴时
                // 观察者是在位的，通知能及时到，不会因为超过 30 秒被判"旧图"丢掉。
                HostRearm.afterDestroy(chain.getThisObject());
                return r;
            }
        });
    }




    // ---------------------------------------------------------------- Dart 日志截流

    /**
     * 把 Dart 的 {@code print()} 截到我们自己的日志文件里。
     *
     * <h3>为什么需要</h3>
     * Dart 的 print 只走 logcat 的 {@code flutter} tag，而这台机器 logcat 刷得极快，
     * 事后导出永远看不到。排查 headless 的问题时**完全看不见 Dart 内部**。
     *
     * <p>Flutter 的 print 最终会走到 {@code FlutterJNI.logMessage(tag, message, level)}
     * （native 反向调 Java）。挂上它，就能把 Dart 的每一条输出落进宿主日志。
     */
    private void installDartLogTap(ClassLoader cl) {
        Method m = null;
        try {
            Class<?> jni = Class.forName("io.flutter.embedding.engine.FlutterJNI", false, cl);
            m = findMethod(jni, "logMessage",
                    new Class<?>[]{String.class, String.class, int.class});
        } catch (Throwable t) {
            Logx.w("[hook] 找 FlutterJNI.logMessage 失败：" + t.getMessage());
        }
        add("dart-log", m, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object[] a = chain.getArgs().toArray();
                    String tag = a.length > 0 && a[0] != null ? String.valueOf(a[0]) : "?";
                    String msg = a.length > 1 && a[1] != null ? String.valueOf(a[1]) : "";
                    if (msg.length() > 300) {
                        msg = msg.substring(0, 300) + "…";
                    }
                    Logx.i("[dart] " + tag + " | " + msg);
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            }
        });
    }

    // ---------------------------------------------------------------- 无界面派发

    /**
     * 注册一个**动态**接收器：模块侧把进程拉起来之后，用它通知我们
     * "起一个无界面引擎，把刚截的那张图派发进去"。
     *
     * <p>为什么是动态的：它只在进程活着时存在，正好符合"要用它的时候才需要它"；
     * 而且动态接收器不受 Android 8+ 对后台**隐式广播**的限制。
     *
     * <p>什么时候不干活：界面引擎已经在（{@link HostState#engine()} 非空）→ 说明界面活着，
     * 那张图会被它自己的观察者看到，我们再派发就会**记两笔账**。
     */
    private void registerHeadlessReceiver(final Context ctx, final ClassLoader hostCl) {
        try {
            android.content.IntentFilter f =
                    new android.content.IntentFilter(Const.ACTION_HEADLESS);
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent intent) {
                    try {
                        // 纯 headless 模式：界面引擎在也照样起我们自己的引擎。
                        // 闸门已经按 messenger 把"宿主观察者的派发"全拦了，
                        // 不会再出现双派发；这里再挡反而会让界面活着时完全没人派发。
                        if (HostHeadlessEngine.uiEnginePresent()) {
                            Logx.i("[headless] 界面引擎在，但按纯 headless 模式仍由我们派发");
                        }
                        Logx.i("[headless] 收到派发请求，开始起无界面引擎");
                        HostHeadlessEngine.boot(c, hostCl, null);
                    } catch (Throwable t) {
                        Logx.e("[headless] 派发请求处理失败", t);
                    }
                }
            };
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            Logx.i("[hook] 已注册无界面派发接收器");
        } catch (Throwable t) {
            Logx.w("[hook] 注册无界面派发接收器失败：" + t.getMessage());
        }
    }

    // ---------------------------------------------------------------- 引擎捕获

    /**
     * 捕获宿主的 {@code FlutterEngine}，供 {@link HostRearm} 重挂观察者时使用。
     *
     * <p>{@code MainActivity.configureFlutterEngine(FlutterEngine)} 是宿主自己实现的
     * （它在这里注册了日志通道、截图通道等）。我们只取参数、不改行为。
     *
     * <p>用反射按"方法名 + 1 个参数"找，**不直接引用 Flutter 类**——模块编译时没有 Flutter 依赖。
     */
    private void installEngineCapture(final ClassLoader cl) {
        Method cfg = null;
        try {
            Class<?> act = Class.forName(Const.HOST_PKG + ".MainActivity", false, cl);
            Method[] all = act.getDeclaredMethods();
            for (int i = 0; i < all.length; i++) {
                Method m = all[i];
                if ("configureFlutterEngine".equals(m.getName())
                        && m.getParameterTypes().length == 1) {
                    m.setAccessible(true);
                    cfg = m;
                    break;
                }
            }
        } catch (Throwable t) {
            Logx.e("[hook] configureFlutterEngine lookup failed", t);
        }
        add("engine-capture", cfg, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object[] args = chain.getArgs().toArray();
                    if (args.length > 0 && args[0] != null) {
                        HostState.setEngine(args[0]);
                        Logx.i("[hook] 已捕获 FlutterEngine（供重挂观察者用）");
                    }
                } catch (Throwable t) {
                    Logx.w("[hook] 捕获 engine 失败：" + t.getMessage());
                }
                return chain.proceed();
            }
        });
    }

    // ---------------------------------------------------------------- 热重载

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        Logx.i("[module] hot reloading, releasing " + handles.size() + " hooks");
        for (int i = 0; i < handles.size(); i++) {
            try {
                handles.get(i).unhook();
            } catch (Throwable ignored) {
            }
        }
        handles.clear();
        installed.clear();
        failedList.clear();
        observerGateInstalled = false;
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        Logx.i("[module] hot reload complete; re-hooking via onPackageReady");
    }

    // ---------------------------------------------------------------- 工具

    private boolean add(final String id, java.lang.reflect.Executable method,
                        XposedInterface.Hooker hooker) {
        if (method == null) {
            failedList.add(id + ":not-found");
            Logx.w("[hook] target not found: " + id);
            return false;
        }
        try {
            try {
                // 请求框架别把目标方法内联掉，否则 hook 可能看不到调用。
                // 构造器没有"内联"这回事，只对普通方法做。
                if (method instanceof Method) {
                    deoptimize((Method) method);
                }
            } catch (Throwable ignored) {
            }
            XposedInterface.HookHandle handle = hook(method)
                    .setId(id)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            handles.add(handle);
            installed.add(id);
            Logx.i("[hook] installed: " + id + " -> "
                    + method.getDeclaringClass().getName() + "#" + method.getName());
            return true;
        } catch (Throwable t) {
            failedList.add(id);
            Logx.e("[hook] install failed: " + id, t);
            return false;
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] params) {
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            try {
                Method m = cursor.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                cursor = cursor.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static String safeTargetSdk(XposedModuleInterface.PackageReadyParam param) {
        try {
            return String.valueOf(param.getApplicationInfo().targetSdkVersion);
        } catch (Throwable t) {
            return "?";
        }
    }
}
