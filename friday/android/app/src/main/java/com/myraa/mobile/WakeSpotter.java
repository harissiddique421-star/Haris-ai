package com.myraa.mobile;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import java.io.File;

/**
 * Hears the wake phrase in a burst of speech with sherpa-onnx keyword spotting (the English
 * zipformer model sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01, Apache-2.0). It listens
 * for the words, in any voice; Voice Guardian then checks that the voice is the owner's.
 *
 * Unlike the recorded-phrase match (MFCC + DTW against three recordings), it does not depend on
 * saying the phrase exactly as recorded: speed, distance and tone matter much less. Measured on
 * synthetic speech in four voices: "Wake up FRIDAY" and "FRIDAY wake up" heard every time, also
 * with TV mixed in; ordinary sentences with "Friday" in them and the TV alone never.
 *
 * The model directory holds encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt and keywords.txt
 * (the phrases as the model's word pieces, made by scripts/fetch-sherpa.sh).
 */
final class WakeSpotter {
  private final KeywordSpotter kws;

  WakeSpotter(File dir,int threads,float threshold){
    KeywordSpotterConfig c=new KeywordSpotterConfig();
    c.featConfig=new FeatureConfig();
    c.modelConfig.transducer.encoder=new File(dir,"encoder.onnx").getAbsolutePath();
    c.modelConfig.transducer.decoder=new File(dir,"decoder.onnx").getAbsolutePath();
    c.modelConfig.transducer.joiner=new File(dir,"joiner.onnx").getAbsolutePath();
    c.modelConfig.tokens=new File(dir,"tokens.txt").getAbsolutePath();
    c.modelConfig.numThreads=Math.max(1,threads);c.modelConfig.provider="cpu";
    c.keywordsFile=new File(dir,"keywords.txt").getAbsolutePath();
    c.maxActivePaths=4;c.keywordsScore=1.0f;c.keywordsThreshold=threshold;c.numTrailingBlanks=1;
    kws=new KeywordSpotter(c);
  }

  /** Lower threshold = hears the phrase more easily. Wake sensitivity low/balanced/high. */
  static float threshold(String sensitivity){return "low".equals(sensitivity)?0.30f:"high".equals(sensitivity)?0.20f:0.25f;}

  /** The wake phrase heard in this 16 kHz speech (for example "WAKE_UP_FRIDAY"), or null. */
  String heard(float[] wav){
    if(wav==null||wav.length==0)return null;
    OnlineStream s=kws.createStream("");
    try{
      // A little silence on both sides, so a phrase at the very start or end is complete.
      s.acceptWaveform(new float[VoiceEncoder.RATE/2],VoiceEncoder.RATE);
      s.acceptWaveform(wav,VoiceEncoder.RATE);
      s.acceptWaveform(new float[VoiceEncoder.RATE],VoiceEncoder.RATE);
      s.inputFinished();
      while(kws.isReady(s)){
        kws.decode(s);
        KeywordSpotterResult r=kws.getResult(s);
        if(r!=null&&r.keyword!=null&&!r.keyword.isEmpty())return r.keyword;
      }
      return null;
    }finally{s.release();}
  }
}
