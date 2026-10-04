package dev.xtgxiso.beecountshot;

/**
 * 共享常量与路径拼装。
 *
 * <p>两条原则，都是被真机坑出来的：
 * <ol>
 *   <li><b>不写死包名。</b>蜂蜜记账有 prod / dev 两个构建风味，用户装哪个不确定。
 *       包名写错的表现是"静默全失效"。</li>
 *   <li><b>不写死数据目录。</b>真机上出现过"包名能查到、但 /data/user/0/&lt;包名&gt; 不存在"
 *       的情况（双开 / 分身 / 非标准用户 id）。数据目录必须运行时探测。</li>
 * </ol>
 * 下面所有路径都由"实际探测到的数据目录"现拼。
 */
public final class Const {

    /** 宿主候选包名（顺序即优先级）。 */
    public static final String[] HOST_PKGS = new String[]{
            "com.tntlikely.beecount",
            "com.tntlikely.beecount.dev",
    };

    /** 默认包名，仅作为探测失败时的兜底。 */
    public static final String HOST_PKG = HOST_PKGS[0];

    /** 宿主里那个"截图监听器 -> Flutter 通道"的方法名，作为拦截闸门的识别依据。 */
    public static final String TARGET_METHOD = "onScreenshotDetected";

    /**
     * 磁贴 -> 宿主进程 的"放行这一次"定向广播。
     *
     * <p>这是唯一的放行通道（早期的"写放行文件"方案已废弃，原因见 {@link ArmSignal}）。
     */
    public static final String ACTION_ARM = "dev.xtgxiso.beecountshot.ARM";

    /** 广播里携带的放行截止时间。 */
    public static final String EXTRA_UNTIL = "until_ms";

    /** 模块 App 的包名（宿主回广播时用）。 */
    public static final String MODULE_PKG = "dev.xtgxiso.beecountshot";

    /**
     * 模块 App -> 宿主 的查询广播。
     *
     * <p>为什么必须用广播而不是读文件：真机上查明，root 的 {@code su} 跑在**全局挂载命名
     * 空间**，而应用的数据目录（CE 存储）挂载在应用自己的命名空间里——所以 {@code su}
     * 根本看不到蜜蜂记账的数据目录，连 {@code dumpsys} 给出的
     * {@code /data/user/0/&lt;包名&gt;} 都是"不存在"。
     *
     * <p>后果是：模块 App 写进去的放行文件宿主读不到，宿主写的遥测我们也读不到，
     * 而且**全程不报错**。广播不涉及任何文件路径，是这条链路上唯一可靠的方式。
     */
    public static final String ACTION_QUERY = "dev.xtgxiso.beecountshot.QUERY";

    /** 宿主 -> 模块 App 的回答广播。 */
    public static final String ACTION_RESULT = "dev.xtgxiso.beecountshot.RESULT";

    /** 查询/回答里携带的截图路径。 */
    public static final String EXTRA_PATH = "path";

    /** 该截图是否被宿主处理过。 */
    public static final String EXTRA_PROCESSED = "processed";

    /** 最近一次「自动记账成功」的时间戳。 */
    public static final String EXTRA_SUCCESS_TS = "success_ts";

    /** 宿主自己的 cacheDir——用来证实"su 看不到应用数据"这件事。 */
    public static final String EXTRA_HOST_CACHE = "host_cache_dir";

    /** 宿主侧遥测字段（直接以字符串形式塞进广播）。 */
    public static final String EXTRA_TELEMETRY = "telemetry";

    /**
     * 是否为"决定性推送"。
     *
     * <p>普通回答是被动应答；决定性推送是宿主**主动**在检测到记账成功的那一刻发出的，
     * 用来把模块 App 从系统冻结中唤醒。真机上就是因为应用退回后台被冻结，两条线程
     * 一起停了近 30 秒，表现为"记账成功后又等了 20 多秒才删"。
     */
    public static final String EXTRA_DECISIVE = "decisive";

    /**
     * 宿主是否**已经自己把截图删掉了**。
     *
     * <p>删除动作现在主要由宿主完成：模块 App 会被系统后台冻结，而"广播唤醒缓存态应用"
     * 会被延迟投递，靠它自己删就慢十几二十秒。宿主那一刻是醒着的，由它动手延迟只剩日志节流。
     */
    public static final String EXTRA_DELETED = "deleted";

