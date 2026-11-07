package com.myraa.mobile;

import java.io.*;
import java.util.*;

/**
 * Scenario checks for SpeechSegmenter with the real Silero VAD weights, on synthetic speech
 * (espeak-ng) mixed with generated noise. Run with scripts/test-voice-models.sh.
 * args: models dir, fixtures dir (r16_s_0.wav = a sentence, r16_ow_0.wav = "Wake up FRIDAY").
 */
public final class SpeechSegmenterCheck {
  static final int R=16000;static int failures;static SileroVad vad;
  public static void main(String[] a) throws Exception {
    try(InputStream in=new FileInputStream(new File(a[0],SileroVad.ASSET))){vad=new SileroVad(in);}
    // The synthesizer leaves silence around each clip: trim it so the pauses below are exact.
    float[] sentence=trim(VoiceModelsCheck.readWav(new File(a[1],"r16_s_0.wav"))),wake=trim(VoiceModelsCheck.readWav(new File(a[1],"r16_ow_0.wav")));
    Random rnd=new Random(7);
    check("quiet room: one sentence",run(cat(sil(1),sentence,sil(1.5)),800,8000),1,sentence.length);
    check("400 ms pause inside a sentence is not cut",run(cat(sil(1),sentence,sil(0.4),sentence,sil(1.5)),800,15000),1,-1);
    check("1.2 s pause splits two sentences",run(cat(sil(1),sentence,sil(1.2),sentence,sil(1.5)),800,15000),2,-1);
    check("700 ms setting splits an 800 ms pause",run(cat(sil(1),wake,sil(0.8),wake,sil(1.5)),700,8000),2,-1);
    check("900 ms setting keeps an 800 ms pause together",run(cat(sil(1),wake,sil(0.8),wake,sil(1.5)),900,8000),1,-1);
    check("white noise only (no speech)",run(add(sil(6),noise(6,0.02,rnd,false)),800,8000),0,-1);
    check("fan-like low rumble only (no speech)",run(add(sil(6),noise(6,0.08,rnd,true)),800,8000),0,-1);
    float[] mixed=cat(sil(1),sentence,sil(1.5));
    check("sentence with fan-like noise at 10 dB SNR",run(add(mixed,scaleTo(noise(mixed.length/(double)R,1,rnd,true),rms(sentence)/Math.pow(10,10/20.0))),800,8000),1,-1);
    check("sentence with white noise at 10 dB SNR",run(add(mixed,scaleTo(noise(mixed.length/(double)R,1,rnd,false),rms(sentence)/Math.pow(10,10/20.0))),800,8000),1,-1);
    // Background TV / conversation: sentences back to back. With a 5 s limit nothing is returned,
    // then the wake phrase after a quiet second is still caught.
    float[] tv=cat(sentence,sil(0.2),sentence,sil(0.25),sentence,sil(0.2),sentence);
    SpeechSegmenter seg=new SpeechSegmenter(vad,800,5000);vad.reset();
    List<short[]> got=all(seg,cat(sil(0.5),tv,sil(1.2),wake,sil(1.5)));
    check("continuous background talk is dropped, wake phrase after it is caught",got,1,wake.length);
    if(seg.droppedLong<1){System.out.println("  expected the long stretch to be counted as dropped");failures++;}
    // Two voices overlapping (sentence over a shifted copy at -6 dB) for 9 s: also dropped as too long.
    float[] babble=add(cat(sentence,sentence),scaleTo(cat(sil(0.7),sentence,sentence),rms(sentence)/2));
    check("two people talking over each other for 9 s is dropped",run(cat(sil(0.5),babble,sil(1.5)),800,5000),0,-1);
    // Energy fallback still works without the model.
    SpeechSegmenter energy=new SpeechSegmenter(null,800,8000);
    check("energy fallback (no model): one sentence",all(energy,cat(sil(1),sentence,sil(1.5))),1,-1);
    if(failures>0){System.out.println(failures+" scenario check(s) failed");System.exit(1);}
    System.out.println("All speech segmentation scenarios passed.");
  }
  static List<short[]> run(float[] audio,int silenceMs,int maxMs){vad.reset();return all(new SpeechSegmenter(vad,silenceMs,maxMs),audio);}
  static List<short[]> all(SpeechSegmenter seg,float[] audio){
    final short[] pcm=new short[audio.length];for(int i=0;i<pcm.length;i++)pcm[i]=(short)Math.max(-32768,Math.min(32767,Math.round(audio[i]*32768)));
    final int[] pos={0};List<short[]> out=new ArrayList<>();
    SpeechSegmenter.Source src=chunk->{if(pos[0]+chunk.length>pcm.length)return false;System.arraycopy(pcm,pos[0],chunk,0,chunk.length);pos[0]+=chunk.length;return true;};
    while(true){short[] b=seg.next(src,()->false,0);if(b==null)break;out.add(b);}
    return out;
  }
  static void check(String name,List<short[]> bursts,int expect,int expectLen){
    boolean ok=bursts.size()==expect;StringBuilder lens=new StringBuilder();
    for(short[] b:bursts)lens.append(String.format(Locale.ROOT,"%.2fs ",b.length/(double)R));
    if(ok&&expectLen>0&&expect==1){double d=bursts.get(0).length-expectLen;ok=d>-0.15*R&&d<0.6*R;} // whole utterance kept, little extra
    if(!ok)failures++;
    System.out.printf(Locale.ROOT,"%-70s bursts %d (expected %d) %s %s%n",name,bursts.size(),expect,lens,ok?"OK":"FAIL");
  }
  static float[] trim(float[] a){int s=0,e=a.length;while(s<e&&Math.abs(a[s])<0.01f)s++;while(e>s&&Math.abs(a[e-1])<0.01f)e--;return Arrays.copyOfRange(a,s,e);}
  static float[] sil(double s){return new float[(int)(s*R)];}
  static float[] cat(float[]... parts){int n=0;for(float[] p:parts)n+=p.length;float[] o=new float[n];int at=0;for(float[] p:parts){System.arraycopy(p,0,o,at,p.length);at+=p.length;}return o;}
  static float[] add(float[] a,float[] b){float[] o=new float[Math.max(a.length,b.length)];for(int i=0;i<o.length;i++)o[i]=(i<a.length?a[i]:0)+(i<b.length?b[i]:0);return o;}
  /** White noise, or a fan-like rumble (integrated noise with a leak, low-frequency heavy). */
  static float[] noise(double s,double amp,Random r,boolean rumble){float[] o=new float[(int)(s*R)];double y=0;for(int i=0;i<o.length;i++){double w=r.nextGaussian();if(rumble){y=0.995*y+0.1*w;o[i]=(float)(amp*y);}else o[i]=(float)(amp*w);}return o;}
  static double rms(float[] a){double t=0;for(float v:a)t+=v*v;return Math.sqrt(t/a.length);}
  static float[] scaleTo(float[] a,double target){double k=target/Math.max(1e-9,rms(a));float[] o=new float[a.length];for(int i=0;i<a.length;i++)o[i]=(float)(a[i]*k);return o;}
}
