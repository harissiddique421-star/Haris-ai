package com.myraa.mobile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cuts a microphone stream into separate bursts of speech, using Silero VAD on 32 ms chunks.
 *
 * Speech starts after two chunks in a row reach the speech threshold (0.5), with 320 ms of audio
 * before it kept so the first syllable is not lost. It ends only after the configured silence
 * (default 800 ms, 700-900 ms allowed) below the lower threshold (0.35), so short pauses inside a
 * sentence do not cut it off. Very short blips (under 250 ms of speech) are dropped. A burst that
 * runs past the maximum length is continuous talk, TV or music: it is dropped as a whole and
 * nothing is returned until the room has been quiet for the silence time again, so a
 * conversation in the background does not trigger anything over and over.
 *
 * Silence is not sent through the model: chunks whose level stays near the room's own noise
 * floor count as non-speech without running it, which keeps the battery cost low in a quiet room.
 * Without a model (null) a simple energy detector is used instead, as in earlier versions.
 */
final class SpeechSegmenter {
  static final int CHUNK=SileroVad.CHUNK,RATE=SileroVad.RATE,CHUNK_MS=CHUNK*1000/RATE;
  static final int DEFAULT_SILENCE_MS=800,MIN_SILENCE_MS=700,MAX_SILENCE_MS=900;
  static final float ON=0.5f,OFF=0.35f;
  private static final int START_CHUNKS=2,PRE_CHUNKS=10,MIN_SPEECH_CHUNKS=8,TAIL_CHUNKS=3;
  interface Source { boolean read(short[] chunk); }
  interface Stop { boolean now(); }

  private final SileroVad vad;
  private final int silenceChunks,maxChunks;
  private final float[] floats=new float[CHUNK];
  private double floor=150;
  private boolean modelIdle;
  /** Bursts dropped because they were too long (continuous talk, TV, music), for the log. */
  int droppedLong;
  /** Steady background noise heard between bursts (no speech), so a voice check can also score the burst without it. */
  final NoiseReducer noise=new NoiseReducer();

  SpeechSegmenter(SileroVad vad,int silenceMs,int maxSpeechMs){
    this.vad=vad;
    silenceChunks=Math.max(1,clampSilence(silenceMs)/CHUNK_MS);
    maxChunks=Math.max(MIN_SPEECH_CHUNKS+1,maxSpeechMs/CHUNK_MS);
  }
  static int clampSilence(int ms){return Math.max(MIN_SILENCE_MS,Math.min(MAX_SILENCE_MS,ms));}
  boolean usesModel(){return vad!=null;}

  /**
   * Reads until one complete burst of speech has been heard and returns it, or null when stopped,
   * when the source ends, or when no speech started within timeoutMs (0 = wait forever).
   */
  short[] next(Source src,Stop stop,long timeoutMs){
    long timeoutChunks=timeoutMs>0?Math.max(1,timeoutMs/CHUNK_MS):Long.MAX_VALUE,waited=0;
    ArrayDeque<short[]> before=new ArrayDeque<>();List<short[]> speech=new ArrayList<>();List<Double> levels=new ArrayList<>();
    int above=0,silence=0,preLen=0;boolean inSpeech=false,skipping=false;
    while(!stop.now()){
      short[] chunk=new short[CHUNK];
      if(!src.read(chunk)||stop.now())return null;
      double level=rms(chunk);
      float p=probability(chunk,level,inSpeech||skipping);
      if(skipping){
        // Waiting for a quiet moment after an over-long burst.
        silence=p>=ON?0:(p<OFF||silence>0)?silence+1:silence;
        if(silence>=silenceChunks){skipping=false;silence=0;above=0;before.clear();}
        continue;
      }
      if(!inSpeech){
        if(++waited>timeoutChunks)return null;
        before.add(chunk);if(before.size()>PRE_CHUNKS)before.poll();
        if(p>=ON){if(++above>=START_CHUNKS){inSpeech=true;speech.clear();levels.clear();speech.addAll(before);preLen=before.size();before.clear();silence=0;}}
        else{above=0;floor=Math.max(30,floor*0.97+level*0.03);if(p<OFF){float[] f=new float[CHUNK];for(int k=0;k<CHUNK;k++)f[k]=chunk[k]/32768f;noise.learn(f);}}
        continue;
      }
      speech.add(chunk);levels.add(level);
      silence=p>=ON?0:(p<OFF||silence>0)?silence+1:silence;
      if(speech.size()>maxChunks){
        droppedLong++;
        // Energy detector only: a "burst" that never ends is steady noise (fan, traffic), so raise the floor to it.
        if(vad==null){Collections.sort(levels);floor=Math.max(floor,Math.min(4000,levels.get(levels.size()/4)*1.1));skipping=false;above=0;}
        else skipping=true;
        speech.clear();levels.clear();inSpeech=false;continue;
      }
      if(silence>=silenceChunks){
        int voiced=speech.size()-silence;
        if(voiced-preLen>=MIN_SPEECH_CHUNKS){
          int keep=Math.min(speech.size(),voiced+TAIL_CHUNKS);short[] out=new short[keep*CHUNK];
          for(int i=0;i<keep;i++)System.arraycopy(speech.get(i),0,out,i*CHUNK,CHUNK);
          return out;
        }
        speech.clear();inSpeech=false;above=0;silence=0;
      }
    }
    return null;
  }
  /** Speech probability of one chunk: Silero when there is sound worth checking, else 0. */
  private float probability(short[] chunk,double level,boolean active){
    if(vad==null)return level>Math.max(floor*2.5,120)?1f:0f; // energy detector fallback
    boolean quiet=level<Math.max(floor*1.25,30);
    if(quiet&&!active){
      if(!modelIdle){vad.reset();modelIdle=true;}
      return 0f;
    }
    modelIdle=false;
    for(int i=0;i<CHUNK;i++)floats[i]=chunk[i]/32768f;
    return vad.process(floats);
  }
  static double rms(short[] s){double t=0;for(short v:s)t+=(double)v*v;return Math.sqrt(t/s.length);}
}
