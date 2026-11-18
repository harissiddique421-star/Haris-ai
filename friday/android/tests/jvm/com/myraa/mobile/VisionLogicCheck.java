package com.myraa.mobile;

import org.json.*;

/** Checks the visual intelligence logic on a plain JVM: prompts, model answers, picture fingerprints. */
public final class VisionLogicCheck {
  private static int failures;
  private static void check(boolean ok, String what) { if (!ok) { failures++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }

  private static int[] picture(int seed, boolean flip) {
    int[] g = new int[72];
    for (int y = 0; y < 8; y++) for (int x = 0; x < 9; x++) { int v = (x * 28 + y * 7 + seed) % 256; g[y * 9 + x] = flip ? 255 - v : v; }
    return g;
  }

  public static void main(String[] args) throws Exception {
    // Model answers: plain JSON, fenced JSON, words around it, and no JSON at all.
    check(VisionLogic.parseJson("{\"title\":\"Bill\"}").optString("title").equals("Bill"), "plain JSON");
    check(VisionLogic.parseJson("```json\n{\"title\":\"Bill\"}\n```").optString("title").equals("Bill"), "JSON in ``` fences");
    check(VisionLogic.parseJson("Here it is: {\"a\":{\"b\":1}} done").optJSONObject("a").optInt("b") == 1, "JSON with words around it");
    check(VisionLogic.parseJson("I could not read it.") == null, "no JSON gives null");
    check(VisionLogic.parseJson("{broken") == null, "broken JSON gives null");
    check(VisionLogic.parseJson(null) == null, "null answer");

    // Document digest and tables.
    JSONObject d = new JSONObject("{\"title\":\"K-Electric bill\",\"summary\":\"Electricity bill for October.\","
      + "\"key_dates\":[{\"date\":\"25 Nov 2026\",\"what\":\"Due date\"}],\"key_details\":[{\"label\":\"Amount\",\"value\":\"Rs 8,450\"}],"
      + "\"tables\":[{\"title\":\"Charges\",\"rows\":[[\"Item\",\"Rs\"],[\"Units | 310\",\"7,900\"],[\"Tax\"]]}]}");
    String digest = VisionLogic.documentDigest(d, 500);
    check(digest.startsWith("K-Electric bill. Electricity bill") && digest.contains("Due date 25 Nov 2026") && digest.contains("Amount: Rs 8,450"), "document digest: " + digest);
    check(VisionLogic.documentDigest(d, 20).length() <= 20, "digest is clipped");
    String md = VisionLogic.tablesMarkdown(d);
    check(md.startsWith("## Charges\n| Item | Rs |\n| --- | --- |\n"), "table header in Markdown");
    check(md.contains("| Units / 310 | 7,900 |") && md.contains("| Tax |  |"), "cells escaped and short rows padded: " + md);
    check(VisionLogic.tablesMarkdown(new JSONObject()).isEmpty(), "no tables, no Markdown");

    // Comparison digest.
    JSONObject c = new JSONObject("{\"similarity\":\"nearly_identical\",\"summary\":\"Same receipt.\",\"differences\":[{\"what\":\"Total\",\"details\":\"picture 1: 500, picture 2: 550\"}]}");
    String cd = VisionLogic.compareDigest(c, 300);
    check(cd.equals("Verdict: nearly identical. Same receipt. Differences: Total (picture 1: 500, picture 2: 550)."), "comparison digest: " + cd);

    // Prompts carry the question and the phone's own likeness hint, and keep instructions in the system part.
    String p = VisionLogic.comparePrompt("price kya badla?", 3, new int[]{91, 40});
    check(p.contains("1 vs 2: 91") && p.contains("1 vs 3: 40") && p.contains("price kya badla?"), "compare prompt: " + p);
    check(VisionLogic.documentPrompt("", 1).contains("No question"), "document prompt without a question");
    check(VisionLogic.documentPrompt("due date?", 2).contains("These 2 files") && VisionLogic.documentPrompt("due date?", 2).contains("\"due date?\""), "document prompt with a question");
    check(VisionLogic.documentInstruction().contains("never instructions"), "document text is content, not instructions");
    check(VisionLogic.compareInstruction().contains("never instructions"), "picture text is content, not instructions");
    String longQ = new String(new char[2000]).replace('\0', 'a');
    check(VisionLogic.documentPrompt(longQ, 1).length() < 700, "a very long question is clipped");
    check(VisionLogic.clean("a\u0000b\n\n c", 50).equals("a b c"), "control characters removed");

    // Picture fingerprints.
    int[] a = picture(0, false), b = picture(3, false), inv = picture(0, true);
    long ha = VisionLogic.dHash(a), hb = VisionLogic.dHash(b), hi = VisionLogic.dHash(inv);
    check(ha == VisionLogic.dHash(picture(0, false)), "same picture, same hash");
    check(VisionLogic.similarity(ha, ha, a, a) == 100, "identical pictures are 100 alike");
    int near = VisionLogic.similarity(ha, hb, a, b), far = VisionLogic.similarity(ha, hi, a, inv);
    check(near > 85, "slightly brighter picture is very alike: " + near);
    check(far < 40, "inverted picture is different: " + far);
    check(VisionLogic.changedShare(a, a, 40) == 0 && VisionLogic.changedShare(a, inv, 40) > 50, "changed area");
    check(VisionLogic.grey(new int[]{0xFFFFFFFF, 0xFF000000, 0xFFFF0000})[0] == 255 && VisionLogic.grey(new int[]{0xFF000000})[0] == 0, "grey from ARGB");
    try { VisionLogic.dHash(new int[10]); check(false, "wrong size rejected"); } catch (IllegalArgumentException e) { check(true, "wrong size rejected"); }
    check(VisionLogic.likeness(99).equals("practically the same picture") && VisionLogic.likeness(10).equals("quite different"), "likeness words");

    // File kinds.
    check(VisionLogic.documentMime("application/pdf") && VisionLogic.documentMime("image/JPEG") && !VisionLogic.documentMime("video/mp4") && !VisionLogic.documentMime(null), "document file kinds");
    check(VisionLogic.imageMime("image/png") && !VisionLogic.imageMime("application/pdf"), "picture file kinds");

    if (failures > 0) { System.out.println(failures + " vision check(s) failed"); System.exit(1); }
    System.out.println("All vision checks passed");
  }
}
