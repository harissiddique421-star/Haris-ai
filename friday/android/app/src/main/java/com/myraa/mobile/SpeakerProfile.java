package com.myraa.mobile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The owner's Voice ID: a voiceprint (GE2E speaker embedding, see VoiceEncoder) averaged over the
 * enrollment sentences, plus the scores used to choose the acceptance threshold. No audio is kept.
 *
 * The threshold is calibrated, not fixed. Each enrollment sentence is cut to the length the
 * Live voice gate checks first (CHECK_MS from the start of the speech) and compared with the
 * average of the other sentences (leave-one-out). Those are "genuine" scores for this person on
 * this phone; the threshold sits a sensitivity margin below their mean, within fixed bounds.
 * Other people's voices recorded with "Test someone else" are "impostor" scores and push the
 * threshold above the highest of them.
 *
 * The bounds and margins come from measuring this model on real recorded speech (10 LibriSpeech
 * test-other speakers enrolled with 5 sentences, 50 genuine and 3,410 impostor trials against
 * 260 other speakers, at 1.2 s): balanced gave 0.5% false accepts and 4% false rejects, with the
 * second, longer check (see VoiceGate) 1.0% and 2%. A 0.6 s word such as "Stop" alone is too
 * short to identify reliably (about half rejected), which errs on the side of ignoring.
 */
final class SpeakerProfile {
  /** Length of speech the gate checks first, and that the genuine scores are measured on. */
  static final int CHECK_MS=1200;
  static final double MIN_THRESHOLD=0.70,MAX_THRESHOLD=0.80,MAX_WITH_IMPOSTORS=0.86;
  /** The enrollment sentences must all be at least this close to the average of the others. */
  static final double MIN_CONSISTENCY=0.62;
  /** A recording this far below the median agreement of all of them is left out (measured: the owner's own 0.81-0.90, another person 0.72). */
  static final double OUTLIER_GAP=0.10;
  static final int MAX_IMPOSTORS=12;

  /**
   * The numbers above are for GE2E. Each speaker model scores on its own scale, so each has its own
   * bounds. TitaNet (see TitaNet) was measured on FRIDAY's Voice ID test recordings with this file's
   * protocol in SherpaCheck: its owner scores sit lower than GE2E's but far above other voices.
   */
  static final class Calib {
    final double minThreshold,maxThreshold,maxWithImpostors,minConsistency,outlierGap,impostorStep,strict,balanced,relaxed;
    Calib(double minT,double maxT,double maxImp,double minCons,double gap,double step,double strict,double balanced,double relaxed){
      minThreshold=minT;maxThreshold=maxT;maxWithImpostors=maxImp;minConsistency=minCons;outlierGap=gap;impostorStep=step;this.strict=strict;this.balanced=balanced;this.relaxed=relaxed;
    }
    double margin(String sensitivity){return "strict".equals(sensitivity)?strict:"relaxed".equals(sensitivity)?relaxed:balanced;}
  }
  static final Calib CALIB_GE2E=new Calib(MIN_THRESHOLD,MAX_THRESHOLD,MAX_WITH_IMPOSTORS,MIN_CONSISTENCY,OUTLIER_GAP,0.04,0.06,0.10,0.13);
  // TitaNet on the test recordings (SherpaCheck): own sentences 0.65-0.74 at 1.2 s (mean 0.70), owner tests 0.68+, friend and TV 0.26 or less.
  static final Calib CALIB_TITANET=new Calib(0.45,0.60,0.70,0.45,0.15,0.08,0.10,0.15,0.20);
  static Calib calib(String model){return Voiceprinter.TITANET.equals(model)?CALIB_TITANET:CALIB_GE2E;}

  /** The speaker model that made this profile (VoiceEncoder.MODEL or Voiceprinter.TITANET). */
  final String model;
  final Calib calib;

  final float[] print;
  final double[] genuine;
  final List<Double> impostor;
  final long created;
  /**
   * Per recording (version 2): voiceprint of the whole sentence, of its first CHECK_MS, and of a
   * room-echo copy (see roomEcho). Kept so more samples can be added later without recording all
   * again. These are 256 numbers each, not audio. Empty for a version 1 profile.
   */
  final List<float[]> samples,shortSamples,echoSamples;
  /** Recordings left out when the profile was built because they did not match the others. */
  final int dropped;

