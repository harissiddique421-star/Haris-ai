package com.myraa.mobile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Voice ID gate for the Live call: microphone audio reaches Gemini only when it is the owner's
 * voice, and the owner's turn ends when the OWNER stops talking, not when the whole room is quiet.
 * Runs on the phone, on its own thread, one check at a time.
 *
 * Speech: Silero VAD (32 ms chunks) plus loudness. The owner talks close to the phone, so their
 * speech is louder at the microphone than people across the room. Each burst learns its talker's
 * level (70th percentile of its voiced chunks); after the owner has been verified once, the call
 * remembers the owner's level. A chunk counts as the talker still speaking only when the VAD hears
 * speech AND it is within 6 dB of that level (9 dB to start a burst), so quieter background talk
 * does not keep a turn open, does not reset the end-of-turn timer and does not start a new burst.
 *
 * Each burst is held back until its first 1.2 s (SpeakerProfile.CHECK_MS) has been compared with
 * the owner's voiceprint:
 *  - owner: the held audio and the rest of the turn are released (FRIDAY may be interrupted);
 *  - close but under the threshold: one more check on 2 s of the same burst;
 *  - anyone else: replaced by silence. When it was as loud as the owner and only just missed
 *    (overlapping voices), the event is "unclear" so FRIDAY can ask the owner to repeat.
 * While the owner talks the voiceprint is checked again every 1.5 s on the last 1.2 s. If it no
 * longer matches, someone else (as loud as the owner) has taken over: the owner's turn ends there.
 * The turn ends ("end") after the silence time without owner-level speech (600 ms by default),
 * whatever the room does. A burst too short for its first check is checked during that silence, on
 * the same audio, so the check does not add to the wait. If the owner starts, much louder, while
 * another voice's burst is still being held, the held part is dropped and checking starts again
 * from the owner.
 * While someone else talks, a jump of 8 dB (the owner starting close to the phone) starts a new
 * burst at once, and the voiceprint is checked every 2 s, so background voices never keep FRIDAY
 * stuck listening and never lock the owner out.
 *
 * Output is in the same order as the input: owner audio as recorded, everything else as a count
 * of silent samples, so Gemini hears silence (and is told the turn ended) as soon as the owner
 * stops, even while others keep talking.
 */
final class VoiceGate {
  static final int CHUNK=SileroVad.CHUNK,RATE=SileroVad.RATE,CHUNK_MS=CHUNK*1000/RATE;
  static final float ON=0.5f,OFF=0.35f;
  private static final int START=2,PRE=10,MIN_SPEECH=8;
  /**
   * After this much silence (96 ms) a burst that has not been checked yet is checked at once, on
   * exactly the audio the end-of-turn check would use, so a short command's voice check runs while
   * the end-of-turn silence is still being timed instead of after it.
   */
  private static final int EARLY=3;
  /** A first check that would only come in the last 256 ms before the end of the turn counts as too late. */
  private static final int LATE=8;
  /** End-of-turn silence for the Live call: default and limits (Google recommends 500 ms or more). */
  static final int DEFAULT_END_MS=600,MIN_END_MS=400,MAX_END_MS=1000;
  static int clampEnd(int ms){return Math.max(MIN_END_MS,Math.min(MAX_END_MS,ms));}
  private static final int CHECK=SpeakerProfile.CHECK_MS/CHUNK_MS+PRE,SECOND=2000/CHUNK_MS+PRE;
  private static final int RECHECK_OWNER=1500/CHUNK_MS,RECHECK_OTHER=2000/CHUNK_MS,WINDOW=SpeakerProfile.CHECK_MS/CHUNK_MS+3;
  /** How far under the threshold a first check may be and still get the longer second check. */
  static final double NEAR=0.06;
  /** A rejected burst this close to the threshold, and as loud as the owner, is "unclear" (overlap). */
  static final double UNCLEAR=0.10;
  /**
   * Within 9 dB of the owner's level may start a burst; a turn continues only on speech within 6 dB
   * of its talker's level (quieter voices around count as silence); 8 dB above the background starts a new burst.
   */
  static final double SAME_TALKER=0.355,KEEP_TALKER=0.5,JUMP=2.5;

