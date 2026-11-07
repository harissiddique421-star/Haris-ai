package com.myraa.mobile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Voice ID gate for the Live call: microphone audio reaches Gemini only when it is the owner's
 * voice. Runs on the phone, on its own thread, one check at a time.
 *
 * Silero VAD finds each burst of speech (start after two 32 ms chunks at 0.5, 320 ms kept from
 * before; end after the silence time below 0.35). The burst is held back, not sent, until its
 * first 1.2 s (SpeakerProfile.CHECK_MS) has been compared with the owner's voiceprint:
 *  - owner: the held audio and the rest of the burst are released (and FRIDAY may be interrupted);
 *  - close but under the threshold: one more check on 2 s of the same burst before deciding;
 *  - anyone else: the burst is replaced by silence until it ends, so Gemini never hears it.
 * A burst that ends before 1.2 s (a short command) is checked on what there is. Bursts shorter
 * than 250 ms of speech are noise and dropped. While the owner talks the voice is checked again
 * every 4 s on the last 2 s, so someone taking over mid-sentence is cut off; while a stranger (or
 * the TV) talks without a pause it is checked every 2 s, so the owner can still talk over it.
 *
 * Output is in the same order as the input: owner audio as it was recorded, everything else as a
 * count of silent samples, so the call keeps its timing and Gemini can still tell when the owner
 * stopped talking.
 */
final class VoiceGate {
  static final int CHUNK=SileroVad.CHUNK,RATE=SileroVad.RATE,CHUNK_MS=CHUNK*1000/RATE;
  static final float ON=0.5f,OFF=0.35f;
  private static final int START=2,PRE=10,MIN_SPEECH=8;
  private static final int CHECK=SpeakerProfile.CHECK_MS/CHUNK_MS+PRE,SECOND=2000/CHUNK_MS+PRE;
  private static final int RECHECK_OWNER=4000/CHUNK_MS,RECHECK_OTHER=2000/CHUNK_MS,WINDOW=2000/CHUNK_MS;
  /** How far under the threshold a first check may be and still get the longer second check. */
  static final double NEAR=0.06;

  enum State { LISTENING, SPEECH_DETECTED, VERIFYING_SPEAKER, AUTHORIZED_USER, UNKNOWN_SPEAKER }
  /** Similarity of 16 kHz speech (floats -1..1) to the owner's voice; NaN when it cannot be computed. */
  interface Scorer { double score(float[] wav); }
  interface Output {
    void owner(short[] pcm);
    void silence(int samples);
    /** owner (allowed: may interrupt FRIDAY), unknown (ignored), end (owner stopped), lost (owner check failed mid-burst), noise. */
    void event(String name,double score);
  }

  private final SileroVad vad;
  private final Scorer scorer;
  private final double threshold;
  private final int silenceChunks;
  private final short[] pending=new short[CHUNK];private int pendingLen;
  private final float[] floats=new float[CHUNK];
  private final ArrayDeque<short[]> before=new ArrayDeque<>();
  private final List<short[]> burst=new ArrayList<>();
  private final ArrayDeque<short[]> recent=new ArrayDeque<>();
  private State state=State.LISTENING;
  private int above,silence,sinceCheck;
  private boolean secondDone;
  // Counters for the diagnostics line.
  int owners,unknowns,checks;double lastScore=Double.NaN;long checkMs;

  VoiceGate(SileroVad vad,Scorer scorer,double threshold,int silenceMs){
    this.vad=vad;this.scorer=scorer;this.threshold=threshold;
    silenceChunks=Math.max(1,SpeechSegmenter.clampSilence(silenceMs)/CHUNK_MS);
  }
  State state(){return state;}
  double threshold(){return threshold;}

  /** Feeds microphone audio (any length); results go to out, in order. */
  void push(short[] pcm,int len,Output out){
    int i=0;
    while(i<len){
      int n=Math.min(CHUNK-pendingLen,len-i);
      System.arraycopy(pcm,i,pending,pendingLen,n);pendingLen+=n;i+=n;
      if(pendingLen==CHUNK){chunk(pending.clone(),out);pendingLen=0;}
    }
  }