  SpeakerProfile(float[] print,double[] genuine,List<Double> impostor,long created){
    this(VoiceEncoder.MODEL,print,genuine,impostor,created,new ArrayList<float[]>(),new ArrayList<float[]>(),new ArrayList<float[]>(),0);
  }
  SpeakerProfile(String model,float[] print,double[] genuine,List<Double> impostor,long created,List<float[]> samples,List<float[]> shortSamples,List<float[]> echoSamples,int dropped){
    this.model=model;this.calib=calib(model);this.print=print;this.genuine=genuine;this.impostor=new ArrayList<>(impostor);this.created=created;
    this.samples=new ArrayList<>(samples);this.shortSamples=new ArrayList<>(shortSamples);this.echoSamples=new ArrayList<>(echoSamples);this.dropped=dropped;
  }

  /** Sensitivity: strict = fewer strangers accepted, relaxed = fewer of your own commands missed. */
  static double margin(String sensitivity){return CALIB_GE2E.margin(sensitivity);}
  static String sensitivity(String s){return "strict".equals(s)||"relaxed".equals(s)?s:"balanced";}

  double meanGenuine(){double s=0;for(double g:genuine)s+=g;return genuine.length==0?0:s/genuine.length;}
  double maxImpostor(){double m=-1;for(double x:impostor)m=Math.max(m,x);return m;}
  /** More samples can be added (the per-recording voiceprints are kept). */
  boolean canAdd(){return !samples.isEmpty()&&samples.size()<MAX_SAMPLES;}

  /** Lowest similarity accepted as the owner. */
  double threshold(String sensitivity){
    Calib k=calib;
    double t=Math.max(k.minThreshold,Math.min(k.maxThreshold,meanGenuine()-k.margin(sensitivity)));
    // Someone else scored this high on this phone: stay above them (a little, but not without limit).
    if(!impostor.isEmpty())t=Math.max(t,Math.min(k.maxWithImpostors,maxImpostor()+k.impostorStep));
    return t;
  }

  /**
   * An "other voice" test that scores as high as the owner's own recordings agree with each other
   * is most likely the owner (or a voice this profile cannot tell apart). Storing it would raise the
   * threshold above the owner's own voice and lock the owner out, so it is not stored.
   */
  boolean tooCloseToOwner(double score){return score>=meanGenuine();}

  /** Similarity of a voiceprint to the owner (cosine, 1 = same direction). */
  double score(float[] e){return e==null?0:VoiceEncoder.cosine(e,print);}

  SpeakerProfile withImpostor(double score){
    List<Double> list=new ArrayList<>(impostor);list.add(score);
    while(list.size()>MAX_IMPOSTORS)list.remove(0);
    return new SpeakerProfile(model,print,genuine,list,created,samples,shortSamples,echoSamples,dropped);
  }

  /** Most recordings a profile keeps (adding more drops the oldest). */
  static final int MAX_SAMPLES=30;

  /** Adds recordings to a version 2 profile and builds it again; the "other voice" scores are kept. */
  SpeakerProfile withSamples(List<float[]> full,List<float[]> shortPrints,List<float[]> echo,long now){
    List<float[]> f=new ArrayList<>(samples),sh=new ArrayList<>(shortSamples),e=new ArrayList<>(echoSamples);
    f.addAll(full);sh.addAll(shortPrints);e.addAll(echo);
    while(f.size()>MAX_SAMPLES){f.remove(0);sh.remove(0);e.remove(0);}
    SpeakerProfile p=build(model,f,sh,e,now);
    return new SpeakerProfile(model,p.print,p.genuine,impostor,p.created,p.samples,p.shortSamples,p.echoSamples,p.dropped);
  }

  static SpeakerProfile build(List<float[]> full,List<float[]> shortPrints,long now){return build(full,shortPrints,new ArrayList<float[]>(),now);}
  static SpeakerProfile build(List<float[]> full,List<float[]> shortPrints,List<float[]> echo,long now){return build(VoiceEncoder.MODEL,full,shortPrints,echo,now);}

