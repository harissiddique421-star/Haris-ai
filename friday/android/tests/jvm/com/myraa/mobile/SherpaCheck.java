package com.myraa.mobile;

import java.io.*;
import java.util.*;

/**
 * Checks FRIDAY's sherpa-onnx engines on a plain JVM with the linux-x64 native library and the same
 * models the APK carries (scripts/test-sherpa.sh fetches them):
 *  - TitaNet Voice ID: the owner registered from the five enrollment sentences exactly as VoiceId
 *    does (whole sentence, its first 1.2 s, a room-echo copy) is let through on both test sentences
 *    by the real Live gate (VoiceGate with Silero VAD), and the friend and the TV never are.
 *    The scores are printed so the bounds in SpeakerProfile.CALIB_TITANET can be checked.
 *  - TitaNet Voice Guardian: three recordings make the wake voice; the owner's other sentences pass
 *    TitaNet.guardianThreshold, other voices do not.
 *  - Wake phrase spotting: synthetic "Wake up FRIDAY" / "FRIDAY wake up" in several voices (made by
 *    the workflow with espeak-ng) are heard; the owner's ordinary sentences, the friend and the TV are not.
 * args: native lib dir, models dir (titanet_small.onnx, kws/), voiceid fixtures dir, Silero models dir, [wake phrase wavs dir]
 */
