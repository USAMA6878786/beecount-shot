package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 宿主进程侧：直接读**自己的** SharedPreferences，回答模块 App 的查询。
 *
 * <p>这是整条跨进程链路上唯一可靠的一环。原因：
 * <ul>
 *   <li>应用读自己的 SharedPreferences 是 Android 的正常存储机制，不可能失败；</li>
 *   <li>而模块 App 用 root 去读宿主的文件是**行不通的**——真机查明 {@code su} 跑在全局
 *       挂载命名空间，看不到别的应用的数据目录（{@code dumpsys} 给的路径都"不存在"，
 *       而宿主自己报告 cacheDir 确实是那个路径）。</li>
 * </ul>
 *
 * <p>所以方向反过来：宿主在自己进程里把答案算出来，用**广播**送回模块 App。
 */
final class HostProbe {

    /** 蜜蜂记账 Flutter 侧 SharedPreferences 的文件名。 */
    private static final String FLUTTER_PREFS = "FlutterSharedPreferences";

    /** 我们自己的遥测 prefs。 */
    private static final String TELEMETRY_PREFS = "beecount_shot";

    /** 处理一条查询，并把答案广播回模块 App。 */
    static void handleQuery(Context ctx, Intent query) {
        String path = (query == null) ? null : query.getStringExtra(Const.EXTRA_PATH);
        try {
            SharedPreferences flutter = ctx.getSharedPreferences(
                    FLUTTER_PREFS, Context.MODE_PRIVATE);

            // 证据 A（主）：闸门刚刚把**这个文件**交给了蜜蜂记账。
            // 这是最直接的证据，不依赖蜜蜂记账自己写没写、写成什么格式。
            boolean viaGate = LastShot.allowedMostRecently(path, HostVerdict.GATE_RECENCY_MS);
            // 证据 B（兜底）：蜜蜂记账自己的"已处理截图"列表里有这个文件。
            boolean viaList = processedContains(flutter, path);
            boolean processed = viaGate || viaList;

            long successTs = latestSuccessTs(flutter.getString(Const.K_APP_LOGS, null));
            long allowedAt = LastShot.at();

            // 删不删只问 HostVerdict 一家。这里曾经自己算过一套判定，结果和宿主推送
            // 路径那套对不上，是历史上误删的根源；现在两条路径共用同一份判据。
            //
            // 两种情况算"可删"：
            //   ① 宿主已经判定过这张图（可能自己没删成功，正等模块 App 兜底）；
            //   ② 现在结算一次成功，这张图在结算结果里。
            final String logsJson = flutter.getString(Const.K_APP_LOGS, null);
            boolean success = HostVerdict.alreadyDecided(path);
            if (!success) {
                // 和宿主推送路径一样：事件钟优先用「落库完成」，识别失败也要能触发结算。
                long eventTs = HostProbe.latestOutcomeTs(logsJson);
                if (eventTs <= 0L) {
                    eventTs = successTs;
                }
                String[] batch = HostVerdict.decide(eventTs, logsJson);
                if (batch != null) {
                    for (int i = 0; i < batch.length; i++) {
                        if (baseName(batch[i]).equals(baseName(path))) {
                            success = true;
                            break;
                        }
                    }
                }
            }

            Intent result = new Intent(Const.ACTION_RESULT);
            result.setPackage(Const.MODULE_PKG);
            result.putExtra(Const.EXTRA_PATH, path == null ? "" : path);
            result.putExtra(Const.EXTRA_PROCESSED, processed);
            result.putExtra(Const.EXTRA_SUCCESS, success);
            result.putExtra(Const.EXTRA_SUCCESS_TS, successTs);
            result.putExtra(Const.EXTRA_ACTIVITY_ALIVE, HostState.isActivityAlive());
            result.putExtra(Const.EXTRA_HOOK_COUNT, HookStats.installedCount());
            result.putExtra(Const.EXTRA_HOOK_FAILED, HookStats.failedCount());
            try {
                result.putExtra(Const.EXTRA_HOST_CACHE, ctx.getCacheDir().getAbsolutePath());
            } catch (Throwable ignored) {
            }
            result.putExtra(Const.EXTRA_TELEMETRY, telemetry(ctx, flutter, path, viaGate, viaList));
            // 如果宿主已经自己删过了，要如实告诉模块 App，免得它再去删一次报"删除失败"。
            result.putExtra(Const.EXTRA_DELETED, HostWatcher.wasDeleted(path));
            ctx.sendBroadcast(result);

            Logx.i("[probe] query path=" + path
                    + " -> gate=" + viaGate + " list=" + viaList
                    + " SUCCESS=" + success
                    + " successTs=" + successTs + " allowedAt=" + allowedAt
                    + " activityAlive=" + HostState.isActivityAlive());
        } catch (Throwable t) {
            Logx.e("[probe] handleQuery failed", t);
        }
    }

