package com.k2fsa.sherpa.onnx;

// Java port of the sherpa-onnx 2.0.1 Kotlin API (Apache-2.0, k2-fsa/sherpa-onnx), only the parts FRIDAY uses.
// The native library reads these fields by name, so names and types must match that version exactly.

public final class OnlineTransducerModelConfig {
  public String encoder="",decoder="",joiner="";
  public QnnConfig qnnConfig=new QnnConfig();
}
