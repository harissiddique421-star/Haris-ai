package com.myraa.mobile;

import java.net.IDN;
import java.util.*;
import java.util.regex.*;

/**
 * Warning signs in links and messages (Stage 9, threat intelligence), plain Java so it is checked on
 * a JVM. It looks at the text only: it never opens a link, never sends it anywhere and keeps nothing.
 *
 * The result is "danger", "caution" or "no_signs" with the reasons found. "no_signs" does not mean a
 * link is safe: a new scam site can look perfectly normal, and the app says so.
 */
final class LinkSafety {
  private LinkSafety() {}

  static final String DANGER = "danger", CAUTION = "caution", NONE = "no_signs";

  private static final Pattern URL = Pattern.compile("(?i)\\b((?:https?://|www\\.)[^\\s<>\"']+|[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|net|org|pk|in|xyz|top|click|link|info|ly|gd|me|io|co|tk|ml|ga|cf|gq|zip|mov|live|site|online|shop|store|app|biz|cc|ru|cn|icu|buzz|rest|work|support|help|ai)(?:/[^\\s<>\"']*)?)");
  private static final Set<String> SHORTENERS = new HashSet<>(Arrays.asList("bit.ly", "tinyurl.com", "cutt.ly", "is.gd", "rb.gy", "t.ly", "shorturl.at", "ow.ly", "rebrand.ly", "tiny.cc", "s.id", "v.gd", "goo.su", "bitly.ws"));
  private static final Set<String> RISKY_TLDS = new HashSet<>(Arrays.asList("xyz", "top", "click", "link", "tk", "ml", "ga", "cf", "gq", "zip", "mov", "icu", "buzz", "rest", "work", "support", "live", "online", "site", "shop"));
  /** Names scammers copy, and the real domains that may use them. */
  private static final String[][] BRANDS = {
    {"whatsapp", "whatsapp.com", "whatsapp.net", "wa.me"}, {"facebook", "facebook.com", "fb.com", "fb.me"}, {"instagram", "instagram.com"},
    {"google", "google.com", "google.com.pk", "goo.gl", "youtube.com", "youtu.be"}, {"paypal", "paypal.com"}, {"amazon", "amazon.com", "amazon.in", "amzn.to"},
    {"apple", "apple.com", "icloud.com"}, {"microsoft", "microsoft.com", "live.com", "office.com"}, {"netflix", "netflix.com"},
    {"jazzcash", "jazzcash.com.pk"}, {"easypaisa", "easypaisa.com.pk"}, {"hbl", "hbl.com"}, {"meezan", "meezanbank.com"}, {"ubl", "ubldigital.com"},
    {"bisp", "bisp.gov.pk"}, {"fbr", "fbr.gov.pk"}, {"nadra", "nadra.gov.pk"}, {"daraz", "daraz.pk"}, {"binance", "binance.com"},
  };
  private static final Pattern SCAM_WORDS = Pattern.compile("(?i)\\b(?:you (?:have )?won|winner|lottery|prize|inaam|jeet(?:a|e|ay)|free (?:gift|iphone|recharge|balance)|claim (?:now|your)|account (?:will be )?(?:blocked|suspended|band)|verify (?:your )?account|update (?:your )?kyc|kyc update|sim (?:will be )?block|urgent(?:ly)?|last chance|bisp|benazir|ehsaas|otp|pin code|password|card number|cvv|apna (?:otp|pin|code) (?:batain|bhejein|share))\\b");

  static final class Finding {
    final String level; final List<String> reasons; final List<String> links;
    Finding(String level, List<String> reasons, List<String> links) { this.level = level; this.reasons = reasons; this.links = links; }
  }

  static List<String> links(String text) {
    List<String> out = new ArrayList<>();
    if (text == null) return out;
    Matcher m = URL.matcher(text);
    while (m.find() && out.size() < 5) { String u = m.group(1).replaceAll("[).,!?]+$", ""); if (!out.contains(u)) out.add(u); }
    return out;
  }