  /**
   * Builds the profile from each recording's voiceprint over the whole sentence (full), over its
   * first CHECK_MS (short) and, optionally, of a room-echo copy (echo, same order). The echo copies
   * only widen the voiceprint so the owner still matches from across a room; they are never used
   * as "genuine" trials, and a recording is always compared with the others without its own copy.
   *
   * A recording that does not match the rest (a cough, someone else talking, a bad take) is left
   * out, as long as at least three quarters of them (and four) remain. Throws IllegalArgumentException
   * with a message for the user when the recordings cannot make a reliable profile.
   */
  static SpeakerProfile build(String model,List<float[]> full,List<float[]> shortPrints,List<float[]> echo,long now){
    Calib cal=calib(model);
    int n=full.size();
    if(n<4||shortPrints.size()!=n)throw new IllegalArgumentException("I need at least four clear sentences.");
    boolean withEcho=!echo.isEmpty();
    if(withEcho&&echo.size()!=n)throw new IllegalArgumentException("Voice samples are incomplete. Record again.");
    List<Integer> kept=new ArrayList<>();for(int i=0;i<n;i++)kept.add(i);
    int minKeep=Math.max(4,(int)Math.ceil(n*0.75));
    while(true){
      float[] sum=sum(full,echo,kept);double worst=1;int worstAt=-1;double[] c=new double[kept.size()];
      for(int k=0;k<kept.size();k++){int i=kept.get(k);c[k]=VoiceEncoder.cosine(full.get(i),others(sum,full,echo,i));if(c[k]<worst){worst=c[k];worstAt=i;}}
      double[] sorted=c.clone();Arrays.sort(sorted);double median=sorted[sorted.length/2];
      // An outlier: far from the others in absolute terms, or clearly below how well the rest agree.
      if(worst>=cal.minConsistency&&worst>=median-cal.outlierGap)break;
      if(kept.size()<=minKeep)throw new IllegalArgumentException("Your sentences didn't sound like the same voice. Record again alone, in a quiet place.");
      kept.remove(Integer.valueOf(worstAt));
    }
    float[] sum=sum(full,echo,kept);
    double[] genuine=new double[kept.size()];
    List<float[]> f=new ArrayList<>(),sh=new ArrayList<>(),e=new ArrayList<>();
    for(int k=0;k<kept.size();k++){
      int i=kept.get(k);
      genuine[k]=VoiceEncoder.cosine(shortPrints.get(i),others(sum,full,echo,i));
      f.add(full.get(i));sh.add(shortPrints.get(i));if(withEcho)e.add(echo.get(i));
    }
    return new SpeakerProfile(model,VoiceEncoder.normalize(sum),genuine,new ArrayList<Double>(),now,withEcho?f:new ArrayList<float[]>(),withEcho?sh:new ArrayList<float[]>(),e,n-kept.size());
  }
  private static float[] sum(List<float[]> full,List<float[]> echo,List<Integer> kept){
    float[] s=new float[full.get(kept.get(0)).length];
    for(int i:kept){add(s,full.get(i),1);if(!echo.isEmpty())add(s,echo.get(i),1);}
    return s;
  }
  private static float[] others(float[] sum,List<float[]> full,List<float[]> echo,int i){
    float[] o=sum.clone();add(o,full.get(i),-1);if(!echo.isEmpty())add(o,echo.get(i),-1);
    return VoiceEncoder.normalize(o);
  }
  private static void add(float[] to,float[] x,int sign){for(int k=0;k<to.length;k++)to[k]+=sign*x[k];}

