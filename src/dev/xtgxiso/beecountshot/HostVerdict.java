package dev.xtgxiso.beecountshot;

import java.util.ArrayList;
import java.util.List;

/**
 * 宿主进程内：**"这几张截图可以删了吗"的唯一判定源**。
 *
 * <p>判定权必须放在宿主，因为只有宿主同时握有三个事实：
 * <ol>
 *   <li>闸门放行过哪几张图（{@link LastShot}）；</li>
 *   <li>「自动记账成功」发生了几次、分别在什么时候（它自己的 Dart 日志）；</li>
 *   <li>哪几次成功已经被用掉过了（本类）。</li>
 * </ol>
 *
 * <p>判定方式在 v2.7 换成了**按次数配对**，因为成功日志里**不带是哪张图**。
 * 原来的做法（v2.6）是"把成功归给最新放行的那张"，连点两次时会张冠李戴：
 *
 * <pre>
 *   放行 A（第 0 秒）→ 放行 B（第 3 秒）
 *   A 的成功在第 13 秒到达，而 B 恰好也在第 13 秒被上报
 *   → 拿 A 的成功去删 B；可 B 可能根本没识别成功 —— 误删
 * </pre>
 *
 * 现在改成数次数：**放行了几张，就等几次成功**。
 * <ul>
 *   <li>放行 1 张、等到 1 次成功 → 删这一张；</li>
 *   <li>放行 2 张、等到 2 次成功 → 两张都删；</li>
 *   <li>放行 2 张、只等到 1 次成功 → 说明有一张没成，但**不知道是哪张**，
 *       于是两张都保留（宁可漏删，绝不误删）。</li>
 * </ul>
 *
 * <p>另外保留「同一次成功不能兑换两次」的约束（{@code usedSuccessTs}），
 * 防止一条成功日志被两条路径各用一次。
 */
final class HostVerdict {

    /** 放行记录的有效期：超过这个时间的放行不再作为删图依据（也避免旧的失败记录一直挡路）。 */
    static final long GATE_RECENCY_MS = 10L * 60L * 1000L;

    /** 一次放行最多等多久的结果。超过就不再把它算进"这一批"，免得一次失败永久堵住后续。 */
    private static final long ALLOW_TTL_MS = 90000L;

    /** 允许的时钟/日志节流误差。 */
    private static final long SLACK_MS = 1000L;

    /** 已经据此动作过的最新成功时间戳。 */
    private static volatile long usedSuccessTs = 0L;

    /** 这个时刻之前的放行都已结算完毕，不再参与判定。 */
    private static volatile long resolvedUpToTs = 0L;

    /** 已判定可删、但还没删掉的文件（模块 App 的兜底删图要靠它确认）。 */
    private static final List<String> pending = new ArrayList<String>(4);

    /**
     * 结算一次成功，返回这次可以删掉的文件（可能为 null = 还不能删）。
     *
     * @param successTs   蜜蜂记账日志里**最新一次**「自动记账成功」的时间戳（毫秒）
     * @param appLogsJson 蜜蜂记账的 {@code flutter.app_logs} 原文（用来数成功次数）
     */
    static synchronized String[] decide(long successTs, String appLogsJson) {
        if (successTs <= 0L) {
            return null;
        }
        if (successTs <= usedSuccessTs) {
            return null; // 这次成功已经兑换过了
        }

        // 收集这一批"已放行、还不知道结果"的图：放行时间必须在本次成功之前，
        // 且不能太老、也不能是已经结算过的。
        List<String> batch = new ArrayList<String>(4);
        long earliest = Long.MAX_VALUE;
        int n = LastShot.size();
        for (int i = 0; i < n; i++) {
            long at = LastShot.atAt(i);
            String p = LastShot.pathAt(i);
            if (at <= resolvedUpToTs || at > successTs) {
                continue;
            }
            if (at < successTs - ALLOW_TTL_MS) {
                continue;
            }
            if (p == null || !RootShell.isSafeScreenshotPath(p)) {
                continue;
            }
            batch.add(p);
            if (at < earliest) {
                earliest = at;
            }
        }
        if (batch.isEmpty()) {
            return null;
        }

        int s = HostProbe.countSuccessesAfter(appLogsJson, earliest - SLACK_MS);
        if (s < batch.size()) {
            Logx.i("[verdict] wait: " + batch.size() + " screenshot(s) handed over, "
                    + s + " success(es) so far -> cannot tell which one, keeping all");
            return null;
        }

        usedSuccessTs = successTs;
        resolvedUpToTs = successTs;
        pending.clear();
        pending.addAll(batch);
        Logx.i("[verdict] DELETE " + batch.size() + " file(s) on "
                + s + " success(es): " + batch);
        return batch.toArray(new String[batch.size()]);
    }

    /**
     * 查询路径专用：宿主是否已经判定过**这张图**可删（可能自己没删成功，正等模块 App 兜底）。
     *
     * <p>按文件名比，兼容宿主记的是完整路径、模块 App 查的是另一条写法的情况。
     */
    static synchronized boolean alreadyDecided(String path) {
        if (path == null) {
            return false;
        }
        String want = baseName(path);
        if (want.length() == 0) {
            return false;
        }
        for (int i = 0; i < pending.size(); i++) {
            if (want.equals(baseName(pending.get(i)))) {
                return true;
            }
        }
        return false;
    }

    /** 这张图处理完了（删掉或确认删不掉），从待办里去掉。 */
    static synchronized void clearOne(String path) {
        if (path == null) {
            return;
        }
        String want = baseName(path);
        for (int i = pending.size() - 1; i >= 0; i--) {
            if (want.equals(baseName(pending.get(i)))) {
                pending.remove(i);
            }
        }
    }

    /** 诊断快照。 */
    static synchronized String diag() {
        return "used_success_ts=" + usedSuccessTs
                + "|resolved_up_to_ts=" + resolvedUpToTs
                + "|pending=" + (pending.isEmpty() ? "<none>" : pending.toString());
    }

    static String baseName(String path) {
        if (path == null) {
            return "";
        }
        String s = path.trim();
        int slash = s.lastIndexOf('/');
        return (slash >= 0) ? s.substring(slash + 1) : s;
    }

    private HostVerdict() {
    }
}
