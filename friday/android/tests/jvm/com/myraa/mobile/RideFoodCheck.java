package com.myraa.mobile;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Checks which ride or food app FRIDAY opens (RideFood) on a plain JVM. */
public final class RideFoodCheck {
  private static int failures;
  private static void check(boolean ok, String what) { if (!ok) { failures++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }
  private static RideFood.Installed only(String... pkgs) { Set<String> s = new HashSet<>(Arrays.asList(pkgs)); return s::contains; }

  public static void main(String[] args) {
    String careem = RideFood.CAREEM.pkg, indrive = RideFood.INDRIVE.pkg, panda = RideFood.FOODPANDA.pkg;
    RideFood.Choice c = RideFood.choose(RideFood.RIDE, "", only(careem, indrive));
    check(c.app == RideFood.CAREEM && c.installed && !c.named && c.others.contains(RideFood.INDRIVE), "no app named: Careem first, inDrive offered");
    c = RideFood.choose(RideFood.RIDE, "", only(indrive));
    check(c.app == RideFood.INDRIVE && c.installed, "only inDrive installed: inDrive");
    c = RideFood.choose(RideFood.RIDE, "in drive", only(careem, indrive));
    check(c.app == RideFood.INDRIVE && c.named && c.others.contains(RideFood.CAREEM), "named in drive is honoured");
    c = RideFood.choose(RideFood.RIDE, "inDriver", only(careem));
    check(c.app == RideFood.INDRIVE && !c.installed && c.others.contains(RideFood.CAREEM), "named but missing: install it, Careem offered");
    c = RideFood.choose(RideFood.RIDE, "", only());
    check(c.app == RideFood.CAREEM && !c.installed && c.others.isEmpty(), "nothing installed: Careem from the Play Store");
    c = RideFood.choose(RideFood.FOOD, "", only(careem, panda));
    check(c.app == RideFood.FOODPANDA && c.installed, "food: Foodpanda first");
    c = RideFood.choose(RideFood.FOOD, "", only(careem));
    check(c.app == RideFood.CAREEM && c.installed, "food without Foodpanda: Careem Food");
    c = RideFood.choose(RideFood.FOOD, "Food Panda", only(careem));
    check(c.app == RideFood.FOODPANDA && !c.installed, "named Food Panda but missing: install it");
    check(RideFood.named("uber") == null && RideFood.named("koi bhi") == null, "unknown or any app means none");
    check(RideFood.CAREEM.playStore().equals("market://details?id=com.careem.acma"), "Play Store link");
    check(RideFood.clean("  Saddar   Karachi ").equals("Saddar Karachi"), "destination is tidied");
    boolean threw = false; try { RideFood.clean(new String(new char[300]).replace('\0', 'a')); } catch (IllegalArgumentException e) { threw = true; }
    check(threw, "very long text is refused");
    String m = RideFood.openedMessage(RideFood.choose(RideFood.RIDE, "", only(careem)), RideFood.RIDE, "Saddar", true, true);
    check(m.contains("Opened Careem") && m.contains("\"Saddar\" is copied") && m.contains("type_text") && m.contains("Never press Book"), "ride message: copied, may type, never books");
    m = RideFood.openedMessage(RideFood.choose(RideFood.FOOD, "", only(panda)), RideFood.FOOD, "", false, false);
    check(m.contains("Opened Foodpanda") && !m.contains("copied") && !m.contains("type_text") && m.contains("place the order yourself"), "food message without a dish: never orders");
    if (failures > 0) { System.out.println(failures + " ride/food check(s) failed"); System.exit(1); }
    System.out.println("Ride and food checks passed");
  }
}
