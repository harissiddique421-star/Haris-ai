package com.myraa.mobile;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import java.io.File;

/**
 * NVIDIA NeMo TitaNet small (CC-BY-4.0, the sherpa-onnx export nemo_en_titanet_small.onnx), run by
 * sherpa-onnx's native library. On FRIDAY's Voice ID test recordings it separated the owner from
 * the friend and the TV far better than GE2E: lowest owner score 0.63 against highest other 0.27
 * (GE2E: 0.74 against 0.70), see .github/workflows/voice-engines.yml. 192 numbers per voiceprint.
 * The native library must be loaded first (SherpaVoice on the phone, System.load in the JVM checks).
 */
final class TitaNet implements Voiceprinter {
  static final String MODEL=Voiceprinter.TITANET,FILE="titanet_small.onnx";
  /** Shortest speech it is asked about: 0.25 s, as for GE2E. */
  private static final int MIN_SAMPLES=VoiceEncoder.RATE/4;
  private final SpeakerEmbeddingExtractor ex;

  /** Voice Guardian: the three recordings' voiceprints must be at least this close to their average. */
  static final double GUARDIAN_MIN_ENROLL=0.55;
  /**
   * Lowest TitaNet similarity accepted as the user. Voice Guardian (wake phrase). Same idea as VoiceGuardian.voiceThreshold, on TitaNet's scale:
   * on the test recordings the owner scored 0.63 or more and other voices 0.27 or less (SherpaCheck).
   */
  static double guardianThreshold(double enrollSelf,String sensitivity,int recentNearMisses){
    double margin="low".equals(sensitivity)?0.20:"high".equals(sensitivity)?0.30:0.25;
    double t=Math.max(0.45,Math.min(0.62,enrollSelf-margin));
    if(recentNearMisses>=3)t=Math.min(0.70,t+0.05);
    return t;
  }

  TitaNet(File model,int threads){
    SpeakerEmbeddingExtractorConfig c=new SpeakerEmbeddingExtractorConfig();
    c.model=model.getAbsolutePath();c.numThreads=Math.max(1,threads);c.provider="cpu";
    ex=new SpeakerEmbeddingExtractor(c);
  }
  @Override public String model(){return MODEL;}
  int dim(){return ex.dim();}

  @Override public float[] embed(float[] wav){
    if(wav==null||wav.length<MIN_SAMPLES)return null;
    OnlineStream s=ex.createStream();
    try{
      s.acceptWaveform(wav,VoiceEncoder.RATE);s.inputFinished();
      if(!ex.isReady(s))return null;
      float[] e=ex.compute(s);
      return e==null||e.length==0?null:VoiceEncoder.normalize(e);
    }finally{s.release();}
  }
}
