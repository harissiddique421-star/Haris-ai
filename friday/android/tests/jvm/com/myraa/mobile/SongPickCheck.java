package com.myraa.mobile;

import java.util.*;

/** Checks which YouTube result FRIDAY plays for a song (SongPick) on a plain JVM. */
public final class SongPickCheck {
  private static int failures;
  private static void check(boolean ok, String what) { if (!ok) { failures++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }
  private static int pick(String q, String... titleChannel) {
    List<String> t = new ArrayList<>(), c = new ArrayList<>();
    for (int i = 0; i < titleChannel.length; i += 2) { t.add(titleChannel[i]); c.add(titleChannel[i + 1]); }
    return SongPick.best(q, t, c);
  }

  public static void main(String[] args) {
    check(pick("Pasoori", "Pasoori - Cover by Ayesha", "Ayesha Music", "Coke Studio | Season 14 | Pasoori | Ali Sethi x Shae Gill", "Coke Studio Pakistan") == 1, "official Coke Studio upload beats a cover at the top");
    check(pick("Tum Hi Ho", "Tum Hi Ho (Slowed + Reverb) | Lofi", "Lofi Vibes", "Tum Hi Ho Lyrics", "Lyrics Hub", "Tum Hi Ho - Aashiqui 2 | Full Video Song | Arijit Singh", "T-Series") == 2, "T-Series full video beats slowed and lyrics edits");
    check(pick("Shape of You Ed Sheeran", "Shape of You - Karaoke", "Sing King", "Ed Sheeran - Shape of You (Official Music Video)", "Ed Sheeran") == 1, "artist's official video beats karaoke");
    check(pick("Kesariya", "Kesariya", "Arijit Singh - Topic", "Kesariya Reaction", "React Bros") == 0, "Topic channel stays first");
    check(pick("Tum Hi Ho lyrics", "Tum Hi Ho - Full Video", "T-Series", "Tum Hi Ho Lyrics", "Lyrics Hub") == 1, "asking for lyrics plays the lyrics video");
    check(pick("Pasoori slowed reverb", "Pasoori", "Ali Sethi - Topic", "Pasoori Slowed Reverb", "Lofi Vibes") == 1, "asking for slowed reverb plays that edit");
    check(pick("Pasoori Ali Sethi", "Pasoori cover", "Someone", "Pasoori", "Ali Sethi") == 1, "the artist the user named wins");
    check(pick("naat", "Beautiful Naat 2024", "Naat Channel", "Another Naat", "Other") == 0, "plain query keeps YouTube's top result");
    check(pick("anything") == 0, "no results gives index 0");
    check(SongPick.best("x", null, null) == 0, "null lists are safe");
    if (failures > 0) { System.out.println(failures + " failed"); System.exit(1); }
    System.out.println("all song pick checks passed");
  }
}
