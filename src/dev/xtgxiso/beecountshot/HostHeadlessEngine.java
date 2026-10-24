package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

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


    /**
     * 空闲多久就把引擎销毁（省内存）。
     *
     * <p>实测一台 1080p 机器：引擎活着时宿主进程 PSS **105 MB**，销毁后 **74 MB**——
     * 一个闲着不干活的 Flutter 引擎值 **约 31 MB**。原来这里是 45 秒（最长拖到 60 秒才检查到），
     * 也就是每点一次磁贴，就为"可能马上再点一次"这个假设多压 31MB 半分钟。
     *
     * <p>压到 12 秒（从**派发那一刻**起算，不是从识别结束算——识别自己花的时间也算在窗口里，
     * 这样识别越慢、引擎反而越早被回收）：还能接住"连点两张 / 失败后马上重试"，
     * 真要再点时最多多花约 1 秒重建。实际存活 = 12~16 秒（检查粒度 4 秒）。
     */
    private static final long IDLE_DESTROY_MS = 12_000L;

    /** 空闲检查的粒度。想"到点就销毁"，粒度不能比阈值还粗（原来 15 秒粒度会把 15 秒阈值拖成 30 秒）。 */
    private static final long IDLE_CHECK_MS = 4_000L;

    /** 只保留一条空闲检查链。 */
    private static volatile boolean idleWatchRunning = false;

    /** 最后一次"有活干"的时刻。 */
    private static volatile long lastActivityAt = 0L;

    /** 派发了但还没收到回执的路径。非空时不允许销毁引擎（否则把正在跑的识别掐死）。 */
    private static volatile String inflight;


    /** 界面引擎出现时有活在飞，等回执完再撤。 */
    private static volatile boolean yieldPending;

    /** 已经删过的路径（兜底时用来跳过）。 */
    private static volatile String cleanedPath;

    private static volatile String lastDispatched;

    /**
     * 已经派发过的截图路径（最近若干张）。
     *
     * <p>为什么不能只靠 {@link #lastDispatched}：模块侧为了让广播穿过冻结窗口，
     * 会把**同一条派发广播重发 3 次**。若两次重发之间已经派发了别的图，
     * `lastDispatched` 就换人了，迟到的重发会被当成"新图"再派一次
     * —— 实测 8 次点击产生 20 次派发，同一张图被 AI 重复识别。
     * 所以这里留一份"派发历史"，同路径只认一次。
     */
    private static final java.util.LinkedHashSet<String> DISPATCHED_HISTORY =
            new java.util.LinkedHashSet<String>();

    /** 派发历史最多记多少张（超过就丢最早的，控制内存）。 */
    private static final int DISPATCHED_HISTORY_MAX = 32;

    /** 应用 Context，删图时要用。 */
    private static volatile Context appCtx;

    /** 宿主的类加载器（Flutter 的类只有它能看见）。 */
    private static volatile ClassLoader hostClsLoader;

    /** 宿主应用自己的 uid（加路由规则用）。 */
    private static volatile int hostUid = -1;

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

    /**
     * 界面引擎出现了 —— 让位。
     *
     * <p>两个 Dart 实例在同一个进程里会抢同一份 prefs（实测抛
     * {@code ConcurrentModificationException: AutoBillingService._loadProcessedScreenshots}），
     * 所以界面一起来就尽快把我们的引擎撤掉，把并存时间压到最短。
     *
     * <p>但如果此刻还有一张截图在识别中，就先不动 —— 中途销毁会把这次记账掐死。
     * 等它回执完（{@code inflight} 清空）再销毁。
     */
    static void yieldToUiEngine() {
        if (engine == null) {
            return;
        }
        if (inflight != null) {
            Logx.i("[headless] 界面引擎出现了，但还有一张在识别中，等它完成再撤");
            // 回执回调里会再看一次
            yieldPending = true;
            return;
        }
        Logx.i("[headless] 界面引擎出现了，撤掉无界面引擎（避免两个 Dart 抢同一份 prefs）");
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                destroyEngine();
            }
        });
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
        // **必须入队，不能只写 pendingPath**：连点时（0.3~1 秒一张）多次 boot 会互相覆盖
        // 同一个 pendingPath，等 1.2 秒后真正派发时只剩最后一张，前面的被静默丢掉
        // （实测连点 6 次只派发 5 张，233804 那张彻底没识别）。
        if (path != null) {
            enqueue(path);
        }
        if (started) {
            // 已经在跑。文件在模块侧"已写入相册"之后才发请求，已经落盘，
            // 所以只留很短的延迟（让上一次的写盘/相册插入完成），然后从队列取下一张。
            lastActivityAt = System.currentTimeMillis();
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    drainQueue();
                }
            }, 400L);
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
        hostUid = ctx.getApplicationInfo().uid;
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
                                // 等它把 enable() 剩下的两行跑完（置 _isEnabled/_isMonitoring），
                                // 再把截图送进去。
                                //
                                // 这个等待原来是 600ms，属于保守值：Dart 那边 await 我们的回执后
                                // 紧接着就置位，实测 250ms 足够；再短就有"标志没置位、图被静默忽略"的风险。
                                dartReady = true;
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        drainQueue();
                                    }
                                }, 250L);
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
    /**
     * 空闲检查：每 {@link #IDLE_CHECK_MS} 看一次，超过 {@link #IDLE_DESTROY_MS} 没活干就销毁。
     *
     * <p>整条链只跑一份：以前每次派发都起一个新链，点三次就有三条同时在数，
     * 销毁时还要靠 `engine == null` 各自退出——纯粹浪费唤醒。
     */
    private static final Runnable IDLE_TICK = new Runnable() {
        @Override
        public void run() {
            try {
                if (engine == null) {
                    idleWatchRunning = false;
                    return;
                }
                long idle = System.currentTimeMillis() - lastActivityAt;
                if (inflight == null && idle >= IDLE_DESTROY_MS) {
                    idleWatchRunning = false;
                    destroyEngine();
                    Logx.i("[headless] 空闲 " + (idle / 1000L) + "s，引擎已销毁（省内存）");
                    return;
                }
                new Handler(Looper.getMainLooper()).postDelayed(this, IDLE_CHECK_MS);
            } catch (Throwable t) {
                idleWatchRunning = false;
                Logx.w("[headless] 空闲检查失败：" + t.getMessage());
            }
        }
    };

    private static void scheduleIdleCheck() {
        if (idleWatchRunning) {
            return;   // 已经在数了
        }
        idleWatchRunning = true;
        new Handler(Looper.getMainLooper()).postDelayed(IDLE_TICK, IDLE_CHECK_MS);
    }

    /**
     * 销毁引擎并复位全部状态。
     *
     * <p>必须复位 {@code started}，否则下次点击不会再起引擎（这正是"用完不销毁"的隐患）。
     */
    private static void destroyEngine() {
        Object e = engine;
        idleWatchRunning = false;
        if (e == null) {
            return;
        }
        try {
            e.getClass().getMethod("destroy").invoke(e);
            Logx.i("[headless] 引擎已销毁（省内存）");
        } catch (Throwable t) {
            Logx.w("[headless] 引擎销毁失败：" + t.getMessage());
        }
        TunnelRoute.release(hostUid);
        synchronized (QUEUE) {
            QUEUE.clear();   // 引擎都没了，排队的图派不出去（模块侧的清理器会兜底删掉）
        }
        engine = null;
        channel = null;
        channelMessenger = null;
        dartReady = false;
        started = false;
        pendingPath = null;
        lastDispatched = null;
    }

    /**
     * 请**模块 App** 去删这张图（不再用宿主自己的 root）。
     *
     * <p>为什么改成这样：删图那一步原来是宿主进程里 `su rm`，等于要求蜜蜂记账也拿到 root。
     * 但图是模块写进相册的、root 也在模块手里，让模块删更顺；宿主只负责"通知一声"。
     * 走 {@link Const#ACTION_RESULT}（processed=true / deleted=false）——
     * 模块侧收到就立刻删，不需要等它自己的清理周期。
     */
    private static void pushDeleteToModule(String path) {
        try {
            if (path == null || path.length() == 0) {
                return;
            }
            android.content.Intent it = new android.content.Intent(Const.ACTION_RESULT);
            it.setPackage(Const.MODULE_PKG);
            it.putExtra(Const.EXTRA_DECISIVE, true);
            it.putExtra(Const.EXTRA_PATH, path);
            it.putExtra(Const.EXTRA_PROCESSED, true);
            it.putExtra(Const.EXTRA_SUCCESS, true);
            it.putExtra(Const.EXTRA_DELETED, false);
            it.addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND);
            appCtx.sendBroadcast(it);
            Logx.i("[headless] 请模块删图：" + RootShell.baseName(path));
        } catch (Throwable t) {
            Logx.w("[headless] 通知模块删图失败：" + t.getMessage());
        }
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
        // 不再 newestShot 兜底（同 dispatchNow 的理由：连点时取最新会取错、丢图）。
        // 没有明确 pendingPath 就说明"只被要求启动引擎、还没指定派发哪张"，直接等。
        if (pendingPath == null) {
            Logx.i("[headless] 还没有明确路径，等指定路径派发");
            return;
        }
        String newest = pendingPath;
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
        // 开着 fake-ip 代理时，MIUI 的后台限制会让它的流量绕开隧道
        // （域名解析成 198.18.x 假地址，隧道外根本不可达，SYN 永远发不出去）。
        // 所以派发前先把它的路由掰回隧道里。实测这样就能从"卡死 40 秒"变成"9 秒识别完"。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    TunnelRoute.ensure(appCtx, hostUid);
                } catch (Throwable ignored) {
                }
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        dispatchNow();
                    }
                });
            }
        }, "bee-tunnel-route").start();
    }

    /**
     * 等派发的截图队列。**一次只派一张**：Dart 那边同一时刻只处理一张，
     * 同时塞进去两张会互相覆盖 {@code inflight}/{@code dispatchedPath}，
     * 结果是"有一张没识别 + 删错文件"。
     *
     * <p>为什么必须有队列：连点时（间隔 2~3 秒）识别还没回来，下一张就到了。
     * 以前这里是"直接忽略"，等于**把这张图静默丢掉**——用户点了却没记账。
     */
    private static final java.util.ArrayDeque<String> QUEUE = new java.util.ArrayDeque<String>();

    /** 队列上限。真排到这个数说明识别已经严重跟不上，多留也没意义。 */
    private static final int QUEUE_MAX = 12;

    /**
     * 把一张要派发的图放进队列（去重）。
     *
     * <p>去重有两个来源：已经派发过的（{@link #DISPATCHED_HISTORY}）不再放；
     * 队列里已有的不重复放（模块侧的补发广播会重复请求同一张）。
     */
    private static void enqueue(String path) {
        if (path == null || path.length() == 0) {
            return;
        }
        synchronized (QUEUE) {
            if (DISPATCHED_HISTORY.contains(path) || QUEUE.contains(path)) {
                return;
            }
            while (QUEUE.size() >= QUEUE_MAX) {
                String dropped = QUEUE.pollFirst();
                Logx.w("[headless] 队列满了，丢掉最早的一张：" + RootShell.baseName(dropped));
            }
            QUEUE.addLast(path);
        }
    }

    /** 从队列取下一张派发（手上没有明确目标时才取）。 */
    private static void drainQueue() {
        if (pendingPath == null) {
            synchronized (QUEUE) {
                pendingPath = QUEUE.pollFirst();
            }
        }
        if (pendingPath == null) {
            Logx.i("[headless] 队列空，等下一次派发请求");
            return;
        }
        attemptDispatch(0);
    }

    private static void dispatchNow() {
        // 不再用 newestShot() 兜底取图：连点时上一张图还没删，newestShot 会取错——
        // 要么取到上一张（被 lastDispatched 判重跳过、本次图漏掉），要么重复排队。
        // 现在截图成功后会走"指定路径派发"（见 ShotTileService），路径一定给到，
        // 所以这里只在 pendingPath 明确时才派发；null 说明还没有明确目标，直接等。
        String path = pendingPath;
        Object mc = channel;
        if (path == null || mc == null) {
            Logx.w("[headless] 还没有明确路径可派发（path=" + path + " channel=" + (mc != null) + "）");
            return;
        }
        if (!dartReady) {
            Logx.w("[headless] Dart 还没就绪，等它 enable 完成");
            return;
        }
        if (path.equals(lastDispatched) || DISPATCHED_HISTORY.contains(path)) {
            Logx.i("[headless] 这张已经派发过，跳过（防重复记账）");
            pendingPath = null;
            return;
        }
        // 上一张还在识别：排队，等它回执后自动续派（以前是直接丢掉这张）
        if (inflight != null) {
            synchronized (QUEUE) {
                if (!QUEUE.contains(path)) {
                    if (QUEUE.size() >= QUEUE_MAX) {
                        String dropped = QUEUE.pollFirst();
                        Logx.w("[headless] 队列满了，丢掉最早的一张：" + RootShell.baseName(dropped));
                    }
                    QUEUE.addLast(path);
                    Logx.i("[headless] 上一张还在识别，排队等派发（队列 "
                            + QUEUE.size() + "）：" + RootShell.baseName(path));
                }
            }
            pendingPath = null;
            return;
        }
        final String dispatchPath = path;
        lastDispatched = path;
        synchronized (DISPATCHED_HISTORY) {
            DISPATCHED_HISTORY.add(path);
            while (DISPATCHED_HISTORY.size() > DISPATCHED_HISTORY_MAX) {
                java.util.Iterator<String> it = DISPATCHED_HISTORY.iterator();
                it.next();
                it.remove();
            }
        }
        dispatchedPath = path;
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
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    TunnelRoute.release(hostUid);
                                }
                            }, "bee-tunnel-release").start();
                            if (yieldPending) {
                                yieldPending = false;
                                new Handler(Looper.getMainLooper()).post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (inflight == null) {
                                            Logx.i("[headless] 识别完成，界面引擎在，撤掉无界面引擎");
                                            destroyEngine();
                                        }
                                    }
                                });
                            }
                            if ("success".equals(n)) {
                                Logx.i("[headless] Dart 处理完成，返回=" 
                                        + (a != null && a.length > 0 ? a[0] : "null"));
                                // 它认了（账记上了），但不会写我们依赖的"落库完成"日志，
                                // 所以这里以回执为准去删图，别再等配对。
                                //
                                // 用**闭包里捕获的那张**，不读 dispatchedPath：
                                // 连点时全局变量可能已经被下一张覆盖，读它会删错文件。
                                //
                                // 这里曾经固定 sleep 1500ms 再删，理由是"怕它还没写完库"。
                                // 但回执本身就是 Dart 处理函数的返回值——库在返回之前就写完了，
                                // 这 1.5 秒纯属白等，而且它压在"点磁贴 → 图消失"这条感知路径上。
                                // 现在只留 250ms 给它的通知/相册扫描一点余量。
                                final String p = dispatchPath;
                                cleanedPath = p;
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            Thread.sleep(250L);
                                        } catch (InterruptedException ignored) {
                                        }
                                        // 注意：这里跑在**宿主进程**里，`ShotCleaner` 的队列是
                                        // 这个进程自己的一份静态状态，和模块 App 里的那个不是同一个对象。
                                        // 所以不能在这里"撤销任务"——那只会操作一个空队列。
                                        // 模块侧靠"扫盘为空就收工"提前结束（见 ShotCleaner 的扫盘段）。
                                        pushDeleteToModule(p);
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
                            // 这一张结束了：看队列里还有没有连点排下的下一张，有就立刻续派。
                            // 不管上面是 success / error / notImplemented 都要续——
                            // 卡住一张不能把后面排队的全废掉。
                            final String next;
                            synchronized (QUEUE) {
                                next = QUEUE.pollFirst();
                            }
                            if (next != null) {
                                Logx.i("[headless] 继续派发排队中的截图：" + RootShell.baseName(next));
                                new Handler(Looper.getMainLooper()).post(new Runnable() {
                                    @Override
                                    public void run() {
                                        pendingPath = next;
                                        dispatchNow();
                                    }
                                });
                            }
                            return null;
                        }
                    });
            mc.getClass().getMethod("invokeMethod", String.class, Object.class, resultCls)
                    .invoke(mc, "onScreenshotDetected", path, cb);
            Logx.i("[headless] 已派发截图（等 Dart 回调）：" + RootShell.baseName(path));

            // 不再做"12 秒无回执就重发"：重发会让同一张图被 Dart 处理两次，
            // 第二次时它内部状态已乱、抛异常 → 蜜蜂记账弹「识别失败」通知（实测连点时出现）。
            // 现在有 binder 续命解冻，识别不会卡死；即便真卡死，下面的 20 秒兜底删图
            // 也会把图清掉、不留残留，所以重试已经没有必要了。

            // 兜底：20 秒内没收到回执也照样删（AI 通常 6~10 秒，20 秒足够）。
            // 政策是"磁贴截的图最终都要删"，不管有没有记成账——
            // 不能因为 Dart 卡住/崩溃就把图永远留在相册里。
            final String wdPath = path;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!wdPath.equals(cleanedPath)) {
                        Logx.w("[headless] 20 秒没回执，兜底删图：" + RootShell.baseName(wdPath));
                        pushDeleteToModule(wdPath);
                    }
                }
            }, 20_000L);


        } catch (Throwable t) {
            Logx.e("[headless] 派发失败", t);
        }
    }
}
