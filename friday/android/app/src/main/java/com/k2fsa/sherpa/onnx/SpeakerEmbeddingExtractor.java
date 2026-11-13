package com.k2fsa.sherpa.onnx;

// Java port of the sherpa-onnx 2.0.1 Kotlin API (Apache-2.0, k2-fsa/sherpa-onnx), only the parts FRIDAY uses.
// The native library reads these fields by name, so names and types must match that version exactly.

/** Speaker embeddings (voiceprints) from a sherpa-onnx speaker model. Load the native library first (see SherpaNative). */
public final class SpeakerEmbeddingExtractor implements AutoCloseable {
  private long ptr;
  public SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig config){ptr=newFromFile(config);if(ptr==0)throw new IllegalArgumentException("Invalid SpeakerEmbeddingExtractorConfig");}
  public OnlineStream createStream(){return new OnlineStream(createStream(ptr));}
  public boolean isReady(OnlineStream s){return isReady(ptr,s.ptr);}
  public float[] compute(OnlineStream s){return compute(ptr,s.ptr);}
  public int dim(){return dim(ptr);}
  public synchronized void release(){if(ptr!=0){delete(ptr);ptr=0;}}
  @Override public void close(){release();}
  private native long newFromFile(SpeakerEmbeddingExtractorConfig config);
  private native void delete(long ptr);
  private native long createStream(long ptr);
  private native boolean isReady(long ptr,long streamPtr);
  private native float[] compute(long ptr,long streamPtr);
  private native int dim(long ptr);
}
