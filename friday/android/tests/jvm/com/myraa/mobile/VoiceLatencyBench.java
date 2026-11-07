package com.myraa.mobile;

import static com.myraa.mobile.SpeechSegmenterCheck.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Measures how long the phone's Voice ID pipeline takes to hand a finished owner command to Gemini,
 * in real time: microphone frames (1024 samples, 64 ms) are fed to VoiceGateSession on the WebView's
 * schedule, and the wall-clock time from the frame that carries the owner's last word to the frame
 * whose answer says "end" is the phone's share of the reply delay. Gemini's own time is measured on
 * the phone by the call's timing log, not here.
 *
 * slow=N runs each voiceprint check N times as long (a phone slower than this computer).
 *
 * Usage: VoiceLatencyBench <models dir> <fixtures/voiceid> <endpoint ms> [slow factor] [repeats]
 */
public final class VoiceLatencyBench {
  public static void main(String[] a) throws Exception {
    File models=new File(a[0]),fx=new File(a[1]);int endMs=Integer.parseInt(a[2]);
    double slow=a.length>3?Double.parseDouble(a[3]):1;int reps=a.length>4?Integer.parseInt(a[4]):3;
    VoiceEncoder enc;try(InputStream in=new FileInputStream(new File(models,VoiceEncoder.ASSET))){enc=new VoiceEncoder(in);}
    List<float[]> full=new ArrayList<>(),shortPrints=new ArrayList<>();
    for(int i=1;i<=5;i++){
      float[] w=VoiceModelsCheck.readWav(new File(fx,"owner_enroll_"+i+".wav"));full.add(enc.embed(w));
      shortPrints.add(enc.embed(Arrays.copyOf(w,Math.min(w.length,SpeakerProfile.CHECK_MS*16+10*SileroVad.CHUNK))));
    }
    SpeakerProfile p=SpeakerProfile.build(full,shortPrints,1);double t=p.threshold("balanced");
    float[] owner1=wav(fx,"owner_test_1"),owner2=wav(fx,"owner_test_2");
    // A short command: the first 1.1 s of a sentence.
    float[] shortCmd=cutShort(trim(owner2),17600);
    float[] tv=cat(wav(fx,"tv_1"),wav(fx,"tv_2"),wav(fx,"tv_3"));
    float[] room=add(tv,cat(sil(1.5),wav(fx,"friend_1"),sil(0.3),wav(fx,"friend_2"),sil(0.3),wav(fx,"friend_1")));
    float[] babble=scaleTo(cat(room,room),rms(owner1)*Math.pow(10,-12/20.0));
    // Warm the JIT the way a running phone app is warm (the models are loaded once per app start).
    for(int i=0;i<3;i++)enc.embed(Arrays.copyOf(owner1,24000));

    System.out.printf(Locale.ROOT,"endpoint %d ms, voice checks x%.1f, %d runs each (milliseconds from the owner's last word to \"end\")%n",endMs,slow,reps);
    scenario("short command (%.1f s), quiet room",shortCmd,null,p,t,enc,models,endMs,slow,reps);
    scenario("sentence (%.1f s), quiet room",owner1,null,p,t,enc,models,endMs,slow,reps);
    scenario("short command (%.1f s), 3 people talking behind",shortCmd,babble,p,t,enc,models,endMs,slow,reps);
    scenario("sentence (%.1f s), 3 people talking behind",owner1,babble,p,t,enc,models,endMs,slow,reps);
    scenario("short command (%.1f s) started while someone behind is mid-sentence",1.0,shortCmd,babble,p,t,enc,models,endMs,slow,reps);
    System.exit(0);
  }

  static void scenario(String label,float[] cmd,float[] bg,SpeakerProfile p,double t,VoiceEncoder enc,File models,int endMs,double slow,int reps) throws Exception {
    scenario(label,1.5,cmd,bg,p,t,enc,models,endMs,slow,reps);
  }
  static void scenario(String label,double start,float[] cmd,float[] bg,SpeakerProfile p,double t,VoiceEncoder enc,File models,int endMs,double slow,int reps) throws Exception {
    float[] audio=cat(sil(start),cmd,sil(2.5));
    if(bg!=null)audio=add(Arrays.copyOf(bg,audio.length),audio);
    long ownerEnd=(long)(start*16000)+lastVoiced(cmd);
    List<Long> lat=new ArrayList<>();List<Long> checks=new ArrayList<>();int missed=0;
    for(int r=0;r<reps;r++){
      long[] res=runRealtime(audio,ownerEnd,p,t,enc,models,endMs,slow);
      if(res[0]<0)missed++;else lat.add(res[0]);checks.add(res[1]);
    }
    System.out.printf(Locale.ROOT,"  %-52s end after %s ms%s  (last voice check %s ms)%n",String.format(Locale.ROOT,label,cmd.length/16000.0),
      lat.isEmpty()?"-":median(lat)+" (runs "+lat+")",missed>0?", no owner turn in "+missed+" run(s) (gate said: "+lastOther+")":"",median(checks));
  }

