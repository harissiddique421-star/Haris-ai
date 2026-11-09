package com.myraa.mobile;

import java.util.*;
import org.json.*;

/**
 * Routine suggestions (Stage 8, predictive assistant), plain Java so it is checked on a JVM. It counts
 * how often the user asks for a kind of action at each hour of the day (action name and hour only:
 * no message text, no recipients, no search words) and, when something has been asked for at least
 * MIN_TIMES at about this hour on different days and not yet today, offers it as a suggestion.
 *
 * A suggestion is a guess from past behaviour, never a fact about what the user will do, and the app
 * says so. Counts live in one small JSON object the caller stores; they fade so old habits drop out.
 */
final class HabitLogic {
  private HabitLogic() {}

  static final int MIN_TIMES = 3, MAX_KEYS = 120;
  /** Actions worth suggesting: things people do at the same time of day. Diagnostics, confirmations and one-off chores are not habits. */
  static final Set<String> HABITS = new HashSet<>(Arrays.asList("play_youtube", "play_music", "resume_video", "search_youtube", "open_app", "navigate", "briefing", "prayer_times", "set_alarm", "meeting_mode", "conversation_mode", "torch", "read_notifications", "upcoming_events", "missed_calls", "expense"));
  static final Map<String, String> WORDS = new HashMap<>();
  static {
    WORDS.put("play_youtube", "play something on YouTube"); WORDS.put("play_music", "play music"); WORDS.put("resume_video", "continue the last video or Quran");
    WORDS.put("search_youtube", "search YouTube"); WORDS.put("open_app", "open an app"); WORDS.put("navigate", "start navigation");
    WORDS.put("briefing", "hear the briefing"); WORDS.put("prayer_times", "ask for prayer times"); WORDS.put("set_alarm", "set an alarm");
    WORDS.put("meeting_mode", "turn on meeting mode"); WORDS.put("conversation_mode", "change the conversation mode"); WORDS.put("torch", "use the torch");
    WORDS.put("read_notifications", "hear the notifications"); WORDS.put("upcoming_events", "check the calendar"); WORDS.put("missed_calls", "check missed calls"); WORDS.put("expense", "note an expense");
  }

  /** Records one successful action at hour (0-23) on day (any stable day key like "2026-11-09"). Returns the updated counts. */
  static JSONObject record(JSONObject counts, String action, int hour, String day) {
    if (counts == null) counts = new JSONObject();
    if (action == null || !HABITS.contains(action) || hour < 0 || hour > 23) return counts;
    String key = action + "@" + hour;
    try {
      JSONObject e = counts.optJSONObject(key);
      if (e == null) { e = new JSONObject().put("n", 0).put("day", ""); counts.put(key, e); }
      // One count per day per hour, so a loop of retries does not look like a habit.
      if (day.equals(e.optString("day"))) return counts;
      e.put("n", Math.min(99, e.optInt("n") + 1)).put("day", day);
      if (counts.length() > MAX_KEYS) fade(counts);
    } catch (JSONException ignored) {}
    return counts;
  }

  /** Halves every count (dropping the ones that reach zero) so the table stays small and old habits fade. */
  static void fade(JSONObject counts) {
    List<String> drop = new ArrayList<>();
    for (Iterator<String> it = counts.keys(); it.hasNext();) {
      String k = it.next(); JSONObject e = counts.optJSONObject(k); if (e == null) { drop.add(k); continue; }
      int n = e.optInt("n") / 2; if (n <= 0) drop.add(k); else try { e.put("n", n); } catch (JSONException ignored) {}
    }
    for (String k : drop) counts.remove(k);
  }

  static final class Suggestion { final String action, words; final int times; Suggestion(String action, String words, int times) { this.action = action; this.words = words; this.times = times; } }

  /** Actions asked for MIN_TIMES or more times at this hour or the one before or after, strongest first, skipping ones already done today. */
  static List<Suggestion> suggest(JSONObject counts, int hour, String day) {
    Map<String, Integer> total = new HashMap<>(); Set<String> doneToday = new HashSet<>();
    if (counts != null) for (Iterator<String> it = counts.keys(); it.hasNext();) {
      String k = it.next(); int at = k.lastIndexOf('@'); if (at <= 0) continue;
      String action = k.substring(0, at); int h; try { h = Integer.parseInt(k.substring(at + 1)); } catch (NumberFormatException e) { continue; }
      JSONObject e = counts.optJSONObject(k); if (e == null) continue;
      if (day.equals(e.optString("day"))) doneToday.add(action);
      int diff = Math.abs(h - hour); if (diff > 12) diff = 24 - diff;
      if (diff <= 1) total.put(action, (total.containsKey(action) ? total.get(action) : 0) + e.optInt("n"));
    }
    List<Suggestion> out = new ArrayList<>();
    for (Map.Entry<String, Integer> en : total.entrySet())
      if (en.getValue() >= MIN_TIMES && !doneToday.contains(en.getKey())) out.add(new Suggestion(en.getKey(), WORDS.containsKey(en.getKey()) ? WORDS.get(en.getKey()) : en.getKey().replace('_', ' '), en.getValue()));
    out.sort((a, b) -> b.times - a.times);
    return out.size() > 3 ? out.subList(0, 3) : out;
  }

  /** The suggestions as a result Gemini can phrase. */
  static JSONObject result(List<Suggestion> list) {
    JSONObject v = new JSONObject();
    try { v.put("status", list.isEmpty() ? "none" : "done").put("message", list.isEmpty()
      ? "No routine stands out for this time of day yet. Say so briefly if asked; do not invent a suggestion."
      : "Around this time the user has often asked for these (a guess from past behaviour, not a fact). Offer at most one, in one short line, as a question; never start it without the user's yes."); } catch (JSONException ignored) {}
    try { JSONArray a = new JSONArray(); for (Suggestion s : list) a.put(new JSONObject().put("action", s.action).put("usually", s.words).put("times", s.times)); v.put("suggestions", a); } catch (JSONException ignored) {}
    return v;
  }
}