  /**
   * A copy of 16 kHz speech as heard from a few metres away in a furnished room: the direct sound
   * plus sparse reflections about as loud in total, dying away over 0.35 s. Training on these
   * copies keeps the owner's voice recognisable from across the room (measured in VoiceGateCheck);
   * it is deterministic and computed on the phone. Same loudness as the input.
   */
  static float[] roomEcho(float[] x){
    int n=(int)(0.35*16000),step=8;java.util.Random r=new java.util.Random(7);
    int taps=n/step;int[] at=new int[taps];float[] g=new float[taps];
    for(int k=0;k<taps;k++){at[k]=1+k*step+r.nextInt(step-1);g[k]=(float)(r.nextGaussian()*0.05*Math.sqrt(step)*Math.pow(10,-3.0*at[k]/n));}
    float[] y=x.clone();
    for(int k=0;k<taps;k++){int d=at[k];float gk=g[k];for(int i=0;i+d<y.length;i++)y[i+d]+=gk*x[i];}
    double a=0,b=0;for(float v:x)a+=v*v;for(float v:y)b+=v*v;
    float s=(float)Math.sqrt(a/Math.max(1e-12,b));for(int i=0;i<y.length;i++)y[i]*=s;
    return y;
  }

  String summary(String sensitivity){
    return String.format(Locale.ROOT,"%s: %d sentences%s, your score %.2f, needs %.2f%s",engine(),genuine.length,dropped>0?" ("+dropped+" unclear left out)":"",meanGenuine(),threshold(sensitivity),
      impostor.isEmpty()?"":String.format(Locale.ROOT,", %d other voice%s (highest %.2f)",impostor.size(),impostor.size()==1?"":"s",maxImpostor()));
  }

  /** The voice engine's name for Settings. */
  String engine(){return Voiceprinter.TITANET.equals(model)?"TitaNet voice engine":"Basic voice engine";}
  /** Voiceprint length each model makes. */
  static int dim(String model){return Voiceprinter.TITANET.equals(model)?192:VoiceEncoder.DIM;}

  JSONObject toJson() throws JSONException {
    JSONArray i=new JSONArray();for(double x:impostor)i.put(x);
    JSONArray g=new JSONArray();for(double x:genuine)g.put(x);
    return new JSONObject().put("version",samples.isEmpty()?1:2).put("model",model).put("print",vec(print)).put("genuine",g).put("impostor",i).put("created",created)
      .put("dropped",dropped).put("samples",vecs(samples)).put("short",vecs(shortSamples)).put("echo",vecs(echoSamples));
  }
  private static JSONArray vec(float[] v) throws JSONException {JSONArray a=new JSONArray();for(float x:v)a.put(Math.round(x*1e5)/1e5);return a;}
  private static JSONArray vecs(List<float[]> l) throws JSONException {JSONArray a=new JSONArray();for(float[] v:l)a.put(vec(v));return a;}
  private static float[] readVec(JSONArray p,int dim) throws JSONException {
    if(p.length()!=dim)throw new JSONException("bad voiceprint");
    float[] v=new float[p.length()];for(int k=0;k<v.length;k++)v[k]=(float)p.getDouble(k);return v;
  }
  private static List<float[]> readVecs(JSONArray a,int dim) throws JSONException {List<float[]> l=new ArrayList<>();if(a!=null)for(int k=0;k<a.length();k++)l.add(readVec(a.getJSONArray(k),dim));return l;}
  static SpeakerProfile fromJson(JSONObject o) throws JSONException {
    int version=o.optInt("version");String model=o.optString("model");
    if((version!=1&&version!=2)||!(VoiceEncoder.MODEL.equals(model)||Voiceprinter.TITANET.equals(model)))throw new JSONException("unknown voice profile");
    int dim=dim(model);
    float[] print=readVec(o.getJSONArray("print"),dim);
    JSONArray g=o.getJSONArray("genuine");double[] genuine=new double[g.length()];for(int k=0;k<genuine.length;k++)genuine[k]=g.getDouble(k);
    JSONArray i=o.optJSONArray("impostor");List<Double> imp=new ArrayList<>();if(i!=null)for(int k=0;k<i.length();k++)imp.add(i.getDouble(k));
    List<float[]> f=readVecs(o.optJSONArray("samples"),dim),sh=readVecs(o.optJSONArray("short"),dim),e=readVecs(o.optJSONArray("echo"),dim);
    if(f.size()!=sh.size()||(!e.isEmpty()&&e.size()!=f.size()))throw new JSONException("bad samples");
    return new SpeakerProfile(model,print,genuine,imp,o.optLong("created"),f,sh,e,o.optInt("dropped"));
  }

  @Override public String toString(){return "SpeakerProfile"+Arrays.toString(genuine);}
}
