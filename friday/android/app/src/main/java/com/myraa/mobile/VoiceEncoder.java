package com.myraa.mobile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
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
    final float[][] mel=melSpectrogram(padded);
    final float[][] parts=new float[slices.size()][];
    if(parts.length>1&&sliceThreads>=2){
      // Longer audio (the second look, enrollment): the partials are independent, so a phone with
      // enough cores computes them side by side; the sum below stays in order, so the result is the same.
      Thread[] ts=new Thread[parts.length];
      try{
        for(int k=1;k<parts.length;k++){final int kk=k,st=slices.get(k)[0];ts[k]=new Thread(()->parts[kk]=forward(mel,st),"friday-voiceprint-part-"+k);ts[k].setDaemon(true);ts[k].start();}
      }catch(RuntimeException|OutOfMemoryError e){ /* whatever did not start is computed below */ }
      parts[0]=forward(mel,slices.get(0)[0]);
      for(int k=1;k<parts.length;k++){if(ts[k]!=null)try{ts[k].join();}catch(InterruptedException e){Thread.currentThread().interrupt();}if(parts[k]==null)parts[k]=forward(mel,slices.get(k)[0]);}
    }else for(int k=0;k<parts.length;k++)parts[k]=forward(mel,slices.get(k)[0]);
    float[] sum=new float[DIM];
    for(float[] e:parts)for(int i=0;i<DIM;i++)sum[i]+=e[i];
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
  /**
   * Threads a voiceprint may use: the three LSTM layers run as a pipeline (layer l+1 works on time
   * step t while layer l is already on t+1), which is 2-3 times faster on a phone with several
   * cores and gives exactly the same numbers as the one-thread version (same operations in the
   * same order per element). 1 = one thread (also the fallback when a thread cannot be started).
   */
  static volatile int threads=Math.min(LAYERS,Runtime.getRuntime().availableProcessors());
  /** Partials computed side by side when the phone has cores to spare beyond the layer pipeline (2 = yes). */
  static volatile int sliceThreads=Runtime.getRuntime().availableProcessors()>=2*LAYERS?2:1;
  /**
   * One partial (160 mel frames from start) through the LSTM, linear layer, ReLU and L2 norm.
   * Float sums with four running totals: the same result to about 1e-6, and several times faster
   * on a phone than one double sum (this runs for every check of who is speaking).
   */
  private float[] forward(float[][] mel,int start){
    float[] seq=new float[PARTIAL*MELS];
    for(int t=0;t<PARTIAL;t++)System.arraycopy(mel[start+t],0,seq,t*MELS,MELS);
    float[] h=threads>=2?pipelined(seq):null;
    if(h==null)h=sequential(seq);
    float[] e=new float[DIM];
    for(int i=0;i<DIM;i++)e[i]=Math.max(0f,linB[i]+dot(linW,i*HID,h,0,HID));
    return normalize(e);
  }
  /** All layers on this thread; returns the last layer's final hidden state. */
  private float[] sequential(float[] seq){
    int in=MELS;float[] h=null;
    for(int l=0;l<LAYERS;l++){
      float[] wi=wih[l],wh=whh[l],b=bias[l];
      // Input part of every gate for all time steps first (no dependency between steps).
      float[] xg=new float[PARTIAL*4*HID];
      for(int t=0;t<PARTIAL;t++){int xo=t*in,go=t*4*HID;for(int r=0;r<4*HID;r++)xg[go+r]=b[r]+dot(wi,r*in,seq,xo,in);}
      float[] outSeq=new float[PARTIAL*HID];h=new float[HID];float[] c=new float[HID],g=new float[4*HID];
      for(int t=0;t<PARTIAL;t++){
        int go=t*4*HID;
        for(int r=0;r<4*HID;r++)g[r]=xg[go+r]+dot(wh,r*HID,h,0,HID);
        step(g,c,h);
        System.arraycopy(h,0,outSeq,t*HID,HID);
      }
      seq=outSeq;in=HID;
    }
    return h;
  }
  /** One LSTM cell update from the gate pre-activations g (i, f, g, o) into c and h. */
  private static void step(float[] g,float[] c,float[] h){
    for(int k=0;k<HID;k++){
      float ig=sig(g[k]),fg=sig(g[HID+k]),cg=(float)Math.tanh(g[2*HID+k]),og=sig(g[3*HID+k]);
      c[k]=fg*c[k]+ig*cg;h[k]=og*(float)Math.tanh(c[k]);
    }
  }
  /**
   * The layers as a pipeline: layer 0 runs on this thread, each further layer on its own thread,
   * each waiting for the time step below it to be ready. Null when a thread could not be started
   * (the caller then runs the one-thread version).
   */
  private float[] pipelined(float[] seq){
    final int n=Math.min(threads,LAYERS);
    final float[][] out=new float[LAYERS][PARTIAL*HID];
    final java.util.concurrent.atomic.AtomicInteger[] ready=new java.util.concurrent.atomic.AtomicInteger[LAYERS];
    for(int l=0;l<LAYERS;l++)ready[l]=new java.util.concurrent.atomic.AtomicInteger();
    final float[] input=seq;final Thread[] workers=new Thread[LAYERS];final boolean[] failed=new boolean[1];
    try{
      // Layers 1.. on helper threads (with 2 threads layers 1 and 2 share the second thread).
      for(int w=1;w<n;w++){
        final int from=w,to=w==n-1?LAYERS-1:w;
        workers[w]=new Thread(()->{for(int l=from;l<=to;l++)layer(l,out[l-1],ready[l-1],out[l],ready[l]);},"friday-voiceprint-"+w);
        workers[w].setDaemon(true);workers[w].start();
      }
    }catch(RuntimeException|OutOfMemoryError e){failed[0]=true;}
    if(failed[0]){ready[0].set(PARTIAL);for(int l=1;l<LAYERS;l++)ready[l].set(PARTIAL);for(Thread t:workers)if(t!=null)try{t.join();}catch(InterruptedException ie){Thread.currentThread().interrupt();}return null;}
    layer(0,input,null,out[0],ready[0]);
    for(int w=1;w<n;w++)try{workers[w].join();}catch(InterruptedException e){Thread.currentThread().interrupt();return null;}
    return Arrays.copyOfRange(out[LAYERS-1],(PARTIAL-1)*HID,PARTIAL*HID);
  }
  /** One LSTM layer over all time steps, each step as soon as the layer below has produced it. */
  private void layer(int l,float[] seq,java.util.concurrent.atomic.AtomicInteger below,float[] outSeq,java.util.concurrent.atomic.AtomicInteger ready){
    int in=l==0?MELS:HID;float[] wi=wih[l],wh=whh[l],b=bias[l];
    float[] h=new float[HID],c=new float[HID],g=new float[4*HID];
    for(int t=0;t<PARTIAL;t++){
      if(below!=null)for(int spins=0;below.get()<=t;spins++){if(spins>200)Thread.yield();}
      int xo=t*in;
      for(int r=0;r<4*HID;r++)g[r]=b[r]+dot(wi,r*in,seq,xo,in)+dot(wh,r*HID,h,0,HID);
      step(g,c,h);
      System.arraycopy(h,0,outSeq,t*HID,HID);
      ready.set(t+1);
    }
  }
  /** a[ao..ao+n) . x[xo..xo+n), n a multiple of 4. */
  private static float dot(float[] a,int ao,float[] x,int xo,int n){
    float s0=0,s1=0,s2=0,s3=0;
    for(int k=0;k<n;k+=4){s0+=a[ao+k]*x[xo+k];s1+=a[ao+k+1]*x[xo+k+1];s2+=a[ao+k+2]*x[xo+k+2];s3+=a[ao+k+3]*x[xo+k+3];}
    return (s0+s1)+(s2+s3);
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
