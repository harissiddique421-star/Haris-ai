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
    // Since rc.50 each sentence also adds a room-echo copy (SpeakerProfile.roomEcho), as VoiceId.register does.
    List<float[]> full=new ArrayList<>(),shortPrints=new ArrayList<>(),echo=new ArrayList<>();
    for(int i=1;i<=5;i++){
      float[] w=wav("owner_enroll_"+i);full.add(enc.embed(w));echo.add(enc.embed(SpeakerProfile.roomEcho(w)));
      shortPrints.add(enc.embed(Arrays.copyOf(w,Math.min(w.length,SpeakerProfile.CHECK_MS*16+10*SileroVad.CHUNK))));
    }
    SpeakerProfile closeOnly=SpeakerProfile.build(full,shortPrints,1);
    SpeakerProfile p=SpeakerProfile.build(full,shortPrints,echo,1);
    System.out.printf(Locale.ROOT,"profile without room-echo training (before rc.50): %s%n",closeOnly.summary("balanced"));
    double t=p.threshold("balanced");
    System.out.printf(Locale.ROOT,"profile: %s%n",p.summary("balanced"));
    expect("threshold within the calibrated bounds",t>=SpeakerProfile.MIN_THRESHOLD&&t<=SpeakerProfile.MAX_THRESHOLD);
    expect("strict is stricter than relaxed",p.threshold("strict")>=p.threshold("balanced")&&p.threshold("balanced")>=p.threshold("relaxed"));
    expect("an accepted other voice raises the threshold above it",p.withImpostor(0.79).threshold("balanced")>=0.83-1e-9);
    SpeakerProfile back=SpeakerProfile.fromJson(new org.json.JSONObject(p.toJson().toString()));
    expect("profile survives save and load",Math.abs(back.threshold("balanced")-t)<1e-9&&VoiceEncoder.cosine(back.print,p.print)>0.99999&&back.samples.size()==5&&back.canAdd());
    SpeakerProfile v1=SpeakerProfile.fromJson(new org.json.JSONObject(closeOnly.toJson().toString()));
    expect("an older profile (no kept samples) still loads; adding samples needs a new registration",!v1.canAdd()&&Math.abs(v1.threshold("balanced")-closeOnly.threshold("balanced"))<1e-9);
    SpeakerProfile more=p.withImpostor(0.6).withSamples(Arrays.asList(enc.embed(wav("owner_test_2"))),Arrays.asList(enc.embed(Arrays.copyOf(wav("owner_test_2"),24320))),Arrays.asList(enc.embed(SpeakerProfile.roomEcho(wav("owner_test_2")))),2);
    expect("adding a sample keeps the other-voice scores and grows the profile",more.samples.size()==6&&more.impostor.size()==1);
    {
      // One bad take (someone else) among the owner's sentences is left out instead of failing the enrollment.
      List<float[]> f=new ArrayList<>(full),sh=new ArrayList<>(shortPrints),e=new ArrayList<>(echo);
      for(String o:new String[]{"owner_test_1","owner_test_2","friend_1"}){float[] w=wav(o);f.add(enc.embed(w));sh.add(enc.embed(Arrays.copyOf(w,24320)));e.add(enc.embed(SpeakerProfile.roomEcho(w)));}
      SpeakerProfile one=SpeakerProfile.build(f,sh,e,1);
      System.out.printf(Locale.ROOT,"   with one take by someone else: %s%n",one.summary("balanced"));
      expect("one take by someone else among 8 is left out, the profile is still built",one.dropped==1&&one.samples.size()==7);
    }
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
    System.out.printf(Locale.ROOT,"   quiet room events %s (owner speaks 1.0-%.1f s)%n",r.events,1+owner1.length/16000.0);
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

    // Owner-specific end of turn: other people keep talking in the background (two conversations
    // at once, 12 dB quieter than the owner, as from across the room) before, during and long after
    // the owner's command. The owner's turn must end about the silence time after the OWNER stops.
    float[] room=add(tv,cat(sil(1.5),friend1,sil(0.3),friend2,sil(0.3),friend1));
    double ownerRms=rms(owner1);
    float[] babble=scaleTo(cat(room,room),ownerRms*Math.pow(10,-12/20.0));
    double ownerStart=2.0,ownerEnd=ownerStart+owner1.length/16000.0;
    Result bg=run(p,t,add(babble,cat(sil(ownerStart),owner1)));
    double endLag=bg.endAt.isEmpty()?Double.POSITIVE_INFINITY:bg.endAt.get(0)-ownerEnd;
    System.out.printf(Locale.ROOT,"   events %s (owner speaks %.1f-%.1f s, background talk runs to %.1f s)%n",bg.events,ownerStart,ownerEnd,babble.length/16000.0);
    scenario("F  owner command while 3 people keep talking 12 dB quieter",bg,true);
    expect(String.format(Locale.ROOT,"   owner's turn ends %.2f s after the owner stops (room still talking; limit 1.2 s)",endLag),endLag>=0&&endLag<=1.2);
    expect(String.format(Locale.ROOT,"   only the owner's turn is sent (%.1f s sent for a %.1f s command)",bg.ownerSamples/16000.0,owner1.length/16000.0),bg.ownerSamples/16000.0<=owner1.length/16000.0+0.32+1.6);
    // Two commands in a row, 2 s apart, with the background never stopping.
    Result two=run(p,t,add(babble,cat(sil(1),owner1,sil(2),owner2)));
    System.out.printf(Locale.ROOT,"   events %s%n",two.events);
    expect("G  two owner commands during non-stop background talk: both accepted, both ended",two.owners>=2&&two.endAt.size()>=2);
    // Background as loud as the owner (people next to the phone) keeps talking after the owner:
    // loudness cannot separate them, so the voiceprint re-check must end the owner's turn.
    float[] loudRoom=scaleTo(cat(room,room),ownerRms*Math.pow(10,-3/20.0));
    Result loud=run(p,t,add(loudRoom,cat(sil(ownerStart),scaleTo(owner1,ownerRms*1.4))));
    double loudLag=loud.endAt.isEmpty()?Double.POSITIVE_INFINITY:loud.endAt.get(0)-ownerEnd;
    System.out.printf(Locale.ROOT,"   events %s%n",loud.events);
    expect(String.format(Locale.ROOT,"H  background as loud as the owner: owner accepted, turn still ends (%.2f s after; limit 3.5 s)",loudLag),loud.owners>=1&&loudLag>=0&&loudLag<=3.5);
    // Owner already known in the call, then speaks with someone else talking just as loud on top:
    // it must never be passed as a confident owner turn of someone else; report what happened.
    Result ov2=run(p,t,cat(sil(0.5),owner2,sil(1.2),add(owner1,scaleTo(friend1,ownerRms)),sil(1.5)));
    System.out.printf(Locale.ROOT,"I  owner, then owner + friend equally loud at once: events %s%n",ov2.events);

    // Far field: the owner first speaks close to the phone (the call learns that level), then from
    // across the room: 14 dB quieter with room echo. It must still be heard and accepted.
    for(int db:new int[]{10,14,18}){
      float[] far=reverb(scaleTo(owner1,ownerRms*Math.pow(10,-db/20.0)),0.35,rnd);
      Result fr=run(p,t,cat(sil(0.5),owner2,sil(2),far,sil(1.5)));
      System.out.printf(Locale.ROOT,"   far %d dB events %s%n",db,fr.events);
      expect(String.format(Locale.ROOT,"L%d owner close, then %d dB quieter with room echo (across the room): both accepted",db,db),fr.owners>=2);
    }

    // Measured accept/reject rates by distance, for the old (close only) and new (with room echo)
    // enrollment. Decision as in a call: first 1.2 s, with the second look on 2 s when close.
    {
      String[] owners={"owner_test_1","owner_test_2"},others={"friend_1","friend_2","tv_1","tv_2","tv_3"};
      double[][] cond={{0,0},{6,0.03},{10,0.05},{14,0.08}};String[] names={"close","1 m","2 m","3 m"};
      for(int which=0;which<2;which++){
        SpeakerProfile prof=which==0?closeOnly:p;double th=prof.threshold("balanced");
        StringBuilder line=new StringBuilder(which==0?"   before rc.50 (close-only enrollment):":"   rc.50 (with room-echo enrollment):   ");
        int far=0,farOk=0,fa=0,imp=0;
        for(int c=0;c<cond.length;c++){
          int ok=0,n=0;
          for(int seed=0;seed<3;seed++)for(String o:owners){n++;if(accepts(prof,th,distant(wav(o),cond[c],seed)))ok++;}
          for(int seed=0;seed<3;seed++)for(String o:others){imp++;if(accepts(prof,th,distant(wav(o),cond[c],seed)))fa++;}
          if(c>0){far+=n;farOk+=ok;}
          line.append(String.format(Locale.ROOT," %s %d/%d",names[c],ok,n));
        }
        System.out.println(line+String.format(Locale.ROOT,"  · other voices accepted %d/%d",fa,imp));
        if(which==1){
          expect(String.format(Locale.ROOT,"M  owner recognised from 1-3 m with room echo: %d/%d (need 90%%)",farOk,far),farOk>=0.9*far);
          expect(String.format(Locale.ROOT,"M  other voices accepted at any distance: %d/%d (need 0)",fa,imp),fa==0);
        }
      }
    }

    // The app's default end of turn (600 ms): a short command ends 0.6 s after the owner stops, and
    // its voice check (run during the pause) does not add to that, also with people talking behind.
    endMs=VoiceGate.DEFAULT_END_MS;
    float[] cmd=VoiceLatencyBench.cutShort(trim(owner2),17600);double cmdEnd=1+VoiceLatencyBench.lastVoiced(cmd)/16000.0;
    for(int k=0;k<2;k++){
      Result sc=run(p,t,k==0?cat(sil(1),cmd,sil(1.5)):add(Arrays.copyOf(babble,(int)(3.6*16000)),cat(sil(1),cmd,sil(1.5))));
      double lag=sc.endAt.isEmpty()?Double.POSITIVE_INFINITY:sc.endAt.get(0)-cmdEnd;
      System.out.printf(Locale.ROOT,"   events %s (owner speaks 1.0-%.1f s)%n",sc.events,cmdEnd);
      expect(String.format(Locale.ROOT,"J%d short command (%.1f s)%s: owner, turn ends %.2f s after (limit 0.70 s)",k+1,cmd.length/16000.0,k==0?"":" with people talking behind",lag),sc.owners==1&&lag>=0&&lag<=0.70);
    }
    endMs=800;

    // Watchdog: even if speech the gate accepts never pauses (here every voice scores as the owner),
    // the owner's turn is closed after 30 s instead of keeping FRIDAY listening for ever.
    {
      SileroVad vad;try(InputStream in=new FileInputStream(new File(models,SileroVad.ASSET))){vad=new SileroVad(in);}
      // Three conversations at once, at the same level: there is never 1 s without speech.
      float[] f4=cat(friend1,friend2,friend1,friend2,friend1,friend2,friend1,friend2,friend1),o4=cat(owner2,owner1,owner2,owner1,owner2,owner1,owner2,owner1,owner2);
      float[] crowd=add(add(cat(tv,tv,tv),scaleTo(f4,rms(tv))),scaleTo(o4,rms(tv)));
      VoiceGate g=new VoiceGate(vad,w->1.0,t,1000);short[] pcm=shorts(cat(sil(0.5),crowd));List<Double> ends=new ArrayList<>();long[] at={0};
      VoiceGate.Output o=new VoiceGate.Output(){public void owner(short[] x){}public void silence(int n){}public void event(String n,double sc){if("end".equals(n))ends.add(at[0]/16000.0);}};
      short[] buf=new short[1024];
      for(int i=0;i<pcm.length;i+=1024){int n=Math.min(1024,pcm.length-i);System.arraycopy(pcm,i,buf,0,n);at[0]=i+n;g.push(buf,n,o);}
      expect(String.format(Locale.ROOT,"K  non-stop speech cannot keep a turn open: first end at %.1f s (limit 32 s)",ends.isEmpty()?-1:ends.get(0)),!ends.isEmpty()&&ends.get(0)<=32);
      System.out.println("   last end: "+g.endInfo);
    }

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
    {
      // rc.53: a voice just under the threshold is "low" confidence (FRIDAY asks to repeat, nothing is let
      // through); far under it stays "unknown". The owner's own sentence is used with a raised threshold.
      float[] w=wav("owner_test_1");float[] padded=new float[w.length+16000];System.arraycopy(w,0,padded,8000,w.length);
      Result base=run(p,0.30,padded);double s0=-1;for(int i=0;i<base.events.size();i++)if(base.events.get(i).startsWith("owner@")){s0=base.scores.get(i);break;}
      expect("the owner's sentence is accepted at a low threshold",s0>0);
      Result near=run(p,Math.min(0.99,s0+0.03),padded),far=run(p,Math.min(0.99,s0+0.25),padded);
      System.out.printf(Locale.ROOT,"   low confidence: owner %.2f, needs +0.03 -> %s; needs +0.25 -> %s%n",s0,near.events,far.events);
      expect("just under the threshold: reported as low confidence, nothing let through",near.ownerSamples==0&&near.events.stream().anyMatch(e->e.startsWith("low@")));
      expect("far under the threshold: unknown, not low",far.ownerSamples==0&&far.events.stream().noneMatch(e->e.startsWith("low@"))&&far.events.stream().anyMatch(e->e.startsWith("unknown@")));
    }
    System.out.println("All Voice ID scenarios passed.");
  }

  /** Speech as from further away: quieter by cond[0] dB and with room echo of strength cond[1]. */
  static float[] distant(float[] x,double[] cond,int seed){
    float[] y=cond[1]>0?reverb(x,0.35,cond[1],new Random(100+seed)):x;
    return scaleTo(y,rms(x)*Math.pow(10,-cond[0]/20.0));
  }
  static boolean accepts(SpeakerProfile p,double t,float[] w){
    int cut=SpeakerProfile.CHECK_MS*16+10*SileroVad.CHUNK;
    double first=p.score(enc.embed(Arrays.copyOf(w,Math.min(w.length,cut))));
    if(first>=t)return true;
    return first>=t-VoiceGate.NEAR&&p.score(enc.embed(Arrays.copyOf(w,Math.min(w.length,32000+10*SileroVad.CHUNK))))>=t;
  }
  /** Simple room echo: the signal plus exponentially decaying random reflections (rt60 seconds). */
  static float[] reverb(float[] x,double rt60,Random rnd){return reverb(x,rt60,0.05,rnd);}
  /** gain 0.05: reflections about as loud as the direct sound (a few metres away in a furnished room). */
  static float[] reverb(float[] x,double rt60,double gain,Random rnd){
    int n=(int)(rt60*16000);float[] h=new float[n];h[0]=1;
    for(int i=1;i<n;i++)h[i]=(float)(rnd.nextGaussian()*gain*Math.pow(10,-3.0*i/n));
    float[] y=new float[x.length+n];
    for(int i=0;i<x.length;i++){float v=x[i];if(v==0)continue;for(int k=0;k<n;k+=1)y[i+k]+=v*h[k];}
    return scaleTo(Arrays.copyOf(y,x.length),rms(x));
  }
  static final class Result { long ownerSamples,silent;int owners,unknowns,checks;boolean exact=true;List<Double> ownerAt=new ArrayList<>(),endAt=new ArrayList<>();List<String> events=new ArrayList<>();List<Double> scores=new ArrayList<>(); }
  static int endMs=800;
  static VoiceGate gate(SpeakerProfile p,double t) throws IOException {
    SileroVad vad;try(InputStream in=new FileInputStream(new File(models,SileroVad.ASSET))){vad=new SileroVad(in);}
    return new VoiceGate(vad,w->p.score(enc.embed(w)),t,endMs);
  }
  /** Streams audio through a fresh gate in 1024-sample frames, as the WebView does. */
  static Result run(SpeakerProfile p,double t,float[] audio) throws IOException {
    VoiceGate g=gate(p,t);short[] pcm=shorts(audio);Result r=new Result();long[] at={0};
    // Owner audio must be exactly the input samples (each 32 ms chunk found in the input).
    VoiceGate.Output out=new VoiceGate.Output(){
      @Override public void owner(short[] x){r.ownerSamples+=x.length;if(!contains(pcm,x))r.exact=false;}
      @Override public void silence(int n){r.silent+=n;}
      @Override public void event(String n,double sc){
        double sec=at[0]/16000.0;r.scores.add(sc);r.events.add(String.format(Locale.ROOT,"%s@%.1fs",n,sec));
        if("owner".equals(n)){r.owners++;r.ownerAt.add(sec);}if("end".equals(n))r.endAt.add(sec);
        if("unknown".equals(n)||"unclear".equals(n)||"low".equals(n)||"lost".equals(n))r.unknowns++;}
    };
    short[] buf=new short[1024];
    for(int i=0;i<pcm.length;i+=1024){int n=Math.min(1024,pcm.length-i);System.arraycopy(pcm,i,buf,0,n);at[0]=i+n;g.push(buf,n,out);}
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