    /**
     * 宿主给出的**最终判定**：这张截图确实被成功记账了，可以删。
     *
     * <p>为什么必须由宿下来判定而不是模块 App 自己算：判定要同时用到两个事实——
     * "放行的是哪张图"（{@code LastShot}）和"成功发生在这个放行之后"。
     * 只有宿主进程同时握有这两者。
     *
     * <p>反例（真机踩过）：模块 App 侧原来只检查"成功时间晚于本次截图"，结果一张
     * **识别失败**（"未识别到账单"）的主页截图，因为前一次成功的时间恰好满足这个宽松条件，
     * 被误删了。
     */
    public static final String EXTRA_SUCCESS = "success";

    /** 蜜蜂记账的截图监听能力当前是否在位（主界面是否还活着）。 */
    public static final String EXTRA_ACTIVITY_ALIVE = "activity_alive";

    /** 宿主里成功安装的 hook 条数 / 失败条数（设置页直接显示，省得翻日志）。 */
    public static final String EXTRA_HOOK_COUNT = "hook_count";
    public static final String EXTRA_HOOK_FAILED = "hook_failed_count";

    // ---------------------------------------------------------------- 桌面小组件

    /** 模块 App -> 宿主：请求日/周/月支出合计。 */
    public static final String ACTION_WIDGET_QUERY = "dev.xtgxiso.beecountshot.WIDGET_QUERY";

    /** 宿主 -> 模块 App：带回三个合计。 */
    public static final String ACTION_WIDGET_RESULT = "dev.xtgxiso.beecountshot.WIDGET_RESULT";

    public static final String EXTRA_DAY_EXPENSE = "day_expense";
    public static final String EXTRA_WEEK_EXPENSE = "week_expense";
    public static final String EXTRA_MONTH_EXPENSE = "month_expense";
    public static final String EXTRA_CURRENCY = "currency";
    public static final String EXTRA_OK = "ok";
    public static final String EXTRA_DETAIL = "detail";

    /**
     * 宿主 -> 模块 App：数据变了，请静默刷新桌面小组件（不打开任何界面）。
     *
     * <p>这条广播发给我们**清单里声明的** {@code ExpenseWidgetProvider}，所以即使模块 App
     * 进程已经被系统回收，也能被它唤起来完成刷新。
     */
    public static final String ACTION_WIDGET_PING = "dev.xtgxiso.beecountshot.WIDGET_PING";

    /** 数据库文件名（蜜蜂记账用 drift，库放在应用文档目录）。 */
    public static final String DB_NAME = "beecount.sqlite";

    /** 统一日志 TAG。 */
    public static final String TAG = "BeeShot";

    /** 日志导出到 Download 时的子目录。 */
    public static final String LOG_DIR = "BeecountShot";

    /** logcat 导出到 Download 后的路径。 */
    public static final String LOGCAT_PATH = "/sdcard/Download/" + LOG_DIR + "/logcat.txt";

    /** 放行信号文件名（历史遗留：文件通道已废弃，见 ArmSignal 的说明）。 */
    public static final String ARM_FILE_NAME = "beecount_shot_arm";

    /** 宿主日志文件名。 */
    public static final String HOST_LOG_NAME = "beecount_shot_host.log";

    /**
     * 放行窗口的额外宽限时间。实际窗口 = 触发延迟 + 这个宽限值。
     *
     * <p>需要覆盖：面板收起 + 系统落库 + ContentObserver 回调 + Flutter 通道调用，
     * 再留足余量。9 秒足够宽松，同时不会因为窗口太长而误放行普通截图。
     */
    public static final long ARM_GRACE_MS = 9000L;

    // ---------------------------------------------------------------- 宿主路径

    /** 宿主自己进程日志的候选路径（宿主写自己的目录；属辅助通道，主通道是广播遥测）。 */
    public static String[] hostLogCandidates(String pkg) {
        return new String[]{
                "/data/user/0/" + pkg + "/cache/" + HOST_LOG_NAME,
                "/data/data/" + pkg + "/cache/" + HOST_LOG_NAME,
                "/data/user_de/0/" + pkg + "/cache/" + HOST_LOG_NAME,
        };
    }

    // ---------------------------------------------------------------- 判定关键词

    /** 记账成功的日志文案（取自 AutoBillingService.processScreenshot）。 */
    public static final String SUCCESS_LOG_MARKER = "自动记账成功";

    /** Dart 日志在 SharedPreferences 里的 key。 */
    public static final String K_APP_LOGS = "flutter.app_logs";

    /** 已处理截图列表在 SharedPreferences 里的 key。 */
    public static final String K_PROCESSED = "flutter.processed_screenshots";

    /** 截图可能存在的目录（各厂商不同；不列 /storage/emulated/0 那种重复写法）。 */
    public static final String[] SCREENSHOT_DIRS = new String[]{
            "/sdcard/Pictures/Screenshots",
            "/sdcard/DCIM/Screenshots",
            "/sdcard/Screenshots",
    };

    private Const() {
    }
}
