package com.myraa.mobile;

import org.json.*;
import java.util.*;

/** Checks FRIDAY's health ledger, self-analysis, development memory, approvals and text-chat self-healing on a plain JVM. */
public final class SelfImprovementCheck {
  private static int failures;
  private static void check(boolean ok, String what) { if (!ok) { failures++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }
  private static JSONObject add(JSONObject l, String day, String kind, long v, int times) { for (int i = 0; i < times; i++) l = SelfImprovement.record(l, day, kind, v); return l; }
  private static SelfImprovement.Finding finding(List<SelfImprovement.Finding> fs, String id) { for (SelfImprovement.Finding f : fs) if (f.id.equals(id)) return f; return null; }

  public static void main(String[] args) throws Exception {
    String today = "2026-11-08";

    // ---- ledger
    JSONObject l = new JSONObject();
    l = SelfImprovement.record(l, today, "chat_ok", 1);
    l = SelfImprovement.record(l, today, "chat_ok", 1);
    l = SelfImprovement.record(l, today, "bad name!", 1);
    check(l.getJSONObject("days").getJSONObject(today).getLong("chat_ok") == 2, "counters add up");
    check(!l.getJSONObject("days").getJSONObject(today).has("bad name!"), "unsafe counter names are refused");
    l = SelfImprovement.record(l, "2026-09-01", "chat_ok", 1);
    l = SelfImprovement.record(l, today, "chat_ok", 0);
    check(!l.getJSONObject("days").has("2026-09-01"), "days older than 28 are forgotten");
    check(SelfImprovement.window(l, today, 0, 7).get("chat_ok") == 2, "7-day window sums");

    // ---- no data, no findings
    check(SelfImprovement.analyze(new JSONObject(), today).isEmpty(), "no measurements: no findings");
    check(SelfImprovement.report(new JSONObject(), today, new ArrayList<>()).contains("enough measurements"), "no measurements: honest report");

    // ---- quota failures: level 3 (owner changes the model), with numbers and trend
    JSONObject q = new JSONObject();
    q = add(q, today, "chat_ok", 1, 6); q = add(q, today, "chat_fail", 1, 4); q = add(q, today, "chat_fail:QUOTA_EXCEEDED", 1, 4);
    q = add(q, "2026-10-30", "chat_ok", 1, 10); q = add(q, "2026-10-30", "chat_fail", 1, 1);
    List<SelfImprovement.Finding> fs = SelfImprovement.analyze(q, today);
    SelfImprovement.Finding cf = finding(fs, "chat_failures");
    check(cf != null && cf.level == 3 && cf.severity.equals("high"), "quota failures are a high, level 3 finding");
    check(cf != null && cf.evidence.contains("4 of 10") && cf.evidence.contains("up "), "evidence has the numbers and the trend: " + (cf == null ? "" : cf.evidence));
    check(cf != null && cf.suggestion.contains("backup model"), "quota advice mentions the backup model");

    // ---- key problems are the owner's to fix
    JSONObject k = add(add(add(new JSONObject(), today, "chat_ok", 1, 2), today, "chat_fail", 1, 3), today, "chat_fail:API_KEY_INVALID", 1, 3);
    SelfImprovement.Finding kf = finding(SelfImprovement.analyze(k, today), "chat_failures");
    check(kf != null && kf.level == 3 && kf.suggestion.contains("Only you"), "key failures need the owner");

    // ---- below thresholds: nothing reported
    JSONObject fine = add(add(new JSONObject(), today, "chat_ok", 1, 20), today, "chat_fail", 1, 1);
    check(finding(SelfImprovement.analyze(fine, today), "chat_failures") == null, "5% failures is not a finding");

    // ---- speed, voice, crashes, agents, ordering
    JSONObject s = new JSONObject();
    s = add(s, today, "chat_ok", 1, 5); s = add(s, today, "chat_ms", 10000, 5);
    s = add(s, today, "live_reply", 1, 6); s = add(s, today, "live_reply_ms", 3200, 6);
    s = add(s, today, "crash", 1, 2);
    s = add(s, today, "agent_run:media", 1, 4); s = add(s, today, "agent_fail:media", 1, 2);
    s = add(s, today, "live_error", 1, 3);
    fs = SelfImprovement.analyze(s, today);
    check(finding(fs, "chat_slow") != null, "slow text replies found");
    check(finding(fs, "voice_slow") != null && finding(fs, "voice_slow").evidence.contains("3.2 s"), "slow voice replies found with average");
    check(finding(fs, "crashes") != null, "crashes found");
    check(finding(fs, "agent_media") != null, "failing agent found");
    check(finding(fs, "live_drops") != null, "Live drops found");
    check(fs.get(0).id.equals("crashes"), "crashes rank first: " + fs.get(0).id);
    String report = SelfImprovement.report(s, today, fs);
    check(report.contains("5 text replies worked") && report.contains("to improve"), "report: " + report);

    // ---- metrics for the dashboard
    JSONObject m = SelfImprovement.metrics(s, today);
    check(m.getJSONObject("this_week").getLong("text_avg_ms") == 10000 && m.getJSONObject("this_week").getLong("crashes") == 2, "dashboard metrics");

    // ---- development memory
    long now = 1_800_000_000_000L;
    JSONArray mem = new JSONArray();
    mem = SelfImprovement.remember(mem, "bug", "Crash: NPE at X.y:1", "first", now);
    mem = SelfImprovement.remember(mem, "bug", "Crash: NPE at X.y:1", "again", now + 1000);
    mem = SelfImprovement.remember(mem, "nonsense", "x", "y", now);
    check(mem.length() == 1 && mem.getJSONObject(0).getInt("count") == 2 && mem.getJSONObject(0).getString("detail").equals("again"), "same bug is counted, not duplicated");
    check(mem.getJSONObject(0).getString("status").equals("open"), "new bug is open");
    mem = SelfImprovement.settle(mem, now + 8L * 86400000L);
    check(mem.getJSONObject(0).getString("status").equals("quiet"), "bug with no repeat for 7 days is quiet, not fixed");
    mem = SelfImprovement.remember(mem, "bug", "Crash: NPE at X.y:1", "back", now + 9L * 86400000L);
    check(mem.getJSONObject(0).getString("status").equals("open"), "a quiet bug that happens again reopens");
    JSONArray big = new JSONArray();
    for (int i = 0; i < SelfImprovement.MEMORY_LIMIT + 20; i++) big = SelfImprovement.remember(big, "note", "note " + i, "", now + i);
    check(big.length() == SelfImprovement.MEMORY_LIMIT, "memory is capped");

    // ---- ideas and approvals
    mem = SelfImprovement.proposeFrom(new JSONArray(), fs, now);
    JSONObject idea = SelfImprovement.find(mem, "idea:crashes");
    check(idea != null && idea.getString("status").equals("proposed"), "findings become proposed ideas");
    check(SelfImprovement.decide(mem, "idea:crashes", "sent", now) == null, "cannot send before approval");
    check(SelfImprovement.decide(mem, "idea:crashes", "approve", now) != null && idea.getString("status").equals("approved"), "owner approves");
    String url = SelfImprovement.issueUrl("harissiddique421-star/Haris-ai", idea, "1.0.0-rc.45", m);
    check(url.startsWith("https://github.com/harissiddique421-star/Haris-ai/issues/new?") && url.contains("title=") && !url.contains(" "), "prefilled GitHub issue link");
    check(SelfImprovement.issueUrl("bad repo", idea, "v", m).isEmpty(), "bad repo name gives no link");
    check(SelfImprovement.decide(mem, "idea:crashes", "sent", now) != null && idea.getString("status").equals("sent"), "approved idea can be sent");
    check(SelfImprovement.decide(mem, "idea:crashes", "dismiss", now) == null, "a sent idea can't be dismissed");
    SelfImprovement.decide(mem, "idea:voice_slow", "dismiss", now);
    mem = SelfImprovement.proposeFrom(mem, fs, now + 5);
    check(SelfImprovement.find(mem, "idea:voice_slow").getString("status").equals("dismissed"), "dismissed ideas stay dismissed");
    int ideas = 0; for (int i = 0; i < mem.length(); i++) if (mem.getJSONObject(i).getString("id").equals("idea:crashes")) ideas++;
    check(ideas == 1, "re-analysis refreshes ideas instead of duplicating them");

    // ---- self-healing decisions
    check(SelfImprovement.heal("TIMEOUT", 1).equals("retry") && SelfImprovement.heal("TIMEOUT", 2).equals("stop"), "timeout: one retry");
    check(SelfImprovement.heal("PROVIDER_UNAVAILABLE", 1).equals("retry"), "outage: retry");
    check(SelfImprovement.heal("QUOTA_EXCEEDED", 1).equals("switch") && SelfImprovement.heal("MODEL_UNAVAILABLE", 2).equals("switch"), "quota or missing model: switch");
    check(SelfImprovement.heal("QUOTA_EXCEEDED", SelfImprovement.MAX_CHAT_ATTEMPTS).equals("stop"), "attempts are limited");
    check(SelfImprovement.heal("API_KEY_INVALID", 1).equals("stop") && SelfImprovement.heal(null, 1).equals("stop"), "key problems stop at once");
    List<String> tried = new ArrayList<>(Collections.singletonList("gemini-3.5-flash"));
    String next = SelfImprovement.nextTextModel("gemini-3.5-flash", tried);
    check(next.equals("gemini-3.8-flash"), "first backup model: " + next);
    tried.add(next); tried.add(SelfImprovement.nextTextModel("gemini-3.5-flash", tried));
    check(SelfImprovement.nextTextModel("gemini-3.5-flash", tried).isEmpty(), "no more backups");
    check(SelfImprovement.nextTextModel("my-custom-model", Collections.singletonList("my-custom-model")).equals("gemini-3.5-flash"), "custom saved model falls back to the default first");

    if (failures > 0) { System.out.println(failures + " self-improvement check(s) failed"); System.exit(1); }
    System.out.println("Self-improvement checks passed.");
  }
}