public final class SherpaCheck {
  static int failures;
  public static void main(String[] a) throws Exception {
    File lib=new File(a[0]),models=new File(a[1]),fx=new File(a[2]);
    for(String n:new String[]{"libonnxruntime.so","libsherpa-onnx-jni.so"})System.load(new File(lib,n).getAbsolutePath());
    TitaNet tn=new TitaNet(new File(models,TitaNet.FILE),2);
    expect("TitaNet makes "+SpeakerProfile.dim(Voiceprinter.TITANET)+"-number voiceprints",tn.dim()==SpeakerProfile.dim(Voiceprinter.TITANET));
    VoiceGateCheck.enc=tn;VoiceGateCheck.fx=fx;VoiceGateCheck.models=new File(a[3]);

    // ---- Voice ID, as VoiceId.register builds it
    List<float[]> full=new ArrayList<>(),sh=new ArrayList<>(),echo=new ArrayList<>();
    int cut=SpeakerProfile.CHECK_MS*16+10*SileroVad.CHUNK;
    for(int i=1;i<=5;i++){float[] w=VoiceGateCheck.wav("owner_enroll_"+i);full.add(tn.embed(w));sh.add(tn.embed(Arrays.copyOf(w,Math.min(w.length,cut))));echo.add(tn.embed(SpeakerProfile.roomEcho(w)));}
    SpeakerProfile p=SpeakerProfile.build(Voiceprinter.TITANET,full,sh,echo,1);
    System.out.printf(Locale.ROOT,"TitaNet profile: genuine %s, mean %.3f, dropped %d, thresholds strict %.3f balanced %.3f relaxed %.3f%n",
      fmt(p.genuine),p.meanGenuine(),p.dropped,p.threshold("strict"),p.threshold("balanced"),p.threshold("relaxed"));
    expect("all five sentences kept",p.dropped==0);
    double t=p.threshold("balanced");
    expect("balanced threshold within TitaNet's bounds",t>=SpeakerProfile.CALIB_TITANET.minThreshold&&t<=SpeakerProfile.CALIB_TITANET.maxThreshold);
    SpeakerProfile back=SpeakerProfile.fromJson(new org.json.JSONObject(p.toJson().toString()));
    expect("TitaNet profile survives save and load",Voiceprinter.TITANET.equals(back.model)&&Math.abs(back.threshold("balanced")-t)<1e-9&&back.samples.size()==5);

    double ownerMin=1,otherMax=-1;
    for(String n:new String[]{"owner_test_1","owner_test_2","friend_1","friend_2","tv_1","tv_2","tv_3"}){
      float[] w=VoiceGateCheck.wav(n);boolean owner=n.startsWith("owner");
      double first=p.score(tn.embed(Arrays.copyOf(w,Math.min(w.length,cut)))),whole=p.score(tn.embed(w));
      double lo=1,hi=-1;for(int s=0;s+19200<=w.length;s+=9600){double x=p.score(tn.embed(Arrays.copyOfRange(w,s,s+19200)));lo=Math.min(lo,x);hi=Math.max(hi,x);}
      System.out.printf(Locale.ROOT,"  %-13s first 1.2 s %.3f, whole %.3f, 1.2 s windows %.3f..%.3f%n",n,first,whole,lo,hi);
      if(owner)ownerMin=Math.min(ownerMin,Math.min(first,whole));else otherMax=Math.max(otherMax,Math.max(hi,Math.max(first,whole)));
      if(owner)expect(n+": accepted by the first check",VoiceGateCheck.accepts(p,t,w));
      else expect(n+": refused, also at relaxed",!VoiceGateCheck.accepts(p,p.threshold("relaxed"),w)&&hi<p.threshold("relaxed"));
    }
    System.out.printf(Locale.ROOT,"TitaNet Voice ID: owner lowest %.3f, others highest %.3f, balanced threshold %.3f%n",ownerMin,otherMax,t);
    expect("an other voice too close to the owner is not stored",p.tooCloseToOwner(p.meanGenuine()));
    expect("an accepted other voice raises the threshold above it",p.withImpostor(t+0.02).threshold("balanced")>=t+0.02);

    // The real Live gate, streamed as in a call.
    VoiceGateCheck.endMs=800;
    for(String n:new String[]{"owner_test_1","owner_test_2"})VoiceGateCheck.scenario("Live gate (TitaNet): "+n,VoiceGateCheck.run(p,t,cat(sil(0.5),VoiceGateCheck.wav(n),sil(1.2))),true);
    for(String n:new String[]{"friend_1","friend_2","tv_1","tv_2","tv_3"})VoiceGateCheck.scenario("Live gate (TitaNet): "+n,VoiceGateCheck.run(p,t,cat(sil(0.5),VoiceGateCheck.wav(n),sil(1.2))),false);
    failures+=VoiceGateCheck.failures;

    // ---- Voice Guardian: three recordings make the wake voice
    float[][] g=new float[3][];float[] sum=new float[tn.dim()];
    for(int i=0;i<3;i++){g[i]=tn.embed(VoiceGateCheck.wav("owner_enroll_"+(i+1)));for(int k=0;k<sum.length;k++)sum[k]+=g[i][k];}
    float[] mean=VoiceEncoder.normalize(sum);double self=1;for(float[] e:g)self=Math.min(self,VoiceEncoder.cosine(e,mean));
    double gt=TitaNet.guardianThreshold(self,"balanced",0);
    System.out.printf(Locale.ROOT,"Guardian: consistency %.3f (needs %.2f), thresholds low %.3f balanced %.3f high %.3f%n",self,TitaNet.GUARDIAN_MIN_ENROLL,
      TitaNet.guardianThreshold(self,"low",0),gt,TitaNet.guardianThreshold(self,"high",0));
    expect("guardian recordings consistent enough",self>=TitaNet.GUARDIAN_MIN_ENROLL);
    for(String n:new String[]{"owner_enroll_4","owner_enroll_5","owner_test_1","owner_test_2","friend_1","friend_2","tv_1","tv_2","tv_3"}){
      float[] w=VoiceGateCheck.wav(n);w=Arrays.copyOf(w,Math.min(w.length,VoiceGuardian_WAKE_MAX));
      double s=VoiceEncoder.cosine(tn.embed(w),mean);boolean owner=n.startsWith("owner");
      double loose=TitaNet.guardianThreshold(self,"high",0);
      System.out.printf(Locale.ROOT,"  guardian %-14s %.3f%n",n,s);
      expect("guardian "+n+(owner?": owner wakes it":": does not wake it, even at high sensitivity"),owner?s>=gt:s<loose);
    }

    // ---- Wake phrase spotting
    WakeSpotter spot=new WakeSpotter(new File(models,"kws"),1,WakeSpotter.threshold("balanced"));
    if(a.length>4){
      File[] pos=new File(a[4]).listFiles((d,n)->n.endsWith(".wav"));Arrays.sort(pos);int wake=0,wakeAll=0,hey=0,heyAll=0;
      for(File f:pos){
        String k=spot.heard(readAnyRate(f));boolean isHey=f.getName().contains("Hey");
        System.out.printf("  spotter %-40s %s%n",f.getName(),k==null?"-":k);
        if(isHey){heyAll++;if(k!=null)hey++;}else{wakeAll++;if(k!=null)wake++;}
      }
      System.out.printf(Locale.ROOT,"Wake phrase spotter: \"Wake up FRIDAY\" / \"FRIDAY wake up\" %d of %d, \"Hey FRIDAY\" %d of %d%n",wake,wakeAll,hey,heyAll);
      // The recorded Guardian phrase is "Wake up FRIDAY": every voice must be heard. "Hey FRIDAY" is shorter and
      // harder (two voices missed it); the recorded-phrase match still covers it, so it only has to be heard mostly.
      expect("\"Wake up FRIDAY\" and \"FRIDAY wake up\" heard in every synthetic voice",wakeAll>0&&wake==wakeAll);
      expect("\"Hey FRIDAY\" heard in most synthetic voices",heyAll==0||hey*2>heyAll);
    }
    for(String n:new String[]{"owner_enroll_1","owner_enroll_2","owner_enroll_3","owner_enroll_4","owner_enroll_5","owner_test_1","owner_test_2","friend_1","friend_2","tv_1","tv_2","tv_3"}){
      String k=spot.heard(VoiceGateCheck.wav(n));
      expect("spotter: no wake phrase in "+n+(k==null?"":" (heard "+k+")"),k==null);
    }
    System.out.println(failures==0?"All sherpa-onnx engine checks passed.":failures+" sherpa-onnx engine check(s) FAILED.");
    if(failures>0)System.exit(1);
  }
  /** Longest burst Voice Guardian checks (VoiceGuardian.WAKE_MAX_MS, 5 s). */
  static final int VoiceGuardian_WAKE_MAX=5*16000;
  /** 16-bit mono WAV at any rate, resampled to 16 kHz (espeak-ng writes 22.05 kHz). */
  static float[] readAnyRate(File f) throws IOException {
    byte[] b=java.nio.file.Files.readAllBytes(f.toPath());java.nio.ByteBuffer bb=java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    int pos=12,rate=16000,off=-1,len=0;
    while(pos+8<=b.length){String id=new String(b,pos,4,"US-ASCII");int l=bb.getInt(pos+4);
      if(id.equals("fmt "))rate=bb.getInt(pos+12);
      if(id.equals("data")){off=pos+8;len=Math.min(l<0?b.length:l,b.length-off);break;}
      pos+=8+l+(l&1);}
    if(off<0)throw new IOException(f+": no data");
    float[] x=new float[len/2];for(int i=0;i<x.length;i++)x[i]=bb.getShort(off+2*i)/32768f;
    if(rate==16000)return x;
    float[] y=new float[(int)((long)x.length*16000/rate)];
    for(int i=0;i<y.length;i++){double t=i*(double)rate/16000;int k=(int)t;double fr=t-k;y[i]=(float)(x[k]*(1-fr)+(k+1<x.length?x[k+1]:0)*fr);}
    return y;
  }
  static float[] sil(double s){return new float[(int)(s*16000)];}
  static float[] cat(float[]... parts){int n=0;for(float[] p:parts)n+=p.length;float[] o=new float[n];int at=0;for(float[] p:parts){System.arraycopy(p,0,o,at,p.length);at+=p.length;}return o;}
  static String fmt(double[] v){StringBuilder b=new StringBuilder("[");for(double x:v)b.append(String.format(Locale.ROOT,b.length()>1?", %.3f":"%.3f",x));return b.append("]").toString();}
  static void expect(String name,boolean ok){System.out.printf("%-72s %s%n",name,ok?"OK":"FAILED");if(!ok)failures++;}
}