  /** Returns {ms from the owner's last word reaching the phone to the "end" answer (-1 = none), last check ms}. */
  static long[] runRealtime(float[] audio,long ownerEnd,SpeakerProfile p,double t,VoiceEncoder enc,File models,int endMs,double slow) throws Exception {
    SileroVad vad;try(InputStream in=new FileInputStream(new File(models,SileroVad.ASSET))){vad=new SileroVad(in);}
    VoiceGate gate=new VoiceGate(vad,w->{
      long s=System.nanoTime();double sc=p.score(enc.embed(w));
      long took=System.nanoTime()-s;if(slow>1)sleepNanos((long)(took*(slow-1)));
      return sc;},t,endMs);
    VoiceGateSession session=new VoiceGateSession(gate);
    lastOther="";short[] pcm=shorts(audio);long t0=System.nanoTime(),ownerEndWall=-1,endWall=-1;
    try{
      int frames=(pcm.length+1023)/1024;
      for(int f=0;f<frames+40&&endWall<0;f++){
        long due=t0+f*64_000_000L;sleepNanos(due-System.nanoTime());
        String b64="";
        if(f<frames){
          int i=f*1024,n=Math.min(1024,pcm.length-i);java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(n*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
          for(int k=0;k<n;k++)b.putShort(pcm[i+k]);b64=Base64.getEncoder().encodeToString(b.array());
          // The frame holding the owner's last word is complete (and handed over) at the end of its 64 ms.
          if(ownerEndWall<0&&i+n>=ownerEnd)ownerEndWall=System.nanoTime();
        }
        JSONObject o=new JSONObject(session.feed(b64,0));
        JSONArray e=o.optJSONArray("e");
        if(e!=null)for(int k=0;k<e.length();k++){String name=e.getJSONArray(k).getString(0);if(!"end".equals(name)&&!"owner".equals(name))lastOther=name;
          if(ownerEndWall>=0&&"end".equals(name)){endWall=System.nanoTime();break;}}
      }
    }finally{session.stop();}
    return new long[]{endWall<0?-1:(endWall-ownerEndWall)/1_000_000,gate.checkMs};
  }

  static String lastOther="";
  static long median(List<Long> v){List<Long> s=new ArrayList<>(v);java.util.Collections.sort(s);return s.isEmpty()?-1:s.get(s.size()/2);}
  static void sleepNanos(long n){if(n<=0)return;try{Thread.sleep(n/1_000_000,(int)(n%1_000_000));}catch(InterruptedException e){Thread.currentThread().interrupt();}}
  static float[] wav(File fx,String name) throws Exception {return VoiceModelsCheck.readWav(new File(fx,name+".wav"));}
  static short[] shorts(float[] f){short[] s=new short[f.length];for(int i=0;i<f.length;i++)s[i]=(short)Math.max(-32768,Math.min(32767,Math.round(f[i]*32768)));return s;}
  /** Index just after the last 32 ms chunk louder than -40 dB of the clip's level. */
  static long lastVoiced(float[] w){
    double ref=rms(w)*0.1;int c=SileroVad.CHUNK;
    for(int i=(w.length/c-1)*c;i>=0;i-=c){double e=0;for(int k=0;k<c;k++)e+=w[i+k]*w[i+k];if(Math.sqrt(e/c)>ref)return i+c;}
    return w.length;
  }
  /** The first `around` samples of speech with a 30 ms fade-out, like a short spoken command. */
  static float[] cutShort(float[] w,int around){
    float[] o=Arrays.copyOf(w,Math.min(around,w.length));int f=480;
    for(int i=0;i<f;i++)o[o.length-f+i]*=1-(i+1)/(float)f;
    return o;
  }
}
