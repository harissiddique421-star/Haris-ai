package com.k2fsa.sherpa.onnx;

// Java port of the sherpa-onnx 2.0.1 Kotlin API (Apache-2.0, k2-fsa/sherpa-onnx), only the parts FRIDAY uses.
// The native library reads these fields by name, so names and types must match that version exactly.

public final class OnlineModelConfig {
  public OnlineTransducerModelConfig transducer=new OnlineTransducerModelConfig();
  public OnlineParaformerModelConfig paraformer=new OnlineParaformerModelConfig();
  public OnlineZipformer2CtcModelConfig zipformer2Ctc=new OnlineZipformer2CtcModelConfig();
  public OnlineNeMoCtcModelConfig neMoCtc=new OnlineNeMoCtcModelConfig();
  public OnlineToneCtcModelConfig toneCtc=new OnlineToneCtcModelConfig();
  public String tokens="";
  public int numThreads=1;
  public boolean debug=false;
  public String provider="cpu",modelType="",modelingUnit="",bpeVocab="";
}
