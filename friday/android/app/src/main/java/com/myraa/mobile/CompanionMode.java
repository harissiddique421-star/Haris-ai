package com.myraa.mobile;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import org.json.*;
import java.util.*;

/**
 * Proactive conversation engine on the phone side: the conversation mode (CompanionModeController and
 * QuietModeController) and the optional daily companion check-in.
 *
 * Modes: active (the default: starts chats, asks questions, follows up on unfinished topics, suggests
 * topics, teases a little; "companion" was its earlier name), normal (mostly answers, sometimes one
 * follow-up), quiet (answers only what is asked) and focus (no interruptions while the user works; with a
 * duration it ends by itself and the previous mode comes back). Frequency (low, medium, high) sets how often
 * Active mode speaks first, and quiet hours stop all initiative and check-ins. The mode shapes the prompt and the in-call
 * check-ins; it never opens the microphone, never messages anyone, and makes no extra AI calls.
 *
 * The check-in is one notification a day at the user's chosen time, only in active mode, and skipped during quiet hours,
 * during a phone or Live call, Do Not Disturb, meeting mode or driving mode. Tapping it is the user's
 * invitation: FRIDAY opens and starts the conversation. Turned off or changed by voice or in Settings.
 */
final class CompanionMode {
  static final String FIRE = "com.myraa.mobile.COMPANION_CHECKIN", OPEN = "com.myraa.mobile.COMPANION_OPEN";
  static final String[] MODES = { "active", "normal", "quiet", "focus" };
  private static final String CHANNEL = "friday_checkin";
  private CompanionMode() {}

