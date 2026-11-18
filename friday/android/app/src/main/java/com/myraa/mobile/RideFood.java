package com.myraa.mobile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Rides and food by voice ("mujhe Saddar jaana hai", "khana order karo"): which Pakistani app to open
 * (Careem, inDrive, Foodpanda) and what to do when it is not installed. Plain Java so it can be checked
 * on a JVM (RideFoodCheck). None of these apps publish a deep link that fills in the destination or a
 * food search, so FRIDAY opens the app, copies the destination or dish so the user can paste it, and,
 * when phone control is on, may type it in the app's own search box. It never books or orders by itself.
 */
final class RideFood {
  private RideFood() {}
  static final String RIDE = "ride", FOOD = "food";

  static final class App {
    final String key, label, pkg, website, kind; final String[] names;
    App(String key, String label, String pkg, String website, String kind, String... names) {
      this.key = key; this.label = label; this.pkg = pkg; this.website = website; this.kind = kind; this.names = names;
    }
    String playStore() { return "market://details?id=" + pkg; }
    String playStoreWeb() { return "https://play.google.com/store/apps/details?id=" + pkg; }
  }

  static final App CAREEM = new App("careem", "Careem", "com.careem.acma", "https://www.careem.com/", RIDE, "careem", "kareem", "karim", "careem go", "careem food");
  static final App INDRIVE = new App("indrive", "inDrive", "sinet.startup.inDriver", "https://indrive.com/", RIDE, "indrive", "in drive", "indriver", "in driver", "indraiv");
  static final App FOODPANDA = new App("foodpanda", "Foodpanda", "com.global.foodpanda.android", "https://www.foodpanda.pk/", FOOD, "foodpanda", "food panda", "panda");
  static final List<App> ALL = Collections.unmodifiableList(Arrays.asList(CAREEM, INDRIVE, FOODPANDA));

  /** The order FRIDAY tries apps in when the user names none. Careem also delivers food (Careem Food). */
  static List<App> preferred(String kind) {
    return FOOD.equals(kind) ? Arrays.asList(FOODPANDA, CAREEM) : Arrays.asList(CAREEM, INDRIVE);
  }

  /** The app the user named, or null when they named none or one FRIDAY does not know. */
  static App named(String spoken) {
    String s = spoken == null ? "" : spoken.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
    if (s.isEmpty() || s.equals("any") || s.equals("koi bhi")) return null;
    for (App a : ALL) for (String n : a.names) if (s.equals(n) || s.contains(n)) return a;
    return null;
  }

  interface Installed { boolean has(String pkg); }

  static final class Choice {
    final App app; final boolean installed; final boolean named; final List<App> others;
    Choice(App app, boolean installed, boolean named, List<App> others) { this.app = app; this.installed = installed; this.named = named; this.others = others; }
  }

  /**
   * Picks the app: the one the user named (even when missing, so FRIDAY can offer to install it), otherwise
   * the first installed app in the preferred order, otherwise the first preferred app (to install).
   * {@code others} lists the other installed apps of the same kind, for "or use X instead".
   */
  static Choice choose(String kind, String spokenApp, Installed installed) {
    App named = named(spokenApp);
    List<App> order = preferred(kind);
    App pick = named;
    if (pick == null) for (App a : order) if (installed.has(a.pkg)) { pick = a; break; }
    if (pick == null) pick = order.get(0);
    List<App> others = new ArrayList<>();
    for (App a : order) if (a != pick && installed.has(a.pkg)) others.add(a);
    return new Choice(pick, installed.has(pick.pkg), named != null, others);
  }

  /** The destination or dish as spoken, trimmed; empty when there is none. Throws when it is far too long. */
  static String clean(String text) {
    String t = text == null ? "" : text.trim().replaceAll("\\s+", " ");
    if (t.length() > 200) throw new IllegalArgumentException("That is too long. Say the place or dish in a few words.");
    return t;
  }

  /** What FRIDAY tells the model after opening the app (English; the model answers in the user's language). */
  static String openedMessage(Choice c, String kind, String what, boolean copied, boolean control) {
    StringBuilder b = new StringBuilder("Opened ").append(c.app.label).append('.');
    boolean ride = RIDE.equals(kind);
    if (!what.isEmpty() && copied) b.append(" \"").append(what).append("\" is copied, so the user can paste it in ")
      .append(ride ? "the Where to? box" : "the search box").append('.');
    if (!what.isEmpty() && control) b.append(" Phone control is on: you may read_screen, tap the ")
      .append(ride ? "Where to / destination" : "search").append(" box, type_text \"").append(what)
      .append("\" and pick the matching result, then stop.");
    b.append(ride ? " Never press Book, Find a driver, Confirm or offer a fare yourself: the user picks the ride and the price and books it."
                  : " Never add to cart, check out or place the order yourself: the user picks the food and orders it.");
    if (!c.others.isEmpty()) {
      b.append(" Also installed: ");
      for (int i = 0; i < c.others.size(); i++) b.append(i == 0 ? "" : ", ").append(c.others.get(i).label);
      b.append(".");
    }
    return b.toString();
  }

  static String notInstalledMessage(Choice c, String where) {
    return c.app.label + " is not installed, so " + where + " opened for it. The user can install it, or say another app"
      + (c.others.isEmpty() ? "." : " (installed: " + c.others.get(0).label + ").");
  }
}