    /**
     * 日志里 {@code afterTs} 之后一共出现过几次「自动记账成功」。
     *
     * <p>连点两次时，两张图都在"已交给蜜蜂记账、还不知道结果"的状态，而成功日志里
     * **不带是哪张图**，单看时间戳分不清属于谁。数次数就能对上：
     * 放行了几张、就等几次成功——次数够了说明这几张都成了，可以一起删；
     * 次数不够说明至少有一张没成功，此时谁都不删（宁可漏删，绝不误删）。
     */
    static int countSuccessesAfter(String json, long afterTs) {
        Parsed p = parse(json);
        int n = 0;
        for (int i = 0; i < p.successTs.size(); i++) {
            if (p.successTs.get(i).longValue() > afterTs) {
                n++;
            }
        }
        return n;
    }

    /** 一次"处理完一张截图"的结果。 */
    static final class Outcome {
        final long ts;
        final boolean hasBill;

        Outcome(long ts, boolean hasBill) {
            this.ts = ts;
            this.hasBill = hasBill;
        }
    }

    /** 一份解析好的 Dart 日志快照（只留判定要用的东西，体积很小）。 */
    static final class Parsed {
        final int len;
        final int hash;
        long latestSuccessTs = 0L;
        long latestOutcomeTs = 0L;
        /** 每一条「自动记账成功」的时间戳。 */
        final java.util.List<Long> successTs = new java.util.ArrayList<Long>();
        /** 每一条「落库完成」事件，按时间升序。 */
        final java.util.List<Outcome> outcomes = new java.util.ArrayList<Outcome>();

        Parsed(int len, int hash) {
            this.len = len;
            this.hash = hash;
        }
    }

    private static final Parsed EMPTY = new Parsed(0, 0);

    /** 最近一次解析的结果。 */
    private static volatile Parsed cache;

