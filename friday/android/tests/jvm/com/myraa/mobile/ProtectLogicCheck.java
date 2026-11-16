package com.myraa.mobile;

/** Checks the Find my phone SMS and the spam call rules (ProtectLogic) on a plain JVM. */
public final class ProtectLogicCheck {
  private static int failures;
  private static void check(boolean ok, String what) { if (!ok) { failures++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }

  public static void main(String[] args) {
    check("1234".equals(ProtectLogic.findCode("FRIDAY dhoondo 1234")), "basic find SMS");
    check("ab12".equals(ProtectLogic.findCode("  friday Dhundo ab12. ")), "other spelling, case and trailing dot");
    check("9876".equals(ProtectLogic.findCode("Friday, find 9876")), "English find with comma");
    check(ProtectLogic.findCode("FRIDAY dhoondo") == null, "no code is not a request");
    check(ProtectLogic.findCode("Bhai FRIDAY dhoondo 1234 please") == null, "extra words are not a request");
    check(ProtectLogic.findCode("Your OTP is 1234") == null, "ordinary SMS ignored");
    check(ProtectLogic.findCode(null) == null, "null SMS ignored");
    check(ProtectLogic.validCode("1234") && ProtectLogic.validCode("Ali2026"), "valid codes");
    check(!ProtectLogic.validCode("123") && !ProtectLogic.validCode("12 34") && !ProtectLogic.validCode("1234567890123"), "too short, space, too long are invalid");
    String h = ProtectLogic.hash("salt", "AbC1");
    check(ProtectLogic.sameHash(h, ProtectLogic.hash("salt", "abc1")), "code is compared without case");
    check(!ProtectLogic.sameHash(h, ProtectLogic.hash("salt", "abc2")), "wrong code does not match");
    check(!ProtectLogic.sameHash(h, ProtectLogic.hash("other", "abc1")), "salt matters");
    check(ProtectLogic.HIGH.equals(ProtectLogic.callRisk("", "92").level), "private number is high risk");
    check(ProtectLogic.HIGH.equals(ProtectLogic.callRisk("+44 7700 900123", "92").level), "foreign number is high risk");
    check(ProtectLogic.HIGH.equals(ProtectLogic.callRisk("0044 7700 900123", "92").level), "00 foreign number is high risk");
    check(ProtectLogic.MEDIUM.equals(ProtectLogic.callRisk("+92 300 1234567", "92").level), "Pakistani +92 number is a normal warning");
    check(ProtectLogic.MEDIUM.equals(ProtectLogic.callRisk("03001234567", "92").level), "Pakistani 03 number is a normal warning");
    check(ProtectLogic.NONE.equals(ProtectLogic.callRisk("1122", "92").level), "short service number not warned");
    check(ProtectLogic.MEDIUM.equals(ProtectLogic.callRisk("+44 7700 900123", "").level), "unknown home country: no foreign rule");
    check(ProtectLogic.key("+92 300-1234567").equals(ProtectLogic.key("03001234567")) && ProtectLogic.key("00923001234567").equals("3001234567"), "number forms share one key");
    check(ProtectLogic.advice(ProtectLogic.callRisk("03001234567", "92")).contains("OTP"), "advice mentions OTP");
    if (failures > 0) { System.out.println(failures + " protection check(s) failed"); System.exit(1); }
    System.out.println("Phone protection checks passed");
  }
}
