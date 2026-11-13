package com.myraa.mobile;

import java.io.*;
import java.util.*;

/**
 * Prints the current GE2E voiceprint's scores on the Voice ID test recordings in the same protocol
 * as scripts/compare-voice-engines.py, so other speaker models can be compared with it:
 * the owner's profile is the normalised mean of the five enrollment recordings; every test
 * recording is scored whole and in 1.2 s windows (what the Live call gate checks), hop 0.6 s.
 * args: models dir, voiceid fixtures dir. Output: one JSON object on stdout.
 */
public final class SpeakerCompare {
  public static void main(String[] a) throws Exception {
    File models=new File(a[0]),fx=new File(a[1]);VoiceEncoder enc;
    try(InputStream in=new FileInputStream(new File(models,VoiceEncoder.ASSET))){enc=new VoiceEncoder(in);}
    float[] mean=new float[VoiceEncoder.DIM];
    for(int i=1;i<=5;i++){float[] e=enc.embed(VoiceModelsCheck.readWav(new File(fx,"owner_enroll_"+i+".wav")));for(int k=0;k<mean.length;k++)mean[k]+=e[k];}
    float[] print=VoiceEncoder.normalize(mean);
    StringBuilder out=new StringBuilder("{\"model\":\"GE2E (current)\",\"scores\":[");boolean first=true;
    String[] names={"owner_test_1","owner_test_2","friend_1","friend_2","tv_1","tv_2","tv_3"};
    for(String n:names){
      float[] w=VoiceModelsCheck.readWav(new File(fx,n+".wav"));String who=n.startsWith("owner")?"owner":"other";
      List<float[]> parts=new ArrayList<>();List<String> kinds=new ArrayList<>();
      parts.add(w);kinds.add("whole");
      for(int s=0;s+19200<=w.length;s+=9600){parts.add(Arrays.copyOfRange(w,s,s+19200));kinds.add("window");}
      for(int i=0;i<parts.size();i++){
        float[] e=enc.embed(parts.get(i));if(e==null)continue;
        out.append(first?"":",").append(String.format(Locale.ROOT,"{\"file\":\"%s\",\"who\":\"%s\",\"kind\":\"%s\",\"score\":%.5f}",n,who,kinds.get(i),VoiceEncoder.cosine(e,print)));first=false;
      }
    }
    System.out.println(out.append("]}"));
  }
}
