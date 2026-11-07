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
 * Modes: normal (answers, and sometimes one relevant follow-up question), companion (more initiative:
 * curiosity, follow-ups on unfinished topics, light topics, teasing), quiet (answers only what is asked)
 * and focus (short, task-only replies while the user works). The mode shapes the prompt and the in-call
 * check-ins; it never opens the microphone, never messages anyone, and makes no extra AI calls.
 *
 * The check-in is one notification a day at the user's chosen time, only in companion mode, and skipped
 * during a phone or Live call, Do Not Disturb, meeting mode or driving mode. Tapping it is the user's
 * invitation: FRIDAY opens and starts the conversation. Turned off or changed by voice or in Settings.
 */
final class CompanionMode {
  static final String FIRE = "com.myraa.mobile.COMPANION_CHECKIN", OPEN = "com.myraa.mobile.COMPANION_OPEN";
  static final String[] MODES = { "normal", "companion", "quiet", "focus" };
  private static final String CHANNEL = "friday_checkin";
  private CompanionMode() {}

  private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(Announcer.PREFS, Context.MODE_PRIVATE); }
  static String mode(Context c) { String m = prefs(c).getString("talkMode", "normal"); return Arrays.asList(MODES).contains(m) ? m : "normal"; }
  static boolean checkinOn(Context c) { return prefs(c).getBoolean("checkinOn", false); }
  static int checkinHour(Context c) { return prefs(c).getInt("checkinHour", 20); }
  static int checkinMinute(Context c) { return prefs(c).getInt("checkinMinute", 0); }

  static String normalize(String raw) {
    String m = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    if (m.startsWith("comp") || m.contains("friend") || m.contains("baat") || m.contains("chat")) return "companion";
    if (m.startsWith("quiet") || m.contains("silent") || m.contains("chup") || m.contains("khamosh")) return "quiet";
    if (m.startsWith("focus") || m.contains("work") || m.contains("kaam")) return "focus";
    if (m.startsWith("norm") || m.equals("default") || m.isEmpty()) return "normal";
    throw new IllegalArgumentException("Choose normal, companion, quiet or focus mode.");
  }
  static String label(String m) { return "companion".equals(m) ? "Companion" : "quiet".equals(m) ? "Quiet" : "focus".equals(m) ? "Focus" : "Normal"; }

  /** Voice command and Settings: the mode and, optionally, the daily check-in (on, hour, minute). */
  static JSONObject set(Context c, String rawMode, Boolean checkin, int hour, int minute) {
    SharedPreferences.Editor e = prefs(c).edit();
    String m = rawMode == null || rawMode.trim().isEmpty() ? mode(c) : normalize(rawMode);
    e.putString("talkMode", m);
    if (hour >= 0 && hour <= 23) { e.putInt("checkinHour", hour); e.putInt("checkinMinute", minute >= 0 && minute <= 59 ? minute : 0); if (checkin == null) checkin = true; }
    if (checkin != null) e.putBoolean("checkinOn", checkin);
    e.apply();
    schedule(c);
    ControlLog.i("conversation mode: " + m + (checkinOn(c) ? " check-in " + time(c) : ""));
    String msg;
    if ("companion".equals(m)) msg = "Companion mode is on. I'll take more initiative in our conversations." + (checkinOn(c) ? " I'll check in once a day at " + time(c) + "; you can turn that off any time." : " Say a time if you want one friendly check-in a day.");
    else if ("quiet".equals(m)) msg = "Quiet mode is on. I'll only answer what you ask." + (checkinOn(c) ? " The daily check-in is paused." : "");
    else if ("focus".equals(m)) msg = "Focus mode is on. Short, work-only replies, no small talk." + (checkinOn(c) ? " The daily check-in is paused." : "");
    else msg = "Normal mode is on." + (checkinOn(c) ? " The daily check-in only runs in companion mode." : "");
    JSONObject v = PhoneControls.result("done", msg);
    try { v.put("mode", m).put("checkin", checkinOn(c)).put("checkin_time", time(c)); } catch (JSONException ignored) {}
    return v;
  }
  static String time(Context c) { return String.format(Locale.ENGLISH, "%02d:%02d", checkinHour(c), checkinMinute(c)); }
  static JSONObject state(Context c) {
    JSONObject v = new JSONObject();
    try { v.put("mode", mode(c)).put("checkin", checkinOn(c)).put("checkinTime", time(c)); } catch (JSONException ignored) {}
    return v;
  }

  /** How much initiative to take; added to the Live and text system prompts. */
  static String prompt(Context c) {
    String m = mode(c);
    String common = "\nINITIATIVE (decide after each turn, in this order): answer what was asked; then either continue the topic with ONE relevant question, follow up on something unfinished from RELEVANT MEMORY, start a new light topic, or simply stop. Never more than one question at a time, never a question after every reply, never the same question twice in a conversation, and no question when the user gave a command, sounds busy, is in a hurry or wants to end (\"bas\", \"theek hai\", \"baad mein\", \"chup\"). If the user says they want silence, stay quiet until they speak, and if they ask for quiet or focus mode call conversation_mode. Follow-ups must come from what the user actually said or saved, never from pretending you observed their life. Remember meaningful answers with remember (exam dates, project progress, opinions they care about).";
    if ("companion".equals(m)) return common + "\nCONVERSATION MODE: Companion. Take real initiative: show curiosity about the user's opinions, day and interests, ask about unfinished projects, exams or how their mood is now when memory supports it (\"Waise kal tum jis project ki baat kar rahe thay, uska kya bana?\"), share a relevant observation from this conversation, tease a little, and sometimes offer an interesting topic (\"Ek interesting sawal poochun?\"). Keep the conversation going naturally instead of ending it, but read the room: serious or sad moments get care, not jokes.";
    if ("quiet".equals(m)) return common + "\nCONVERSATION MODE: Quiet. Only respond to what the user says. No follow-up questions unless a detail is missing for their request, no check-ins, no new topics, no small talk. Short answers.";
    if ("focus".equals(m)) return common + "\nCONVERSATION MODE: Focus (the user is working). Short, precise, task-only replies. No small talk, jokes, personal follow-ups or new topics; save them for later. Ask a question only when the task needs it.";
    return common + "\nCONVERSATION MODE: Normal. Respond naturally; now and then, when the context genuinely supports it, ask one relevant follow-up question or briefly follow up on one unfinished topic from memory.";
  }

  // ---- daily check-in
  private static PendingIntent alarm(Context c) { return PendingIntent.getBroadcast(c, 61, new Intent(c, ReminderReceiver.class).setAction(FIRE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }
  static void schedule(Context c) {
    AlarmManager a = c.getSystemService(AlarmManager.class); if (a == null) return;
    a.cancel(alarm(c));
    if (!checkinOn(c) || !"companion".equals(mode(c))) return;
    Calendar next = Calendar.getInstance();
    next.set(Calendar.HOUR_OF_DAY, checkinHour(c)); next.set(Calendar.MINUTE, checkinMinute(c)); next.set(Calendar.SECOND, 0); next.set(Calendar.MILLISECOND, 0);
    if (next.getTimeInMillis() <= System.currentTimeMillis() + 60_000L) next.add(Calendar.DAY_OF_YEAR, 1);
    // A 10-minute window keeps it battery friendly and needs no exact-alarm permission.
    a.setWindow(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), 10 * 60_000L, alarm(c));
  }

  /** Why a check-in should not happen right now, or "" when it may. */
  static String skipReason(Context c) {
    if (!checkinOn(c) || !"companion".equals(mode(c))) return "off";
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
    m.createNotificationChannel(new NotificationChannel(CHANNEL, "FRIDAY companion check-in", NotificationManager.IMPORTANCE_DEFAULT));
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