  enum State { LISTENING, SPEECH_DETECTED, VERIFYING_SPEAKER, AUTHORIZED_USER, UNKNOWN_SPEAKER }
  /** Similarity of 16 kHz speech (floats -1..1) to the owner's voice; NaN when it cannot be computed. */
  interface Scorer { double score(float[] wav); }
  interface Output {
    void owner(short[] pcm);
    void silence(int samples);
    /**
     * owner (allowed: may interrupt FRIDAY), end (the owner's turn ended), unknown (ignored),
     * unclear (overlapping voices, ask the owner to repeat), noise.
     */
    void event(String name,double score);
  }

  private final SileroVad vad;
  private final Scorer scorer;
  private final double threshold;
  private final int silenceChunks;
  /** Score of the current burst computed early (EARLY chunks into a pause), NaN when none. */
  private double early=Double.NaN;
  private boolean earlyTried;
  private final short[] pending=new short[CHUNK];private int pendingLen;
  private final float[] floats=new float[CHUNK];
  private final ArrayDeque<short[]> before=new ArrayDeque<>();
  private final ArrayDeque<Double> beforeLevels=new ArrayDeque<>();
  private final List<short[]> burst=new ArrayList<>();
  private final List<Double> burstLevels=new ArrayList<>();
  private final ArrayDeque<short[]> recent=new ArrayDeque<>();
  private final ArrayDeque<Double> recentLevels=new ArrayDeque<>();
  /** Levels of the owner's own speech in this turn (last 3 s), for the end-of-turn decision. */
  private final ArrayDeque<Double> turnLevels=new ArrayDeque<>();
  private static final int TURN_LEVELS=3000/CHUNK_MS;
  private State state=State.LISTENING;
  private int above,silence,sinceCheck,jump,voicedChunks;
  /** A held burst's talker level counts as known after this much speech (640 ms); only then can a louder voice cut in. */
  private static final int SETTLED=20;
  private boolean secondDone;
  /** Level of the current burst's talker, and of the owner once verified in this call (0 = not known). */
  private double talkerLevel,ownerLevel;
  // Counters for the diagnostics line.
  int owners,unknowns,checks;double lastScore=Double.NaN;long checkMs;

