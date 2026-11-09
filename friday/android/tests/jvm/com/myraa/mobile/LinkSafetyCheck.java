package com.myraa.mobile;

/** Checks the link warning signs (Stage 9) on a plain JVM. */
public final class LinkSafetyCheck {
  private static int failures;
  private static void check(boolean ok, String what) { if (!ok) { failures++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }
  private static String level(String text) { LinkSafety.Finding f = LinkSafety.check(text); System.out.println("     " + f.level + " " + f.reasons); return f.level; }

  public static void main(String[] args) {
    check(LinkSafety.NONE.equals(level("Watch this https://www.youtube.com/watch?v=abc")), "real YouTube link has no signs");
    check(LinkSafety.NONE.equals(level("Meeting notes: docs.google.com/document/d/1")), "real Google link has no signs");
    check(LinkSafety.NONE.equals(level("Kal milte hain, 5 baje")), "no link, no signs");
    check(LinkSafety.DANGER.equals(level("Your account will be blocked, verify at http://whatsapp-verify-login.xyz/now")), "fake WhatsApp site is danger");
    check(LinkSafety.DANGER.equals(level("Congratulations you won! paypa1.com/claim")), "paypa1 lookalike is danger");
    check(LinkSafety.DANGER.equals(level("BISP payment: bisp-gov-pk.online/apply")), "fake BISP site is danger");
    check(LinkSafety.DANGER.equals(level("Open http://192.168.4.20/login")), "bare IP address is danger");
    check(LinkSafety.DANGER.equals(level("Go to https://jazzcash.com.pk@evil.example.com/pay")), "@ trick is danger");
    check(LinkSafety.DANGER.equals(level("Visit https://xn--pple-43d.com")), "punycode is danger");
    check(LinkSafety.CAUTION.equals(level("See bit.ly/3abcd")), "shortener is caution");
    check(LinkSafety.CAUTION.equals(level("Please share your OTP with me")), "asking for an OTP is caution");
    check(LinkSafety.NONE.equals(level("JazzCash receipt: https://www.jazzcash.com.pk/receipt")), "real JazzCash is fine");
    check(LinkSafety.NONE.equals(level("Read https://www.publicnews.com/story")), "short bank names inside other words are not flagged");
    check(LinkSafety.DANGER.equals(level("hbl-secure.info/login")), "fake HBL site is danger");
    check(LinkSafety.links("a https://a.com/x). and www.b.org").size() == 2, "two links found, punctuation trimmed");
    check(LinkSafety.host("https://user@Evil.com:8080/x").equals("evil.com"), "host after @ and without port");
    check(LinkSafety.advice(LinkSafety.check("youtube.com")).contains("does not prove"), "no signs is never called safe");
    if (failures > 0) { System.out.println(failures + " link safety check(s) failed"); System.exit(1); }
    System.out.println("Link safety checks passed");
  }
}
