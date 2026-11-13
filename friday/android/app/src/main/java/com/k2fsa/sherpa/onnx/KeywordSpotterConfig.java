package com.k2fsa.sherpa.onnx;

// Java port of the sherpa-onnx 2.0.1 Kotlin API (Apache-2.0, k2-fsa/sherpa-onnx), only the parts FRIDAY uses.
// The native library reads these fields by name, so names and types must match that version exactly.

public final class KeywordSpotterConfig {
  public FeatureConfig featConfig=new FeatureConfig();
  public OnlineModelConfig modelConfig=new OnlineModelConfig();
  public int maxActivePaths=4;
  public String keywordsFile="keywords.txt";
  public float keywordsScore=1.5f,keywordsThreshold=0.25f;
  public int numTrailingBlanks=2;
}
