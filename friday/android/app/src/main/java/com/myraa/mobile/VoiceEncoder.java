package com.myraa.mobile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Speaker embedding on the phone: the GE2E speaker encoder released with Resemblyzer
 * (Apache-2.0; weights in assets/voice_encoder.safetensors), run in plain Java.
 *
 * It turns speech into a 256-number "voiceprint" that describes who is speaking, not what is
 * said. Two voiceprints are compared by cosine similarity (1 = identical direction). This is
 * speaker identification, separate from speech recognition.
 *
 * Front end and network match Resemblyzer: 40-band Slaney mel power spectrogram (25 ms Hann
 * window, 10 ms hop, centred with reflect padding), partial windows of 160 frames taken
 * 1.3 times per second, a 3-layer LSTM (256), a linear layer with ReLU, L2 normalisation, and
 * the normalised mean of the partial embeddings. Audio is first raised to -30 dBFS when quieter.
 */
final class VoiceEncoder {
  static final int RATE=16000,DIM=256;
  static final String ASSET="voice_encoder.safetensors";
  private static final int NFFT=400,HOP=160,MELS=40,PARTIAL=160,HID=256,LAYERS=3;
  private static final double RATE_PER_SEC=1.3,MIN_COVERAGE=0.75,TARGET_DBFS=-30;
  // Slaney mel scale constants; declared before MEL so they are set when melBank() runs.
  private static final double FSP=200.0/3,MIN_LOG_HZ=1000,MIN_LOG_MEL=MIN_LOG_HZ/FSP,LOGSTEP=Math.log(6.4)/27;
  private static final float[] WINDOW=new float[NFFT];
  private static final double[][] COS=new double[NFFT/2+1][NFFT],SIN=new double[NFFT/2+1][NFFT];
  private static final float[][] MEL=melBank();
  static{
    for(int i=0;i<NFFT;i++)WINDOW[i]=(float)(0.5-0.5*Math.cos(2*Math.PI*i/NFFT)); // periodic Hann
    for(int k=0;k<=NFFT/2;k++)for(int n=0;n<NFFT;n++){double a=2*Math.PI*k*n/NFFT;COS[k][n]=Math.cos(a);SIN[k][n]=Math.sin(a);}
  }
  private final float[][] wih=new float[LAYERS][],whh=new float[LAYERS][],bias=new float[LAYERS][];
  private final float[] linW,linB;

  VoiceEncoder(InputStream weights) throws IOException {
    Safetensors s=new Safetensors(weights);
    for(int l=0;l<LAYERS;l++){
      int in=l==0?MELS:HID;
      wih[l]=s.get("lstm.weight_ih_l"+l,4*HID,in);whh[l]=s.get("lstm.weight_hh_l"+l,4*HID,HID);
      float[] bi=s.get("lstm.bias_ih_l"+l,4*HID),bh=s.get("lstm.bias_hh_l"+l,4*HID);
      bias[l]=new float[4*HID];for(int i=0;i<4*HID;i++)bias[l][i]=bi[i]+bh[i];
    }
    linW=s.get("linear.weight",HID,HID);linB=s.get("linear.bias",HID);
  }

  /** Voiceprint of 16 kHz mono speech (floats in -1..1), L2-normalised. Null when there is too little audio. */
  float[] embed(float[] wav){
    if(wav==null||wav.length<RATE/4)return null;
    wav=normalizeVolume(wav);
    // Partial windows, as in Resemblyzer's compute_partial_slices.
    int frames=(int)Math.ceil((wav.length+1)/(double)HOP);
    int step=Math.max(1,(int)Math.round((RATE/RATE_PER_SEC)/HOP));
    List<int[]> slices=new ArrayList<>();
    for(int i=0;i<Math.max(1,frames-PARTIAL+step+1);i+=step)slices.add(new int[]{i,i+PARTIAL});
    int[] last=slices.get(slices.size()-1);
    double coverage=(wav.length-last[0]*HOP)/(double)(PARTIAL*HOP);
    if(coverage<MIN_COVERAGE&&slices.size()>1)slices.remove(slices.size()-1);
    int need=slices.get(slices.size()-1)[1]*HOP;
    float[] padded=wav;
    if(need>wav.length){padded=new float[need];System.arraycopy(wav,0,padded,0,wav.length);}
    float[][] mel=melSpectrogram(padded);
    float[] sum=new float[DIM];
    for(int[] sl:slices){float[] e=forward(mel,sl[0]);for(int i=0;i<DIM;i++)sum[i]+=e[i];}
    return normalize(sum);
  }
  static double cosine(float[] a,float[] b){
    double d=0,na=0,nb=0;for(int i=0;i<a.length;i++){d+=a[i]*b[i];na+=a[i]*a[i];nb+=b[i]*b[i];}
    return na==0||nb==0?0:d/Math.sqrt(na*nb);
  }
  static float[] normalize(float[] v){
    double n=0;for(float x:v)n+=x*x;n=Math.sqrt(n);float[] out=new float[v.length];
    if(n>0)for(int i=0;i<v.length;i++)out[i]=(float)(v[i]/n);
    return out;
  }
  static float[] toFloat(short[] pcm){float[] f=new float[pcm.length];for(int i=0;i<pcm.length;i++)f[i]=pcm[i]/32768f;return f;}

