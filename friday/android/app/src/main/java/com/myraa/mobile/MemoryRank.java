package com.myraa.mobile;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Plain logic for long-term memory, kept free of Android so it can be checked on a JVM:
 * normalising a fact, guessing its category, spotting duplicates and contradictions,
 * flagging sensitive facts, and scoring how relevant a memory is to what the user just said.
 */
final class MemoryRank {
  private MemoryRank() {}

  static final String PROFILE = "profile", PREFERENCE = "preference", PROJECT = "project", CONTEXT = "context";
  static final String[] CATEGORIES = { PROFILE, PREFERENCE, PROJECT, CONTEXT };

  /** English and Roman Urdu/Hindi filler words that say nothing about the topic. */
  private static final Set<String> STOP = new HashSet<>(Arrays.asList(
    "a","an","the","is","are","was","were","be","to","of","and","or","in","on","at","for","with","my","me","i","you","your","it","this","that","do","does","did","so","but","not","no","yes","can","will","what","how","why","when","where","who","please","about","from","as","by","just","also","very","its","im","am","have","has","had","we","our",
    "hai","hain","ho","hoon","hun","tha","thi","the","ka","ki","ke","ko","se","mein","main","mai","mujhe","mera","meri","mere","tum","tumhe","tumhara","aap","ap","ye","yeh","wo","woh","kya","kaise","kyun","kab","kahan","kaun","aur","bhi","to","na","nahi","nahin","haan","han","ek","karo","kar","raha","rahi","rahe","hota","hoti","bas","acha","achha","theek","thik","yaar","please","batao","bolo"));

  static String normalize(String s) {
    return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
  }

  static Set<String> tokens(String s) {
    Set<String> out = new LinkedHashSet<>();
    for (String w : normalize(s).split(" ")) if (w.length() > 1 && !STOP.contains(w)) out.add(stem(w));
    return out;
  }
  /** Very light stemming so "projects"/"project" and "apps"/"app" meet. */
  private static String stem(String w) { return w.length() > 4 && w.endsWith("s") && !w.endsWith("ss") ? w.substring(0, w.length() - 1) : w; }

  static double similarity(String a, String b) {
    Set<String> x = tokens(a), y = tokens(b);
    if (x.isEmpty() || y.isEmpty()) return normalize(a).equals(normalize(b)) ? 1 : 0;
    int both = 0; for (String t : x) if (y.contains(t)) both++;
    return both / (double) (x.size() + y.size() - both);
  }

  private static final Pattern PROJECT_CUES = Pattern.compile("(?i)\\b(project|projects|app|application|website|repo|build|building|bana raha|bana rahi|banana|deadline|task|tasks|pending|milestone|launch|release|client|startup|business|feature|bug|progress|decided|decision|plan|kaam)\\b");
  private static final Pattern PREF_CUES = Pattern.compile("(?i)\\b(favou?rite|prefer|prefers|like|likes|love|loves|hate|hates|pasand|napasand|voice|style|always|never|mostly|usually|music|song|songs|food|drink|chai|coffee|team)\\b");
  private static final Pattern PROFILE_CUES = Pattern.compile("(?i)\\b(name|naam|call me|bulao|age|born|birthday|language|zuban|zaban|live in|rehta|rehti|city|work as|job|student|study|studies|sister|brother|mother|father|wife|husband|son|daughter|friend|behen|bhai|ammi|abbu|beta|beti)\\b");

  /** Picks a category for a fact the user did not label. Conversation summaries are always "context". */
  static String guessCategory(String fact) {
    String f = fact == null ? "" : fact;
    if (PROJECT_CUES.matcher(f).find()) return PROJECT;
    if (PREF_CUES.matcher(f).find()) return PREFERENCE;
    if (PROFILE_CUES.matcher(f).find()) return PROFILE;
    return PROFILE;
  }
  static String category(String raw, String fact) {
    String c = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    for (String k : CATEGORIES) if (k.equals(c)) return k;
    if (c.startsWith("pref")) return PREFERENCE;
    if (c.startsWith("proj") || c.equals("task")) return PROJECT;
    if (c.startsWith("summ") || c.startsWith("conv")) return CONTEXT;
    if (c.startsWith("prof") || c.equals("about") || c.equals("me")) return PROFILE;
    return guessCategory(fact);
  }

  /**
   * The subject of a "X is Y" fact ("my favourite colour", "meri behen ka naam"), used to replace an
   * old value instead of keeping both. Empty when the fact has no clear subject.
   */
  static String subjectKey(String fact) {
    String n = normalize(fact);
    String[] splits = { " is ", " are ", " was ", " = " };
    String best = "";
    for (String s : splits) {
      String padded = " " + n + " "; int i = padded.indexOf(s);
      if (i > 0) { String subject = padded.substring(0, i).trim(); if (best.isEmpty() || subject.length() < best.length()) best = subject; }
    }
    // Roman Urdu puts the verb last: "meri behen ka naam sara hai" -> subject is everything before the value.
    if (best.isEmpty() && (n.endsWith(" hai") || n.endsWith(" hain"))) { String[] w = n.split(" "); if (w.length >= 3) best = String.join(" ", Arrays.copyOf(w, w.length - 2)); }
    if (best.isEmpty()) return "";
    String[] words = best.split(" ");
    if (words.length > 6 || tokens(best).isEmpty()) return "";
    return best;
  }

  private static final Pattern SECRET = Pattern.compile("(?is).*\\b(password|passcode|pin|otp|cvv|card number|pass word)\\b.*");
  private static final Pattern SENSITIVE = Pattern.compile("(?i)\\b(cnic|id card|passport|bank|account number|iban|salary|income|loan|debt|qarz|address|ghar ka pata|diagnos\\w*|disease|illness|bimari|beemari|medicine|medication|dawai|depression|anxiety|therapy|pregnan\\w*|religion|sect|political|party|sexual|lawsuit|court case)\\b");

  /** Passwords, PINs, OTPs and long digit runs are never stored. */
  static boolean secret(String text) { return text != null && (SECRET.matcher(text).matches() || text.replaceAll("[\\s-]", "").matches(".*\\d{12,}.*")); }
  /** Health, money, ID, address, religion and similar: stored only after the user clearly agrees. */
  static boolean sensitive(String text) { return text != null && SENSITIVE.matcher(text).find(); }

  /**
   * How relevant a stored memory is to the current message. Topic overlap counts most; profile facts
   * and recently used or updated memories get a small boost so the basics are not crowded out.
   */
  static double score(Set<String> query, String text, String category, long updated, int uses, long now) {
    double s = 0;
    if (!query.isEmpty()) { Set<String> t = tokens(text); for (String q : query) if (t.contains(q)) s += 3; }
    if (PROFILE.equals(category)) s += 1.5; else if (PREFERENCE.equals(category)) s += 0.8;
    double days = Math.max(0, (now - updated) / 86_400_000.0);
    s += 1.0 / (1 + days / 7);
    s += Math.min(1.0, uses / 10.0);
    return s;
  }
}
