package com.myraa.mobile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * "FRIDAY, unlock my phone and open YouTube": the one task waiting for the phone's own unlock.
 *
 * Voice never unlocks the phone. The wake listener only queues the task; MainActivity asks Android
 * to show its normal lock screen (fingerprint, face, PIN or pattern, entered by the user) and runs
 * the task only after Android reports the unlock and the keyguard is really gone. The task expires
 * after a minute, is used at most once, and is dropped when the unlock is cancelled or fails.
 * Only a fixed list of apps can be opened this way. Nothing about the unlock itself is seen or stored.
 */
final class UnlockTask {
  static final long TTL_MS = 60_000;
  /** Spoken names (English, Hindi, Urdu) to the app name MobileActions opens. */
  private static final Map<String, String> APPS = new LinkedHashMap<>();
  static {
    for (String s : new String[]{"youtube", "you tube", "यूट्यूब", "یوٹیوب"}) APPS.put(s, "youtube");
    for (String s : new String[]{"whatsapp", "whats app", "watsapp", "व्हाट्सएप", "वॉट्सऐप", "واٹس ایپ"}) APPS.put(s, "whatsapp");
    for (String s : new String[]{"google chrome", "chrome", "क्रोम", "کروم"}) APPS.put(s, "chrome");
    for (String s : new String[]{"google maps", "maps", "map", "मैप्स", "میپس"}) APPS.put(s, "google maps");
    for (String s : new String[]{"instagram", "insta", "इंस्टाग्राम", "انسٹاگرام"}) APPS.put(s, "instagram");
    for (String s : new String[]{"gmail", "जीमेल"}) APPS.put(s, "gmail");
    for (String s : new String[]{"spotify"}) APPS.put(s, "spotify");
  }
  private static final Pattern UNLOCK = Pattern.compile("(?<!\\p{L})(unlock|un lock|anlock|अनलॉक|अनलोक|ان لاک|انلاک)(?!\\p{L})");

  private static String app;
  private static long queuedAt;

  private UnlockTask() {}

  /**
   * What to do for heard text (already lower-cased): null when it is not an unlock command, "" for a plain
   * "unlock my phone" (FRIDAY then starts a normal call), or the app to open after the unlock.
   */
  static String parse(String heard) {
    if (heard == null) return null;
    String s = heard.toLowerCase(Locale.ROOT);
    java.util.regex.Matcher m = UNLOCK.matcher(s);
    if (!m.find()) return null;
    String after = " " + s.substring(m.end()) + " ";
    for (Map.Entry<String, String> e : APPS.entrySet())
      if (Pattern.compile("(?<!\\p{L})" + Pattern.quote(e.getKey()) + "(?!\\p{L})").matcher(after).find()) return e.getValue();
    return "";
  }

  static synchronized void queue(String target, long now) { app = target == null ? "" : target; queuedAt = now; }

  /** The waiting task, once: null when there is none, it expired or it was cancelled. */
  static synchronized String take(long now) {
    String t = app; long at = queuedAt; app = null;
    if (t == null) return null;
    if (now < at || now - at > TTL_MS) { ControlLog.i("unlock: the waiting task expired, nothing was opened"); return null; }
    return t;
  }

  static synchronized boolean waiting(long now) { return app != null && now >= queuedAt && now - queuedAt <= TTL_MS; }

  static synchronized void cancel(String why) {
    if (app != null) ControlLog.i("unlock: waiting task dropped (" + why + ")");
    app = null;
  }
}
