package com.myraa.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

/** Stores the routine counts of HabitLogic in the app's preferences (action name and hour only). */
final class Habits {
  private Habits() {}
  private static final String KEY = "habits";
  private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("myraa.mobile.v1", 0); }
  private static JSONObject load(Context c) { try { return new JSONObject(prefs(c).getString(KEY, "{}")); } catch (JSONException e) { return new JSONObject(); } }
  static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()); }

  /** Called when an action finished well. Nothing but the action name and the hour is kept. */
  static synchronized void record(Context c, String action, String status) {
    if (c == null || action == null || !HabitLogic.HABITS.contains(action)) return;
    if (status == null || !(status.equals("done") || status.equals("sent") || status.equals("playing") || status.equals("opened"))) return;
    JSONObject counts = HabitLogic.record(load(c), action, Calendar.getInstance().get(Calendar.HOUR_OF_DAY), today());
    prefs(c).edit().putString(KEY, counts.toString()).apply();
  }
  static synchronized List<HabitLogic.Suggestion> now(Context c) { return HabitLogic.suggest(load(c), Calendar.getInstance().get(Calendar.HOUR_OF_DAY), today()); }
  static JSONObject suggestions(Context c) { return HabitLogic.result(now(c)); }
  static synchronized void clear(Context c) { prefs(c).edit().remove(KEY).apply(); }
}
