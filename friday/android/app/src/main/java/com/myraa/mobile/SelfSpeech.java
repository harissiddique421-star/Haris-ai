package com.myraa.mobile;

import android.os.SystemClock;

/**
 * Knows when FRIDAY itself is talking through the phone's speech engine (replies, caller names,
 * reminders), so the wake word never wakes on FRIDAY's own voice. The microphone's echo
 * cancellation removes most of it; this also covers phones without echo cancellation and the
 * short tail of sound left in the room after the speech ends.
 */
final class SelfSpeech {
  private static final long TAIL_MS=600;
  private static int speaking;
  private static long quietSince,startedAt;
  /** A speech engine that never reports the end (a crash) must not mute the wake word for good. */
  private static final long STALE_MS=60_000;
  private SelfSpeech(){}
  static synchronized void started(){speaking++;startedAt=SystemClock.elapsedRealtime();}
  static synchronized void finished(){if(speaking>0)speaking--;if(speaking==0)quietSince=SystemClock.elapsedRealtime();}
  /** True while FRIDAY speaks and for a short moment after, or when it spoke at any time since sinceMs (elapsedRealtime). */
  static synchronized boolean overlaps(long sinceMs){
    if(speaking>0&&SystemClock.elapsedRealtime()-startedAt>STALE_MS){speaking=0;quietSince=startedAt+STALE_MS;}
    return speaking>0||quietSince+TAIL_MS>sinceMs;
  }
  static boolean now(){return overlaps(SystemClock.elapsedRealtime());}
}
