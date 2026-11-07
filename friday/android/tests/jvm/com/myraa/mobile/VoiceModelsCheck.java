package com.myraa.mobile;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/**
 * Plain-JVM check of the on-device voice models against independent reference outputs
 * (official Silero ONNX in onnxruntime, librosa mel spectrogram, GE2E run through
 * onnxruntime's LSTM). Run with scripts/test-voice-models.sh. Exits non-zero on any mismatch.
 *
 * args: models dir, fixtures dir (wav files + reference.json)
 */
public final class VoiceModelsCheck {
  public static void main(String[] args) throws Exception {
    File models=new File(args[0]),fixtures=new File(args[1]);
    SileroVad vad;VoiceEncoder enc;
    try(InputStream in=new FileInputStream(new File(models,SileroVad.ASSET))){vad=new SileroVad(in);}
    try(InputStream in=new FileInputStream(new File(models,VoiceEncoder.ASSET))){enc=new VoiceEncoder(in);}
    JSONObject ref=new JSONObject(new String(Files.readAllBytes(new File(fixtures,"reference.json").toPath()),"UTF-8"));
    int failures=0;
    for(String name:new TreeSet<>(ref.keySet())){
      JSONObject r=ref.getJSONObject(name);float[] wav=readWav(new File(fixtures,name));
      // Silero VAD probabilities, chunk by chunk.
      vad.reset();JSONArray pv=r.getJSONArray("vad");double maxVad=0;float[] chunk=new float[SileroVad.CHUNK];
      long t0=System.nanoTime();
      for(int i=0;i<pv.length();i++){System.arraycopy(wav,i*512,chunk,0,512);maxVad=Math.max(maxVad,Math.abs(vad.process(chunk)-pv.getDouble(i)));}
      double vadMs=(System.nanoTime()-t0)/1e6/Math.max(1,pv.length());
      // Mel spectrogram, first frames, relative error.
      float[][] mel=VoiceEncoder.melSpectrogram(wav);JSONArray m0=r.getJSONArray("mel0");double maxMel=0;
      for(int f=0;f<m0.length();f++){JSONArray row=m0.getJSONArray(f);for(int k=0;k<row.length();k++){double a=row.getDouble(k),b=mel[f][k];maxMel=Math.max(maxMel,Math.abs(a-b)/(Math.abs(a)+1e-4));}}
      // Speaker embedding cosine against the reference embedding.
      t0=System.nanoTime();float[] e=enc.embed(wav);double encMs=(System.nanoTime()-t0)/1e6;
      JSONArray re=r.getJSONArray("embed");float[] rv=new float[re.length()];for(int i=0;i<rv.length;i++)rv[i]=(float)re.getDouble(i);
      double cos=VoiceEncoder.cosine(e,rv);
      boolean ok=maxVad<1e-3&&maxMel<1e-3&&cos>0.9999;
      if(!ok)failures++;
      System.out.printf(Locale.ROOT,"%-18s vad max|diff| %.2e (%.2f ms/chunk)  mel max rel %.2e  embed cosine %.6f (%.0f ms for %.1f s)  %s%n",name,maxVad,vadMs,maxMel,cos,encMs,wav.length/16000.0,ok?"OK":"MISMATCH");
    }
    if(failures>0){System.out.println(failures+" file(s) did not match the reference");System.exit(1);}
    System.out.println("All voice model outputs match the reference.");
  }
  static float[] readWav(File f) throws IOException {
    byte[] b=Files.readAllBytes(f.toPath());ByteBuffer bb=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    int pos=12,dataOff=-1,dataLen=0;
    while(pos+8<=b.length){String id=new String(b,pos,4,"US-ASCII");int len=bb.getInt(pos+4);
      if(id.equals("fmt ")){if(bb.getShort(pos+8)!=1||bb.getShort(pos+10)!=1||bb.getInt(pos+12)!=16000||bb.getShort(pos+22)!=16)throw new IOException(f+": need 16 kHz mono PCM16");}
      if(id.equals("data")){dataOff=pos+8;dataLen=Math.min(len,b.length-dataOff);break;}
      pos+=8+len+(len&1);}
    if(dataOff<0)throw new IOException(f+": no data");
    float[] out=new float[dataLen/2];for(int i=0;i<out.length;i++)out[i]=bb.getShort(dataOff+2*i)/32768f;return out;
  }
}
