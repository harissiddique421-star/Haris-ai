package com.myraa.mobile;

import java.util.*;
import java.util.regex.*;

/**
 * Plain logic for the Second Brain notes (notes, ideas, tasks, decisions), kept free of Android so
 * it can be checked on a JVM: picking the kind, pulling #tags out of the text, making a short title,
 * refusing secrets, and scoring how well a note matches a search.
 *
 * Search is word matching with a little typo tolerance (prefixes and one-letter mistakes). It is not
 * an embedding or semantic search, and the app never claims that it is.
 */
final class NoteLogic {
  private NoteLogic() {}

  static final String NOTE = "note", IDEA = "idea", TASK = "task", DECISION = "decision";
  static final String[] KINDS = { NOTE, IDEA, TASK, DECISION };
  static final int MAX_TEXT = 1000, MAX_TAGS = 8;

  /** The kind the user asked for, or a guess from the words when they did not say. */
  static String kind(String raw, String text) {
    String k = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    for (String s : KINDS) if (s.equals(k)) return s;
    if (k.startsWith("todo") || k.startsWith("to-do") || k.equals("kaam")) return TASK;
    if (k.startsWith("decid") || k.equals("faisla") || k.equals("faisala")) return DECISION;
    if (k.startsWith("idea") || k.equals("khayal")) return IDEA;
    String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
    if (t.matches("(?s)^(?:todo|to do|task|karna hai|yaad se)\\b.*") || t.matches("(?s).*\\b(?:karna hai|karni hai|karne hain|need to|have to|must|todo)\\b.*")) return TASK;
    if (t.matches("(?s).*\\b(?:decided|decision|faisla|tay kiya|tay hua|we will go with|final hai)\\b.*")) return DECISION;
    if (t.matches("(?s)^(?:idea|khayal)\\b.*|.*\\b(?:what if|kya ho agar|idea)\\b.*")) return IDEA;
    return NOTE;
  }

  private static final Pattern TAG = Pattern.compile("(?<![\\p{L}\\p{N}_])#([\\p{L}\\p{N}_-]{1,30})");

  /** #tags in the text plus any given separately, lower case, without '#', at most MAX_TAGS. */
  static List<String> tags(String text, String extra) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    Matcher m = TAG.matcher(text == null ? "" : text);
    while (m.find()) out.add(m.group(1).toLowerCase(Locale.ROOT));
    if (extra != null) for (String t : extra.split("[,\\s]+")) { String c = t.replaceAll("^#+", "").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_-]", ""); if (!c.isEmpty() && c.length() <= 30) out.add(c); }
    List<String> list = new ArrayList<>(out);
    return list.size() > MAX_TAGS ? list.subList(0, MAX_TAGS) : list;
  }
  static String joinTags(List<String> tags) { return tags.isEmpty() ? "" : "," + String.join(",", tags) + ","; }
  static List<String> splitTags(String stored) {
    List<String> out = new ArrayList<>();
    if (stored != null) for (String t : stored.split(",")) if (!t.isEmpty()) out.add(t);
    return out;
  }

  /** The text without a leading "note:"/"idea:"/"task:" label and with spaces tidied. */
  static String clean(String text) {
    String t = text == null ? "" : text.trim().replaceAll("\\s+", " ");
    return t.replaceFirst("(?i)^(?:note|idea|task|todo|decision|faisla)\\s*[:-]\\s*", "").trim();
  }

  /** The first sentence or first 60 characters, cut at a word. */
  static String title(String text) {
    String t = clean(text).replaceAll("(?<![\\p{L}\\p{N}_])#[\\p{L}\\p{N}_-]+", "").replaceAll("\\s+", " ").trim();
    int end = -1;
    for (String p : new String[]{". ", "? ", "! ", "\n"}) { int i = t.indexOf(p); if (i > 0 && (end < 0 || i < end)) end = i; }
    if (end > 0) t = t.substring(0, end);
    if (t.length() > 60) { int cut = t.lastIndexOf(' ', 60); t = t.substring(0, cut > 20 ? cut : 60) + "…"; }
    return t;
  }

  /** Throws with a message the user can hear when the note cannot be saved. */
  static void check(String text) {
    String t = clean(text);
    if (t.isEmpty()) throw new IllegalArgumentException("Tell me what to note down.");
    if (t.length() > MAX_TEXT) throw new IllegalArgumentException("That is too long for one note. Keep it under " + MAX_TEXT + " characters.");
    if (MemoryRank.secret(t)) throw new IllegalArgumentException("I don't save passwords, PINs, OTPs or card numbers in notes.");
  }

  /** Levenshtein distance, stopping early once it passes max. */
  static int distance(String a, String b, int max) {
    if (Math.abs(a.length() - b.length()) > max) return max + 1;
    int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) prev[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      cur[0] = i; int best = cur[0];
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
        best = Math.min(best, cur[j]);
      }
      if (best > max) return max + 1;
      int[] s = prev; prev = cur; cur = s;
    }
    return prev[b.length()];
  }

  /** How well one query word matches one note word: exact 3, prefix 2, one typo 1.5, else 0. */
  static double wordMatch(String q, String w) {
    if (q.equals(w)) return 3;
    if (q.length() >= 3 && w.length() >= 3 && (w.startsWith(q) || q.startsWith(w))) return 2;
    if (q.length() >= 4 && w.length() >= 4 && distance(q, w, 1) <= 1) return 1.5;
    return 0;
  }

  /**
   * Relevance of a note to a search. Each query word takes its best match in the note; words in the
   * title or tags count a bit more. 0 means no query word matched at all.
   */
  static double score(String query, String title, String body, List<String> tags) {
    Set<String> q = MemoryRank.tokens(query);
    if (q.isEmpty()) return 0;
    Set<String> titleWords = MemoryRank.tokens(title), bodyWords = MemoryRank.tokens(body);
    Set<String> tagWords = new HashSet<>(); for (String t : tags) tagWords.addAll(MemoryRank.tokens(t.replace('-', ' ').replace('_', ' ')));
    double total = 0; int hit = 0;
    for (String w : q) {
      double best = 0;
      for (String t : titleWords) best = Math.max(best, wordMatch(w, t) * 1.3);
      for (String t : tagWords) best = Math.max(best, wordMatch(w, t) * 1.3);
      for (String t : bodyWords) best = Math.max(best, wordMatch(w, t));
      if (best > 0) hit++;
      total += best;
    }
    if (hit == 0) return 0;
    // Notes that match most of the query words beat notes that match one of them many times.
    return total * (0.5 + 0.5 * hit / q.size());
  }
}
