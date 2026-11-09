package com.myraa.mobile;

/** Checks the "unlock my phone and open …" commands and the waiting task's expiry and cancel, on a plain JVM. */
public final class UnlockTaskCheck {
  private static int failures;
  private static void check(boolean ok, String what) { if (!ok) { failures++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }

  public static void main(String[] args) {
    check("youtube".equals(UnlockTask.parse("friday unlock phone and open youtube")), "unlock and open YouTube");
    check("whatsapp".equals(UnlockTask.parse("friday phone unlock karo aur whatsapp kholo")), "Hinglish unlock and open WhatsApp");
    check("google maps".equals(UnlockTask.parse("hey friday unlock my phone and open google maps")), "Google Maps");
    check("chrome".equals(UnlockTask.parse("friday unlock phone open chrome")), "Chrome");
    check("".equals(UnlockTask.parse("friday unlock my phone")), "plain unlock has no app");
    check("".equals(UnlockTask.parse("friday unlock phone and open my bank app")), "an app outside the list is not opened");
    check(UnlockTask.parse("friday open youtube") == null, "no unlock word, not an unlock command");
    check(UnlockTask.parse("friday unlocked achievements on youtube") == null, "unlocked is not unlock");
    check(UnlockTask.parse(null) == null, "nothing heard");

    long t = 1_000_000;
    UnlockTask.queue("youtube", t);
    check(UnlockTask.waiting(t + 1000), "task waits for the unlock");
    check("youtube".equals(UnlockTask.take(t + 5000)), "task runs after the unlock");
    check(UnlockTask.take(t + 6000) == null, "task runs only once");

    UnlockTask.queue("whatsapp", t);
    check(UnlockTask.take(t + UnlockTask.TTL_MS + 1) == null, "expired task never runs");

    UnlockTask.queue("chrome", t);
    UnlockTask.cancel("unlock cancelled");
    check(!UnlockTask.waiting(t + 10) && UnlockTask.take(t + 10) == null, "cancelled task never runs");

    UnlockTask.queue("instagram", t);
    check(UnlockTask.take(t - 1) == null, "clock moved back: task dropped");

    if (failures > 0) { System.out.println(failures + " unlock check(s) failed"); System.exit(1); }
    System.out.println("unlock task checks passed");
  }
}