  /** silenceMs: how long the owner must be quiet before the turn ends (clamped to 400-1000 ms). */
  VoiceGate(SileroVad vad,Scorer scorer,double threshold,int silenceMs){
    this.vad=vad;this.scorer=scorer;this.threshold=threshold;
    silenceChunks=Math.max(1,(clampEnd(silenceMs)+CHUNK_MS/2)/CHUNK_MS);
  }
  /** The end-of-turn silence actually used, in milliseconds. */
  int endMs(){return silenceChunks*CHUNK_MS;}
  State state(){return state;}
  double threshold(){return threshold;}
  double ownerLevel(){return ownerLevel;}

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
    double level=SpeechSegmenter.rms(c);
    switch(state){
      case LISTENING:{
        out.silence(CHUNK);
        before.add(c);beforeLevels.add(level);if(before.size()>PRE){before.poll();beforeLevels.poll();}
        // Once the owner's level is known, quieter background talk does not start a burst at all.
        boolean candidate=p>=ON&&(ownerLevel==0||level>=ownerLevel*SAME_TALKER);
        if(candidate){if(++above>=START)startBurst(before,beforeLevels);}
        else above=0;
        return;
      }
      case SPEECH_DETECTED: case VERIFYING_SPEAKER:{
        // Someone much louder (the owner, close to the phone) starts while another voice's burst is
        // still being held: drop the held part (it was the other voice) and verify from here, so the
        // owner is not judged on the background talker's audio.
        if(p>=ON&&talkerLevel>0&&voicedChunks>=SETTLED&&level>=talkerLevel*JUMP&&(ownerLevel==0||level>=ownerLevel*SAME_TALKER)){
          if(++jump>=START){
            burst.add(c);burstLevels.add(level);
            int keep=Math.min(burst.size(),PRE);
            out.silence((burst.size()-keep)*CHUNK);unknowns++;out.event("unknown",Double.NaN);
            ArrayDeque<short[]> pre=new ArrayDeque<>(burst.subList(burst.size()-keep,burst.size()));
            ArrayDeque<Double> preL=new ArrayDeque<>(burstLevels.subList(burst.size()-keep,burst.size()));
            startBurst(pre,preL);return;
          }
          burst.add(c);burstLevels.add(level);silence=0;return;
        }
        jump=0;
        burst.add(c);burstLevels.add(level);
        if(p>=ON){talkerLevel=voicedLevel(burstLevels,burst.size());voicedChunks++;}
        silence=nextSilence(p,level,silence);
        if(silence==0){early=Double.NaN;earlyTried=false;} // the talker went on: an early score no longer covers the burst
        if(silence>=silenceChunks){
          // The burst ended before the first check: a short command, or noise.
          int voiced=burst.size()-silence;
          if(voiced-PRE<MIN_SPEECH){out.silence(burst.size()*CHUNK);out.event("noise",Double.NaN);reset();return;}
          double s=!Double.isNaN(early)?early:check(burst,Math.min(burst.size(),voiced+EARLY));
          if(s>=threshold){release(out,s);out.event("end",s);reset();}
          else{out.silence(burst.size()*CHUNK);rejected(out,s);reset();}
          return;
        }
        int need=secondDone?SECOND:CHECK,voicedNow=burst.size()-silence;
        // A pause in a burst whose next check would not come before the turn ends (or only just before):
        // check now, on exactly the audio the end-of-turn check would use, while the silence is timed.
        if(silence>=EARLY&&!earlyTried&&voicedNow-PRE>=MIN_SPEECH&&burst.size()+silenceChunks-silence<need+LATE){
          earlyTried=true;early=check(burst,voicedNow+EARLY);return;
        }
        // Already scored up to this pause: wait for the end of the turn or for the talker to go on.
        if(!Double.isNaN(early))return;
        if(burst.size()<need)return;
        state=State.VERIFYING_SPEAKER;
        double s=check(burst,burst.size());
        if(s>=threshold){release(out,s);state=State.AUTHORIZED_USER;sinceCheck=0;recentFrom(burst,burstLevels);seedTurn(burstLevels);}
        else if(!secondDone&&s>=threshold-NEAR){secondDone=true;} // keep holding: one longer check
        else{out.silence(burst.size()*CHUNK);rejected(out,s);toUnknown(burst,burstLevels);}
        return;
      }
      case AUTHORIZED_USER:{
        out.owner(c);remember(c,level);
        // The owner's level over the last 3 s of their speech (their loud syllables, not the room).
        if(p>=ON&&level>=talkerLevel*KEEP_TALKER){turnLevels.add(level);if(turnLevels.size()>TURN_LEVELS)turnLevels.poll();talkerLevel=Math.max(talkerLevel*0.98,voicedLevel(new ArrayList<>(turnLevels),turnLevels.size()));}
        silence=nextSilence(p,level,silence);
        // The owner stopped (quieter talk around them does not count): the turn ends now.
        if(silence>=silenceChunks){ownerLevel=0.7*ownerLevel+0.3*talkerLevel;out.event("end",lastScore);reset();return;}
        if(++sinceCheck>=RECHECK_OWNER&&silence==0){
          sinceCheck=0;double s=check(new ArrayList<>(recent),recent.size());
          // Someone as loud as the owner took over: end the owner's turn here (a little lenient,
          // so the owner is not cut off mid-sentence).
          if(s<threshold-NEAR){out.event("end",s);toUnknown(new ArrayList<>(recent),new ArrayList<>(recentLevels));}
        }
        return;
      }
      case UNKNOWN_SPEAKER:{
        out.silence(CHUNK);remember(c,level);silence=nextSilence(p,level,silence);
        if(silence>=silenceChunks){reset();return;}
        // Someone suddenly much louder than the current talker (the owner, close to the phone):
        // start a new burst from here instead of waiting for the room to go quiet.
        if(p>=ON&&level>=talkerLevel*JUMP&&(ownerLevel==0||level>=ownerLevel*SAME_TALKER)){
          if(++jump>=START){
            ArrayDeque<short[]> pre=new ArrayDeque<>();ArrayDeque<Double> preL=new ArrayDeque<>();
            List<short[]> r=new ArrayList<>(recent);List<Double> rl=new ArrayList<>(recentLevels);
            for(int i=Math.max(0,r.size()-PRE);i<r.size();i++){pre.add(r.get(i));preL.add(rl.get(i));}
            startBurst(pre,preL);return;
          }
        }else jump=0;
        if(p>=ON)talkerLevel=0.9*talkerLevel+0.1*level;
        if(++sinceCheck>=RECHECK_OTHER&&silence==0){
          sinceCheck=0;double s=check(new ArrayList<>(recent),recent.size());
          if(s>=threshold){
            // The owner is talking over someone else at a similar level: let the owner through from here.
            owners++;out.event("owner",s);learnOwner(new ArrayList<>(recentLevels));
            for(short[] r:recent)out.owner(r);
            state=State.AUTHORIZED_USER;silence=0;seedTurn(new ArrayList<>(recentLevels));
          }
        }
        return;
      }
    }
  }
  private void startBurst(ArrayDeque<short[]> pre,ArrayDeque<Double> preLevels){
    state=State.SPEECH_DETECTED;burst.clear();burstLevels.clear();
    burst.addAll(pre);burstLevels.addAll(preLevels);before.clear();beforeLevels.clear();
    talkerLevel=voicedLevel(burstLevels,burst.size());silence=0;secondDone=false;jump=0;above=0;early=Double.NaN;earlyTried=false;voicedChunks=0;
  }
  private void rejected(Output out,double s){
    unknowns++;
    // As loud as the owner and only just under the threshold: probably the owner with other voices on top.
    boolean unclear=ownerLevel>0&&talkerLevel>=ownerLevel*0.5&&s>=threshold-UNCLEAR;
    out.event(unclear?"unclear":"unknown",s);
  }
  private void release(Output out,double s){
    owners++;out.event("owner",s);learnOwner(burstLevels);
    for(short[] b:burst)out.owner(b);
  }
  private void learnOwner(List<Double> levels){
    double l=voicedLevel(levels,levels.size());
    if(l>0)ownerLevel=ownerLevel==0?l:0.7*ownerLevel+0.3*l;
  }
  private void seedTurn(List<Double> levels){
    turnLevels.clear();double l=voicedLevel(levels,levels.size());
    for(int i=Math.max(0,levels.size()-TURN_LEVELS);i<levels.size();i++)if(levels.get(i)>=l*KEEP_TALKER)turnLevels.add(levels.get(i));
    talkerLevel=Math.max(l,voicedLevel(new ArrayList<>(turnLevels),turnLevels.size()));
  }
  private void toUnknown(List<short[]> chunks,List<Double> levels){
    state=State.UNKNOWN_SPEAKER;sinceCheck=0;jump=0;silence=0;
    recent.clear();recentLevels.clear();
    for(int i=Math.max(0,chunks.size()-WINDOW);i<chunks.size();i++){recent.add(chunks.get(i));recentLevels.add(levels.get(i));}
    talkerLevel=voicedLevel(levels,levels.size());
    burst.clear();burstLevels.clear();early=Double.NaN;
  }
  /** Typical level of the loudest talker in the chunks: 70th percentile, ignoring near-silence. */
  static double voicedLevel(List<Double> levels,int n){
    double[] v=new double[n];int k=0;for(int i=0;i<n;i++){double x=levels.get(i);if(x>30)v[k++]=x;}
    if(k==0)return 0;
    v=Arrays.copyOf(v,k);Arrays.sort(v);return v[Math.min(k-1,(int)(k*0.7))];
  }
  private double check(List<short[]> chunks,int count){
    float[] wav=new float[count*CHUNK];
    for(int i=0;i<count;i++){short[] c=chunks.get(i);for(int k=0;k<CHUNK;k++)wav[i*CHUNK+k]=c[k]/32768f;}
    long t=System.nanoTime();double s=scorer.score(wav);checkMs=(System.nanoTime()-t)/1_000_000;checks++;
    if(Double.isNaN(s))s=0;lastScore=s;return s;
  }
  private void remember(short[] c,double level){recent.add(c);recentLevels.add(level);while(recent.size()>WINDOW){recent.poll();recentLevels.poll();}}
  private void recentFrom(List<short[]> b,List<Double> l){
    recent.clear();recentLevels.clear();
    for(int i=Math.max(0,b.size()-WINDOW);i<b.size();i++){recent.add(b.get(i));recentLevels.add(l.get(i));}
  }
  /**
   * End-of-turn counter for the current talker: speech from them resets it; silence, or speech
   * more than 9 dB quieter than them (other people further away), counts towards the end.
   */
  private int nextSilence(float p,double level,int silence){
    boolean talker=talkerLevel<=0||level>=talkerLevel*KEEP_TALKER;
    if(p>=ON&&talker)return 0;
    if(p<OFF||!talker||silence>0)return silence+1;
    return silence;
  }
  private void reset(){
    state=State.LISTENING;burst.clear();burstLevels.clear();recent.clear();recentLevels.clear();before.clear();beforeLevels.clear();
    turnLevels.clear();above=0;silence=0;sinceCheck=0;jump=0;secondDone=false;talkerLevel=0;early=Double.NaN;
  }
}
