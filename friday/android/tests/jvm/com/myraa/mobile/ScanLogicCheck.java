package com.myraa.mobile;

/** Checks the document scan maths (ScanLogic) on a plain JVM with a made-up photo of a page on a table. */
public final class ScanLogicCheck {
  private static int failures;
  private static void check(boolean ok, String what) { if (!ok) { failures++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }
  private static int rgb(int r, int g, int b) { return 0xFF000000 | (r << 16) | (g << 8) | b; }

  /** A 600x800 photo: dark table, a yellowish page from (100,120) to (500,700) with a shadow on its left, text lines. */
  private static int[] photo(int w, int h) {
    int[] px = new int[w * h];
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
      int p = rgb(70, 55, 45);
      if (x >= 100 && x < 500 && y >= 120 && y < 700) {
        double shade = 0.6 + 0.4 * (x - 100) / 400.0; // shadow from the phone on the left
        p = rgb((int) (235 * shade), (int) (225 * shade), (int) (190 * shade));
        boolean text = (y - 160) % 40 < 6 && y > 160 && y < 660 && x > 140 && x < 460;
        if (text) p = rgb((int) (40 * shade), (int) (40 * shade), (int) (45 * shade));
      }
      px[y * w + x] = p;
    }
    return px;
  }

  public static void main(String[] args) {
    int w = 600, h = 800; int[] px = photo(w, h);
    int[] b = ScanLogic.paperBounds(px, w, h);
    check(Math.abs(b[0] - 100) <= 12 && Math.abs(b[1] - 120) <= 12 && Math.abs(b[2] - 500) <= 12 && Math.abs(b[3] - 700) <= 12,
      "finds the page on the table: " + b[0] + "," + b[1] + " to " + b[2] + "," + b[3]);
    int cw = b[2] - b[0], ch = b[3] - b[1]; int[] crop = new int[cw * ch];
    for (int y = 0; y < ch; y++) System.arraycopy(px, (b[1] + y) * w + b[0], crop, y * cw, cw);
    int[] clean = ScanLogic.clean(crop, cw, ch, ScanLogic.COLOR);
    // Paper in the shadow (left) and in the light (right) both come out white; text stays dark.
    int shadowPaper = clean[(300 - b[1]) * cw + (130 - b[0])], lightPaper = clean[(300 - b[1]) * cw + (480 - b[0])];
    check(ScanLogic.lum(shadowPaper) >= 245 && ScanLogic.lum(lightPaper) >= 245, "paper turns white in shadow and light: " + ScanLogic.lum(shadowPaper) + ", " + ScanLogic.lum(lightPaper));
    int ink = clean[(162 - b[1]) * cw + (200 - b[0])];
    check(ScanLogic.lum(ink) <= 70, "text stays dark: " + ScanLogic.lum(ink));
    int r = (lightPaper >> 16) & 255, bl = lightPaper & 255;
    check(Math.abs(r - bl) <= 10, "yellow light removed (paper neutral white)");
    int[] gray = ScanLogic.clean(crop, cw, ch, ScanLogic.BW);
    int g = gray[(300 - b[1]) * cw + (300 - b[0])];
    check(((g >> 16) & 255) == (g & 255) && ScanLogic.lum(g) == 255, "black and white mode: grey pixels, white paper");

    // A crooked page leaves table in a corner of the crop: it turns white, a dark heading inside the page stays.
    int cw2 = 200, ch2 = 260; int[] tilt = new int[cw2 * ch2];
    for (int y = 0; y < ch2; y++) for (int x = 0; x < cw2; x++) tilt[y * cw2 + x] = x + y < 12 ? rgb(70, 55, 45) : (y >= 40 && y < 60 && x >= 30 && x < 170) ? rgb(30, 30, 30) : rgb(230, 225, 200);
    int[] out = ScanLogic.clean(tilt, cw2, ch2, ScanLogic.COLOR);
    ScanLogic.whiteCorners(out, tilt, cw2, ch2);
    check(ScanLogic.lum(out[1 * cw2 + 1]) == 255, "table in the corner turned white");
    check(ScanLogic.lum(out[50 * cw2 + 100]) <= 70, "dark heading inside the page kept");

    // A photo that is all paper (no table around it) is not cropped.
    int[] flat = new int[300 * 400]; java.util.Arrays.fill(flat, rgb(230, 230, 225));
    int[] fb = ScanLogic.paperBounds(flat, 300, 400);
    check(fb[0] == 0 && fb[1] == 0 && fb[2] == 300 && fb[3] == 400, "no table in the photo: whole photo kept");

    check(ScanLogic.mode("", "ye page black and white mein scan karo").equals(ScanLogic.BW), "black and white from the user's words");
    check(ScanLogic.mode("", "CNIC scan karo").equals(ScanLogic.COLOR), "CNIC stays in colour");
    check(ScanLogic.mode("gray", "").equals(ScanLogic.GRAY), "mode asked by the model");
    check(ScanLogic.title("", "FRIDAY mera CNIC scan karo").equals("CNIC scan"), "CNIC title from what was heard");
    check(ScanLogic.title("bijli ka bill", "").equals("Bill scan"), "bill title");
    check(ScanLogic.title("", "ye page scan karo").equals("Scanned document"), "plain page: Scanned document");
    check(ScanLogic.title("Ali ki marksheet", "").equals("Ali ki marksheet scan"), "the user's own name for it");
    check(ScanLogic.title("", "scan karke Ali ko bhej do").equals("Scanned document"), "a sentence about sending is not a title");

    int[] p = ScanLogic.page(1600, 1000);
    check(p[0] == 842 && p[1] == 595 && p[4] <= 842 - 48 && p[5] <= 595 - 48, "wide card on a landscape A4 page");
    p = ScanLogic.page(1200, 1700);
    check(p[0] == 595 && p[1] == 842 && p[2] >= 24 && p[3] >= 24, "page on a portrait A4 page with margins");
    int[] f = ScanLogic.fit(4000, 3000, 1800);
    check(f[0] == 1800 && f[1] == 1350, "big photo scaled to 1800 px");

    if (failures > 0) { System.out.println(failures + " scan check(s) failed"); System.exit(1); }
    System.out.println("ScanLogicCheck passed");
  }
}
