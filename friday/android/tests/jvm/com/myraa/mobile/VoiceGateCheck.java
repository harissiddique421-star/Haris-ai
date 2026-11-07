package com.myraa.mobile;

import java.io.*;
import java.util.*;
import static com.myraa.mobile.SpeechSegmenterCheck.*;

/**
 * Scenario checks for HARIS Voice ID (SpeakerProfile, VoiceGate, VoiceGateSession) with the real
 * Silero VAD and GE2E weights on real recorded speech: LibriSpeech test-other / train-clean-100
 * utterances (CC BY 4.0, from the Resemblyzer repository's audio_data), cut to 4 s.
 * The owner is LibriSpeech speaker 2609; "friend" is speaker 3005; "TV" is three other speakers
 * back to back; FRIDAY's own voice is the espeak-ng sentence from the other checks.
 * args: models dir, fixtures dir. Run with scripts/test-voice-models.sh.
 */
public final class VoiceGateCheck {
  static int failures;static VoiceEncoder enc;static File models,fx;
  public static void main(String[] a) throws Exception {
    models=new File(a[0]);fx=new File(a[1]);
    try(InputStream in=new FileInputStream(new File(models,VoiceEncoder.ASSET))){enc=new VoiceEncoder(in);}
    // Enrollment exactly as VoiceId.register does it: whole sentence and its first CHECK_MS.
    List<float[]> full=new ArrayList<>(),shortPrints=new ArrayList<>();
    for(int i=1;i<=5;i++){
      float[] w=wav("owner_enroll_"+i);full.add(enc.embed(w));
      shortPrints.add(enc.embed(Arrays.copyOf(w,Math.min(w.length,SpeakerProfile.CHECK_MS*16+10*SileroVad.CHUNK))));
    }
    SpeakerProfile p=SpeakerProfile.build(full,shortPrints,1);
    double t=p.threshold("balanced");
    System.out.printf(Locale.ROOT,"profile: %s%n",p.summary("balanced"));
    expect("threshold within the calibrated bounds",t>=SpeakerProfile.MIN_THRESHOLD&&t<=SpeakerProfile.MAX_THRESHOLD);
    expect("strict is stricter than relaxed",p.threshold("strict")>=p.threshold("balanced")&&p.threshold("balanced")>=p.threshold("relaxed"));
    expect("an accepted other voice raises the threshold above it",p.withImpostor(0.79).threshold("balanced")>=0.83-1e-9);
    SpeakerProfile back=SpeakerProfile.fromJson(new org.json.JSONObject(p.toJson().toString()));
    expect("profile survives save and load",Math.abs(back.threshold("balanced")-t)<1e-9&&VoiceEncoder.cosine(back.print,p.print)>0.999999);
    boolean refused=false;
    try{List<float[]> mixed=new ArrayList<>(full.subList(0,3)),ms=new ArrayList<>(shortPrints.subList(0,3));
      mixed.add(enc.embed(wav("friend_1")));ms.add(enc.embed(Arrays.copyOf(wav("friend_1"),24320)));
      mixed.add(enc.embed(wav("tv_1")));ms.add(enc.embed(Arrays.copyOf(wav("tv_1"),24320)));
      SpeakerProfile.build(mixed,ms,1);}catch(IllegalArgumentException e){refused=true;}
    expect("enrollment with three different people's voices is refused",refused);

    float[] owner1=wav("owner_test_1"),owner2=wav("owner_test_2"),friend1=wav("friend_1"),friend2=wav("friend_2");
    float[] tv=cat(wav("tv_1"),wav("tv_2"),wav("tv_3"));
    float[] tts=trim(VoiceModelsCheck.readWav(new File(fx.getParentFile(),"r16_s_0.wav")));
    Random rnd=new Random(11);

    Result r=run(p,t,cat(sil(1),owner1,sil(1.5)));
    scenario("A  owner speaks: allowed (can command and interrupt)",r,true);
    expect("   owner audio reaches Gemini unchanged, nothing lost",r.exact&&r.ownerSamples>=owner1.length);
    scenario("A2 owner, another sentence",run(p,t,cat(sil(1),owner2,sil(1.5))),true);
    scenario("A3 owner with fan noise at 15 dB SNR",run(p,t,noisy(cat(sil(1),owner1,sil(1.5)),owner1,15,rnd)),true);
    scenario("B  a friend talks (two sentences): ignored",run(p,t,cat(sil(1),friend1,sil(1),friend2,sil(1.5))),false);
    scenario("C  two people talking over each other: ignored",run(p,t,cat(sil(1),add(friend1,scaleTo(wav("tv_2"),rms(friend1)*0.8)),sil(1.5))),false);
    scenario("D  TV: 12 s of other voices without a pause: ignored",run(p,t,cat(sil(1),tv,sil(1.5))),false);
    scenario("E  FRIDAY's own (synthetic) voice leaking from the speaker: ignored",run(p,t,cat(sil(1),scaleTo(tts,0.03),sil(1.5))),false);
    Result quiet=run(p,t,add(sil(6),noise(6,0.02,rnd,false)));
    scenario("   room noise only: nothing sent, no voice checks",quiet,false);
    expect("   no voice checks ran on noise",quiet.checks==0);
    // The owner talks over the TV (owner 6 dB louder): checked every 2 s, so the owner gets through.
    float[] over=cat(sil(1),add(tv,cat(sil(4),scaleTo(owner1,rms(tv)*2))),sil(1.5));
    Result ov=run(p,t,over);
    scenario("D2 owner talks over the TV: owner gets through",ov,true);

    // The session wrapper: base64 in, ordered JSON out.
    VoiceGateSession s=new VoiceGateSession(gate(p,t));
    float[] stream=cat(sil(1),owner1,sil(1.5));short[] pcm=shorts(stream);
    long owner=0,silence=0;boolean ownerEvent=false;
    for(int i=0;i<pcm.length;i+=1024){
      int n=Math.min(1024,pcm.length-i);java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(n*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      for(int k=0;k<n;k++)b.putShort(pcm[i+k]);
      org.json.JSONObject o=new org.json.JSONObject(s.feed(Base64.getEncoder().encodeToString(b.array()),0));
      long[] got=tally(o);owner+=got[0];silence+=got[1];ownerEvent|=got[2]>0;
    }
    for(int k=0;k<100&&owner+silence<pcm.length-SileroVad.CHUNK;k++){Thread.sleep(50);long[] got=tally(new org.json.JSONObject(s.feed("",0)));owner+=got[0];silence+=got[1];ownerEvent|=got[2]>0;}
    s.stop();
    expect("session: owner audio and silence come back in order, nothing lost ("+owner+" owner + "+silence+" silent samples of "+pcm.length+")",ownerEvent&&owner>=owner1.length&&owner+silence>=pcm.length-SileroVad.CHUNK);

    if(failures>0){System.out.println(failures+" Voice ID check(s) failed.");System.exit(1);}
    System.out.println("All Voice ID scenarios passed.");
  }

  static final class Result { long ownerSamples,silent;int owners,unknowns,checks;boolean exact=true; }
  static VoiceGate gate(SpeakerProfile p,double t) throws IOException {
    SileroVad vad;try(InputStream in=new FileInputStream(new File(models,SileroVad.ASSET))){vad=new SileroVad(in);}
    return new VoiceGate(vad,w->p.score(enc.embed(w)),t,800);
  }
  /** Streams audio through a fresh gate in 1024-sample frames, as the WebView does. */
  static Result run(SpeakerProfile p,double t,float[] audio) throws IOException {
    VoiceGate g=gate(p,t);short[] pcm=shorts(audio);Result r=new Result();
    // Owner audio must be exactly the input samples (each 32 ms chunk found in the input).
    VoiceGate.Output out=new VoiceGate.Output(){
      @Override public void owner(short[] x){r.ownerSamples+=x.length;if(!contains(pcm,x))r.exact=false;}
      @Override public void silence(int n){r.silent+=n;}
      @Override public void event(String n,double sc){if("owner".equals(n))r.owners++;if("unknown".equals(n)||"lost".equals(n))r.unknowns++;}
    };
    short[] buf=new short[1024];
    for(int i=0;i<pcm.length;i+=1024){int n=Math.min(1024,pcm.length-i);System.arraycopy(pcm,i,buf,0,n);g.push(buf,n,out);}
    r.checks=g.checks;return r;
  }
  private static final Map<short[],Set<Long>> index=new IdentityHashMap<>();
  /** True when chunk x occurs in pcm at a chunk boundary (hash lookup). */
  static boolean contains(short[] pcm,short[] x){
    Set<Long> set=index.get(pcm);
    if(set==null){set=new HashSet<>();for(int i=0;i+SileroVad.CHUNK<=pcm.length;i+=SileroVad.CHUNK)set.add(hash(pcm,i));index.put(pcm,set);}
    return set.contains(hash(x,0));
  }
  static long hash(short[] a,int at){long h=1125899906842597L;for(int i=0;i<SileroVad.CHUNK;i++)h=31*h+a[at+i];return h;}
  static long[] tally(org.json.JSONObject o) throws org.json.JSONException {
    long owner=0,silence=0,events=0;org.json.JSONArray a=o.optJSONArray("a");
    if(a!=null)for(int i=0;i<a.length();i++){org.json.JSONArray it=a.getJSONArray(i);if("o".equals(it.getString(0)))owner+=Base64.getDecoder().decode(it.getString(1)).length/2;else silence+=it.getLong(1);}
    org.json.JSONArray e=o.optJSONArray("e");if(e!=null)for(int i=0;i<e.length();i++)if("owner".equals(e.getJSONArray(i).getString(0)))events++;
    return new long[]{owner,silence,events};
  }
  static void scenario(String name,Result r,boolean ownerExpected){
    boolean ok=ownerExpected?r.owners>0&&r.ownerSamples>0:r.ownerSamples==0&&r.owners==0;
    System.out.printf(Locale.ROOT,"%-72s owner audio %5.1f s, allowed %d, ignored %d, checks %d  %s%n",name,r.ownerSamples/16000.0,r.owners,r.unknowns,r.checks,ok?"OK":"FAILED");
    if(!ok)failures++;
  }
  static void expect(String name,boolean ok){System.out.printf("%-72s %s%n",name,ok?"OK":"FAILED");if(!ok)failures++;}
  static float[] wav(String name) throws IOException {return VoiceModelsCheck.readWav(new File(fx,name+".wav"));}
  static short[] shorts(float[] f){short[] s=new short[f.length];for(int i=0;i<f.length;i++)s[i]=(short)Math.max(-32768,Math.min(32767,Math.round(f[i]*32768)));return s;}
  static float[] noisy(float[] mix,float[] speech,double snr,Random rnd){return add(mix,scaleTo(noise(mix.length/16000.0,1,rnd,true),rms(speech)/Math.pow(10,snr/20)));}
}
