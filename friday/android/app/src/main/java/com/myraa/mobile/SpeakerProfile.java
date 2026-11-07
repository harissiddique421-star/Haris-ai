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
  static final int MAX_IMPOSTORS=12;

  final float[] print;
  final double[] genuine;
  final List<Double> impostor;
  final long created;

  SpeakerProfile(float[] print,double[] genuine,List<Double> impostor,long created){
    this.print=print;this.genuine=genuine;this.impostor=new ArrayList<>(impostor);this.created=created;
  }

  /** Sensitivity: strict = fewer strangers accepted, relaxed = fewer of your own commands missed. */
  static double margin(String sensitivity){return "strict".equals(sensitivity)?0.06:"relaxed".equals(sensitivity)?0.13:0.10;}
  static String sensitivity(String s){return "strict".equals(s)||"relaxed".equals(s)?s:"balanced";}

  double meanGenuine(){double s=0;for(double g:genuine)s+=g;return genuine.length==0?0:s/genuine.length;}
  double maxImpostor(){double m=-1;for(double x:impostor)m=Math.max(m,x);return m;}

  /** Lowest similarity accepted as the owner. */
  double threshold(String sensitivity){
    double t=Math.max(MIN_THRESHOLD,Math.min(MAX_THRESHOLD,meanGenuine()-margin(sensitivity)));
    // Someone else scored this high on this phone: stay above them (a little, but not without limit).
    if(!impostor.isEmpty())t=Math.max(t,Math.min(MAX_WITH_IMPOSTORS,maxImpostor()+0.04));
    return t;
  }

  /** Similarity of a voiceprint to the owner (cosine, 1 = same direction). */
  double score(float[] e){return e==null?0:VoiceEncoder.cosine(e,print);}

  SpeakerProfile withImpostor(double score){
    List<Double> list=new ArrayList<>(impostor);list.add(score);
    while(list.size()>MAX_IMPOSTORS)list.remove(0);
    return new SpeakerProfile(print,genuine,list,created);
  }

  /**
   * Builds the profile from each sentence's voiceprint over the whole sentence (full) and over its
   * first CHECK_MS (short). Throws IllegalArgumentException with a message for the user when the
   * recordings cannot make a reliable profile.
   */
  static SpeakerProfile build(List<float[]> full,List<float[]> shortPrints,long now){
    int n=full.size();
    if(n<4||shortPrints.size()!=n)throw new IllegalArgumentException("I need at least four clear sentences.");
    float[] sum=new float[VoiceEncoder.DIM];
    for(float[] e:full)for(int k=0;k<sum.length;k++)sum[k]+=e[k];
    double[] genuine=new double[n];double worst=1;
    for(int i=0;i<n;i++){
      float[] others=new float[VoiceEncoder.DIM];
      for(int k=0;k<others.length;k++)others[k]=sum[k]-full.get(i)[k];
      others=VoiceEncoder.normalize(others);
      genuine[i]=VoiceEncoder.cosine(shortPrints.get(i),others);
      worst=Math.min(worst,VoiceEncoder.cosine(full.get(i),others));
    }
    if(worst<MIN_CONSISTENCY)throw new IllegalArgumentException("Your sentences didn't sound like the same voice. Record again alone, in a quiet place, at your normal speaking distance.");
    return new SpeakerProfile(VoiceEncoder.normalize(sum),genuine,new ArrayList<Double>(),now);
  }

  String summary(String sensitivity){
    return String.format(Locale.ROOT,"%d sentences, your score %.2f, needs %.2f%s",genuine.length,meanGenuine(),threshold(sensitivity),
      impostor.isEmpty()?"":String.format(Locale.ROOT,", %d other voice%s (highest %.2f)",impostor.size(),impostor.size()==1?"":"s",maxImpostor()));
  }

  JSONObject toJson() throws JSONException {
    JSONArray p=new JSONArray();for(float x:print)p.put((double)x);
    JSONArray g=new JSONArray();for(double x:genuine)g.put(x);
    JSONArray i=new JSONArray();for(double x:impostor)i.put(x);
    return new JSONObject().put("version",1).put("model","ge2e-resemblyzer").put("print",p).put("genuine",g).put("impostor",i).put("created",created);
  }
  static SpeakerProfile fromJson(JSONObject o) throws JSONException {
    if(o.optInt("version")!=1||!"ge2e-resemblyzer".equals(o.optString("model")))throw new JSONException("unknown voice profile");
    JSONArray p=o.getJSONArray("print");if(p.length()!=VoiceEncoder.DIM)throw new JSONException("bad voiceprint");
    float[] print=new float[p.length()];for(int k=0;k<print.length;k++)print[k]=(float)p.getDouble(k);
    JSONArray g=o.getJSONArray("genuine");double[] genuine=new double[g.length()];for(int k=0;k<genuine.length;k++)genuine[k]=g.getDouble(k);
    JSONArray i=o.optJSONArray("impostor");List<Double> imp=new ArrayList<>();if(i!=null)for(int k=0;k<i.length();k++)imp.add(i.getDouble(k));
    return new SpeakerProfile(print,genuine,imp,o.optLong("created"));
  }
  @Override public String toString(){return "SpeakerProfile"+Arrays.toString(genuine);}
}