  /** Mel power spectrogram, frames x 40 (librosa.feature.melspectrogram with Resemblyzer's settings). */
  static float[][] melSpectrogram(float[] wav){
    int pad=NFFT/2,n=wav.length,frames=1+n/HOP;
    float[] x=new float[n+2*pad];
    for(int i=0;i<x.length;i++){int j=i-pad;if(j<0)j=-j;if(j>=n)j=2*(n-1)-j;x[i]=n==1?wav[0]:wav[Math.max(0,Math.min(n-1,j))];}
    float[][] out=new float[frames][MELS];double[] power=new double[NFFT/2+1];float[] seg=new float[NFFT];
    for(int f=0;f<frames;f++){
      int base=f*HOP;for(int i=0;i<NFFT;i++)seg[i]=x[base+i]*WINDOW[i];
      for(int k=0;k<=NFFT/2;k++){
        double re=0,im=0;double[] cs=COS[k],sn=SIN[k];
        for(int i=0;i<NFFT;i++){re+=seg[i]*cs[i];im-=seg[i]*sn[i];}
        power[k]=re*re+im*im;
      }
      for(int m=0;m<MELS;m++){double s=0;float[] w=MEL[m];for(int k=0;k<w.length;k++)if(w[k]!=0)s+=w[k]*power[k];out[f][m]=(float)s;}
    }
    return out;
  }
  /** One partial (160 mel frames from start) through the LSTM, linear layer, ReLU and L2 norm. */
  private float[] forward(float[][] mel,int start){
    float[] input=new float[PARTIAL*MELS];
    for(int t=0;t<PARTIAL;t++)System.arraycopy(mel[start+t],0,input,t*MELS,MELS);
    float[] seq=input;int in=MELS;float[] hLast=null;
    for(int l=0;l<LAYERS;l++){
      float[] outSeq=new float[PARTIAL*HID];float[] h=new float[HID],c=new float[HID],g=new float[4*HID];
      float[] wi=wih[l],wh=whh[l],b=bias[l];
      for(int t=0;t<PARTIAL;t++){
        int xo=t*in;
        for(int r=0;r<4*HID;r++){
          double s=b[r];int oi=r*in,oh=r*HID;
          for(int k=0;k<in;k++)s+=wi[oi+k]*seq[xo+k];
          for(int k=0;k<HID;k++)s+=wh[oh+k]*h[k];
          g[r]=(float)s;
        }
        for(int k=0;k<HID;k++){
          float ig=sig(g[k]),fg=sig(g[HID+k]),cg=(float)Math.tanh(g[2*HID+k]),og=sig(g[3*HID+k]);
          c[k]=fg*c[k]+ig*cg;h[k]=og*(float)Math.tanh(c[k]);
        }
        System.arraycopy(h,0,outSeq,t*HID,HID);
      }
      seq=outSeq;in=HID;hLast=h;
    }
    float[] e=new float[DIM];
    for(int i=0;i<DIM;i++){double s=linB[i];int o=i*HID;for(int k=0;k<HID;k++)s+=linW[o+k]*hLast[k];e[i]=(float)Math.max(0,s);}
    return normalize(e);
  }
  private static float sig(float v){return (float)(1/(1+Math.exp(-v)));}
  private static float[] normalizeVolume(float[] wav){
    double ms=0;for(float v:wav)ms+=v*v;ms/=wav.length;if(ms<=0)return wav;
    double change=TARGET_DBFS-10*Math.log10(ms);if(change<0)return wav; // only raise quiet audio
    float k=(float)Math.pow(10,change/20);float[] out=new float[wav.length];
    for(int i=0;i<wav.length;i++)out[i]=wav[i]*k;return out;
  }
  /** librosa.filters.mel(sr=16000, n_fft=400, n_mels=40): Slaney mel scale and area normalisation. */
  private static float[][] melBank(){
    int bins=NFFT/2+1;double[] mels=new double[MELS+2];double lo=hzToMel(0),hi=hzToMel(RATE/2.0);
    for(int i=0;i<mels.length;i++)mels[i]=melToHz(lo+(hi-lo)*i/(MELS+1));
    float[][] w=new float[MELS][bins];
    for(int m=0;m<MELS;m++){
      double l=mels[m],c=mels[m+1],r=mels[m+2],enorm=2.0/(r-l);
      for(int k=0;k<bins;k++){
        double f=k*(RATE/2.0)/(bins-1),lower=(f-l)/(c-l),upper=(r-f)/(r-c);
        w[m][k]=(float)(Math.max(0,Math.min(lower,upper))*enorm);
      }
    }
    return w;
  }
  private static double hzToMel(double hz){return hz>=MIN_LOG_HZ?MIN_LOG_MEL+Math.log(hz/MIN_LOG_HZ)/LOGSTEP:hz/FSP;}
  private static double melToHz(double m){return m>=MIN_LOG_MEL?MIN_LOG_HZ*Math.exp(LOGSTEP*(m-MIN_LOG_MEL)):FSP*m;}
}
