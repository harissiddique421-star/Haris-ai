package com.myraa.mobile;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Voice ID recording through the Live call's own microphone chain. The page captures with exactly
 * the settings a Live call uses (WebView getUserMedia with echo cancellation, noise suppression,
 * gain control and voice isolation, 85 Hz high-pass, 16 kHz) and hands the samples over here, so
 * the voiceprint is learned from the same sound the call's voice gate later checks.
 *
 * Before rc.51 registration used FRIDAY's own Android microphone (MicInput), whose processing
 * differs from the call's; the owner's voice could then match in "Test my voice" but not in a call.
 * Samples are only held in memory until they are turned into voiceprints; nothing is saved.
 */
final class CallMicPcm {
  private static volatile CallMicPcm active;
  private final LinkedBlockingQueue<short[]> queue=new LinkedBlockingQueue<>();
  private short[] rest;private int restAt;
  private volatile boolean closed;
  /** Set when the page reports the microphone could not be opened. */
  volatile String failed;

  static CallMicPcm open(){CallMicPcm p=new CallMicPcm();active=p;return p;}
  static boolean recording(){CallMicPcm p=active;return p!=null&&!p.closed;}

  /** From the page: 16-bit little-endian mono PCM at 16 kHz, base64. Ignored when no recording is waiting. */
  static void feed(String base64){
    CallMicPcm p=active;if(p==null||p.closed||base64==null||base64.isEmpty())return;
    byte[] b;try{b=android.util.Base64.decode(base64,android.util.Base64.DEFAULT);}catch(IllegalArgumentException e){return;}
    if(b.length>64000)return;
    short[] s=new short[b.length/2];
    for(int i=0;i<s.length;i++)s[i]=(short)((b[2*i]&0xff)|(b[2*i+1]<<8));
    if(p.queue.size()<2000)p.queue.add(s);
  }
  static void fail(String why){CallMicPcm p=active;if(p!=null){p.failed=why==null?"microphone unavailable":why;p.closed=true;}}

  /** Fills chunk completely; false when closed, failed, or no audio came for 4 s. */
  boolean read(short[] chunk){
    int got=0;
    while(got<chunk.length){
      if(rest==null||restAt>=rest.length){
        if(closed)return false;
        try{rest=queue.poll(4,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
        restAt=0;if(rest==null){if(failed==null)failed="no audio from the microphone";return false;}
      }
      int n=Math.min(chunk.length-got,rest.length-restAt);
      System.arraycopy(rest,restAt,chunk,got,n);got+=n;restAt+=n;
    }
    return true;
  }
  void close(){closed=true;queue.clear();if(active==this)active=null;}
}
