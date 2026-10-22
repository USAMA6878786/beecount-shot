package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 在没有界面的情况下，把宿主自己的 Flutter/Dart 拉起来，并把"有新截图"送进去。
 *
 * <h3>为什么可行（已实测）</h3>
 * <ol>
 *   <li>宿主 {@code lib/main.dart} 启动时会读偏好，发现用户开过「截图自动识别」就自己
 *       调 {@code enable()} —— 这一步会把 {@code _isEnabled / _isMonitoring} 置位，
 *       而它们正是 {@code _handleScreenshot} 开头唯一会拦人的判断。</li>
 *   <li>但它 {@code enable()} 是「先调原生 startScreenshotObserver、后置位标志」，
 *       我们这个引擎没有 Activity 注册的原生处理者，会抛 MissingPluginException ——
 *       所以必须挂一个**只回 success 的桩**，让 enable() 走完。</li>
 * </ol>
 * 实测日志：{@code ✅ [ScreenshotMonitor] 截图监听已启用，_isEnabled=true, _isMonitoring=true}
 *
 * <h3>为什么不需要再注册观察者</h3>
 * 我们**知道**刚截的是哪张图（磁贴流程里路径是现成的），所以直接调
 * {@code onScreenshotDetected(path)} 就够了，不必监听相册。
 *
 * <h3>什么时候用它</h3>
 * 只在"宿主进程是被我们重新拉起来的、没有界面引擎"时用。界面引擎在的时候走它自己的
 * 观察者，避免同一张图被派发两次（重复记账）。
 */
final class HostHeadlessEngine {

    /** 截图通道（和它自己的 Dart 用同一个通道名）。 */
    private static final String CHANNEL = "com.tntlikely.beecount/screenshot";

    /** 引擎必须被强引用住，否则会被 GC。 */
    private static volatile Object engine;
    private static volatile Object channel;

    /** 我们这个引擎的 BinaryMessenger —— 闸门靠它认"这是不是我们自己发的"。 */
    private static volatile Object channelMessenger;

    /** 待派发的截图路径（可能比引擎就绪先到）。 */
    private static volatile String pendingPath;
    private static volatile boolean dartReady = false;
    private static volatile boolean started = false;

    /** 上一次派发过的路径：标记路径和广播路径可能同时触发，同一张只能派发一次。 */
    private static volatile String dispatchedPath;


    /** 空闲多久就把引擎销毁（省内存）。 */
    private static final long IDLE_DESTROY_MS = 45_000L;

    /** 最后一次"有活干"的时刻。 */
    private static volatile long lastActivityAt = 0L;

    /** 派发了但还没收到回执的路径。非空时不允许销毁引擎（否则把正在跑的识别掐死）。 */

    /** 派发了但还没收到回执的路径。非空时不允许销毁引擎（否则把正在跑的识别掐死）。 */
    private static volatile String inflight;

    /** 已经删过的路径（兜底时用来跳过）。 */
    private static volatile String cleanedPath;

    private static volatile String lastDispatched;

    /** 应用 Context，删图时要用。 */
    private static volatile Context appCtx;

    /** 宿主的类加载器（Flutter 的类只有它能看见）。 */
    private static volatile ClassLoader hostClsLoader;

