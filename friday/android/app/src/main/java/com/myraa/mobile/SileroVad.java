package com.myraa.mobile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Silero VAD v5 (16 kHz), run on the phone in plain Java with the published weights
 * (assets/silero_vad_16k.safetensors, MIT licence). Gives the probability that a 32 ms chunk
 * (512 samples) contains speech. It keeps its own recurrent state and 64 samples of context,
 * exactly like the official ONNX wrapper, so feed chunks in order and call reset() between
 * unrelated streams. Not thread-safe: one instance per audio stream.
 *
 * The computation follows the model graph: a learned STFT (256-sample window, hop 128, on the
 * 64-sample context plus the chunk, reflect-padded by 64), magnitude, four Conv1d+ReLU layers,
 * an LSTM cell (128) and a 1x1 Conv1d with sigmoid.
 */
final class SileroVad {
  static final int RATE=16000,CHUNK=512,CONTEXT=64;
  static final String ASSET="silero_vad_16k.safetensors";
  private static final int WIN=256,HOP=128,BINS=129,H=128;
  private final float[] basis,w1,b1,w2,b2,w3,b3,w4,b4,wih,whh,bih,bhh,wOut;private final float bOut;
  private final float[] h=new float[H],c=new float[H],context=new float[CONTEXT];
  // Scratch buffers, reused for every chunk.
  private final float[] x=new float[CONTEXT+CHUNK+CONTEXT];
  private final float[][] mag=new float[BINS][4],e1=new float[128][4],e2=new float[64][2],e3=new float[64][1],e4=new float[128][1];
  private final float[] gates=new float[4*H];

  SileroVad(InputStream weights) throws IOException {
    Safetensors s=new Safetensors(weights);
    basis=s.get("stft_conv.weight",2*BINS,1,WIN);
    w1=s.get("conv1.weight",128,BINS,3);b1=s.get("conv1.bias",128);
    w2=s.get("conv2.weight",64,128,3);b2=s.get("conv2.bias",64);
    w3=s.get("conv3.weight",64,64,3);b3=s.get("conv3.bias",64);
    w4=s.get("conv4.weight",128,64,3);b4=s.get("conv4.bias",128);
    wih=s.get("lstm_cell.weight_ih",4*H,H);whh=s.get("lstm_cell.weight_hh",4*H,H);
    bih=s.get("lstm_cell.bias_ih",4*H);bhh=s.get("lstm_cell.bias_hh",4*H);
    wOut=s.get("final_conv.weight",1,H,1);bOut=s.get("final_conv.bias",1)[0];
  }
  void reset(){Arrays.fill(h,0);Arrays.fill(c,0);Arrays.fill(context,0);}

  /** Speech probability (0..1) for the next 512 samples, given as floats in -1..1. */
  float process(float[] chunk){
    if(chunk.length!=CHUNK)throw new IllegalArgumentException("Silero VAD needs 512-sample chunks at 16 kHz");
    int n=CONTEXT+CHUNK;
    System.arraycopy(context,0,x,0,CONTEXT);System.arraycopy(chunk,0,x,CONTEXT,CHUNK);
    for(int i=0;i<CONTEXT;i++)x[n+i]=x[n-2-i]; // reflect padding on the right
    System.arraycopy(chunk,CHUNK-CONTEXT,context,0,CONTEXT);
    // Learned STFT: rows 0..128 are the real parts, 129..257 the imaginary parts.
    for(int t=0;t<4;t++){
      int start=t*HOP;
      for(int f=0;f<BINS;f++){
        double re=0,im=0;int ro=f*WIN,io=(f+BINS)*WIN;
        for(int j=0;j<WIN;j++){float v=x[start+j];re+=basis[ro+j]*v;im+=basis[io+j]*v;}
        mag[f][t]=(float)Math.sqrt(re*re+im*im);
      }
    }
    conv(mag,BINS,4,w1,b1,128,1,e1);
    conv(e1,128,4,w2,b2,64,2,e2);
    conv(e2,64,2,w3,b3,64,2,e3);
    conv(e3,64,1,w4,b4,128,1,e4);
    // LSTM cell, PyTorch gate order: input, forget, cell, output.
    for(int g=0;g<4*H;g++){
      double s=bih[g]+bhh[g];int o=g*H;
      for(int k=0;k<H;k++)s+=wih[o+k]*e4[k][0]+whh[o+k]*h[k];
      gates[g]=(float)s;
    }
    for(int k=0;k<H;k++){
      float i=sigmoid(gates[k]),fg=sigmoid(gates[H+k]),gg=(float)Math.tanh(gates[2*H+k]),o=sigmoid(gates[3*H+k]);
      c[k]=fg*c[k]+i*gg;h[k]=o*(float)Math.tanh(c[k]);
    }
    double out=bOut;for(int k=0;k<H;k++)out+=wOut[k]*Math.max(0f,h[k]);
    return sigmoid((float)out);
  }
  /** Conv1d with kernel 3, padding 1, then ReLU. */
  private static void conv(float[][] in,int cin,int tin,float[] w,float[] b,int cout,int stride,float[][] out){
    int tout=(tin-1)/stride+1;
    for(int o=0;o<cout;o++)for(int t=0;t<tout;t++){
      double s=b[o];int center=t*stride;
      for(int ci=0;ci<cin;ci++){
        int wo=(o*cin+ci)*3;float[] row=in[ci];
        for(int k=0;k<3;k++){int p=center+k-1;if(p>=0&&p<tin)s+=w[wo+k]*row[p];}
      }
      out[o][t]=(float)Math.max(0,s);
    }
  }
  private static float sigmoid(float v){return (float)(1/(1+Math.exp(-v)));}
}
