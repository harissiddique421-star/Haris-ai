package com.k2fsa.sherpa.onnx;

// Java port of the sherpa-onnx 2.0.1 Kotlin API (Apache-2.0, k2-fsa/sherpa-onnx), only the parts FRIDAY uses.
// The native library reads these fields by name, so names and types must match that version exactly.

/** Audio fed to a keyword spotter or speaker model. Call release() when done (native memory). */
public final class OnlineStream implements AutoCloseable {
  long ptr;
  OnlineStream(long ptr){if(ptr==0)throw new IllegalStateException("Failed to create native OnlineStream");this.ptr=ptr;}
  public void acceptWaveform(float[] samples,int sampleRate){acceptWaveform(ptr,samples,sampleRate);}
  public void inputFinished(){inputFinished(ptr);}
  public synchronized void release(){if(ptr!=0){delete(ptr);ptr=0;}}
  @Override public void close(){release();}
  private native void acceptWaveform(long ptr,float[] samples,int sampleRate);
  private native void inputFinished(long ptr);
  private native void delete(long ptr);
}