    /**
     * 这次派发是不是**我们自己的**无界面引擎发出的？
     *
     * <p>闸门用它把两条路分开：我们发的放行，宿主的观察者发的一律拦掉
     * （纯 headless 模式）。比较的是 {@code MethodChannel.binaryMessenger} 的对象身份。
     */
    static boolean isOurChannel(Object methodChannel) {
        Object mine = channelMessenger;
        if (mine == null || methodChannel == null) {
            return false;
        }
        for (String name : new String[]{"binaryMessenger", "messenger"}) {
            try {
                java.lang.reflect.Field f = methodChannel.getClass().getDeclaredField(name);
                f.setAccessible(true);
                if (f.get(methodChannel) == mine) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private HostHeadlessEngine() {
    }

    /** 界面引擎是否已经存在（存在就不该再由我们派发）。 */
    static boolean uiEnginePresent() {
        return HostState.engine() != null;
    }

    /**
     * 启动（幂等）。必须在主线程调用——FlutterEngine 只能在主线程创建。
     *
     * @param path 要派发的截图路径；null 表示只启动不派发
     */
    static void boot(final Context ctx, final ClassLoader hostCl, final String path) {
        if (path != null) {
            pendingPath = path;
        }
        if (started) {
            // 已经在跑。
            // 注意：**不能立刻 dispatchNow()** —— 上一次的图刚被删，新的还没写完，
            // newestShot() 会取到旧路径、被 lastDispatched 判重跳过，表现为"第二次点了没反应"。
            // 所以延迟一下再派发，并允许重试。
            lastActivityAt = System.currentTimeMillis();
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    attemptDispatch(0);
                }
            }, 1200L);
            return;
        }
        started = true;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    doStart(ctx, hostCl);
                } catch (Throwable t) {
                    Logx.e("[headless] 启动失败", t);
                }
            }
        });
    }

    private static void doStart(Context ctx, ClassLoader cl) throws Exception {
        hostClsLoader = cl;
        appCtx = ctx;
        Logx.i("[headless] 启动无界面引擎…");
        Class<?> engineCls = Class.forName(
                "io.flutter.embedding.engine.FlutterEngine", true, cl);

        // 1) FlutterLoader 初始化（正常由 FlutterActivity 做）
        try {
            Class<?> loaderCls = Class.forName(
                    "io.flutter.embedding.engine.loader.FlutterLoader", true, cl);
            Object loader;
            try {
                loader = loaderCls.getMethod("getInstance").invoke(null);
            } catch (NoSuchMethodException e) {
                loader = loaderCls.getConstructor().newInstance();
            }
            loaderCls.getMethod("startInitialization", Context.class).invoke(loader, ctx);
            try {
                loaderCls.getMethod("ensureInitializationComplete", Context.class, String[].class)
                        .invoke(loader, ctx, null);
            } catch (NoSuchMethodException e) {
                loaderCls.getMethod("ensureInitializationComplete", Context.class)
                        .invoke(loader, ctx);
            }
            Logx.i("[headless] FlutterLoader 就绪");
        } catch (Throwable t) {
            Logx.e("[headless] FlutterLoader 初始化失败", t);
            return;
        }

        // 2) 建引擎
        Object e = engineCls.getConstructor(Context.class).newInstance(ctx);
        engine = e;
        Logx.i("[headless] 引擎已创建");

        // 3) 插件（SharedPreferences / sqflite / path_provider 都在这）
        try {
            Class<?> reg = Class.forName("io.flutter.plugins.GeneratedPluginRegistrant", true, cl);
            reg.getMethod("registerWith", engineCls).invoke(null, e);
            Logx.i("[headless] 插件已注册");
        } catch (Throwable t) {
            Logx.w("[headless] 插件注册失败：" + t.getMessage());
        }

        // 4) 截图通道：挂桩 + 保留 channel 引用（后面要主动派发）
        installChannelStub(cl, engineCls, e);

        // 5) 跑它的 Dart 入口
        Class<?> dartExecCls = Class.forName(
                "io.flutter.embedding.engine.dart.DartExecutor", true, cl);
        Class<?> epCls = Class.forName(
                "io.flutter.embedding.engine.dart.DartExecutor$DartEntrypoint", true, cl);
        Object entrypoint = epCls.getMethod("createDefault").invoke(null);
        Object dartExec = engineCls.getMethod("getDartExecutor").invoke(e);
        dartExecCls.getMethod("executeDartEntrypoint", epCls).invoke(dartExec, entrypoint);
        Logx.i("[headless] 已提交 Dart 入口");
    }

    private static void installChannelStub(ClassLoader cl, Class<?> engineCls, Object engineObj)
            throws Exception {
        Class<?> mcCls = Class.forName("io.flutter.plugin.common.MethodChannel", true, cl);
        Class<?> handlerCls = Class.forName(
                "io.flutter.plugin.common.MethodChannel$MethodCallHandler", true, cl);
        Object dartExec = engineCls.getMethod("getDartExecutor").invoke(engineObj);
        Object messenger = dartExec.getClass().getMethod("getBinaryMessenger").invoke(dartExec);

        Object mc = mcCls.getConstructor(
                Class.forName("io.flutter.plugin.common.BinaryMessenger", true, cl), String.class)
                .newInstance(messenger, CHANNEL);

        Object handler = Proxy.newProxyInstance(handlerCls.getClassLoader(),
                new Class<?>[]{handlerCls}, new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (!"onMethodCall".equals(method.getName())) {
                            return null;
                        }
                        try {
                            Object call = args[0];
                            Object result = args[1];
                            String name;
                            try {
                                name = (String) call.getClass().getMethod("getMethod").invoke(call);
                            } catch (NoSuchMethodException e2) {
                                name = (String) call.getClass().getField("method").get(call);
                            }
                            Logx.i("[headless] Dart 调原生：" + name);
                            // 一律 success：让 enable() 走完（它先调原生、后置位标志）
                            result.getClass().getMethod("success", Object.class)
                                    .invoke(result, (Object) null);
                            if ("startScreenshotObserver".equals(name)) {
                                // Dart 已经把监听"以为"开起来了，标志也置位了。
                                // 稍等一下让 ensure 走完，再把截图送进去。
                                dartReady = true;
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        dispatchNow();
                                    }
                                }, 600L);
                            }
                        } catch (Throwable t) {
                            Logx.w("[headless] 桩处理失败：" + t.getMessage());
                        }
                        return null;
                    }
                });
        mcCls.getMethod("setMethodCallHandler", handlerCls).invoke(mc, handler);
        channelMessenger = messenger;
        channel = mc;
        Logx.i("[headless] 截图通道桩已挂上");
    }

    /** 把 pendingPath 派发给 Dart。 */
    /** 找最新的一张本模块截图（路径没传进来时用）。 */
    /** 空闲检查：每 15 秒看一次，超过 IDLE_DESTROY_MS 没活干就销毁引擎。 */
    private static void scheduleIdleCheck() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (engine == null) {
                        return;
                    }
                    long idle = System.currentTimeMillis() - lastActivityAt;
                    if (inflight != null) {
                        // 还有一张在识别中，不能销毁
                        scheduleIdleCheck();
                        return;
                    }
                    if (inflight != null) {
                        // 还有一张在识别中，不能销毁
                        scheduleIdleCheck();
                        return;
                    }
                    if (idle >= IDLE_DESTROY_MS) {
                        destroyEngine();
                    } else {
                        scheduleIdleCheck();
                    }
                } catch (Throwable t) {
                    Logx.w("[headless] 空闲检查失败：" + t.getMessage());
                }
            }
        }, 15_000L);
    }

    /**
     * 销毁引擎并复位全部状态。
     *
     * <p>必须复位 {@code started}，否则下次点击不会再起引擎（这正是"用完不销毁"的隐患）。
     */
    private static void destroyEngine() {
        Object e = engine;
        if (e == null) {
            return;
        }
        try {
            e.getClass().getMethod("destroy").invoke(e);
            Logx.i("[headless] 空闲超时，引擎已销毁（省内存）");
        } catch (Throwable t) {
            Logx.w("[headless] 引擎销毁失败：" + t.getMessage());
        }
        engine = null;
        channel = null;
        channelMessenger = null;
        dartReady = false;
        started = false;
        pendingPath = null;
        lastDispatched = null;
    }

    private static String newestShot() {
        try {
            RootShell.Result r = RootShell.exec(
                    "ls -t /sdcard/DCIM/Screenshots/*beecountshot* 2>/dev/null | head -1", 5000L);
            String f = r.out == null ? "" : r.out.trim();
            return f.length() > 0 ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 带重试的派发：取到的最新图若已经派发过（说明新图还没落盘），等一会儿再试。
     * 最多试 3 次，总共约 3.6 秒。
     */
    private static void attemptDispatch(final int attempt) {
        String newest = pendingPath != null ? pendingPath : newestShot();
        if (newest != null && newest.equals(lastDispatched)) {
            // 这张已经派发过：
            //  - 还在处理中 → 说明是同一次点击的重复触发，直接忽略（不空转）
            //  - 已经处理完 → 可能是新图还没落盘，等一会儿重试
            if (inflight != null) {
                Logx.i("[headless] 这张还在处理中，忽略重复派发");
                return;
            }
            if (attempt >= 3) {
                Logx.i("[headless] 这张已处理过且没有新图，跳过");
                return;
            }
            Logx.i("[headless] 最新还是上一张（" + RootShell.baseName(newest) + "），"
                    + (attempt + 1) + " 秒后再试");
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    attemptDispatch(attempt + 1);
                }
            }, 1200L);
            return;
        }
        pendingPath = newest;
        dispatchNow();
    }

    private static void dispatchNow() {
        if (pendingPath == null) {
            pendingPath = newestShot();
            if (pendingPath != null) {
                Logx.i("[headless] 未指定路径，取最新截图：" + RootShell.baseName(pendingPath));
            }
        }
        String path = pendingPath;
        Object mc = channel;
        if (path == null || mc == null) {
            Logx.w("[headless] 还没东西可派发（path=" + path + " channel=" + (mc != null) + "）");
            return;
        }
        if (!dartReady) {
            Logx.w("[headless] Dart 还没就绪，等它 enable 完成");
            return;
        }
        if (path.equals(lastDispatched)) {
            Logx.i("[headless] 这张已经派发过，跳过（防重复记账）");
            pendingPath = null;
            return;
        }
        lastDispatched = path;
        dispatchedPath = path;
        inflight = path;
        inflight = path;
        lastActivityAt = System.currentTimeMillis();
        scheduleIdleCheck();
        pendingPath = null;
        try {
            // 带 Result 回调派发 —— 这是**唯一**能看到 Dart 侧结果的手段：
            //   success       → Dart 跑完了
            //   error         → Dart 抛了异常（拿到具体文本）
            //   notImplemented→ Dart 压根没注册处理器（enable 没走到）
            // 用 channel 实例自己的类加载器 —— 它必然和 MethodChannel 同源，
            // 不会出现"模块加载器看不到 Flutter 类"的老问题。
            ClassLoader chLoader = mc.getClass().getClassLoader();
            Logx.i("[headless] channelLoader=" + (chLoader != null) 
                    + " hostClsLoader=" + (hostClsLoader != null));
            Class<?> resultCls = Class.forName(
                    "io.flutter.plugin.common.MethodChannel$Result", true, chLoader);
            Object cb = Proxy.newProxyInstance(resultCls.getClassLoader(),
                    new Class<?>[]{resultCls}, new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] a) {
                            String n = method.getName();
                            inflight = null;
                            inflight = null;
                            if ("success".equals(n)) {
                                Logx.i("[headless] Dart 处理完成，返回=" 
                                        + (a != null && a.length > 0 ? a[0] : "null"));
                                // 它认了（账记上了），但不会写我们依赖的"落库完成"日志，
                                // 所以这里以回执为准去删图，别再等配对。
                                final String p = dispatchedPath;
                                cleanedPath = p;
                                cleanedPath = p;
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            Thread.sleep(1500L);
                                        } catch (InterruptedException ignored) {
                                        }
                                        ShotCleaner.deleteAfterHeadless(appCtx, p);
                                    }
                                }, "bee-headless-clean").start();
                            } else if ("error".equals(n)) {
                                Logx.e("[headless] Dart 报错：" 
                                        + (a != null && a.length > 0 ? a[0] : "")
                                        + " / " + (a != null && a.length > 1 ? a[1] : "")
                                        + " / " + (a != null && a.length > 2 ? a[2] : ""), null);
                            } else if ("notImplemented".equals(n)) {
                                Logx.w("[headless] Dart 没注册处理器（notImplemented）——enable 没走到");
                            }
                            return null;
                        }
                    });
            mc.getClass().getMethod("invokeMethod", String.class, Object.class, resultCls)
                    .invoke(mc, "onScreenshotDetected", path, cb);
            Logx.i("[headless] 已派发截图（等 Dart 回调）：" + RootShell.baseName(path));

            // 兜底：20 秒内没收到回执也照样删（AI 通常 6~10 秒，20 秒足够）。
            // 政策是"磁贴截的图最终都要删"，不管有没有记成账——
            // 不能因为 Dart 卡住/崩溃就把图永远留在相册里。
            final String wdPath = path;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!wdPath.equals(cleanedPath)) {
                        Logx.w("[headless] 20 秒没回执，兜底删图：" + RootShell.baseName(wdPath));
                        ShotCleaner.deleteAfterHeadless(appCtx, wdPath);
                    }
                }
            }, 20_000L);


        } catch (Throwable t) {
            Logx.e("[headless] 派发失败", t);
        }
    }
}
