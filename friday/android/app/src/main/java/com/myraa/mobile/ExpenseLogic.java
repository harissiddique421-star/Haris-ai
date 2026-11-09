package com.myraa.mobile;

import java.util.*;
import java.util.regex.*;

/**
 * Plain logic for the expense tracker (Stage 11, personal finance), kept free of Android so it is
 * checked on a JVM: reading an amount and a category out of "500 rupay petrol" or "Rs 1,200 groceries",
 * the period a summary covers, and the words of a summary.
 *
 * It only records what the user says they spent. It never reads bank apps, SMS or cards, never moves
 * money, and refuses to store card numbers or PINs in a note.
 */
final class ExpenseLogic {
  private ExpenseLogic() {}

  static final String[] CATEGORIES = { "food", "transport", "bills", "shopping", "health", "education", "family", "charity", "other" };
  static final double MAX_AMOUNT = 100_000_000;
  static final int MAX_NOTE = 200;

  private static final Pattern AMOUNT = Pattern.compile("(?i)(?:rs\\.?|pkr|rupees?|rupay|rupaye|₨)?\\s*(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?\\s*(k|hazaar|hazar|thousand|lakh|lac|lakhs)?\\b\\s*(?:rs\\.?|pkr|rupees?|rupay|rupaye|ka|ke|ki)?");

  /** The first money amount in the text, or -1. "1.5k", "2 hazaar", "3 lakh" and "1,200" all work. */
  static double amount(String text) {
    if (text == null) return -1;
    Matcher m = AMOUNT.matcher(text);
    while (m.find()) {
      String whole = m.group(1).replace(",", ""), frac = m.group(2), unit = m.group(3) == null ? "" : m.group(3).toLowerCase(Locale.ROOT);
      // A bare small number followed by a time word ("5 baje", "7 am") is not money.
      String after = text.substring(m.end()).trim().toLowerCase(Locale.ROOT);
      if (unit.isEmpty() && after.matches("^(?:baje|am|pm|o'?clock|minute|min|ghante|hours?|days?|din|%).*")) continue;
      double v = Double.parseDouble(whole + (frac == null ? "" : "." + frac));
      if (unit.equals("k") || unit.startsWith("haza") || unit.equals("thousand")) v *= 1000;
      else if (unit.startsWith("la")) v *= 100_000;
      if (v <= 0 || v > MAX_AMOUNT) continue;
      return v;
    }
    return -1;
  }

  private static final String[][] WORDS = {
    { "food", "food", "khana", "lunch", "dinner", "breakfast", "nashta", "chai", "tea", "coffee", "restaurant", "biryani", "pizza", "burger", "grocery", "groceries", "sabzi", "doodh", "milk", "ration", "snack", "mithai" },
    { "transport", "petrol", "fuel", "diesel", "cng", "uber", "careem", "bykea", "indrive", "rickshaw", "rikshaw", "bus", "train", "taxi", "fare", "kiraya", "toll", "parking", "ticket", "flight" },
    { "bills", "bill", "electricity", "bijli", "gas", "sui", "water", "pani", "internet", "wifi", "mobile", "recharge", "load", "easyload", "balance", "package", "rent", "kiraya makaan", "fees", "subscription", "netflix" },
    { "shopping", "shopping", "kapray", "kapre", "clothes", "shoes", "jooti", "jutay", "dress", "gift", "tohfa", "phone", "charger", "earbuds", "watch", "bag", "amazon", "daraz" },
    { "health", "medicine", "dawai", "dawa", "doctor", "hospital", "pharmacy", "test", "lab", "checkup", "dentist", "glasses", "gym" },
    { "education", "school", "college", "university", "tuition", "course", "books", "kitab", "kitabein", "stationery", "copy", "exam", "fee" },
    { "family", "ammi", "abbu", "bhai", "behan", "bachon", "bachay", "beta", "beti", "wife", "biwi", "husband", "shohar", "ghar", "home", "eidi" },
    { "charity", "charity", "sadqa", "sadaqah", "zakat", "donation", "masjid", "khairat", "fitra", "qurbani" },
  };

  /** The category the user named, or a guess from the words, else other. */
  static String category(String raw, String text) {
    String r = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    for (String c : CATEGORIES) if (c.equals(r)) return c;
    if (r.startsWith("khan") || r.equals("eating")) return "food";
    if (r.startsWith("travel") || r.startsWith("safar")) return "transport";
    if (r.startsWith("medic") || r.startsWith("sehat")) return "health";
    if (r.startsWith("study") || r.startsWith("taleem")) return "education";
    Set<String> words = MemoryRank.tokens(text == null ? "" : text);
    for (String[] group : WORDS) for (int i = 1; i < group.length; i++) { Set<String> k = MemoryRank.tokens(group[i]); if (!k.isEmpty() && words.containsAll(k)) return group[0]; }
    return "other";
  }

