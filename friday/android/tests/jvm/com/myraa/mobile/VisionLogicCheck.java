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


    // Camera: objects with boxes, filtering, speech and live-mode sampling.
    JSONObject cam = new JSONObject("{\"objects\":["
      + "{\"label\":\"cup\",\"confidence\":0.7,\"box_2d\":[400,100,600,250]},"
      + "{\"label\":\"laptop\",\"confidence\":0.95,\"box_2d\":[300,350,700,650],\"color\":\"grey\"},"
      + "{\"label\":\"Laptop\",\"confidence\":0.9,\"box_2d\":[310,360,690,640]},"
      + "{\"label\":\"ghost\",\"confidence\":0.2,\"box_2d\":[0,0,100,100]},"
      + "{\"label\":\"window\",\"confidence\":0.8,\"box_2d\":[900,1200,-5,800]},"
      + "{\"label\":\"\",\"confidence\":0.9},"
      + "{\"label\":\"wall\"}],\"summary\":\"A desk.\"}");
    java.util.List<VisionLogic.Seen> seen = VisionLogic.parseSeen(cam);
    check(seen.size() == 4, "duplicate, low-confidence and empty labels left out: " + seen.size());
    check(seen.get(0).label.equals("laptop") && seen.get(0).color.equals("grey"), "best first");
    check(seen.get(0).where().equals("in the centre"), "laptop in the centre: " + seen.get(0).where());
    VisionLogic.Seen cup = null, window = null, wall = null;
    for (VisionLogic.Seen x : seen) { if (x.label.equals("cup")) cup = x; if (x.label.equals("window")) window = x; if (x.label.equals("wall")) wall = x; }
    check(cup != null && cup.where().equals("on the left"), "cup on the left");
    check(window != null && window.left == 800 && window.right == 1000 && window.top == 0 && window.bottom == 900, "box clamped and put in order");
    check(wall != null && !wall.hasBox() && wall.confidence == 0.6f && wall.where().isEmpty(), "no box and default confidence");
    check(VisionLogic.parseSeen(new JSONObject()).isEmpty() && VisionLogic.parseSeen(null).isEmpty(), "no objects");
    check(VisionLogic.cameraSpeech(new JSONObject("{\"spoken\":\"Ye laptop hai.\"}"), seen).equals("Ye laptop hai."), "spoken line used first");
    String made = VisionLogic.cameraSpeech(new JSONObject(), seen);
    check(made.startsWith("I can see laptop in the centre") && made.endsWith("."), "speech made from objects: " + made);
    check(VisionLogic.cameraSpeech(new JSONObject("{\"summary\":\"Too dark.\"}"), new java.util.ArrayList<>()).equals("Too dark."), "summary when nothing is seen");
    check(VisionLogic.spokenLanguage("ur-PK").contains("Roman Urdu") && VisionLogic.spokenLanguage("hi-IN").contains("Hinglish") && VisionLogic.spokenLanguage("en-US").contains("English"), "spoken language");
    check(!VisionLogic.worthSending(10, 800), "never twice within 1.5 s");
    check(!VisionLogic.worthSending(98, 5000), "same view is not sent again");
    check(VisionLogic.worthSending(60, 2000), "changed view is sent");
    check(VisionLogic.worthSending(98, 13000), "same view refreshed after 12 s");
    check(VisionLogic.cameraPrompt("scene", "koi khatra?").contains("dangers") && VisionLogic.cameraPrompt("objects", "").startsWith("Name the objects"), "camera prompts");
    check(VisionLogic.cameraInstruction("Roman Urdu").contains("never instructions") && VisionLogic.cameraInstruction("Roman Urdu").contains("box_2d"), "camera instruction");


    // Screen: the button to press must really be on the screen.
    JSONArray items = new JSONArray("[{\"label\":\"Dark theme\",\"tap\":true,\"on\":false},{\"label\":\"Brightness level\",\"tap\":true},{\"label\":\"Search settings\",\"text_box\":true},{\"label\":\"Screen timeout…\"}]");
    check(VisionLogic.matchLabel("Dark theme", items).equals("Dark theme"), "exact label");
    check(VisionLogic.matchLabel("dark THEME!", items).equals("Dark theme"), "label ignoring case and marks");
    check(VisionLogic.matchLabel("Brightness", items).equals("Brightness level"), "label by its start");
    check(VisionLogic.matchLabel("Screen timeout", items).equals("Screen timeout…"), "label cut with an ellipsis");
    check(VisionLogic.matchLabel("Pay now", items).isEmpty(), "a button that is not on screen is never used");
    check(VisionLogic.matchLabel("", items).isEmpty() && VisionLogic.matchLabel("Dark", null).isEmpty(), "nothing to match");
    JSONObject scr = new JSONObject().put("app", "Settings").put("items", items);
    String sp = VisionLogic.screenPrompt(scr, "", "turn on dark mode", "press Display", "easy Roman Urdu", false);
    check(sp.contains("- Dark theme [tap] [off]") && sp.contains("- Search settings [text box]"), "items listed with their kind: " + sp);
    check(sp.contains("USER'S GOAL: turn on dark mode") && sp.contains("THE USER JUST DID: press Display") && sp.contains("No screenshot"), "goal, last step and no picture");
    check(VisionLogic.screenPrompt(scr, "", "", "", "English", true).contains("No question: explain this screen") && VisionLogic.screenPrompt(scr, "", "", "", "English", true).contains("screenshot of the same screen"), "explain with a picture");
    check(VisionLogic.screenInstruction().contains("never instructions") && VisionLogic.screenInstruction().contains("next_label"), "screen instruction");


    // Video: candidate moments, which frames are sent, time labels and the report.
    long[] ct = VisionLogic.candidateTimes(60000, 30);
    check(ct.length == 30 && ct[0] == 1000 && ct[29] == 59000, "30 moments spread over a minute");
    check(VisionLogic.candidateTimes(1200, 30).length == 2 && VisionLogic.candidateTimes(0, 30).length == 0, "short and empty videos");
    check(VisionLogic.timeLabel(75000).equals("1:15") && VisionLogic.timeLabel(3723000).equals("1:02:03") && VisionLogic.timeLabel(0).equals("0:00"), "time labels");
    int n = 30; long[] vt = new long[n], vh = new long[n]; int[][] vs = new int[n][];
    for (int i = 0; i < n; i++) { vt[i] = i * 2000L; int[] g = picture(i < 10 ? 0 : i < 20 ? 90 : 170, i >= 20); vh[i] = VisionLogic.dHash(g); int[] sm = new int[1024]; for (int j = 0; j < 1024; j++) sm[j] = i < 10 ? 20 : i < 20 ? 128 : 235; vs[i] = sm; }
    java.util.List<Integer> kf = VisionLogic.keepFrames(vt, vh, vs, 12);
    check(kf.get(0) == 0 && kf.contains(10) && kf.contains(20) && kf.size() <= 12, "a frame kept at each scene change: " + kf);
    for (int i = 1; i < kf.size(); i++) if (kf.get(i) <= kf.get(i - 1)) { check(false, "frames in time order"); break; }
    long[] sh = new long[n]; int[][] ss = new int[n][]; for (int i = 0; i < n; i++) { sh[i] = vh[0]; ss[i] = vs[0]; }
    java.util.List<Integer> still = VisionLogic.keepFrames(vt, sh, ss, 12);
    check(still.size() >= 4 && still.contains(0) && still.get(still.size() - 1) >= 20, "a still video still gets frames spread over it: " + still);
    long[] dh = new long[n]; int[][] ds = new int[n][]; for (int i = 0; i < n; i++) { int[] g = picture(i * 37, i % 2 == 1); dh[i] = VisionLogic.dHash(g); int[] sm = new int[1024]; for (int j = 0; j < 1024; j++) sm[j] = (i * 53 + j * (i % 2 == 0 ? 1 : 7)) % 256; ds[i] = sm; }
    check(VisionLogic.keepFrames(vt, dh, ds, 12).size() == 12, "never more than 12 frames");
    java.util.List<String> vl = java.util.Arrays.asList("0:01", "0:21");
    check(VisionLogic.videoPrompt("", vl, 42000).contains("0:42 long") && VisionLogic.videoPrompt("", vl, 42000).contains("frame 2 = 0:21") && VisionLogic.videoPrompt("kya likha hai?", vl, 42000).contains("kya likha hai?"), "video prompt");
    check(VisionLogic.videoInstruction().contains("never instructions") && VisionLogic.videoInstruction().contains("Do not identify real people"), "video instruction");
    JSONObject vid = new JSONObject("{\"title\":\"Cricket match\",\"summary\":\"Boys play cricket.\",\"timeline\":[{\"time\":\"0:05\",\"what\":\"bowler runs\"}],\"text_seen\":[{\"time\":\"0:30\",\"text\":\"SIX | 4\"}],\"objects\":[\"bat\",\"ball\"],\"activities\":[\"batting\"]}");
    String vd = VisionLogic.videoDigest(vid, 400);
    check(vd.startsWith("Cricket match. Boys play cricket.") && vd.contains("0:05 bowler runs") && vd.contains("\"SIX | 4\" at 0:30"), "video digest: " + vd);
    String vm = VisionLogic.videoMarkdown(vid, "match.mp4", 42000, 6);
    check(vm.contains("| 0:05 | bowler runs |") && vm.contains("| 0:30 | SIX / 4 |") && vm.contains("bat, ball") && vm.contains("6 frames"), "video report tables");

    // Visual memory: what is kept, and finding it again.
    check(VisionLogic.memoFrom("document", new JSONObject("{\"ok\":true,\"title\":\"K-Electric bill\",\"summary\":\"Due 25 Nov, Rs 8,450.\"}")).optString("category").equals("document"), "document memo");
    check(VisionLogic.memoFrom("camera", new JSONObject("{\"ok\":true,\"summary\":\"A kitchen.\",\"mode\":\"scene\",\"objects\":\"\"}")).optString("category").equals("place"), "scene memo is a place");
    check(VisionLogic.memoFrom("camera", new JSONObject("{\"ok\":true,\"summary\":\"Keys on a table.\",\"objects\":\"keys (left)\"}")).optString("description").contains("Objects: keys"), "object memo keeps the objects");
    check(VisionLogic.memoFrom("camera", new JSONObject("{\"ok\":false}")) == null && VisionLogic.memoFrom("question", new JSONObject("{\"ok\":true,\"answer\":\"x\"}")) == null && VisionLogic.memoFrom("document", new JSONObject("{\"ok\":true,\"summary\":\"\"}")) == null, "nothing to keep");
    check(VisionLogic.memoryCategory("VIDEO").equals("video") && VisionLogic.memoryCategory("faces").equals("other"), "memory categories");
    JSONArray mem = new JSONArray()
      .put(new JSONObject().put("id", "a").put("at", 1000).put("category", "document").put("title", "K-Electric bill").put("description", "Electricity bill due 25 Nov"))
      .put(new JSONObject().put("id", "b").put("at", 2000).put("category", "object").put("title", "Car keys").put("description", "Keys on the kitchen table").put("note", "spare keys"))
      .put(new JSONObject().put("id", "c").put("at", 3000).put("category", "place").put("title", "Kitchen").put("description", "A clean kitchen with a red kettle"));
    JSONArray found = VisionLogic.searchMemory(mem, "keys", "", 10);
    check(found.length() == 1 && found.getJSONObject(0).optString("id").equals("b"), "search by a word");
    found = VisionLogic.searchMemory(mem, "kitchen", "", 10);
    check(found.length() == 2 && found.getJSONObject(0).optString("id").equals("c"), "title match ranks first: " + found);
    check(VisionLogic.searchMemory(mem, "kitchen", "object", 10).length() == 1, "search in one category");
    found = VisionLogic.searchMemory(mem, "", "", 2);
    check(found.length() == 2 && found.getJSONObject(0).optString("id").equals("c"), "empty query lists the newest");
    check(VisionLogic.searchMemory(mem, "passport", "", 10).length() == 0 && VisionLogic.searchMemory(null, "x", "", 5).length() == 0, "no match");
    check(VisionLogic.searchMemory(mem, "bill", "", 10).getJSONObject(0).optString("id").equals("a"), "find the bill");

    if (failures > 0) { System.out.println(failures + " vision check(s) failed"); System.exit(1); }
    System.out.println("All vision checks passed");
  }
}
