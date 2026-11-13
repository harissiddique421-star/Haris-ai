package com.k2fsa.sherpa.onnx;

// Java port of the sherpa-onnx 2.0.1 Kotlin API (Apache-2.0, k2-fsa/sherpa-onnx), only the parts FRIDAY uses.
// The native library reads these fields by name, so names and types must match that version exactly.

/** Streaming keyword spotting (a small zipformer transducer). Load the native library first (see SherpaNative). */
public final class KeywordSpotter implements AutoCloseable {
  private long ptr;
  public KeywordSpotter(KeywordSpotterConfig config){ptr=newFromFile(config);if(ptr==0)throw new IllegalArgumentException("Invalid KeywordSpotterConfig");}
  /** keywords: "" for the keywords file, or tokenised keywords separated by "/". */
  public OnlineStream createStream(String keywords){return new OnlineStream(createStream(ptr,keywords));}
  public boolean isReady(OnlineStream s){return isReady(ptr,s.ptr);}
  public void decode(OnlineStream s){decode(ptr,s.ptr);}
  public void reset(OnlineStream s){reset(ptr,s.ptr);}
  public KeywordSpotterResult getResult(OnlineStream s){return getResult(ptr,s.ptr);}
  public synchronized void release(){if(ptr!=0){delete(ptr);ptr=0;}}
  @Override public void close(){release();}
  private native long newFromFile(KeywordSpotterConfig config);
  private native void delete(long ptr);
  private native long createStream(long ptr,String keywords);
  private native boolean isReady(long ptr,long streamPtr);
  private native void decode(long ptr,long streamPtr);
  private native void reset(long ptr,long streamPtr);
  private native KeywordSpotterResult getResult(long ptr,long streamPtr);
}
