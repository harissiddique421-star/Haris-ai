package com.myraa.mobile;

import org.json.*;
import java.util.*;

/** Checks how FRIDAY reads GitHub releases and which downloaded APKs it accepts, on a plain JVM. */
public final class UpdatePlanCheck {
  private static int failures;
  private static void check(boolean ok, String what) { if (!ok) { failures++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }
  private static final String CERT = "1c5e21215aac68144dddb31d03ad95e8aa53c9bdacdcd5a4e6a30eb49049c66b";
  private static final String OTHER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static String sha(char c) { StringBuilder b = new StringBuilder(); for (int i = 0; i < 64; i++) b.append(c); return b.toString(); }

  private static JSONObject release(String tag, long code, String name, String cert, String sha, String assetUrl, boolean draft) throws JSONException {
    String apk = "FRIDAY-Mobile-" + name + "-private.apk";
    String body = "## What changed\n- Better replies\n\n" + UpdatePlan.MARKER + new JSONObject().put("versionCode", code).put("versionName", name).put("sha256", sha).put("cert", cert).put("apk", apk) + " -->";
    return new JSONObject().put("tag_name", tag).put("draft", draft).put("body", body).put("published_at", "2026-11-09T10:00:00Z")
      .put("assets", new JSONArray().put(new JSONObject().put("name", apk).put("size", 8_700_000).put("browser_download_url", assetUrl == null ? "https://github.com/harissiddique421-star/Haris-ai/releases/download/" + tag + "/" + apk : assetUrl)));
  }

  public static void main(String[] args) throws Exception {
    JSONArray list = new JSONArray()
      .put(release("friday-v1.0.0-rc.45", 45, "1.0.0-rc.45", CERT, sha('a'), null, false))
      .put(release("friday-v1.0.0-rc.47", 47, "1.0.0-rc.47", CERT, sha('b'), null, false))
      .put(release("friday-v1.0.0-rc.48", 48, "1.0.0-rc.48", CERT, sha('c'), null, true))                                // draft
      .put(release("friday-v1.0.0-rc.49", 49, "1.0.0-rc.49", CERT, "nothex", null, false))                               // bad hash
      .put(release("friday-v1.0.0-rc.50", 50, "1.0.0-rc.50", CERT, sha('d'), "https://evil.example.com/friday.apk", false)) // not GitHub
      .put(release("other-v9", 99, "9", CERT, sha('e'), null, false))                                                     // not FRIDAY
      .put(new JSONObject().put("tag_name", "friday-v1.0.0-rc.51").put("body", "no marker").put("draft", false))           // unmarked
      .put(release("friday-v1.0.0-rc.52", 52, "1.0.0-rc.52", OTHER, sha('f'), null, false));                              // other key

    List<UpdatePlan.Release> all = UpdatePlan.parse(list);
    check(all.size() == 3, "only valid FRIDAY releases are read: " + all.size());
    check(all.get(0).versionCode == 52 && all.get(2).versionCode == 45, "newest first");
    check(all.get(1).notes.contains("Better replies") && !all.get(1).notes.contains("friday-update"), "change report without the machine line");

    UpdatePlan.Release next = UpdatePlan.newest(all, 46, CERT);
    check(next != null && next.versionCode == 47, "offers the newest release signed with the installed key, not one with another key");
    check(UpdatePlan.newest(all, 47, CERT) == null, "nothing newer: no offer");
    check(UpdatePlan.older(all, 47).size() == 1 && UpdatePlan.older(all, 47).get(0).versionCode == 45, "older releases for rollback");
    check(UpdatePlan.Release.from(next.json()).sha256.equals(next.sha256), "release survives storage");

    check(UpdatePlan.allowedHost("https://github.com/x/y/releases/download/a/b.apk"), "github.com allowed");
    check(UpdatePlan.allowedHost("https://objects.githubusercontent.com/github-production-release-asset/1"), "GitHub asset host allowed");
    check(!UpdatePlan.allowedHost("http://github.com/x.apk"), "plain http refused");
    check(!UpdatePlan.allowedHost("https://github.com.evil.example/x.apk"), "look-alike host refused");
    check(!UpdatePlan.allowedHost("https://evilgithubusercontent.com/x.apk"), "suffix trick refused");

    String pkg = "com.myraa.mobile";
    check(UpdatePlan.verify(next, sha('b'), pkg, 47, CERT, pkg, 46, CERT) == null, "matching file is accepted");
    check(UpdatePlan.verify(next, sha('0'), pkg, 47, CERT, pkg, 46, CERT).contains("SHA-256"), "wrong hash refused");
    check(UpdatePlan.verify(next, sha('b'), "com.other", 47, CERT, pkg, 46, CERT).contains("different app"), "other app refused");
    check(UpdatePlan.verify(next, sha('b'), pkg, 48, CERT, pkg, 46, CERT).contains("version"), "version mismatch refused");
    check(UpdatePlan.verify(next, sha('b'), pkg, 47, CERT, pkg, 47, CERT).contains("isn't newer"), "same version refused");
    check(UpdatePlan.verify(next, sha('b'), pkg, 47, OTHER, pkg, 46, CERT).contains("your FRIDAY key"), "other signer refused");
    check(UpdatePlan.verify(next, sha('b'), pkg, 47, "", pkg, 46, CERT).contains("signature"), "unreadable signature refused");
    check(UpdatePlan.verify(null, sha('b'), pkg, 47, CERT, pkg, 46, CERT) != null, "no release refused");

    if (failures > 0) { System.out.println(failures + " update check(s) failed"); System.exit(1); }
    System.out.println("Update checks passed.");
  }
}
