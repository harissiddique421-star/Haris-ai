package com.myraa.mobile;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Debug log for the phone control layer: written to logcat (tag FRIDAY.Control) and kept as the
 * last 80 lines in memory only, so the user can see it in Settings > diagnostics without a PC.
 * Never logs message text, typed text or phone numbers, only what kind of step ran and why it failed.
 */
final class ControlLog {
  private static final String TAG="FRIDAY.Control";
  private static final ArrayDeque<String> lines=new ArrayDeque<>();
  private ControlLog(){}
  static synchronized void i(String message){
    Log.i(TAG,message);
    lines.addLast(new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+" "+message);
    while(lines.size()>80)lines.removeFirst();
  }
  static synchronized List<String> recent(){return new ArrayList<>(lines);}
}
