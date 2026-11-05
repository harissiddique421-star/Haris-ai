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
 * frames (a compact description of how the voice sounds over time) plus an average voice
 * colour. At listening time every short burst of speech is compared with the three
 * recordings by dynamic time warping, so it must be the same phrase said in a similar voice.
 * Everything runs on the phone without internet. Only these numbers are kept, in the app's
 * private storage; no audio is saved and nothing is logged except match scores.
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
  static void delete(Context c){new File(c.getFilesDir(),FILE).delete();setWanted(c,false);cached=null;}

  // ---- recording the user's voice
  interface Progress { void update(String status,int step,String message); }
  static volatile boolean recording;
  /** Records the wake phrase three times on a background thread, then saves the voice. done runs at the end either way. */
  static void record(Context c,Progress p,Runnable done){
    if(recording){p.update("busy",0,"Already recording.");return;}
    recording=true;Context app=c.getApplicationContext();
    new Thread(()->{
      AudioRecord r=null;
      try{
        Thread.sleep(700); // let the wake word listener release the microphone
        r=open();r.startRecording();List<short[]> takes=new ArrayList<>();
        for(int i=1;i<=3;i++){
          p.update("listen",i,"Say \"Wake up FRIDAY\" now ("+i+" of 3).");
          short[] u=nextUtterance(r,()->false,7000);
          if(u==null){p.update("error",i,"I didn't hear you. Tap Record my voice and try again in a quiet place.");return;}
          takes.add(u);p.update("heard",i,i<3?"Got it.":"Checking your voice…");Thread.sleep(400);
        }
        String problem=enroll(app,takes);
        p.update(problem==null?"enrolled":"error",3,problem==null?"Your voice is saved. FRIDAY now wakes only for your voice.":problem);
      }catch(InterruptedException e){Thread.currentThread().interrupt();p.update("error",0,"Recording stopped.");}
      catch(RuntimeException e){p.update("error",0,"The microphone is busy. End any call or recording and try again.");}
      finally{if(r!=null){try{r.stop();}catch(RuntimeException ignored){}r.release();}recording=false;done.run();}
    },"friday-guardian-enroll").start();
  }

  // ---- audio capture
  static AudioRecord open(){
    int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
    AudioRecord r=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,RATE));
    if(r.getState()!=AudioRecord.STATE_INITIALIZED){r.release();throw new IllegalStateException("microphone busy");}
    return r;
  }
  interface Stop { boolean now(); }
  /**
   * Waits for one burst of speech and returns it, trimmed, or null when stopped or when
   * nothing was said before the timeout (0 = no timeout). Bursts longer than 3.5 s are skipped.
   */
  static short[] nextUtterance(AudioRecord r,Stop stop,long timeoutMs){
    final int pre=25,endSilence=40,minFrames=35,maxFrames=350;
    short[] hop=new short[HOP];
    ArrayDeque<short[]> before=new ArrayDeque<>();
    ArrayList<short[]> speech=new ArrayList<>();
    double floor=150;int loud=0,quiet=0;boolean inSpeech=false,tooLong=false;
    long deadline=timeoutMs>0?System.currentTimeMillis()+timeoutMs:Long.MAX_VALUE;
    while(!stop.now()){
      int got=0;
      while(got<HOP){int n=r.read(hop,got,HOP-got);if(n<=0){return null;}got+=n;}
      if(stop.now())return null;
      double e=rms(hop);short[] copy=hop.clone();
      boolean voiced=e>Math.max(floor*3.0,260);
      if(!inSpeech){
        if(System.currentTimeMillis()>deadline)return null;
        before.add(copy);if(before.size()>pre)before.poll();
        if(voiced){if(++loud>=3){inSpeech=true;speech.addAll(before);before.clear();quiet=0;}}
        else{loud=0;floor=Math.max(40,floor*0.97+e*0.03);}
        continue;
      }
      speech.add(copy);
      if(speech.size()>maxFrames){tooLong=true;}
      quiet=voiced?0:quiet+1;
      if(quiet>=endSilence){
        if(!tooLong&&speech.size()-quiet>=minFrames){
          int keep=speech.size()-quiet+10;short[] out=new short[keep*HOP];
          for(int i=0;i<keep;i++)System.arraycopy(speech.get(i),0,out,i*HOP,HOP);
          return out;
        }
        speech.clear();inSpeech=false;tooLong=false;loud=0;
        if(timeoutMs>0&&System.currentTimeMillis()>deadline)return null;
      }
    }
    return null;
  }
  private static double rms(short[] s){double t=0;for(short v:s)t+=(double)v*v;return Math.sqrt(t/s.length);}

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
  private static final class Voice { float[][][] templates;float[] colour;double limit,colourLimit; }
  private static Voice cached;

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
    try{save(c,v);}catch(IOException|JSONException e){return "Your voice could not be saved on this phone.";}
    cached=v;setWanted(c,true);
    ControlLog.i("guardian: voice saved (limit "+String.format(Locale.ROOT,"%.2f",v.limit)+")");
    return null;
  }
  /** True when the burst is the wake phrase in the saved voice. Logs only the scores. */
  static boolean matches(Context c,short[] pcm){
    Voice v=load(c);if(v==null)return false;
    Features f=features(pcm);
    double[] d=new double[v.templates.length];for(int i=0;i<d.length;i++)d[i]=dtw(f.frames,v.templates[i]);
    Arrays.sort(d);double phrase=d.length>1?(d[0]+d[1])/2:d[0],voice=distance(f.colour,v.colour);
    boolean ok=phrase<=v.limit&&voice<=v.colourLimit;
    if(!Double.isInfinite(phrase)&&phrase<=v.limit*1.6)
      ControlLog.i("guardian: "+(ok?"matched":"not matched")+String.format(Locale.ROOT," (phrase %.2f/%.2f, voice %.1f/%.1f)",phrase,v.limit,voice,v.colourLimit));
    return ok;
  }
  private static void save(Context c,Voice v) throws IOException,JSONException {
    JSONObject o=new JSONObject().put("version",1).put("limit",v.limit).put("colourLimit",v.colourLimit).put("colour",pack(new float[][]{v.colour}));
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
      JSONArray t=o.getJSONArray("templates");v.templates=new float[t.length()][][];for(int i=0;i<t.length();i++)v.templates[i]=unpack(t.getString(i));
      return cached=v;
    }catch(IOException|JSONException|RuntimeException e){ControlLog.i("guardian: saved voice unreadable");return null;}
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