    /**
     * 把整份 Dart 日志解析成一份小快照，**带缓存**。
     *
     * <p>为什么要缓存：一次"日志变了"的事件里，宿主会连着问好几个问题——最近一次成功是什么时候、
     * 最近一次落库是什么时候、这之后成功了几次、落库事件都有哪些——原先每个问题都各自把整份
     * JSON 从头解析一遍。而蜜蜂记账这份日志实测有 130KB 左右，等于一条日志变化就要解析四五遍。
     * 缓存之后同一份内容只解析一次，后面全部走内存里的小列表。
     *
     * <p>用「长度 + hashCode」当键：{@code String.hashCode()} 只算一次并被缓存，
     * 比重新解析 JSON 便宜得多。（缓存只留一份，两个线程交替查询时最多退化回原来的开销。）
     */
    static Parsed parse(String json) {
        if (json == null || json.length() == 0) {
            return EMPTY;
        }
        int len = json.length();
        int hash = json.hashCode();
        Parsed hit = cache;
        if (hit != null && hit.len == len && hit.hash == hash) {
            return hit;
        }
        Parsed p = new Parsed(len, hash);
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String msg = o.optString("message", "");
                long ts = o.optLong("timestamp", 0L);
                if (msg.indexOf(Const.SUCCESS_LOG_MARKER) >= 0) {
                    p.successTs.add(Long.valueOf(ts));
                    if (ts > p.latestSuccessTs) {
                        p.latestSuccessTs = ts;
                    }
                }
                if (msg.indexOf(Const.OUTCOME_LOG_MARKER) >= 0) {
                    p.outcomes.add(new Outcome(ts, parseBills(msg) > 0));
                    if (ts > p.latestOutcomeTs) {
                        p.latestOutcomeTs = ts;
                    }
                }
            }
        } catch (Throwable t) {
            Logx.w("[probe] app_logs parse failed: " + t.getMessage());
        }
        // 数组顺序理论上就是时间顺序，排一下更稳妥。
        java.util.Collections.sort(p.outcomes, new java.util.Comparator<Outcome>() {
            @Override
            public int compare(Outcome a, Outcome b) {
                return a.ts < b.ts ? -1 : (a.ts > b.ts ? 1 : 0);
            }
        });
        cache = p;
        return p;
    }

    /**
     * 列出 {@code afterTs} 之后所有的"处理完一张截图"事件，按时间先后。
     *
     * <p>这是精确配对的基础：蜜蜂记账每处理完一张截图就写一条
     * {@link Const#OUTCOME_LOG_MARKER}，里面有 {@code 成功=N 笔}——
     * N>0 说明这张有账目，N=0 说明未识别到账单。
     * 于是"放行的先后顺序"和"出结果的先后顺序"可以一一对上，不用再猜。
     */
    static java.util.List<Outcome> outcomesAfter(String json, long afterTs) {
        Parsed p = parse(json);
        java.util.List<Outcome> out = new java.util.ArrayList<Outcome>();
        for (int i = 0; i < p.outcomes.size(); i++) {
            Outcome o = p.outcomes.get(i);
            if (o.ts > afterTs) {
                out.add(o);
            }
        }
        return out;
    }

    /** 最新一条"处理完"事件的时间戳；一条都没有则 0。 */
    static long latestOutcomeTs(String json) {
        return parse(json).latestOutcomeTs;
    }

    /** 从 {@code 成功=N 笔} 里抠出 N。解析不出来按 0（= 没识别到账单）处理，方向是安全的。 */
    private static int parseBills(String msg) {
        int idx = msg.indexOf(Const.OUTCOME_BILLS_FIELD);
        if (idx < 0) {
            return 0;
        }
        int s = idx + Const.OUTCOME_BILLS_FIELD.length();
        int e = s;
        while (e < msg.length() && msg.charAt(e) >= '0' && msg.charAt(e) <= '9') {
            e++;
        }
        if (e == s) {
            return 0;
        }
        try {
            return Integer.parseInt(msg.substring(s, e));
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 把蜜蜂记账最近几条日志原文摘出来（只留 message，截断）。
     *
     * <p>纯为诊断：成功日志里**不带是哪张图**，想知道"未识别到账单"在日志里到底写成什么、
     * 能不能据此精确配对，就得先看见原文。
     */
    static String recentLogs(String json, int max, int maxLen) {
        if (json == null || json.length() == 0) {
            return "<none>";
        }
        try {
            JSONArray arr = new JSONArray(json);
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, arr.length() - max);
            for (int i = from; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(" // ");
                }
                sb.append(o.optString("message", ""));
            }
            String s = sb.toString().replace('\n', ' ');
            return s.length() > maxLen ? s.substring(s.length() - maxLen) : s;
        } catch (Throwable t) {
            return "<parse-failed: " + t.getMessage() + ">";
        }
    }

    /**
     * 该截图路径是否被蜜蜂记账处理过（按文件名比，兼容各种路径写法）。
     *
     * <p><b>必须用 {@code getAll()} 取值。</b>真机日志里长期有一行
     * {@code proc_set_error=java.lang.String cannot be cast to java.util.Set}——
     * 蜜蜂记账这个键实际存的是**一个 String**，而旧代码假设它是 {@code Set<String>}，
     * 于是 {@code getStringSet()} 每次都抛 ClassCastException 被吞掉，
     * 这条兜底判据（证据 B）实际上从来没生效过。现在两种类型都认。
     */
    private static boolean processedContains(SharedPreferences flutter, String path) {
        String base = baseName(path);
        if (base.length() == 0) {
            return false;
        }
        try {
            Object raw = flutter.getAll().get(Const.K_PROCESSED);
            if (raw == null) {
                return false;
            }
            if (raw instanceof Set) {
                for (Object o : (Set<?>) raw) {
                    if (o != null && base.equals(baseName(String.valueOf(o)))) {
                        return true;
                    }
                }
                return false;
            }
            // 实际是 String（可能是一段 JSON/逗号分隔的文本）——直接按子串找文件名。
            return String.valueOf(raw).indexOf(base) >= 0;
        } catch (Throwable t) {
            Logx.w("[probe] processed list unreadable: " + t.getMessage());
        }
        return false;
    }

    /** 从 Dart 日志里找出最近一次「自动记账成功」的时间戳，没有则 0。 */
    static long latestSuccessTs(String json) {
        return parse(json).latestSuccessTs;
    }

    /**
     * 遥测快照，一行 key=value。
     *
     * <p>注意必须用 {@code getAll()} 取值：同一个 key 可能是 String 也可能是 Long，
     * 直接 {@code getString(key)} 遇到 Long 会抛 ClassCastException（真机上就踩了，
     * 一堆字段显示成 {@code <err>}）。
     */
    private static String telemetry(Context ctx, SharedPreferences flutter,
                                   String queryPath, boolean viaGate, boolean viaList) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("host_cache_dir=").append(ctx.getCacheDir().getAbsolutePath());
            sb.append("|files_dir=").append(ctx.getFilesDir().getAbsolutePath());
        } catch (Throwable ignored) {
        }

        try {
            SharedPreferences sp = ctx.getSharedPreferences(TELEMETRY_PREFS, Context.MODE_PRIVATE);
            Map<String, ?> all = sp.getAll();
            String[] keys = {
                    "host_pkg", "hooks_ok", "hooks_failed", "hooks_ready_at",
                    "alive_at", "pid", "host_sdk",
                    "channel_allowed", "channel_blocked", "observer_blocked",
                    "last_allowed_path", "last_allowed_at", "activity_destroyed_at",
            };
            for (int i = 0; i < keys.length; i++) {
                Object v = all.get(keys[i]);
                sb.append('|').append(keys[i]).append('=').append(v == null ? "<none>" : String.valueOf(v));
            }
        } catch (Throwable t) {
            sb.append("|telemetry_error=").append(t.getMessage());
        }

        // 判定依据本身的快照，方便一眼看出卡在哪
        sb.append("|verdict_gate=").append(viaGate);
        sb.append("|verdict_list=").append(viaList);
        // 当前还有几张没兑现的通行证。点一次磁贴应当只 +1（哪怕发了两条广播），
        // 这个数字是验证"一次点击 = 一张通行证"最直接的凭据。
        sb.append("|arm_live=").append(ArmSignal.liveCount());
        sb.append("|activity_alive=").append(HostState.isActivityAlive());
        sb.append("|gate_last_path=").append(LastShot.path() == null ? "<none>" : LastShot.path());
        sb.append("|gate_last_age_ms=").append(
                LastShot.at() == 0L ? -1L : (System.currentTimeMillis() - LastShot.at()));
        sb.append('|').append(HostWatcher.diag());
        sb.append('|').append(HostVerdict.diag());

        // 同 processedContains：这个键实际是 String 而不是 Set，用 getAll() 取才不会抛。
        try {
            Object raw = flutter.getAll().get(Const.K_PROCESSED);
            sb.append("|proc_kind=").append(raw == null ? "<none>" : raw.getClass().getSimpleName());
            if (raw instanceof Set) {
                Set<?> set = (Set<?>) raw;
                sb.append("|proc_set_size=").append(set.size());
                StringBuilder sample = new StringBuilder();
                Iterator<?> it = set.iterator();
                int n = 0;
                while (it.hasNext() && n < 3) {
                    sample.append(baseName(String.valueOf(it.next()))).append(';');
                    n++;
                }
                sb.append("|proc_sample=").append(sample);
            } else if (raw != null) {
                String s = String.valueOf(raw);
                sb.append("|proc_raw_len=").append(s.length());
                sb.append("|proc_raw_tail=").append(
                        s.length() > 200 ? s.substring(s.length() - 200) : s);
            }
        } catch (Throwable t) {
            sb.append("|proc_set_error=").append(t.getMessage());
        }

        try {
            String logs = flutter.getString(Const.K_APP_LOGS, null);
            sb.append("|applog_len=").append(logs == null ? -1 : logs.length());
            sb.append("|recent_logs=").append(recentLogs(logs, 8, 600));
        } catch (Throwable t) {
            sb.append("|applog_error=").append(t.getMessage());
        }

        return sb.toString();
    }

    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        int slash = p.lastIndexOf('/');
        return (slash >= 0) ? p.substring(slash + 1) : p;
    }

    private HostProbe() {
    }
}
