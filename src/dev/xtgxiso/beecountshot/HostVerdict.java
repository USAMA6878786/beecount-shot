package dev.xtgxiso.beecountshot;

/**
 * 宿主进程内：**"这张截图可以删了吗"的唯一判定源**。
 *
 * <p>判定权必须放在宿主，因为只有宿主同时握有三个事实：
 * <ol>
 *   <li>闸门放行的是哪张图（{@link LastShot}）；</li>
 *   <li>这次"自动记账成功"是什么时候发生的（它自己的 Dart 日志）；</li>
 *   <li>上一次成功是不是已经被用掉过了（本类）。</li>
 * </ol>
 *
 * <p>历史上判定曾经分散在两处（宿主推送路径 / 模块 App 查询路径各算各的），
 * 而且只检查"成功时间晚于本次放行"。那会漏掉一种情况，也是本类存在的原因：
 *
 * <pre>
 *   第 1 次：点磁贴 → 放行 A → 记成功（时间戳 S1）→ 删掉 A
 *   第 2 次：隔两三秒又点一次 → 放行 B → 但 B 识别失败，没有新的成功日志
 *           此时"最新成功"仍是 S1，而 S1 又恰好晚于"第 2 次放行"，
 *           于是拿着第 1 次的成功去删第 2 次的截图 —— 误删。
 * </pre>
 *
 * 所以第三条判据「必须是**没被用过的新成功**」缺一不可：同一次成功只能兑换一张图。
 *
 * <p>三条判据全部满足才返回 true，任何一条不满足就保留截图——宁可漏删，绝不误删。
 */
final class HostVerdict {

    /** 放行记录的有效期：超过这个时间的放行不再作为删图依据。 */
    static final long GATE_RECENCY_MS = 10L * 60L * 1000L;

    /** 允许的时钟/日志节流误差。成功时间可以略早于放行时间，但不能早太多。 */
    private static final long SLACK_MS = 2000L;

    /** 已经据此动作过的最新成功时间戳。核心防重复/防误删字段。 */
    private static volatile long usedSuccessTs = 0L;

    /** 已判定可删、但可能还没删成功的文件（模块 App 的兜底删图要靠它确认）。 */
    private static volatile String pendingPath = null;

    /**
     * @param path      待判定的截图路径
     * @param successTs 蜜蜂记账日志里**最新一次**「自动记账成功」的时间戳（毫秒）
     * @return true 表示这张图可以在这次成功之后被删掉
     */
    static synchronized boolean shouldDelete(String path, long successTs) {
        if (path == null || successTs <= 0L) {
            return false;
        }
        // 路径本身必须先过安全检查（只可能是截图、不能是相机照片、不能有 shell 元字符）。
        if (!RootShell.isSafeScreenshotPath(path)) {
            return false;
        }
        // ① 这张图必须就是闸门最近放行的那一张——不是它，后面两条都不成立。
        if (!LastShot.allowedMostRecently(path, GATE_RECENCY_MS)) {
            Logx.i("[verdict] no: not the gated screenshot (path=" + path + ")");
            return false;
        }
        long allowedAt = LastShot.at();
        // ② 成功必须发生在这次放行之后（允许 2 秒误差）。
        if (successTs < allowedAt - SLACK_MS) {
            Logx.i("[verdict] no: success(" + successTs + ") not after allow(" + allowedAt + ")");
            return false;
        }
        // ③ 必须是**没被用过的新成功**——同一次成功只能兑换一张图。
        if (successTs <= usedSuccessTs) {
            Logx.i("[verdict] no: success(" + successTs + ") already used (used=" + usedSuccessTs + ")"
                    + " -> refusing to delete a second screenshot on one success");
            return false;
        }
        Logx.i("[verdict] YES: delete " + path
                + " (successTs=" + successTs + " allowedAt=" + allowedAt + ")");
        return true;
    }

    /**
     * 已经据此执行（或已经交给模块 App 执行）后调用。
     *
     * <p>必须在**删除动作之前**调用：先占位再删，可以避免同一次成功被两条路径
     * 各用一次（宿主删 + 模块 App 兜底删），也不会因为删除耗时而出现重入。
     */
    static synchronized void consume(String path, long successTs) {
        if (successTs > usedSuccessTs) {
            usedSuccessTs = successTs;
        }
        pendingPath = path;
    }

    /**
     * 查询路径专用：宿主是否已经判定过**这张图**可删（可能自己没删成功，正等模块 App 兜底）。
     *
     * <p>按文件名比，兼容宿主记的是完整路径、模块 App 查的是另一条写法的情况。
     */
    static boolean alreadyDecided(String path) {
        String p = pendingPath;
        if (p == null || path == null) {
            return false;
        }
        String a = baseName(p);
        return a.length() > 0 && a.equals(baseName(path));
    }

    /** 已判定可删的文件已经处理完（删掉了或放弃），清掉占位。 */
    static synchronized void clearPending() {
        pendingPath = null;
    }

    /** 诊断快照。 */
    static String diag() {
        return "used_success_ts=" + usedSuccessTs
                + "|pending_path=" + (pendingPath == null ? "<none>" : pendingPath);
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
