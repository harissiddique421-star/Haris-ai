package com.myraa.mobile;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the on-device voice models from the APK once: Silero VAD (speech detection) and the
 * GE2E speaker encoder (voiceprints). If a model is missing or damaged, callers get null and
 * fall back to the earlier behaviour (energy-based speech detection, or the basic voice check).
 */
final class VoiceModels {
  private static byte[] vadWeights;private static boolean vadFailed;
  private static VoiceEncoder encoder;private static boolean encoderFailed;
  private static boolean preloadStarted;
  private VoiceModels(){}

  /**
   * Loads, on a low-priority background thread, the voice models this phone will need, so the first
   * Live call, Voice ID check or wake phrase does not wait for them: the speech detector, the speaker
   * model of the saved Voice ID profile, and Voice Guardian's model and wake phrase spotter when it is on.
   * Each model also runs once on a little noise, so the first real check is as fast as the later ones.
   * Safe to call often: it runs once per app process.
   */
  static void preload(Context c){
    final Context app=c.getApplicationContext();
    synchronized(VoiceModels.class){if(preloadStarted)return;preloadStarted=true;}
    Thread t=new Thread(()->{
      try{
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        long t0=android.os.SystemClock.elapsedRealtime();
        vad(app);
        java.util.Set<Voiceprinter> warm=new java.util.HashSet<>();
        SpeakerProfile p=VoiceId.registered(app)?VoiceId.load(app):null;
        if(p!=null){Voiceprinter v=voiceprinter(app,p.model);if(v!=null)warm.add(v);}
        if(VoiceGuardian.active(app)){
          Voiceprinter v=VoiceGuardian.hasTitaNet(app)?SherpaVoice.titanet(app):encoder(app);if(v!=null)warm.add(v);
          SherpaVoice.spotter(app,WakeWordService.sensitivity(app));
        }
        float[] noise=new float[16000];java.util.Random r=new java.util.Random(7);for(int i=0;i<noise.length;i++)noise[i]=(float)(r.nextGaussian()*0.01);
        for(Voiceprinter v:warm)try{v.embed(noise);}catch(RuntimeException ignored){}
        ControlLog.i("voice: models ready in the background in "+(android.os.SystemClock.elapsedRealtime()-t0)+" ms");
      }catch(Throwable e){ControlLog.i("voice: background model loading stopped ("+e.getClass().getSimpleName()+")");}
    },"friday-voice-preload");
    t.setDaemon(true);
    t.start();
  }

  /** False when a model failed to load in this app process (FRIDAY then uses weaker fallbacks). */
  static synchronized boolean vadOk(){return !vadFailed;}
  static synchronized boolean encoderOk(){return !encoderFailed;}
  /** Self-healing: forgets a failed load and loads the models again. True when both load now. */
  static boolean reload(Context c){
    synchronized(VoiceModels.class){vadFailed=false;encoderFailed=false;vadWeights=null;}
    boolean ok=vad(c)!=null&&encoder(c)!=null;
    ControlLog.i("voice: models loaded again ("+(ok?"ok":"still failing")+")");
    return ok;
  }
  /** A new Silero VAD for one audio stream (each stream has its own state), or null. */
  static synchronized SileroVad vad(Context c){
    if(vadFailed)return null;
    try{
      if(vadWeights==null)vadWeights=read(c,SileroVad.ASSET);
      return new SileroVad(new ByteArrayInputStream(vadWeights));
    }catch(IOException|RuntimeException e){vadFailed=true;ControlLog.i("voice: speech detector model unavailable ("+e.getClass().getSimpleName()+"), using the energy detector");return null;}
  }
  /** The shared speaker encoder (stateless, safe to share between threads), or null. */
  static synchronized VoiceEncoder encoder(Context c){
    if(encoder!=null||encoderFailed)return encoder;
    try(InputStream in=c.getAssets().open(VoiceEncoder.ASSET)){encoder=new VoiceEncoder(in);}
    catch(IOException|RuntimeException e){encoderFailed=true;ControlLog.i("voice: speaker model unavailable ("+e.getClass().getSimpleName()+")");}
    return encoder;
  }
  /**
   * The best speaker model on this phone for a new voice profile: TitaNet when sherpa-onnx runs
   * here, otherwise GE2E. Null when neither loads.
   */
  static Voiceprinter bestVoiceprinter(Context c){TitaNet t=SherpaVoice.titanet(c);return t!=null?t:encoder(c);}
  /** The speaker model that made a saved voice (its model name), or null when it cannot run here. */
  static Voiceprinter voiceprinter(Context c,String model){
    if(Voiceprinter.TITANET.equals(model))return SherpaVoice.titanet(c);
    return VoiceEncoder.MODEL.equals(model)?encoder(c):null;
  }
  /** True only when loading the speaker model was tried and failed (never loads it: cheap enough for app start). */
  static synchronized boolean encoderFailed(){return encoderFailed;}
  private static byte[] read(Context c,String name) throws IOException {
    try(InputStream in=c.getAssets().open(name)){
      ByteArrayOutputStream out=new ByteArrayOutputStream(1<<20);byte[] buf=new byte[1<<16];int n;
      while((n=in.read(buf))>0)out.write(buf,0,n);
      return out.toByteArray();
    }
  }
}
