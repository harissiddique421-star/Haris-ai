package com.myraa.mobile;

import java.util.Arrays;

/**
 * Removes steady background noise (fan, AC, traffic hum, hiss) from 16 kHz speech before Voice ID
 * compares it with the owner's voiceprint. Plain Java, no Android, so it is checked on a JVM.
 *
 * The noise spectrum is learnt from audio the speech detector heard as no speech (learn), and each
 * voice check then gets a spectral-subtraction copy of its audio (clean): 32 ms frames, half
 * overlap, each frequency band turned down by how much of it is the learnt noise, never below a
 * floor so the voice does not sound broken. It does not touch what Gemini hears, only the copy used
 * for the voice check. It cannot remove other people's voices or a TV (those are not steady noise);
 * the voiceprint itself handles those.
 */
final class NoiseReducer {
  static final int N=512,HOP=256,BINS=N/2+1;
  /** How strongly the noise estimate is subtracted, and the lowest gain a band may get. */
  static final double OVER=2.0,FLOOR=0.12;
  /**
   * A score on the noise-removed copy counts this much less than one on the audio as recorded:
   * removing noise also smooths away a little of what makes voices different, and in the checks one
   * other person's voice reached the threshold on a cleaned copy without this.
   */
  static final double PENALTY=0.03;
  /** Noise below this level (RMS of 16-bit samples) is too quiet to matter: no subtraction. */
  static final double QUIET=60;
  private static final double[] WINDOW=new double[N];
  static{for(int i=0;i<N;i++)WINDOW[i]=Math.sqrt(0.5-0.5*Math.cos(2*Math.PI*i/N));}

  private final double[] noise=new double[BINS];
  private int frames;
  private double noiseRms;
  private final float[] tail=new float[N];private int tailLen;

  /** Feeds audio that contains no speech (floats -1..1). Keeps a slowly moving average of its spectrum. */
  void learn(float[] x){
    if(tailLen+x.length<N){System.arraycopy(x,0,tail,tailLen,x.length);tailLen+=x.length;return;}
    float[] all=new float[tailLen+x.length];System.arraycopy(tail,0,all,0,tailLen);System.arraycopy(x,0,all,tailLen,x.length);
    int at=0;double[] re=new double[N],im=new double[N];
    for(;at+N<=all.length;at+=HOP){
      double e=0;for(int i=0;i<N;i++){re[i]=all[at+i]*WINDOW[i];im[i]=0;e+=all[at+i]*all[at+i];}
      fft(re,im);
      double a=frames<20?1.0/(frames+1):0.05;
      for(int k=0;k<BINS;k++)noise[k]=(1-a)*noise[k]+a*(re[k]*re[k]+im[k]*im[k]);
      noiseRms=(1-a)*noiseRms+a*Math.sqrt(e/N)*32768;frames++;
    }
    tailLen=all.length-at;System.arraycopy(all,at,tail,0,tailLen);
  }
  /** True once enough noise has been heard (about a third of a second) and it is loud enough to matter. */
  boolean active(){return frames>=8&&noiseRms>=QUIET;}
  double noiseLevel(){return noiseRms;}
  void reset(){Arrays.fill(noise,0);frames=0;noiseRms=0;tailLen=0;}

  /** A copy of x with the learnt noise turned down; x itself when no noise was learnt. */
  float[] clean(float[] x){
    if(!active()||x.length<N)return x;
    float[] y=new float[x.length];double[] re=new double[N],im=new double[N];
    for(int at=0;at+N<=x.length;at+=HOP){
      for(int i=0;i<N;i++){re[i]=x[at+i]*WINDOW[i];im[i]=0;}
      fft(re,im);
      for(int k=0;k<BINS;k++){
        double p=re[k]*re[k]+im[k]*im[k];
        double g=p<=0?FLOOR:Math.max(FLOOR,1-OVER*noise[k]/p);
        re[k]*=g;im[k]*=g;
        if(k>0&&k<N/2){re[N-k]=re[k];im[N-k]=-im[k];}
      }
      // Inverse FFT through the forward one: conjugate, transform, conjugate, scale.
      for(int i=0;i<N;i++)im[i]=-im[i];
      fft(re,im);
      for(int i=0;i<N;i++)y[at+i]+=(float)(re[i]/N*WINDOW[i]);
    }
    return y;
  }

  /** In-place radix-2 FFT (N a power of two). */
  static void fft(double[] re,double[] im){
    int n=re.length;
    for(int i=1,j=0;i<n;i++){int bit=n>>1;for(;(j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double t=re[i];re[i]=re[j];re[j]=t;t=im[i];im[i]=im[j];im[j]=t;}}
    for(int len=2;len<=n;len<<=1){
      double ang=-2*Math.PI/len,wr=Math.cos(ang),wi=Math.sin(ang);
      for(int i=0;i<n;i+=len){
        double cr=1,ci=0;
        for(int k=0;k<len/2;k++){
          int a=i+k,b=a+len/2;
          double xr=re[b]*cr-im[b]*ci,xi=re[b]*ci+im[b]*cr;
          re[b]=re[a]-xr;im[b]=im[a]-xi;re[a]+=xr;im[a]+=xi;
          double t=cr*wr-ci*wi;ci=cr*wi+ci*wr;cr=t;
        }
      }
    }
  }
}
