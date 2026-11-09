package com.myraa.mobile;

import android.content.Context;
import android.media.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Voice Guardian: FRIDAY wakes only when the wake phrase is said in the user's own voice.
 *
 * The user records "Wake up FRIDAY" three times. Each recording becomes a sequence of MFCC
 * frames (how the phrase sounds over time) and a voiceprint from the GE2E speaker encoder
 * (who is speaking; see VoiceEncoder). At listening time Silero VAD cuts the microphone into
 * bursts of speech (see SpeechSegmenter); each burst must match the phrase (dynamic time warping
 * against the three recordings) and the voice (cosine similarity of voiceprints above a
 * threshold set from the user's own recordings and the sensitivity). Anyone else saying the
 * phrase, or the user saying something else, is ignored.
 * Everything runs on the phone without internet. Only these numbers are kept, in the app's
 * private storage; no audio is saved and nothing is logged except match scores.
 *
 * Voices saved by older versions (no voiceprint) keep working with the earlier average-colour
 * check until the user taps Record again.
 *
 * This is a basic speaker check, not bank-grade security: a recording of the user's voice can
 * pass it. That is why waking on a locked phone still asks for the phone's own unlock.
 */
final class VoiceGuardian {
  static final int RATE=16000;
  private static final int FRAME=400,HOP=160,NFFT=512,MELS=26,CEPS=12;
  private static final String FILE="voice_guardian.json";
  private static final double[] WINDOW=new double[FRAME];
  private static final double[][] FILTERS=melFilters();
  static{for(int i=0;i<FRAME;i++)WINDOW[i]=0.54-0.46*Math.cos(2*Math.PI*i/(FRAME-1));}

  // ---- settings
  static boolean wanted(Context c){return c.getSharedPreferences(Announcer.PREFS,Context.MODE_PRIVATE).getBoolean("guardian",false);}
  static void setWanted(Context c,boolean on){c.getSharedPreferences(Announcer.PREFS,Context.MODE_PRIVATE).edit().putBoolean("guardian",on).apply();}
  static boolean enrolled(Context c){return new File(c.getFilesDir(),FILE).isFile();}
  /** Guardian decides the wake only when it is turned on and the user's voice is recorded. */
  static boolean active(Context c){return wanted(c)&&enrolled(c);}
  static void delete(Context c){new File(c.getFilesDir(),FILE).delete();setWanted(c,false);synchronized(VoiceGuardian.class){cached=null;nearMisses.clear();}}

  // ---- recording the user's voice
  interface Progress { void update(String status,int step,String message); }
  static volatile boolean recording;
  /** Records the wake phrase three times on a background thread, then saves the voice. done runs at the end either way. */
  static void record(Context c,Progress p,Runnable done){
    if(recording){p.update("busy",0,"Already recording.");return;}
    recording=true;Context app=c.getApplicationContext();
    new Thread(()->{
      MicInput r=null;
      try{
        Thread.sleep(700); // let the wake word listener release the microphone
        r=open();r.start();List<short[]> takes=new ArrayList<>();
        SpeechSegmenter seg=segmenter(app,ENROLL_MAX_MS);final MicInput mic=r;
        for(int i=1;i<=3;i++){
          p.update("listen",i,"Say \"Wake up FRIDAY\" now ("+i+" of 3).");
          short[] u=seg.next(mic::readFully,()->false,7000);
          if(u==null){p.update("error",i,"I didn't hear you. Tap Record my voice and try again in a quiet place.");return;}
          takes.add(u);p.update("heard",i,i<3?"Got it.":"Checking your voice…");Thread.sleep(400);
        }
        String problem=enroll(app,takes);
        p.update(problem==null?"enrolled":"error",3,problem==null?"Your voice is saved. FRIDAY now wakes only for your voice.":problem);
      }catch(InterruptedException e){Thread.currentThread().interrupt();p.update("error",0,"Recording stopped.");}
      catch(RuntimeException e){p.update("error",0,"The microphone is busy. End any call or recording and try again.");}
      finally{if(r!=null)r.close();recording=false;done.run();}
    },"friday-guardian-enroll").start();
  }

  // ---- audio capture
  /** Longest burst checked for the wake phrase, and for an enrollment recording; longer is background talk. */
  static final int WAKE_MAX_MS=5000,ENROLL_MAX_MS=7000;
  /** Shortest burst that can hold the wake phrase. */
  private static final int MIN_BURST_MS=500;
  /** The microphone with the phone's noise suppression, echo cancellation and gain control. */
  static MicInput open(){return MicInput.open();}
  interface Stop extends SpeechSegmenter.Stop {}
  /** Silence that ends a burst of speech, 700-900 ms (default 800). */
  static int silenceMs(Context c){return SpeechSegmenter.clampSilence(c.getSharedPreferences(Announcer.PREFS,Context.MODE_PRIVATE).getInt("voiceSilenceMs",SpeechSegmenter.DEFAULT_SILENCE_MS));}
  static void setSilenceMs(Context c,int ms){c.getSharedPreferences(Announcer.PREFS,Context.MODE_PRIVATE).edit().putInt("voiceSilenceMs",SpeechSegmenter.clampSilence(ms)).apply();}
  /** A speech detector for one listening session: Silero VAD, or the energy detector if the model is missing. */
  static SpeechSegmenter segmenter(Context c,int maxMs){return new SpeechSegmenter(VoiceModels.vad(c),silenceMs(c),maxMs);}

  // ---- features
  static final class Features { final float[][] frames;final float[] colour; Features(float[][] f,float[] c){frames=f;colour=c;} }
  static Features features(short[] pcm){
    int count=Math.max(0,(pcm.length-FRAME)/HOP+1);
    float[][] out=new float[count][CEPS];float[] colour=new float[CEPS];
    double[] re=new double[NFFT],im=new double[NFFT],mel=new double[MELS];
    for(int f=0;f<count;f++){
      Arrays.fill(re,0);Arrays.fill(im,0);int base=f*HOP;
      for(int i=0;i<FRAME;i++){double prev=i+base>0?pcm[base+i-1]:0;re[i]=(pcm[base+i]-0.97*prev)*WINDOW[i];}
      fft(re,im);
      for(int m=0;m<MELS;m++){double s=0;double[] w=FILTERS[m];for(int k=0;k<w.length;k++)if(w[k]!=0)s+=w[k]*(re[k]*re[k]+im[k]*im[k]);mel[m]=Math.log(s+1e-6);}
      for(int c=1;c<=CEPS;c++){double s=0;for(int m=0;m<MELS;m++)s+=mel[m]*Math.cos(Math.PI*c*(m+0.5)/MELS);out[f][c-1]=(float)s;colour[c-1]+=(float)s;}
    }
    if(count>0)for(int c=0;c<CEPS;c++)colour[c]/=count;
    // Remove the average so the room and the microphone matter less than the words.
    for(float[] fr:out)for(int c=0;c<CEPS;c++)fr[c]-=colour[c];
    return new Features(out,colour);
  }
  private static double[][] melFilters(){
    double lo=mel(60),hi=mel(7600);int bins=NFFT/2+1;double[][] f=new double[MELS][bins];
    double[] pts=new double[MELS+2];for(int i=0;i<pts.length;i++)pts[i]=inv(lo+(hi-lo)*i/(MELS+1))*NFFT/RATE;
    for(int m=0;m<MELS;m++)for(int k=0;k<bins;k++){
      double l=pts[m],c=pts[m+1],r=pts[m+2];
      f[m][k]=k>=l&&k<=c?(k-l)/(c-l):k>c&&k<=r?(r-k)/(r-c):0;
    }
    return f;
  }
  private static double mel(double hz){return 2595*Math.log10(1+hz/700);}
  private static double inv(double m){return 700*(Math.pow(10,m/2595)-1);}
  private static void fft(double[] re,double[] im){
    int n=re.length;
    for(int i=1,j=0;i<n;i++){int bit=n>>1;for(;(j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double t=re[i];re[i]=re[j];re[j]=t;t=im[i];im[i]=im[j];im[j]=t;}}
    for(int len=2;len<=n;len<<=1){
      double a=-2*Math.PI/len,wr=Math.cos(a),wi=Math.sin(a);
      for(int i=0;i<n;i+=len){double cr=1,ci=0;
        for(int k=0;k<len/2;k++){
          int p=i+k,q=p+len/2;double tr=re[q]*cr-im[q]*ci,ti=re[q]*ci+im[q]*cr;
          re[q]=re[p]-tr;im[q]=im[p]-ti;re[p]+=tr;im[p]+=ti;
          double nr=cr*wr-ci*wi;ci=cr*wi+ci*wr;cr=nr;
        }
      }
    }
  }
  /** Dynamic time warping distance per step; Infinity when the lengths are too different. */
  static double dtw(float[][] a,float[][] b){
    int n=a.length,m=b.length;if(n==0||m==0||n>2*m||m>2*n)return Double.POSITIVE_INFINITY;
    int band=Math.max(Math.abs(n-m),(int)(0.3*Math.max(n,m)));
    double[] prev=new double[m+1],cur=new double[m+1];Arrays.fill(prev,Double.POSITIVE_INFINITY);prev[0]=0;
    for(int i=1;i<=n;i++){
      Arrays.fill(cur,Double.POSITIVE_INFINITY);
      int from=Math.max(1,(int)((long)i*m/n)-band),to=Math.min(m,(int)((long)i*m/n)+band);
      for(int j=from;j<=to;j++){
        double d=0;float[] x=a[i-1],y=b[j-1];for(int c=0;c<CEPS;c++){double t=x[c]-y[c];d+=t*t;}
        cur[j]=Math.sqrt(d)+Math.min(prev[j-1],Math.min(prev[j],cur[j-1]));
      }
      double[] t=prev;prev=cur;cur=t;
    }
    return prev[m]/(n+m);
  }
  private static double distance(float[] a,float[] b){double d=0;for(int i=0;i<a.length;i++){double t=a[i]-b[i];d+=t*t;}return Math.sqrt(d);}

  // ---- the saved voice
  /** templates/limit: the phrase; print/printSelf: the voiceprint (null in voices saved before voiceprints); colour: older voice check. */
  private static final class Voice { float[][][] templates;float[] colour,print;double limit,colourLimit,printSelf; }
  private static Voice cached;
  /** Recent voice rejections of the right phrase: someone else, or a recording, trying to wake FRIDAY. */
  private static final ArrayDeque<Long> nearMisses=new ArrayDeque<>();

  /** Builds and saves the voice from three recordings. Returns a short problem message, or null when saved. */
  static String enroll(Context c,List<short[]> takes){
    if(takes.size()<3)return "I need three recordings.";
    float[][][] t=new float[takes.size()][][];float[] colour=new float[CEPS];
    for(int i=0;i<takes.size();i++){Features f=features(takes.get(i));t[i]=f.frames;for(int k=0;k<CEPS;k++)colour[k]+=f.colour[k]/takes.size();}
    double max=0,min=Double.POSITIVE_INFINITY,sum=0;int pairs=0;
    for(int i=0;i<t.length;i++)for(int j=i+1;j<t.length;j++){double d=dtw(t[i],t[j]);if(Double.isInfinite(d))return "The three recordings were too different in length. Say \"Wake up FRIDAY\" the same way each time.";max=Math.max(max,d);min=Math.min(min,d);sum+=d;pairs++;}
    if(max>min*2.6)return "One recording sounded different from the others. Try again in a quiet place, saying \"Wake up FRIDAY\" the same way each time.";
    double spread=0;for(short[] take:takes)spread=Math.max(spread,distance(features(take).colour,colour));
    Voice v=new Voice();v.templates=t;v.colour=colour;
    v.limit=Math.max(max*1.35,sum/pairs*1.5);v.colourLimit=Math.max(spread*2.5,4.0);
    // Voiceprint: the average of the three recordings' voiceprints.
    VoiceEncoder enc=VoiceModels.encoder(c);
    if(enc!=null){
      float[][] prints=new float[takes.size()][];float[] mean=new float[VoiceEncoder.DIM];
      for(int i=0;i<prints.length;i++){prints[i]=enc.embed(VoiceEncoder.toFloat(takes.get(i)));if(prints[i]==null)return "A recording was too short. Say \"Wake up FRIDAY\" clearly each time.";for(int k=0;k<mean.length;k++)mean[k]+=prints[i][k];}
      v.print=VoiceEncoder.normalize(mean);
      double self=1;for(float[] e:prints)self=Math.min(self,VoiceEncoder.cosine(e,v.print));
      if(self<MIN_ENROLL_CONSISTENCY)return "The three recordings didn't sound like the same voice. Record again alone, in a quiet place.";
      v.printSelf=self;
    }else ControlLog.i("guardian: speaker model unavailable, saving the basic voice check only");
    try{save(c,v);}catch(IOException|JSONException e){return "Your voice could not be saved on this phone.";}
    synchronized(VoiceGuardian.class){cached=v;nearMisses.clear();}
    setWanted(c,true);
    ControlLog.i("guardian: voice saved (phrase limit "+String.format(Locale.ROOT,"%.2f",v.limit)+(v.print!=null?String.format(Locale.ROOT,", voiceprint consistency %.2f, threshold %.2f",v.printSelf,voiceThreshold(v.printSelf,"balanced",0)):"")+")");
    return null;
  }
  /** The three enrollment voiceprints must all be at least this close to their average. */
  static final double MIN_ENROLL_CONSISTENCY=0.70;
  /**
   * Lowest voiceprint similarity accepted as the user. It starts from how close the user's own
   * three recordings were to each other and leaves a margin set by the sensitivity, within fixed
   * bounds so a sloppy enrollment cannot make it permissive. After repeated near-misses (the
   * right phrase in another voice, e.g. someone else or a TV) it is stricter for two minutes.
   */
  static double voiceThreshold(double enrollSelf,String sensitivity,int recentNearMisses){
    double margin="low".equals(sensitivity)?0.05:"high".equals(sensitivity)?0.13:0.09;
    double t=Math.max(0.72,Math.min(0.88,enrollSelf-margin));
    if(recentNearMisses>=3)t=Math.min(0.92,t+0.05);
    return t;
  }
  /** True when the burst is the wake phrase in the saved voice. Logs only the scores. */
  static boolean matches(Context c,short[] pcm){return matches(c,pcm,null);}
  /** Same, and in steady background noise (noise learnt between bursts) also checks a copy with that noise turned down. */
  static boolean matches(Context c,short[] pcm,NoiseReducer noise){double[] r=score(c,pcm,noise);return r!=null&&r[0]<=1&&r[1]<=1;}
  /** Sensitivity: low = stricter (fewer false wakes), high = more forgiving. */
  static double factor(Context c){String s=WakeWordService.sensitivity(c);return "low".equals(s)?1.0:"high".equals(s)?1.5:1.25;}
  /** {phrase distance / limit, voice score / limit}; both at or under 1 is a match. Null without a saved voice. */
  static double[] score(Context c,short[] pcm){return score(c,pcm,null);}
  /**
   * With a noise reducer that has learnt steady noise (fan, AC, traffic, hiss), the phrase and the
   * voice are also measured on a copy with that noise turned down, and the closer of the two counts.
   * Removing steady noise does not make someone else's voice more like the owner's (measured in
   * VoiceGateCheck), so this helps the owner in noise without letting others in.
   */
  static double[] score(Context c,short[] pcm,NoiseReducer noise){
    Voice v=load(c);if(v==null)return null;
    short[] cleanPcm=null;
    if(noise!=null&&noise.active()){float[] cf=noise.clean(VoiceEncoder.toFloat(pcm));cleanPcm=new short[cf.length];for(int i=0;i<cf.length;i++)cleanPcm[i]=(short)Math.max(-32768,Math.min(32767,Math.round(cf[i]*32768)));}
    double seconds=pcm.length/(double)RATE;
    // Too short to hold the phrase, or a long stretch of talk: not the wake phrase.
    if(seconds*1000<MIN_BURST_MS||seconds*1000>WAKE_MAX_MS)return new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
    Features f=features(pcm);
    double phrase=phraseDistance(f,v);
    if(cleanPcm!=null)phrase=Math.min(phrase,phraseDistance(features(cleanPcm),v));
    double k=factor(c),phraseRatio=phrase/(v.limit*k);
    VoiceEncoder enc=v.print!=null?VoiceModels.encoder(c):null;
    if(enc==null){
      // Voices saved before voiceprints (or no speaker model): the earlier average-colour check.
      double voice=distance(f.colour,v.colour);boolean ok=phraseRatio<=1&&voice<=v.colourLimit*k;
      if(!Double.isInfinite(phrase)&&phrase<=v.limit*1.6)
        ControlLog.i("guardian: "+(ok?"matched":"not matched")+String.format(Locale.ROOT," (phrase %.2f/%.2f, voice %.1f/%.1f, basic check: record again for the voiceprint check)",phrase,v.limit,voice,v.colourLimit));
      return new double[]{phraseRatio,voice/(v.colourLimit*k)};
    }
    // The voiceprint costs more, so it is computed only when the phrase is close.
    if(phrase>v.limit*1.6)return new double[]{phraseRatio,Double.POSITIVE_INFINITY};
    float[] e=enc.embed(VoiceEncoder.toFloat(pcm));
    double sim=e==null?0:VoiceEncoder.cosine(e,v.print);
    boolean cleaned=false;
    int misses=recentNearMisses();
    double threshold=voiceThreshold(v.printSelf,WakeWordService.sensitivity(c),misses);
    if(cleanPcm!=null&&sim<threshold&&sim>=threshold-NoiseReducer.REACH){float[] ce=enc.embed(VoiceEncoder.toFloat(cleanPcm));double cs=ce==null?0:VoiceEncoder.cosine(ce,v.print)-NoiseReducer.PENALTY;if(cs>sim){sim=cs;cleaned=true;}}
    boolean ok=phraseRatio<=1&&sim>=threshold;
    if(phraseRatio<=1&&!ok)nearMiss();
    ControlLog.i("guardian: "+(ok?"matched":"not matched")+String.format(Locale.ROOT," (phrase %.2f/%.2f, voice %.2f, needs %.2f%s%s)",phrase,v.limit*k,sim,threshold,cleaned?", with background noise removed":"",misses>=3?", stricter after repeated misses":""));
    // Voice ratio: at or under 1 exactly when the similarity reaches the threshold.
    return new double[]{phraseRatio,(1-sim)/Math.max(1e-6,1-threshold)};
  }
  private static double phraseDistance(Features f,Voice v){
    double[] d=new double[v.templates.length];for(int i=0;i<d.length;i++)d[i]=dtw(f.frames,v.templates[i]);
    Arrays.sort(d);return d.length>1?(d[0]+d[1])/2:d[0];
  }
  private static synchronized int recentNearMisses(){
    long now=System.currentTimeMillis();while(!nearMisses.isEmpty()&&now-nearMisses.peekFirst()>120_000)nearMisses.pollFirst();return nearMisses.size();
  }
  private static synchronized void nearMiss(){nearMisses.addLast(System.currentTimeMillis());while(nearMisses.size()>10)nearMisses.pollFirst();}
  /** True when the saved voice has a voiceprint (recorded with this version or later). */
  static boolean hasVoiceprint(Context c){Voice v=load(c);return v!=null&&v.print!=null;}
  private static void save(Context c,Voice v) throws IOException,JSONException {
    JSONObject o=new JSONObject().put("version",v.print!=null?2:1).put("limit",v.limit).put("colourLimit",v.colourLimit).put("colour",pack(new float[][]{v.colour}));
    if(v.print!=null)o.put("print",packVector(v.print)).put("printSelf",v.printSelf);
    JSONArray t=new JSONArray();for(float[][] f:v.templates)t.put(pack(f));o.put("templates",t);
    File tmp=new File(c.getFilesDir(),FILE+".tmp");
    try(FileOutputStream out=new FileOutputStream(tmp)){out.write(o.toString().getBytes(StandardCharsets.UTF_8));}
    if(!tmp.renameTo(new File(c.getFilesDir(),FILE)))throw new IOException("rename");
  }
  private static synchronized Voice load(Context c){
    if(cached!=null)return cached;
    File file=new File(c.getFilesDir(),FILE);if(!file.isFile())return null;
    try(FileInputStream in=new FileInputStream(file)){
      byte[] raw=new byte[(int)Math.min(file.length(),4_000_000)];int got=0,n;while(got<raw.length&&(n=in.read(raw,got,raw.length-got))>0)got+=n;
      JSONObject o=new JSONObject(new String(raw,0,got,StandardCharsets.UTF_8));
      Voice v=new Voice();v.limit=o.getDouble("limit");v.colourLimit=o.getDouble("colourLimit");v.colour=unpack(o.getString("colour"))[0];
      if(o.has("print")){v.print=unpackVector(o.getString("print"));v.printSelf=o.getDouble("printSelf");}
      JSONArray t=o.getJSONArray("templates");v.templates=new float[t.length()][][];for(int i=0;i<t.length();i++)v.templates[i]=unpack(t.getString(i));
      return cached=v;
    }catch(IOException|JSONException|RuntimeException e){ControlLog.i("guardian: saved voice unreadable");return null;}
  }
  private static String packVector(float[] f){
    ByteBuffer b=ByteBuffer.allocate(4+f.length*4);b.putInt(f.length);for(float x:f)b.putFloat(x);
    return Base64.encodeToString(b.array(),Base64.NO_WRAP);
  }
  private static float[] unpackVector(String s){
    ByteBuffer b=ByteBuffer.wrap(Base64.decode(s,Base64.NO_WRAP));int n=b.getInt();if(n!=VoiceEncoder.DIM)throw new IllegalArgumentException("bad voiceprint");
    float[] f=new float[n];for(int i=0;i<n;i++)f[i]=b.getFloat();return f;
  }
  private static String pack(float[][] f){
    ByteBuffer b=ByteBuffer.allocate(8+f.length*CEPS*4);b.putInt(f.length).putInt(CEPS);
    for(float[] r:f)for(int i=0;i<CEPS;i++)b.putFloat(r[i]);
    return Base64.encodeToString(b.array(),Base64.NO_WRAP);
  }
  private static float[][] unpack(String s){
    ByteBuffer b=ByteBuffer.wrap(Base64.decode(s,Base64.NO_WRAP));int n=b.getInt(),w=b.getInt();if(w!=CEPS||n<0||n>2000)throw new IllegalArgumentException("bad voice");
    float[][] f=new float[n][CEPS];for(float[] r:f)for(int i=0;i<CEPS;i++)r[i]=b.getFloat();return f;
  }
}
