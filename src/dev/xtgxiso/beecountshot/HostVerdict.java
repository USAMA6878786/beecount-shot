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
 * 原来的做法（v2.6）是"把成功归给最新放行的那张"，连点两次时会张冠李戴。
 *
 * <p><b>v2.23 起策略简化了：只要一张截图配到了"识别完成"的结果，就删——不管它有没有
 * 识别出账单。</b>
 *
 * <p>为什么可以这样：会走到这里的图**只可能是点磁贴截的那几张**（{@link LastShot} 里
 * 只记闸门放行过的路径），而"配到结果"意味着蜜蜂记账**已经把它读完并识别完了**
 * （{@code 落库完成} 那条日志是识别结束时才写的）。用户的意图本来就是"这张图我看过了，
 * 不用留"——识别到账单要删，没识别到账单同样不用留。所以不再区分。
 *
 * <p>保留的两条约束：
 * <ul>
 *   <li>{@code usedEventTs}：同一条"识别完成"日志不能被用两次，否则一张图会被当成两张；</li>
 *   <li>没等到结果的图**继续等**，不会提前删——"识别完成以后才删"这条底线还在。</li>
 * </ul>
 *
 * <p>失败路径（找不到"落库完成"日志时才走）仍按成功次数推断，见 {@link #decide}。
 */
final class HostVerdict {

    /** 放行记录的有效期：超过这个时间的放行不再作为删图依据（也避免旧的失败记录一直挡路）。 */
    static final long GATE_RECENCY_MS = 10L * 60L * 1000L;

    /** 一次放行最多等多久的结果。超过就不再把它算进"这一批"，免得一次失败永久堵住后续。 */
    private static final long ALLOW_TTL_MS = 90000L;

    /** 允许的时钟/日志节流误差。 */
    private static final long SLACK_MS = 1000L;

    /** 已经据此动作过的最新成功时间戳。 */
    private static volatile long usedEventTs = 0L;

    /** 这个时刻之前的放行都已结算完毕，不再参与判定。 */
    private static volatile long resolvedUpToTs = 0L;

    /** 已判定可删、但还没删掉的文件（模块 App 的兜底删图要靠它确认）。 */
    private static final List<String> pending = new ArrayList<String>(4);

    /**
     * 待删清单的保留上限。
     *
     * <p>v2.12 之前这里是每轮 {@code clear()} 再重建的，于是模块 App 只要晚一轮来问
     * "这张判定过没有"，就会得到"没有"——而那时宿主其实已经判定过了。
     * 现在改成**追加保留**（带个上限，满了从最老的开始丢），跨轮次也能查得到。
     */
    private static final int PENDING_CAP = 16;

    /**
     * 是否允许"按处理顺序推断着删"。由模块 App 的设置在放行广播里带过来
     * （两个进程够不着对方的 SharedPreferences）。
     */
    private static volatile boolean precise = true;

    static void setPrecise(boolean v) {
        precise = v;
    }

    /**
     * 结算一次结果，返回这次可以删掉的文件（可能为 null = 还不能删）。
     *
     * <p><b>首选精确配对</b>：蜜蜂记账每处理完一张截图就写一条
     * {@link Const#OUTCOME_LOG_MARKER}，里面带 {@code 成功=N 笔}。
     * 一条对应一张截图，按先后和"闸门放行的先后"一一对上，谁成功谁没识别一目了然，
     * 可以做到"删成功的、留没识别的"，而且对 S/F/S/F 这种交替的情况也正确。
     *
     * <p>找不到这类日志（以后它改了措辞）就退回 v2.8 的次数推断。
     *
     * @param eventTs     触发这次结算的事件时间戳：有"落库完成"日志就用它的时间，
     *                    否则退回「自动记账成功」的时间
     * @param appLogsJson 蜜蜂记账的 {@code flutter.app_logs} 原文
     */
    static synchronized String[] decide(long eventTs, String appLogsJson) {
        if (eventTs <= 0L) {
            return null;
        }
        if (eventTs <= usedEventTs) {
            return null; // 这次事件已经结算过了
        }

        // 收集这一批"已放行、还不知道结果"的图：放行时间必须在本次事件之前，
        // 且不能太老、也不能是已经结算过的。顺序是**放行先后**（老→新），
        // 也就是蜜蜂记账拿到手的先后。
        List<String> batch = new ArrayList<String>(4);
        List<Long> batchAt = new ArrayList<Long>(4);
        long earliest = Long.MAX_VALUE;
        int n = LastShot.size();
        for (int i = 0; i < n; i++) {
            long at = LastShot.atAt(i);
            String p = LastShot.pathAt(i);
            if (at <= resolvedUpToTs || at > eventTs) {
                continue;
            }
            if (at < eventTs - ALLOW_TTL_MS) {
                continue;
            }
            if (p == null || !RootShell.isSafeScreenshotPath(p)) {
                continue;
            }
            batch.add(p);
            batchAt.add(Long.valueOf(at));
            if (at < earliest) {
                earliest = at;
            }
        }
        if (batch.isEmpty()) {
            return null;
        }

        // ---- 精确配对：一条"落库完成"对应一张截图 ----
        long outcomeFrom = Math.max(usedEventTs, earliest - SLACK_MS);
        java.util.List<Long> outcomes = HostProbe.outcomesAfter(appLogsJson, outcomeFrom);
        if (!outcomes.isEmpty()) {
            return pair(outcomes, batch, batchAt, eventTs);
        }

        // ---- 退回次数推断（找不到"落库完成"日志时才走这里）----
        // 数这一批自己等来的成功次数。起点必须同时排除"已经兑换过的成功"，
        // 否则上一张的成功会被重复算进来，把还没出结果的那张也一起删了。
        long from = Math.max(earliest - SLACK_MS, usedEventTs);
        int s = HostProbe.countSuccessesAfter(appLogsJson, from);

        int delCount;
        if (s >= batch.size()) {
            delCount = batch.size(); // 全都成了
        } else if (precise) {
            // 只等到 s 次成功、但放行过更多张：按"先放行的先出结果"推断前 s 张成了。
            delCount = s;
            Logx.w("[verdict] infer: " + batch.size() + " handed over, only " + s
                    + " success(es) -> assuming the FIRST " + s
                    + " succeeded (relies on BeeCount processing them in order)");
        } else {
            Logx.i("[verdict] wait: " + batch.size() + " screenshot(s) handed over, "
                    + s + " success(es) so far -> cannot tell which one, keeping all");
            return null;
        }
        if (delCount <= 0) {
            return null;
        }
        return finish(batch, batchAt, delCount, eventTs,
                "inferred " + s + " success(es)");
    }

    /**
     * 精确配对：把"落库完成"事件按先后依次认领给按放行先后排好的截图。
     *
     * <p>一张截图只有在**拿到自己的结果**之后才会被处理——配到结果就删，
     * 不再区分有没有识别到账单（见类注释）。没等到结果的那些继续等下一轮。
     */
    private static String[] pair(java.util.List<Long> outcomes,
                                 List<String> batch, List<Long> batchAt, long eventTs) {
        List<String> del = new ArrayList<String>(4);
        int consumed = 0;      // 认领掉多少张（不论成功失败）
        long lastOutcomeTs = 0L;
        int oi = 0;
        for (int i = 0; i < batch.size(); i++) {
            long at = batchAt.get(i).longValue();
            // 跳掉那些早于这张放行时间的事件（属于更老的截图）
            while (oi < outcomes.size() && outcomes.get(oi).longValue() < at - SLACK_MS) {
                oi++;
            }
            if (oi >= outcomes.size()) {
                break; // 这张还没出结果，后面的更不会有 —— 到此为止
            }
            long oTs = outcomes.get(oi).longValue();
            oi++;
            consumed++;
            lastOutcomeTs = oTs;
            // 配到结果 = 蜜蜂记账已经把它读完识别完了 -> 删。不再看有没有识别到账单。
            Logx.i("[verdict] paired " + batch.get(i) + " -> 识别完成，删");
            del.add(batch.get(i));
        }
        if (consumed == 0) {
            return null;
        }

        // 用掉的事件时间作为新的"已结算"水位，下次只认领更晚的事件。
        if (lastOutcomeTs > usedEventTs) {
            usedEventTs = lastOutcomeTs;
        }
        // 结算边界推进到**被认领的那几张**（含未识别的），没等到结果的继续等着。
        long maxAt = 0L;
        for (int i = 0; i < consumed; i++) {
            long at = batchAt.get(i).longValue();
            if (at > maxAt) {
                maxAt = at;
            }
        }
        if (maxAt > resolvedUpToTs) {
            resolvedUpToTs = maxAt;
        }

        rememberPending(del);
        Logx.i("[verdict] DELETE " + del.size() + " of " + batch.size()
                + " file(s) (exact pairing): " + del);
        return del.toArray(new String[del.size()]);
    }

    /** 收尾：记录已结算水位、标记待删清单。 */
    private static String[] finish(List<String> batch, List<Long> batchAt,
                                   int delCount, long eventTs, String why) {
        if (eventTs > usedEventTs) {
            usedEventTs = eventTs;
        }
        // 只把"处理掉的这几张"标记为已结算，后面还没出结果的继续等——
        // 不能拿 eventTs 一刀切，否则会把后面那张也一起划走。
        long maxAt = 0L;
        for (int i = 0; i < delCount; i++) {
            long at = batchAt.get(i).longValue();
            if (at > maxAt) {
                maxAt = at;
            }
        }
        if (maxAt > resolvedUpToTs) {
            resolvedUpToTs = maxAt;
        }

        List<String> del = new ArrayList<String>(delCount);
        for (int i = 0; i < delCount; i++) {
            del.add(batch.get(i));
        }
        rememberPending(del);
        Logx.i("[verdict] DELETE " + delCount + " of " + batch.size()
                + " file(s) (" + why + "): " + del);
        return del.toArray(new String[delCount]);
    }

    /** 把这一批待删文件记进去（累加，不覆盖），超出上限从最老的开始丢。 */
    private static void rememberPending(List<String> del) {
        for (int i = 0; i < del.size(); i++) {
            String p = del.get(i);
            if (p == null || p.length() == 0) {
                continue;
            }
            String want = baseName(p);
            boolean dup = false;
            for (int j = 0; j < pending.size(); j++) {
                if (want.equals(baseName(pending.get(j)))) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                pending.add(p);
            }
        }
        while (pending.size() > PENDING_CAP) {
            pending.remove(0);
        }
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
        return "used_event_ts=" + usedEventTs
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