  /** The text without the amount and currency words, tidied, for the note. */
  static String note(String text) {
    String t = text == null ? "" : text.trim().replaceAll("\\s+", " ");
    t = t.replaceFirst("(?i)^(?:expense|kharch|kharcha|spent|spend|add)\\s*[:-]?\\s*", "");
    t = AMOUNT.matcher(t).replaceFirst(" ").replaceAll("(?i)\\b(?:rs\\.?|pkr|rupees?|rupay|rupaye|spent|kharch kiye|kharch kiya|kharch|lagaye|lagay|diye|diya|pe|par|ka|ke|ki|on|for)\\b", " ");
    t = t.replaceAll("\\s+", " ").replaceAll("^[\\s,.:-]+|[\\s,.:-]+$", "");
    return t.length() > MAX_NOTE ? t.substring(0, MAX_NOTE) : t;
  }

  /** Throws with a message the user can hear when the expense cannot be saved. */
  static void check(double amount, String note) {
    if (amount <= 0) throw new IllegalArgumentException("Tell me the amount, like 500 rupees.");
    if (amount > MAX_AMOUNT) throw new IllegalArgumentException("That amount is too large for one expense.");
    if (MemoryRank.secret(note)) throw new IllegalArgumentException("I don't save card numbers, PINs or passwords with expenses.");
  }

  /** Start of the period (today, yesterday, week, month, year, all) in local time, as a millisecond clock value; -1 means everything. */
  static long since(String period, long now, TimeZone zone) {
    String p = period == null ? "" : period.trim().toLowerCase(Locale.ROOT);
    Calendar c = Calendar.getInstance(zone); c.setTimeInMillis(now);
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
    if (p.isEmpty() || p.equals("all") || p.equals("total") || p.equals("sab")) return -1;
    if (p.startsWith("today") || p.equals("aaj")) return c.getTimeInMillis();
    if (p.startsWith("yesterday") || p.equals("kal")) return c.getTimeInMillis() - 86_400_000L;
    if (p.startsWith("week") || p.startsWith("hafta") || p.equals("7")) { c.add(Calendar.DAY_OF_MONTH, -6); return c.getTimeInMillis(); }
    if (p.startsWith("year") || p.equals("saal")) { c.set(Calendar.DAY_OF_YEAR, 1); return c.getTimeInMillis(); }
    c.set(Calendar.DAY_OF_MONTH, 1); return c.getTimeInMillis();
  }
  /** Whether a "yesterday" summary ends at today's start. */
  static long until(String period, long now, TimeZone zone) {
    String p = period == null ? "" : period.trim().toLowerCase(Locale.ROOT);
    if (p.startsWith("yesterday") || p.equals("kal")) return since("today", now, zone);
    return Long.MAX_VALUE;
  }
  static String periodName(String period) {
    String p = period == null ? "" : period.trim().toLowerCase(Locale.ROOT);
    if (p.isEmpty() || p.equals("all") || p.equals("total") || p.equals("sab")) return "in total";
    if (p.startsWith("today") || p.equals("aaj")) return "today";
    if (p.startsWith("yesterday") || p.equals("kal")) return "yesterday";
    if (p.startsWith("week") || p.startsWith("hafta") || p.equals("7")) return "in the last 7 days";
    if (p.startsWith("year") || p.equals("saal")) return "this year";
    return "this month";
  }

  static String money(double v) {
    long r = Math.round(v);
    return Math.abs(v - r) < 0.005 ? String.format(Locale.US, "Rs %,d", r) : String.format(Locale.US, "Rs %,.2f", v);
  }

  /** "Rs 4,300 this month: food Rs 2,500, transport Rs 1,800." with the biggest categories first. */
  static String summary(Map<String, Double> totals, int count, String period) {
    double sum = 0; for (double v : totals.values()) sum += v;
    if (count == 0) return "No expenses recorded " + periodName(period) + ".";
    List<Map.Entry<String, Double>> rows = new ArrayList<>(totals.entrySet());
    rows.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
    StringBuilder s = new StringBuilder(money(sum)).append(' ').append(periodName(period)).append(" in ").append(count).append(count == 1 ? " expense" : " expenses");
    if (rows.size() > 1 || !rows.get(0).getKey().equals("other")) {
      s.append(": ");
      for (int i = 0; i < rows.size() && i < 5; i++) { if (i > 0) s.append(", "); s.append(rows.get(i).getKey()).append(' ').append(money(rows.get(i).getValue())); }
    }
    return s.append('.').toString();
  }
}
