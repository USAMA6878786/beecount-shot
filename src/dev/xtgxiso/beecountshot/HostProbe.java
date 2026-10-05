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
        if (json == null || json.length() == 0) {
            return 0;
        }
        int n = 0;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String msg = o.optString("message", "");
                if (msg.indexOf(Const.SUCCESS_LOG_MARKER) < 0) {
                    continue;
                }
                long ts = o.optLong("timestamp", 0L);
                if (ts > afterTs) {
                    n++;
                }
            }
        } catch (Throwable t) {
            Logx.w("[probe] app_logs count failed: " + t.getMessage());
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

    /**
     * 列出 {@code afterTs} 之后所有的"处理完一张截图"事件，按时间先后。
     *
     * <p>这是精确配对的基础：蜜蜂记账每处理完一张截图就写一条
     * {@link Const#OUTCOME_LOG_MARKER}，里面有 {@code 成功=N 笔}——
     * N>0 说明这张有账目，N=0 说明未识别到账单。
     * 于是"放行的先后顺序"和"出结果的先后顺序"可以一一对上，不用再猜。
     */
    static java.util.List<Outcome> outcomesAfter(String json, long afterTs) {
        java.util.List<Outcome> out = new java.util.ArrayList<Outcome>();
        if (json == null || json.length() == 0) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String msg = o.optString("message", "");
                if (msg.indexOf(Const.OUTCOME_LOG_MARKER) < 0) {
                    continue;
                }
                long ts = o.optLong("timestamp", 0L);
                if (ts <= afterTs) {
                    continue;
                }
                out.add(new Outcome(ts, parseBills(msg) > 0));
            }
        } catch (Throwable t) {
            Logx.w("[probe] app_logs outcomes parse failed: " + t.getMessage());
        }
        // 数组顺序理论上就是时间顺序，排一下更稳妥。
        java.util.Collections.sort(out, new java.util.Comparator<Outcome>() {
            @Override
            public int compare(Outcome a, Outcome b) {
                return a.ts < b.ts ? -1 : (a.ts > b.ts ? 1 : 0);
            }
        });
        return out;
    }

    /** 最新一条"处理完"事件的时间戳；一条都没有则 0。 */
    static long latestOutcomeTs(String json) {
        java.util.List<Outcome> all = outcomesAfter(json, 0L);
        return all.isEmpty() ? 0L : all.get(all.size() - 1).ts;
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

    /** 该截图路径是否被蜜蜂记账处理过（按文件名比，兼容各种路径写法）。 */
    private static boolean processedContains(SharedPreferences flutter, String path) {
        String base = baseName(path);
        if (base.length() == 0) {
            return false;
        }
        try {
            Set<String> set = flutter.getStringSet(Const.K_PROCESSED, null);
            if (set == null) {
                return false;
            }
            for (String s : set) {
                if (base.equals(baseName(s))) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Logx.w("[probe] processed list unreadable: " + t.getMessage());
        }
        return false;
    }

    /** 从 Dart 日志里找出最近一次「自动记账成功」的时间戳，没有则 0。 */
    static long latestSuccessTs(String json) {
        if (json == null || json.length() == 0) {
            return 0L;
        }
        long best = 0L;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String msg = o.optString("message", "");
                if (msg.indexOf(Const.SUCCESS_LOG_MARKER) < 0) {
                    continue;
                }
                long ts = o.optLong("timestamp", 0L);
                if (ts > best) {
                    best = ts;
                }
            }
        } catch (Throwable t) {
            Logx.w("[probe] app_logs parse failed: " + t.getMessage());
        }
        return best;
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
        sb.append("|activity_alive=").append(HostState.isActivityAlive());
        sb.append("|gate_last_path=").append(LastShot.path() == null ? "<none>" : LastShot.path());
        sb.append("|gate_last_age_ms=").append(
                LastShot.at() == 0L ? -1L : (System.currentTimeMillis() - LastShot.at()));
        sb.append('|').append(HostWatcher.diag());
        sb.append('|').append(HostVerdict.diag());

        try {
            Set<String> set = flutter.getStringSet(Const.K_PROCESSED, null);
            sb.append("|proc_set_size=").append(set == null ? -1 : set.size());
            if (set != null) {
                StringBuilder sample = new StringBuilder();
                Iterator<String> it = set.iterator();
                int n = 0;
                while (it.hasNext() && n < 3) {
                    sample.append(baseName(it.next())).append(';');
                    n++;
                }
                sb.append("|proc_sample=").append(sample);
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
