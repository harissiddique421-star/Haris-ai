package com.myraa.mobile;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The sherpa-onnx voice engines on the phone: TitaNet voiceprints (Voice ID and Voice Guardian)
 * and keyword spotting for the wake phrase. The native library (libsherpa-onnx-jni.so with
 * onnxruntime, arm64-v8a and armeabi-v7a) and the models are packed into the APK by
 * scripts/build-android.sh. The models are copied once per install from the APK to the app's
 * private storage, because the native library reads files.
 *
 * Everything is optional: when the library or a model cannot be loaded (an x86 phone, a build
 * without them, a damaged copy), callers get null and FRIDAY keeps using its built-in GE2E
 * voiceprint and recorded-phrase match, exactly as before.
 */
final class SherpaVoice {
  static final String ASSETS="sherpa";
  private static boolean nativeTried,nativeOk,titaTried,spotTried;
  private static TitaNet titanet;
  private static WakeSpotter spotter;private static float spotterThreshold;
  private SherpaVoice(){}

  private static synchronized boolean nativeReady(){
    if(nativeTried)return nativeOk;
    nativeTried=true;
    try{System.loadLibrary("sherpa-onnx-jni");nativeOk=true;}
    catch(Throwable t){ControlLog.i("voice: sherpa-onnx engine unavailable on this phone ("+t.getClass().getSimpleName()+"), using the built-in voice models");}
    return nativeOk;
  }
  /** Threads for one model call: two on phones with four or more cores. */
  private static int threads(){return Runtime.getRuntime().availableProcessors()>=4?2:1;}

  /** Cheap check (loads nothing): this APK carries the engines and the phone's processor can run them. */
  static boolean present(Context c){
    if(nativeTried&&!nativeOk)return false;
    boolean abi=false;for(String a:android.os.Build.SUPPORTED_ABIS)if("arm64-v8a".equals(a)||"armeabi-v7a".equals(a))abi=true;
    if(!abi)return false;
    try{String[] l=c.getAssets().list(ASSETS);if(l!=null)for(String n:l)if(TitaNet.FILE.equals(n))return true;}catch(IOException ignored){}
    return false;
  }

  /** The TitaNet speaker model, or null when it cannot run on this phone. */
  static synchronized TitaNet titanet(Context c){
    if(titanet!=null||titaTried)return titanet;
    titaTried=true;
    if(!nativeReady())return null;
    try{File f=install(c,TitaNet.FILE);if(f!=null){titanet=new TitaNet(f,threads());ControlLog.i("voice: TitaNet speaker model ready ("+titanet.dim()+" numbers)");}}
    catch(Throwable t){ControlLog.i("voice: TitaNet speaker model unavailable ("+t.getClass().getSimpleName()+")");}
    return titanet;
  }

  /** The wake phrase keyword spotter for this sensitivity, or null when it cannot run on this phone. */
  static synchronized WakeSpotter spotter(Context c,String sensitivity){
    float th=WakeSpotter.threshold(sensitivity);
    if(spotter!=null&&spotterThreshold==th)return spotter;
    if(spotter==null&&spotTried)return null;
    spotTried=true;
    if(!nativeReady())return null;
    try{
      File dir=null;
      for(String n:new String[]{"kws/encoder.onnx","kws/decoder.onnx","kws/joiner.onnx","kws/tokens.txt","kws/keywords.txt"}){File f=install(c,n);if(f==null)return spotter=null;dir=f.getParentFile();}
      spotter=new WakeSpotter(dir,1,th);spotterThreshold=th;
      ControlLog.i("voice: wake phrase spotter ready ("+sensitivity+")");
    }catch(Throwable t){spotter=null;ControlLog.i("voice: wake phrase spotter unavailable ("+t.getClass().getSimpleName()+")");}
    return spotter;
  }

  /** Copies assets/sherpa/name to private storage once per app install or update; null when the APK has no such model. */
  private static File install(Context c,String name) throws IOException {
    long stamp;
    try{stamp=c.getPackageManager().getPackageInfo(c.getPackageName(),0).lastUpdateTime;}catch(Exception e){stamp=0;}
    File out=new File(new File(c.getFilesDir(),ASSETS),name),mark=new File(out.getPath()+".stamp");
    if(out.isFile()&&mark.isFile()&&readStamp(mark)==stamp)return out;
    InputStream in;
    try{in=c.getAssets().open(ASSETS+"/"+name);}catch(IOException e){return null;}
    File dir=out.getParentFile();if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("mkdir");
    File tmp=new File(out.getPath()+".tmp");
    try(InputStream i=in;OutputStream o=new FileOutputStream(tmp)){byte[] b=new byte[1<<16];int n;while((n=i.read(b))>0)o.write(b,0,n);}
    if(!tmp.renameTo(out)){tmp.delete();throw new IOException("rename");}
    try(OutputStream o=new FileOutputStream(mark)){o.write(Long.toString(stamp).getBytes("UTF-8"));}
    return out;
  }
  private static long readStamp(File f){
    try(InputStream i=new java.io.FileInputStream(f)){byte[] b=new byte[32];int n=i.read(b);return Long.parseLong(new String(b,0,Math.max(0,n),"UTF-8").trim());}
    catch(Exception e){return -1;}
  }
}
