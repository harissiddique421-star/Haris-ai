package com.myraa.mobile;

/** Checks FRIDAY's motion gestures on a plain JVM with made-up camera grids: a bright hand moving over a room. */
public final class GestureLogicCheck {
  private static int failures;
  private static void check(boolean ok, String what) { if (!ok) { failures++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }

  /** A room (grey with a fixed pattern) with a 7x7 bright hand centred at (hx, hy); no hand when hx < 0. */
  static int[] room(float hx, float hy, int light) {
    int[] g = new int[GestureLogic.N];
    for (int y = 0; y < GestureLogic.H; y++) for (int x = 0; x < GestureLogic.W; x++) g[y * GestureLogic.W + x] = Math.min(255, light + ((x * 7 + y * 13) % 20));
    if (hx >= 0) {
      int cx = Math.round(hx * (GestureLogic.W - 1)), cy = Math.round(hy * (GestureLogic.H - 1));
      for (int y = cy - 3; y <= cy + 3; y++) for (int x = cx - 3; x <= cx + 3; x++) if (x >= 0 && y >= 0 && x < GestureLogic.W && y < GestureLogic.H) g[y * GestureLogic.W + x] = 230;
    }
    return g;
  }

  /** Feeds still frames, then the hand along the given points (one frame per 66 ms), then still frames. Returns what fired. */
  static String run(GestureLogic.Detector d, long[] clock, float[][] path) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < 8; i++) { GestureLogic.Gesture g = d.feed(room(-1, 0, 110), clock[0]); clock[0] += 66; if (g != null) out.append(g.name).append(' '); }
    for (float[] p : path) { GestureLogic.Gesture g = d.feed(room(p[0], p[1], 110), clock[0]); clock[0] += 66; if (g != null) out.append(g.name).append(' '); }
    for (int i = 0; i < 8; i++) { GestureLogic.Gesture g = d.feed(room(-1, 0, 110), clock[0]); clock[0] += 66; if (g != null) out.append(g.name).append(' '); }
    return out.toString().trim();
  }

  static float[][] line(float x0, float y0, float x1, float y1, int steps) {
    float[][] p = new float[steps][];
    for (int i = 0; i < steps; i++) { float k = i / (float) (steps - 1); p[i] = new float[]{x0 + (x1 - x0) * k, y0 + (y1 - y0) * k}; }
    return p;
  }

  public static void main(String[] args) {
    long[] clock = {0};
    GestureLogic.Detector d = new GestureLogic.Detector(GestureLogic.threshold("normal"));
    check(run(d, clock, line(0.1f, 0.5f, 0.9f, 0.5f, 8)).equals("swipe_right"), "swipe right");
    clock[0] += 2000;
    check(run(d, clock, line(0.9f, 0.5f, 0.1f, 0.5f, 8)).equals("swipe_left"), "swipe left");
    clock[0] += 2000;
    check(run(d, clock, line(0.5f, 0.9f, 0.5f, 0.1f, 8)).equals("swipe_up"), "swipe up");
    clock[0] += 2000;
    check(run(d, clock, line(0.5f, 0.1f, 0.5f, 0.9f, 8)).equals("swipe_down"), "swipe down");
    clock[0] += 2000;
    float[][] wave = new float[12][];
    for (int i = 0; i < 12; i++) wave[i] = new float[]{i % 4 < 2 ? 0.25f + 0.25f * (i % 2) : 0.75f - 0.25f * (i % 2), 0.4f};
    for (int i = 0; i < 12; i++) { int ph = i % 6; wave[i] = new float[]{ph < 3 ? 0.2f + 0.25f * ph : 0.7f - 0.25f * (ph - 3), 0.4f}; }
    check(run(d, clock, wave).equals("wave"), "wave back and forth");
    clock[0] += 2000;

    // A small slow drift is not a swipe.
    check(run(d, clock, line(0.4f, 0.5f, 0.55f, 0.5f, 8)).isEmpty(), "small movement ignored");
    clock[0] += 2000;

    // Cooldown: a second swipe right after the first is ignored.
    String two = run(d, clock, line(0.1f, 0.5f, 0.9f, 0.5f, 8));
    long before = clock[0];
    float[][] again = line(0.9f, 0.5f, 0.1f, 0.5f, 8);
    StringBuilder quick = new StringBuilder();
    for (float[] p : again) { GestureLogic.Gesture g = d.feed(room(p[0], p[1], 110), clock[0]); clock[0] += 66; if (g != null) quick.append(g.name); }
    for (int i = 0; i < 8; i++) { GestureLogic.Gesture g = d.feed(room(-1, 0, 110), clock[0]); clock[0] += 66; if (g != null) quick.append(g.name); }
    check(two.equals("swipe_right") && quick.length() == 0 && clock[0] - before < 1600, "no second gesture within the cooldown");
    clock[0] += 2000;

    // The whole picture changing (the phone moved) is never a gesture.
    GestureLogic.Detector s = new GestureLogic.Detector(GestureLogic.threshold("high"));
    long t = 0; String shook = "";
    for (int i = 0; i < 30; i++) { GestureLogic.Gesture g = s.feed(room(-1, 0, i % 2 == 0 ? 60 : 160), t); t += 66; if (g != null) shook += g.name; }
    check(shook.isEmpty(), "phone shaking ignored");

    // Cover: dark for about a second fires once; a short dark moment does not.
    GestureLogic.Detector c = new GestureLogic.Detector(GestureLogic.threshold("normal"));
    t = 0; String covered = "";
    for (int i = 0; i < 10; i++) { c.feed(room(-1, 0, 120), t); t += 66; }
    for (int i = 0; i < 5; i++) { GestureLogic.Gesture g = c.feed(room(-1, 0, 5), t); t += 66; if (g != null) covered += g.name; }
    for (int i = 0; i < 10; i++) { GestureLogic.Gesture g = c.feed(room(-1, 0, 120), t); t += 66; if (g != null) covered += g.name; }
    check(covered.isEmpty(), "a short dark moment is not cover");
    for (int i = 0; i < 40; i++) { GestureLogic.Gesture g = c.feed(room(-1, 0, 5), t); t += 66; if (g != null) covered += g.name + " "; }
    check(covered.trim().equals("cover"), "held cover fires once: " + covered);
    for (int i = 0; i < 10; i++) { GestureLogic.Gesture g = c.feed(room(-1, 0, 120), t); t += 66; if (g != null) covered += "x"; }
    check(!covered.contains("x"), "uncovering is not a gesture");

    // Sensitivity and commands.
    check(GestureLogic.threshold("low") > GestureLogic.threshold("normal") && GestureLogic.threshold("normal") > GestureLogic.threshold("high"), "sensitivity thresholds");
    check(GestureLogic.command("swipe_right").equals("next") && GestureLogic.command("cover").equals("pause") && GestureLogic.command("nod").isEmpty(), "gesture commands");

    // Grid from a camera plane: upright and mirrored like a selfie.
    int w = 64, h = 48; byte[] y = new byte[w * h];
    for (int i = 0; i < w * h; i++) y[i] = (byte) ((i % w) < w / 2 ? 10 : 200); // left half dark in the sensor image
    int[] g0 = GestureLogic.grid(y, w, h, w, 0, false), gm = GestureLogic.grid(y, w, h, w, 0, true);
    check(g0[0] == 10 && g0[GestureLogic.W - 1] == 200 && gm[0] == 200 && gm[GestureLogic.W - 1] == 10, "grid and mirror");
    int[] g90 = GestureLogic.grid(y, w, h, w, 90, false);
    check(g90[0] == 10 && g90[GestureLogic.N - 1] == 200, "grid turned 90 degrees");
    int[] g270 = GestureLogic.grid(y, w, h, w, 270, false);
    check(g270[0] == 200 && g270[GestureLogic.N - 1] == 10, "grid turned 270 degrees");

    if (failures > 0) { System.out.println(failures + " gesture check(s) failed"); System.exit(1); }
    System.out.println("All gesture checks passed");
  }
}