  private void chunk(short[] c,Output out){
    for(int k=0;k<CHUNK;k++)floats[k]=c[k]/32768f;
    float p=vad.process(floats);
    switch(state){
      case LISTENING:{
        out.silence(CHUNK);
        before.add(c);if(before.size()>PRE)before.poll();
        if(p>=ON){if(++above>=START){state=State.SPEECH_DETECTED;burst.clear();burst.addAll(before);before.clear();silence=0;secondDone=false;}}
        else above=0;
        return;
      }
      case SPEECH_DETECTED: case VERIFYING_SPEAKER:{
        burst.add(c);silence=nextSilence(p,silence);
        if(silence>=silenceChunks){
          // The burst ended before the first check: a short command, or noise.
          int voiced=burst.size()-silence;
          if(voiced-PRE<MIN_SPEECH){out.silence(burst.size()*CHUNK);out.event("noise",Double.NaN);reset();return;}
          double s=check(burst,Math.min(burst.size(),voiced+3));
          if(s>=threshold){release(out,s);out.event("end",s);reset();}
          else{out.silence(burst.size()*CHUNK);unknowns++;out.event("unknown",s);reset();}
          return;
        }
        int need=secondDone?SECOND:CHECK;
        if(burst.size()<need)return;
        state=State.VERIFYING_SPEAKER;
        double s=check(burst,burst.size());
        if(s>=threshold){release(out,s);state=State.AUTHORIZED_USER;sinceCheck=0;recentFrom(burst);}
        else if(!secondDone&&s>=threshold-NEAR){secondDone=true;} // keep holding: one longer check
        else{out.silence(burst.size()*CHUNK);unknowns++;out.event("unknown",s);state=State.UNKNOWN_SPEAKER;sinceCheck=0;recentFrom(burst);burst.clear();}
        return;
      }
      case AUTHORIZED_USER:{
        out.owner(c);remember(c);silence=nextSilence(p,silence);
        if(silence>=silenceChunks){out.event("end",lastScore);reset();return;}
        if(++sinceCheck>=RECHECK_OWNER&&silence==0){
          sinceCheck=0;double s=check(new ArrayList<>(recent),recent.size());
          // A little more lenient than the first check, so the owner is not cut off mid-sentence.
          if(s<threshold-NEAR){unknowns++;out.event("lost",s);state=State.UNKNOWN_SPEAKER;}
        }
        return;
      }
      case UNKNOWN_SPEAKER:{
        out.silence(CHUNK);remember(c);silence=nextSilence(p,silence);
        if(silence>=silenceChunks){reset();return;}
        if(++sinceCheck>=RECHECK_OTHER&&silence==0){
          sinceCheck=0;double s=check(new ArrayList<>(recent),recent.size());
          if(s>=threshold){
            // The owner is talking over someone else (or the TV): let the owner through from here.
            owners++;out.event("owner",s);
            for(short[] r:recent)out.owner(r);
            state=State.AUTHORIZED_USER;
          }
        }
        return;
      }
    }
  }
  private void release(Output out,double s){owners++;out.event("owner",s);for(short[] b:burst)out.owner(b);}
  private double check(List<short[]> chunks,int count){
    float[] wav=new float[count*CHUNK];
    for(int i=0;i<count;i++){short[] c=chunks.get(i);for(int k=0;k<CHUNK;k++)wav[i*CHUNK+k]=c[k]/32768f;}
    long t=System.nanoTime();double s=scorer.score(wav);checkMs=(System.nanoTime()-t)/1_000_000;checks++;
    if(Double.isNaN(s))s=0;lastScore=s;return s;
  }
  private void remember(short[] c){recent.add(c);while(recent.size()>WINDOW)recent.poll();}
  private void recentFrom(List<short[]> b){recent.clear();for(int i=Math.max(0,b.size()-WINDOW);i<b.size();i++)recent.add(b.get(i));}
  private static int nextSilence(float p,int silence){return p>=ON?0:(p<OFF||silence>0)?silence+1:silence;}
  private void reset(){state=State.LISTENING;burst.clear();recent.clear();before.clear();above=0;silence=0;sinceCheck=0;secondDone=false;}
}