  private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(Announcer.PREFS, Context.MODE_PRIVATE); }
  static String mode(Context c) {
    SharedPreferences p = prefs(c); String m = p.getString("talkMode", "active");
    if ("companion".equals(m)) m = "active";
    // A timed focus mode ends by itself and the previous mode comes back.
    if ("focus".equals(m) && p.getLong("focusUntil", 0) > 0 && p.getLong("focusUntil", 0) <= System.currentTimeMillis()) {
      m = p.getString("modeBeforeFocus", "active"); p.edit().putString("talkMode", m).remove("focusUntil").apply();
    }
    return Arrays.asList(MODES).contains(m) ? m : "normal";
  }
  static boolean active(Context c) { return "active".equals(mode(c)); }
  static String frequency(Context c) { String f = prefs(c).getString("talkFrequency", "medium"); return f.matches("low|medium|high") ? f : "medium"; }
  static boolean quietHoursOn(Context c) { return prefs(c).getBoolean("quietHours", true); }
  static String quietStart(Context c) { return prefs(c).getString("quietStart", "22:00"); }
  static String quietEnd(Context c) { return prefs(c).getString("quietEnd", "08:00"); }
  static boolean inQuietHours(Context c) {
    if (!quietHoursOn(c)) return false;
    Calendar now = Calendar.getInstance(); int t = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), a = mins(quietStart(c)), b = mins(quietEnd(c));
    if (a == b) return false;
    return a < b ? t >= a && t < b : t >= a || t < b;
  }
  static int mins(String hhmm) { try { String[] p = hhmm.split(":"); return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]); } catch (RuntimeException e) { return 0; } }
  static boolean checkinOn(Context c) { return prefs(c).getBoolean("checkinOn", true); }
  static int checkinHour(Context c) { return prefs(c).getInt("checkinHour", 20); }
  static int checkinMinute(Context c) { return prefs(c).getInt("checkinMinute", 0); }

  static String normalize(String raw) {
    String m = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    if (m.startsWith("act") || m.startsWith("comp") || m.contains("friend") || m.contains("baat") || m.contains("chat")) return "active";
    if (m.startsWith("quiet") || m.contains("silent") || m.contains("chup") || m.contains("khamosh")) return "quiet";
    if (m.startsWith("focus") || m.contains("work") || m.contains("kaam")) return "focus";
    if (m.startsWith("norm") || m.equals("default") || m.isEmpty()) return "normal";
    throw new IllegalArgumentException("Choose active, normal, quiet or focus mode.");
  }
  static String label(String m) { return "active".equals(m) ? "Active" : "quiet".equals(m) ? "Quiet" : "focus".equals(m) ? "Focus" : "Normal"; }

  /**
   * Voice command and Settings. Fields (all optional): mode, on/checkin (daily check-in), hour, minute,
   * in_minutes (how long focus mode lasts), frequency, quietHours, quietStart, quietEnd.
   */
  static JSONObject set(Context c, JSONObject in) {
    SharedPreferences.Editor e = prefs(c).edit();
    String before = mode(c), raw = in.optString("mode", "");
    String m = raw.trim().isEmpty() ? before : normalize(raw);
    e.putString("talkMode", m);
    if ("focus".equals(m)) {
      if (!"focus".equals(before)) e.putString("modeBeforeFocus", before);
      int minutes = in.optInt("in_minutes", 0);
      if (minutes > 0) e.putLong("focusUntil", System.currentTimeMillis() + Math.min(minutes, 12 * 60) * 60_000L); else if (!"focus".equals(before)) e.remove("focusUntil");
    } else e.remove("focusUntil");
    Boolean checkin = in.has("checkin") ? Boolean.valueOf(in.optBoolean("checkin")) : in.has("on") ? Boolean.valueOf(in.optBoolean("on")) : null;
    int hour = in.has("hour") ? in.optInt("hour", -1) : -1;
    if (hour >= 0 && hour <= 23) { int minute = in.optInt("minute", 0); e.putInt("checkinHour", hour); e.putInt("checkinMinute", minute >= 0 && minute <= 59 ? minute : 0); if (checkin == null) checkin = true; }
    if (checkin != null) e.putBoolean("checkinOn", checkin);
    String f = in.optString("frequency", ""); if (f.matches("low|medium|high")) e.putString("talkFrequency", f);
    if (in.has("quietHours")) e.putBoolean("quietHours", in.optBoolean("quietHours"));
    for (String k : new String[]{ "quietStart", "quietEnd" }) { String v = in.optString(k, ""); if (v.matches("([01]\\d|2[0-3]):[0-5]\\d")) e.putString(k, v); }
    e.commit();
    schedule(c);
    ControlLog.i("conversation mode: " + m + " frequency " + frequency(c) + (checkinOn(c) ? " check-in " + time(c) : "") + (quietHoursOn(c) ? " quiet " + quietStart(c) + "-" + quietEnd(c) : ""));
    String msg;
    if ("active".equals(m)) msg = "Active mode is on. I'll start conversations, ask questions and follow up on things we talked about." + (checkinOn(c) ? " I'll also check in once a day at " + time(c) + "; you can turn that off any time." : "");
    else if ("quiet".equals(m)) msg = "Quiet mode is on. I'll only answer what you ask.";
    else if ("focus".equals(m)) { long until = prefs(c).getLong("focusUntil", 0); msg = "Focus mode is on. No small talk or interruptions" + (until > 0 ? " until " + new java.text.SimpleDateFormat("HH:mm", Locale.ENGLISH).format(new Date(until)) + ", then I'll go back to " + label(prefs(c).getString("modeBeforeFocus", "active")) + " mode." : " until you change it."); }
    else msg = "Normal mode is on. I'll mostly answer and only sometimes ask something.";
    if (quietHoursOn(c) && !"quiet".equals(m)) msg += " Quiet hours: " + quietStart(c) + " to " + quietEnd(c) + ".";
    JSONObject v = PhoneControls.result("done", msg);
    try { v.put("mode", m).put("frequency", frequency(c)).put("checkin", checkinOn(c)).put("checkin_time", time(c)); } catch (JSONException ignored) {}
    return v;
  }
  static String time(Context c) { return String.format(Locale.ENGLISH, "%02d:%02d", checkinHour(c), checkinMinute(c)); }
  static JSONObject state(Context c) {
    JSONObject v = new JSONObject();
    try {
      v.put("mode", mode(c)).put("frequency", frequency(c)).put("checkin", checkinOn(c)).put("checkinTime", time(c))
        .put("quietHours", quietHoursOn(c)).put("quietStart", quietStart(c)).put("quietEnd", quietEnd(c)).put("focusUntil", prefs(c).getLong("focusUntil", 0));
    } catch (JSONException ignored) {}
    return v;
  }

  /** How much initiative to take; added to the Live and text system prompts. */
  static String prompt(Context c) {
    String m = mode(c);
    String common = "\nINITIATIVE (decide after each turn, in this order): answer what was asked; then either continue the topic with ONE relevant question, follow up on something unfinished from RELEVANT MEMORY, start a new light topic, or simply stop. Never more than one question at a time, never a question after every reply, never the same question twice in a conversation, and no question when the user gave a command, sounds busy, is in a hurry or wants to end (\"bas\", \"theek hai\", \"baad mein\", \"chup\"). If the user says they want silence, stay quiet until they speak, and if they ask for quiet or focus mode call conversation_mode. Follow-ups must come from what the user actually said or saved, never from pretending you observed their life. Remember meaningful answers with remember (exam dates, project progress, opinions they care about).";
    if (inQuietHours(c) && !"quiet".equals(m) && !"focus".equals(m)) return common + "\nQUIET HOURS are on now: answer naturally but do not start new topics or ask extra questions unless the user is clearly chatting.";
    if ("active".equals(m)) return common + "\nCONVERSATION MODE: Active (" + ("high".equals(frequency(c)) ? "after most replies keep the conversation going with one natural question" : "low".equals(frequency(c)) ? "now and then add one natural question" : "often add one natural question") + "). Do not just answer and stop: react to what the user said and build on it (User: \"Aaj university gaya tha.\" -> \"Achha! Kya hua aaj, kuch interesting ya wahi boring lectures?\"). Take real initiative: show curiosity about the user's opinions, day and interests, ask about unfinished projects, exams or how their mood is now when memory supports it (\"Waise kal tum jis project ki baat kar rahe thay, uska kya bana?\"), share a relevant observation from this conversation, tease a little, and sometimes offer an interesting topic (\"Ek interesting sawal poochun?\"). Keep the conversation going naturally instead of ending it, but read the room: serious or sad moments get care, not jokes.";
    if ("quiet".equals(m)) return common + "\nCONVERSATION MODE: Quiet. Only respond to what the user says. No follow-up questions unless a detail is missing for their request, no check-ins, no new topics, no small talk. Short answers.";
    if ("focus".equals(m)) return common + "\nCONVERSATION MODE: Focus (the user is working). Short, precise, task-only replies. No small talk, jokes, personal follow-ups or new topics; save them for later. Ask a question only when the task needs it.";
    return common + "\nCONVERSATION MODE: Normal. Respond naturally; now and then, when the context genuinely supports it, ask one relevant follow-up question or briefly follow up on one unfinished topic from memory.";
  }

  // ---- daily check-in
  private static PendingIntent alarm(Context c) { return PendingIntent.getBroadcast(c, 61, new Intent(c, ReminderReceiver.class).setAction(FIRE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }
  static void schedule(Context c) {
    AlarmManager a = c.getSystemService(AlarmManager.class); if (a == null) return;
    a.cancel(alarm(c));
    if (!checkinOn(c) || !active(c)) return;
    Calendar next = Calendar.getInstance();
    next.set(Calendar.HOUR_OF_DAY, checkinHour(c)); next.set(Calendar.MINUTE, checkinMinute(c)); next.set(Calendar.SECOND, 0); next.set(Calendar.MILLISECOND, 0);
    if (next.getTimeInMillis() <= System.currentTimeMillis() + 60_000L) next.add(Calendar.DAY_OF_YEAR, 1);
    // A 10-minute window keeps it battery friendly and needs no exact-alarm permission.
    a.setWindow(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), 10 * 60_000L, alarm(c));
  }

  /** Why a check-in should not happen right now, or "" when it may. */
  static String skipReason(Context c) {
    if (!checkinOn(c) || !active(c)) return "off";
    if (Autonomy.paused(c)) return "paused";
    if (inQuietHours(c)) return "quiet hours";
    String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(new Date());
    if (today.equals(prefs(c).getString("checkinLastDay", ""))) return "already today";
    if (LiveVoiceService.running) return "live call";
    try { AudioManager am = c.getSystemService(AudioManager.class); if (am != null && (am.getMode() == AudioManager.MODE_IN_CALL || am.getMode() == AudioManager.MODE_IN_COMMUNICATION || am.getMode() == AudioManager.MODE_RINGTONE)) return "phone call"; } catch (RuntimeException ignored) {}
    try { NotificationManager n = c.getSystemService(NotificationManager.class); if (n != null && n.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL) return "do not disturb"; } catch (RuntimeException ignored) {}
    if (MeetingMode.on(c)) return "meeting mode";
    if (Announcer.driving(c)) return "driving";
    if (System.currentTimeMillis() - prefs(c).getLong("lastLiveAt", 0) < 60 * 60_000L) return "talked recently";
    return "";
  }

  /** The alarm fired: one gentle notification, then the next day's alarm. Nothing is spoken and the mic stays off. */
  static void fire(Context c) {
    String skip = skipReason(c);
    if (!skip.isEmpty()) { ControlLog.i("companion check-in skipped (" + skip + ")"); schedule(c); return; }
    prefs(c).edit().putString("checkinLastDay", new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(new Date())).apply();
    String text = line(c);
    NotificationManager m = c.getSystemService(NotificationManager.class);
    m.createNotificationChannel(new NotificationChannel(CHANNEL, "FRIDAY check-in", NotificationManager.IMPORTANCE_DEFAULT));
    PendingIntent open = PendingIntent.getActivity(c, 62, new Intent(c, MainActivity.class).setAction(OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    try {
      m.notify(6200, new Notification.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("FRIDAY").setContentText(text)
        .setStyle(new Notification.BigTextStyle().bigText(text + "\nTap to talk. Turn off: Settings > FRIDAY > Conversation."))
        .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).setTimeoutAfter(3 * 60 * 60_000L).build());
    } catch (SecurityException ignored) {}
    ControlLog.i("companion check-in posted");
    schedule(c);
  }

  /** A short opening that hints at an unfinished topic without showing private details on the lock screen. */
  static String line(Context c) {
    long now = System.currentTimeMillis();
    JSONObject project = MemoryStore.get(c).latest(MemoryRank.PROJECT), talk = MemoryStore.get(c).latest(MemoryRank.CONTEXT);
    int pick = (int) ((now / 86_400_000L) % 3);
    if (project != null && now - project.optLong("updated") < 7 * 86_400_000L && pick != 2) return pick == 0 ? "Waise tumhare project ka kya bana? Batao, mujhe janna hai." : "Project mein jo kaam chal raha tha, woh kahan tak pohcha?";
    if (talk != null && now - talk.optLong("updated") < 2 * 86_400_000L && pick == 1) return "Kal wali baat adhoori reh gayi thi... continue karein?";
    String[] lines = { "Achha, ek baat batao... aaj tumhara din kaisa guzra?", "Ek interesting sawal poochun? 😏", "Aaj kya chal raha hai dimagh mein? Thori der baat karein?" };
    return lines[(int) ((now / 86_400_000L) % lines.length)];
  }
}
