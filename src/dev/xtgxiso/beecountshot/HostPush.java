package dev.xtgxiso.beecountshot;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 宿主进程内：**主动把"有新截图"这条消息塞进蜜蜂记账的 Dart 侧**。
 *
 * <h3>为什么需要它</h3>
 * 蜜蜂记账的截图识别是 <b>Dart 代码</b>，只有它的 {@code MethodChannel} 能叫醒。
 * 而它自己的触发方式是"注册一个 MediaStore 观察者"，这个观察者在
 * {@code MainActivity.onDestroy} 里被<b>无条件注销</b>——界面一没了，它就再也感知不到截图。
 *
 * <p>但它接消息的那一端还在：Dart 侧 {@code screenshot_monitor_service.dart} 里
 * {@code _channel.setMethodCallHandler(...)} 注册的处理器，只要 Dart isolate 活着就一直在。
 * 配合"引擎保活"（{@link BeeShotModule} 里的 {@code host-engine-keepalive}），
 * **界面销毁后这个处理器依然有效**。
 *
 * <p>所以这里做的就是把原生侧本来就会做的事（{@code invokeMethod("onScreenshotDetected", path)}）
 * 由我们自己来发一次 —— **完全绕过那个已经没了的观察者**。
 *
 * <h3>怎么拿到 messenger</h3>
 * 不额外加 hook：现有的通道闸门（{@code gate-flutter-channel}）拦的就是
 * {@code MethodChannel.invokeMethod("onScreenshotDetected", ...)}，
 * 那时 {@code chain.getThisObject()} 正是那个截图通道实例，从它身上把 messenger 抠出来即可。
 *
 * <h3>安全边界</h3>
 * 调用方**只能在"观察者确实没反应"时才推**，否则同一张图会被上报两次、记两笔账。
 */
final class HostPush {

    /** 蜜蜂记账的截图通道名（上游 MainActivity.SCREENSHOT_CHANNEL）。 */
    static final String CHANNEL = "com.tntlikely.beecount/screenshot";

    private static volatile Object messenger;

    static boolean ready() {
        return messenger != null;
    }

    /**
     * 直接收下一个 messenger（来自 {@code MethodChannel} 构造函数的 hook）。
     *
     * <p>比 {@link #captureFrom} 更早：只要蜜蜂记账**创建**了那个截图通道就能拿到，
     * 不必先等它成功上报一次截图。
     */
    static void stash(Object m) {
        if (m == null) {
            return;
        }
        boolean changed = (m != messenger);
        messenger = m;
        if (changed) {
            Logx.i("[push] messenger 已就位（来自通道构造）");
        }
    }

    /** 从拦到的 MethodChannel 实例上取出 BinaryMessenger（字段名可能不同，按类型找）。 */
    static void captureFrom(Object methodChannel) {
        if (methodChannel == null) {
            return;
        }
        try {
            Class<?> c = methodChannel.getClass();
            while (c != null && c != Object.class) {
                Field[] fields = c.getDeclaredFields();
                for (int i = 0; i < fields.length; i++) {
                    Field f = fields[i];
                    String t = f.getType().getName();
                    if (t.contains("BinaryMessenger")) {
                        f.setAccessible(true);
                        Object v = f.get(methodChannel);
                        if (v != null) {
                            // **每次都刷新**：FlutterEngine 会随 Activity 重建而更换，
                            // 缓存旧的 messenger 等于推给一个已经死掉的引擎——v2.33 就是这么失败的。
                            boolean changed = (v != messenger);
                            messenger = v;
                            if (changed) {
                                Logx.i("[push] captured BinaryMessenger (engine may be new)");
                            }
                            return;
                        }
                    }
                }
                c = c.getSuperclass();
            }
            Logx.w("[push] BinaryMessenger field not found on " + methodChannel.getClass());
        } catch (Throwable t) {
            Logx.e("[push] capture failed", t);
        }
    }

    /**
     * 主动往 Dart 侧发一条"检测到新截图"。
     *
     * <p>注意：这条调用会**再次经过我们自己的通道闸门**（它拦的就是这个方法），
     * 所以调用时机必须落在本次点击的放行窗口内，否则会被自己的闸门拦掉。
     *
     * @return true = 消息已发出（不代表 Dart 一定识别成功）
     */
    static boolean push(String path) {
        Object m = messenger;
        if (m == null) {
            Logx.w("[push] 没有 messenger（宿主还没走过一次正常截图流程？）");
            return false;
        }
        if (path == null || path.length() < 8) {
            return false;
        }
        try {
            Class<?> channelClass = Class.forName("io.flutter.plugin.common.MethodChannel", false,
                    m.getClass().getClassLoader());
            Class<?> messengerClass = Class.forName("io.flutter.plugin.common.BinaryMessenger", false,
                    m.getClass().getClassLoader());
            Constructor<?> ctor = channelClass.getConstructor(messengerClass, String.class);
            ctor.setAccessible(true);
            Object channel = ctor.newInstance(m, CHANNEL);

            // 带 Result 回调的版本：能直接看出 Dart 到底收下没有。
            // success/error 之外还有一个 notImplemented —— 那是"Dart 侧没有这个处理器"，
            // 也就是说消息压根没人接（引擎活着但 isolate 不认这个通道）。
            Class<?> resultClass = Class.forName(
                    "io.flutter.plugin.common.MethodChannel$Result", false,
                    m.getClass().getClassLoader());
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    resultClass.getClassLoader(), new Class<?>[]{resultClass},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("notImplemented".equals(method.getName())) {
                                Logx.w("[push] Dart 侧没有处理这个通道（notImplemented）：消息没人接");
                            } else if ("success".equals(method.getName())) {
                                Logx.i("[push] Dart 已接收（success）");
                            } else if ("error".equals(method.getName())) {
                                Logx.w("[push] Dart 处理出错：" + (args != null && args.length > 0
                                        ? String.valueOf(args[0]) : "?"));
                            }
                            return null;
                        }
                    });
            Method invoke = channelClass.getMethod(
                    "invokeMethod", String.class, Object.class, resultClass);
            invoke.setAccessible(true);
            invoke.invoke(channel, Const.TARGET_METHOD, path, proxy);
            Logx.i("[push] pushed onScreenshotDetected: " + RootShell.baseName(path));
            return true;
        } catch (Throwable t) {
            Logx.e("[push] push failed", t);
            return false;
        }
    }

    private HostPush() {
    }
}
