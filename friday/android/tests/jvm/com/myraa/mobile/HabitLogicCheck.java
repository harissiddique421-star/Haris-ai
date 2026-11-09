package com.myraa.mobile;

import java.util.*;
import org.json.*;

/** Checks routine suggestions on a plain JVM. */
public final class HabitLogicCheck {
  private static int failures;
  private static void check(boolean ok, String what) { if (!ok) { failures++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }

  public static void main(String[] args) {
    JSONObject c = new JSONObject();
    for (int d = 1; d <= 3; d++) HabitLogic.record(c, "play_youtube", 21, "2026-11-0" + d);
    HabitLogic.record(c, "play_youtube", 21, "2026-11-03");  // same day again: not counted twice
    check(c.getJSONObject("play_youtube@21").getInt("n") == 3, "three days, one count per day");
    List<HabitLogic.Suggestion> s = HabitLogic.suggest(c, 21, "2026-11-04");
    check(s.size() == 1 && s.get(0).action.equals("play_youtube") && s.get(0).times == 3, "suggested at 21:00 on a new day");
    check(HabitLogic.suggest(c, 22, "2026-11-04").size() == 1, "an hour later still counts");
    check(HabitLogic.suggest(c, 15, "2026-11-04").isEmpty(), "not suggested in the afternoon");
    check(HabitLogic.suggest(c, 21, "2026-11-03").isEmpty(), "not suggested again on a day it was already done");
    HabitLogic.record(c, "battery", 21, "2026-11-04"); HabitLogic.record(c, "whatsapp_message", 21, "2026-11-04");
    check(!c.has("battery@21") && !c.has("whatsapp_message@21"), "diagnostics and messages are never habits");
    JSONObject two = new JSONObject(); HabitLogic.record(two, "briefing", 8, "a"); HabitLogic.record(two, "briefing", 8, "b");
    check(HabitLogic.suggest(two, 8, "c").isEmpty(), "two times is not yet a habit");
    HabitLogic.record(c, "play_music", 23, "2026-11-01"); HabitLogic.record(c, "play_music", 23, "2026-11-02");
    HabitLogic.record(c, "play_music", 0, "2026-11-03"); HabitLogic.record(c, "play_music", 0, "2026-11-04");
    s = HabitLogic.suggest(c, 23, "2026-11-05");
    check(s.size() == 1 && s.get(0).action.equals("play_music") && s.get(0).times == 4, "midnight wraps to 23:00: " + s.size());
    JSONObject big = new JSONObject();
    for (int i = 0; i < HabitLogic.MAX_KEYS + 1; i++) HabitLogic.record(big, i % 2 == 0 ? "open_app" : "torch", i % 24, "d" + i);
    check(big.length() <= HabitLogic.MAX_KEYS + 1, "table stays bounded: " + big.length());
    HabitLogic.fade(c);
    check(c.getJSONObject("play_youtube@21").getInt("n") == 1, "fading halves counts");
    check(HabitLogic.result(new ArrayList<>()).optString("status").equals("none"), "no suggestions is honest");
    check(HabitLogic.result(HabitLogic.suggest(two, 8, "c")).optJSONArray("suggestions").length() == 0, "empty list in result");
    if (failures > 0) { System.out.println(failures + " habit check(s) failed"); System.exit(1); }
    System.out.println("Habit checks passed");
  }
}
