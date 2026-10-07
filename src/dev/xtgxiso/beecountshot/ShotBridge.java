package dev.xtgxiso.beecountshot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 模块 App 侧：通过**广播**向宿主问问题（跨进程主通道）。
 *
 * <p>为什么不用文件/root 读：真机查明 {@code su} 跑在全局挂载命名空间，看不到别的应用的
 * 数据目录（连 {@code dumpsys} 给出的路径都"不存在"）。而广播完全不涉及文件路径，
 * 是这条链路上唯一可靠的方式。
 *
 * <p>用法是同步的：{@link #query} 发一条查询广播，阻塞等宿主在同一进程里读自己的
 * SharedPreferences 后回过来，超时返回未收到。
 */
final class ShotBridge {

    /** 宿主返回的答案。 */
    static final class Answer {
        public boolean received;
        /** 宿主主动推送（用来唤醒被冻结的应用），而非应答。 */
        public boolean decisive;
        /** 宿主判定：这张图被交给了蜜蜂记账。 */
        public boolean processed;
        /**
         * 宿主判定：这次确实成功记账了、可以删。
         *
         * <p>这个判定权必须在宿主手里：它同时握有"放行的是哪张图"和"成功发生在放行之后"
         * 两个事实。曾经把它放在模块 App 侧算，结果误删了一张识别失败的截图。
         */
        public boolean success;
        /** 宿主是否已经自己把文件删掉了。 */
        public boolean deleted;
        /** 蜜蜂记账的截图监听能力是否在位（主界面是否还活着）。 */
        public boolean activityAlive;
        /** 宿主里成功安装的 hook 条数；-1 表示未知。 */
        public int hookCount = -1;
        public int hookFailed = -1;
        public long successTs;
        public String path;
        public String hostCacheDir;
        public String telemetry;
    }

    /**
     * 推送队列与应答队列分开。
     *
     * <p>原因是两类消息的消费方式不同：宿主**主动推送**（decisive）由清理线程
     * `await()` 等；而**应答**（query 的回包）由发起查询的线程 `poll()` 拿。
     * 如果共用一个队列，一次查询的应答可能被 await 吃掉（或反过来），
     * 表现为偶发"宿主无回应"这种假警报。分开之后各拿各的，互不干扰。
     */
    private static final ArrayBlockingQueue<Answer> PUSH_QUEUE =
            new ArrayBlockingQueue<Answer>(8);
    private static final ArrayBlockingQueue<Answer> REPLY_QUEUE =
            new ArrayBlockingQueue<Answer>(8);

    private static volatile boolean receiverReady = false;

    static void ensureReceiver(Context ctx) {
        if (receiverReady || ctx == null) {
            return;
        }
        synchronized (ShotBridge.class) {
            if (receiverReady) {
                return;
            }
            try {
                IntentFilter filter = new IntentFilter(Const.ACTION_RESULT);
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent == null || !Const.ACTION_RESULT.equals(intent.getAction())) {
                            return;
                        }
                        // 这条回包里带着"可以删哪个文件"的结论，是权限最大的一条通道。
                        // 校验策略见 SenderGuard：只在确认识别出发件人是陌生应用时才拒。
                        if (!SenderGuard.allow(this, context)) {
                            return;
                        }
                        Answer a = new Answer();
                        a.received = true;
                        a.decisive = intent.getBooleanExtra(Const.EXTRA_DECISIVE, false);
                        a.processed = intent.getBooleanExtra(Const.EXTRA_PROCESSED, false);
                        a.success = intent.getBooleanExtra(Const.EXTRA_SUCCESS, false);
                        a.deleted = intent.getBooleanExtra(Const.EXTRA_DELETED, false);
                        a.activityAlive = intent.getBooleanExtra(Const.EXTRA_ACTIVITY_ALIVE, false);
                        a.hookCount = intent.getIntExtra(Const.EXTRA_HOOK_COUNT, -1);
                        a.hookFailed = intent.getIntExtra(Const.EXTRA_HOOK_FAILED, -1);
                        a.successTs = intent.getLongExtra(Const.EXTRA_SUCCESS_TS, 0L);
                        a.path = intent.getStringExtra(Const.EXTRA_PATH);
                        a.hostCacheDir = intent.getStringExtra(Const.EXTRA_HOST_CACHE);
                        a.telemetry = intent.getStringExtra(Const.EXTRA_TELEMETRY);
                        Logx.i("[bus] host answered: decisive=" + a.decisive
                                + " processed=" + a.processed
                                + " SUCCESS=" + a.success
                                + " deletedByHost=" + a.deleted
                                + " activityAlive=" + a.activityAlive
                                + " successTs=" + a.successTs
                                + " path=" + a.path);
                        if (a.telemetry != null) {
                            Logx.i("[bus] host telemetry: " + a.telemetry);
                        }
                        (a.decisive ? PUSH_QUEUE : REPLY_QUEUE).offer(a);

                        // 刚刚记账成功后，顺手把桌面小组件的数字也刷一下，
                        // 免得它要等到下一次 30 分钟的周期更新。
                        if (a.success || a.deleted) {
                            try {
                                ExpenseWidgetProvider.refreshAll(
                                        context.getApplicationContext() != null
                                                ? context.getApplicationContext() : context);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                };
                Context app = ctx.getApplicationContext() != null
                        ? ctx.getApplicationContext() : ctx;
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    app.registerReceiver(receiver, filter);
                }
                receiverReady = true;
                Logx.i("[bus] result receiver registered (app <- host)");
            } catch (Throwable t) {
                Logx.e("[bus] register receiver failed", t);
            }
        }
    }

    /**
     * 等宿主**主动推送**。被冻结的进程会被这条广播唤醒，因此这是低延迟路径。
     *
     * @return 收到的答案；超时返回 received=false
     */
    static Answer await(long timeoutMs) {
        Answer back = null;
        try {
            back = PUSH_QUEUE.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return back != null ? back : new Answer();
    }

    /**
     * 同步查询宿主（兜底：万一推送漏了）。
     *
     * @param path      关心的截图路径（宿主会回答"这张处理过吗"）
     * @param timeoutMs 等待上限
     * @return 收到的答案；超时返回 received=false
     */
    static Answer query(Context ctx, String path, long timeoutMs) {
        ensureReceiver(ctx);
        if (!receiverReady) {
            Logx.w("[bus] receiver not ready, cannot query host");
            return new Answer();
        }
        REPLY_QUEUE.clear();
        try {
            Intent q = new Intent(Const.ACTION_QUERY);
            q.setPackage(HostInfo.pkg());
            q.putExtra(Const.EXTRA_PATH, path == null ? "" : path);
            ctx.sendBroadcast(q);
        } catch (Throwable t) {
            Logx.e("[bus] send query failed", t);
            return new Answer();
        }
        Answer back = null;
        try {
            back = REPLY_QUEUE.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (back == null) {
            Logx.w("[bus] no answer from host within " + timeoutMs
                    + "ms —— 模块没有被注入宿主的进程，或作用域没勾对");
            return new Answer();
        }
        return back;
    }

    private ShotBridge() {
    }
}