  static String host(String url) {
    String u = url.replaceFirst("(?i)^https?://", "");
    int at = u.indexOf('@'), slash = u.indexOf('/');
    if (at >= 0 && (slash < 0 || at < slash)) u = u.substring(at + 1);
    u = u.split("[/?#]", 2)[0];
    return u.replaceFirst(":\\d+$", "").replaceFirst("^www\\.", "").toLowerCase(Locale.ROOT);
  }

  private static boolean ownedBy(String host, String domain) { return host.equals(domain) || host.endsWith("." + domain); }

  /** Replaces digits that stand in for letters (paypa1, g00gle) so lookalikes are found. */
  private static String unLeet(String s) { return s.replace('0', 'o').replace('1', 'l').replace('3', 'e').replace('5', 's').replace('4', 'a').replace("rn", "m"); }

  static Finding check(String text) {
    List<String> found = links(text), reasons = new ArrayList<>();
    int danger = 0, caution = 0;
    for (String url : found) {
      String h = host(url);
      if (h.isEmpty()) continue;
      String ascii; try { ascii = IDN.toASCII(h); } catch (Exception e) { ascii = h; }
      if (ascii.contains("xn--") || !ascii.equals(h)) { danger++; reasons.add(h + " uses look-alike letters from another alphabet"); }
      if (h.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) { danger++; reasons.add(h + " is a bare number address instead of a name"); }
      String noPort = url.replaceFirst("(?i)^https?://", "");
      if (noPort.indexOf('@') > 0 && (noPort.indexOf('/') < 0 || noPort.indexOf('@') < noPort.indexOf('/'))) { danger++; reasons.add("the link hides its real address after an @"); }
      if (SHORTENERS.contains(h)) { caution++; reasons.add(h + " is a link shortener, so the real site is hidden"); }
      String tld = h.substring(h.lastIndexOf('.') + 1);
      if (RISKY_TLDS.contains(tld)) { caution++; reasons.add("." + tld + " addresses are cheap and often used for scams"); }
      if (url.toLowerCase(Locale.ROOT).startsWith("http://") && url.toLowerCase(Locale.ROOT).matches(".*(?:login|signin|verify|account|bank|wallet|pay).*")) { caution++; reasons.add("a login or payment page without a secure (https) connection"); }
      String flat = unLeet(h.replace("-", ""));
      for (String[] b : BRANDS) {
        boolean official = false; for (int i = 1; i < b.length; i++) if (ownedBy(h, b[i])) official = true;
        if (!official && (b[0].length() > 4 ? flat.contains(b[0]) : Arrays.asList(unLeet(h).split("[.-]")).contains(b[0]))) { danger++; reasons.add(h + " uses the name " + b[0] + " but is not its real website"); break; }
      }
      if (h.split("\\.").length > 4 || h.length() - h.replace("-", "").length() >= 3) { caution++; reasons.add(h + " is an unusually long, hyphenated address"); }
    }
    Matcher w = SCAM_WORDS.matcher(text == null ? "" : text);
    Set<String> words = new LinkedHashSet<>(); while (w.find() && words.size() < 3) words.add(w.group().toLowerCase(Locale.ROOT));
    if (!words.isEmpty() && !found.isEmpty()) { caution++; reasons.add("pressure or prize words next to a link (" + String.join(", ", words) + ")"); }
    else if (!words.isEmpty() && asksSecret(words)) { caution++; reasons.add("it asks about a code, PIN or password (" + String.join(", ", words) + ")"); }
    String level = danger > 0 || caution >= 3 ? DANGER : caution > 0 ? CAUTION : NONE;
    return new Finding(level, reasons, found);
  }

  private static boolean asksSecret(Set<String> words) { for (String x : words) if (x.matches(".*(?:otp|pin|password|cvv|card number).*")) return true; return false; }

  /** One sentence FRIDAY can say. */
  static String advice(Finding f) {
    if (DANGER.equals(f.level)) return "Strong warning signs: " + String.join("; ", f.reasons) + ". Don't open it or share any code, PIN or password; check with the sender another way.";
    if (CAUTION.equals(f.level)) return "Be careful: " + String.join("; ", f.reasons) + ". Open it only if you trust the sender, and never enter a code or password from it.";
    if (f.links.isEmpty()) return "I found no link in that text.";
    return "I found no common warning signs, but that does not prove the link is safe. New scam sites can look normal.";
  }
}
