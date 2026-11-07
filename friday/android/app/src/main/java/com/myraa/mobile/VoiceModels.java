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
  private VoiceModels(){}

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
