package com.myraa.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * HARIS Voice ID: the owner records several different sentences; FRIDAY keeps a voiceprint (see
 * SpeakerProfile) and, during a Live call, sends Gemini only the owner's speech (see VoiceGate).
 *
 * Privacy: no audio is saved or uploaded. The profile (256 numbers and a few scores) is encrypted
 * with AES-GCM under a key held by the Android Keystore, which never leaves the phone's secure
 * hardware where the phone has it. Delete removes the file and the key.
 *
 * Limits: speaker recognition is a probability, not proof. A recording of the owner's voice can
 * pass it (there is no replay or deepfake detection), and very short words are hard to identify.
 */
final class VoiceId {
  private static final String FILE="voice_id.bin",KEY_ALIAS="friday_voice_id";
  private static final String PREF_ON="voiceIdOn",PREF_SENS="voiceIdSensitivity",PREF_TESTS="voiceIdTests";
  /** Sentences to read during enrollment; different words so the voiceprint is about the voice, not the phrase. */
  static final String[] SENTENCES={
    "Hello Haris AI, this is my voice.",
    "Hey Haris, open my assistant.",
    "Turn on the flashlight.",
    "Tell me today's schedule.",
    "Send a WhatsApp message.",
    "FRIDAY, stop talking and listen to me.",
  };
  private static final int MIN_SENTENCES=5,MIN_SENTENCE_MS=900,SENTENCE_MAX_MS=9000,TEST_MAX_MS=9000;
  private static SpeakerProfile cached;
  static volatile boolean recording;

  // ---- settings
  private static SharedPreferences prefs(Context c){return c.getSharedPreferences(Announcer.PREFS,Context.MODE_PRIVATE);}
  static boolean wanted(Context c){return prefs(c).getBoolean(PREF_ON,true);}
  static void setWanted(Context c,boolean on){prefs(c).edit().putBoolean(PREF_ON,on).apply();}
  static String sensitivity(Context c){return SpeakerProfile.sensitivity(prefs(c).getString(PREF_SENS,"balanced"));}
  static void setSensitivity(Context c,String s){prefs(c).edit().putString(PREF_SENS,SpeakerProfile.sensitivity(s)).apply();}
  static boolean registered(Context c){return new File(c.getFilesDir(),FILE).isFile();}
  /** Live calls are gated only when Voice ID is on and a voice is registered. */
  static boolean active(Context c){return wanted(c)&&registered(c);}

  /** {"registered","on","sensitivity","threshold","summary","recording","tests":{...}} for Settings. */
  static JSONObject state(Context c){
    JSONObject o=new JSONObject();
    try{
      SpeakerProfile p=registered(c)?load(c):null;
      o.put("registered",p!=null).put("on",wanted(c)).put("sensitivity",sensitivity(c)).put("recording",recording)
        .put("unreadable",registered(c)&&p==null).put("models",!VoiceModels.encoderFailed());
      if(p!=null)o.put("threshold",Math.round(p.threshold(sensitivity(c))*100)/100.0).put("summary",p.summary(sensitivity(c)));
      o.put("tests",new JSONObject(prefs(c).getString(PREF_TESTS,"{}")));
    }catch(JSONException ignored){}
    return o;
  }

  // ---- the encrypted profile
  static synchronized SpeakerProfile load(Context c){
    if(cached!=null)return cached;
    File f=new File(c.getFilesDir(),FILE);if(!f.isFile())return null;
    try(FileInputStream in=new FileInputStream(f)){
      byte[] all=new byte[(int)Math.min(f.length(),1_000_000)];int got=0,n;while(got<all.length&&(n=in.read(all,got,all.length-got))>0)got+=n;
      if(got<13)throw new IOException("short");
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,Arrays.copyOfRange(all,0,12)));
      byte[] plain=cipher.doFinal(all,12,got-12);
      return cached=SpeakerProfile.fromJson(new JSONObject(new String(plain,StandardCharsets.UTF_8)));
    }catch(Exception e){ControlLog.i("voice id: saved profile unreadable ("+e.getClass().getSimpleName()+")");return null;}
  }
  private static synchronized void save(Context c,SpeakerProfile p) throws Exception {
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));
    byte[] iv=cipher.getIV(),body=cipher.doFinal(p.toJson().toString().getBytes(StandardCharsets.UTF_8));
    if(iv.length!=12)throw new IOException("unexpected IV");
    File tmp=new File(c.getFilesDir(),FILE+".tmp");
    try(FileOutputStream out=new FileOutputStream(tmp)){out.write(iv);out.write(body);}
    if(!tmp.renameTo(new File(c.getFilesDir(),FILE)))throw new IOException("rename");
    cached=p;
  }
  private static SecretKey key(boolean create) throws Exception {
    KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
    if(store.containsAlias(KEY_ALIAS))return (SecretKey)store.getKey(KEY_ALIAS,null);
    if(!create)throw new IOException("no key");
    KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
    return g.generateKey();
  }
  static synchronized void delete(Context c){
    new File(c.getFilesDir(),FILE).delete();new File(c.getFilesDir(),FILE+".tmp").delete();cached=null;
    prefs(c).edit().remove(PREF_TESTS).apply();
    try{KeyStore s=KeyStore.getInstance("AndroidKeyStore");s.load(null);if(s.containsAlias(KEY_ALIAS))s.deleteEntry(KEY_ALIAS);}catch(Exception ignored){}
    ControlLog.i("voice id: profile deleted");
  }

  // ---- voiceprints
  /** Voiceprint of the whole recording and of its first CHECK_MS, the part the Live gate checks first. */
  private static float[][] prints(VoiceEncoder enc,short[] pcm){
    float[] wav=VoiceEncoder.toFloat(pcm);
    int cut=Math.min(wav.length,SpeakerProfile.CHECK_MS*VoiceEncoder.RATE/1000+10*SileroVad.CHUNK);
    return new float[][]{enc.embed(wav),enc.embed(Arrays.copyOf(wav,cut))};
  }

  // ---- recording (background thread; progress goes to the WebView)
  interface Progress { void update(String status,int step,int total,String message); }

  /** Records the enrollment sentences and saves the profile. done runs at the end either way. */
  static void register(Context c,Progress p,Runnable done){
    if(recording){p.update("busy",0,0,"Already recording.");return;}
    recording=true;Context app=c.getApplicationContext();
    new Thread(()->{
      MicInput mic=null;int total=SENTENCES.length;
      try{
        VoiceEncoder enc=VoiceModels.encoder(app);
        if(enc==null){p.update("error",0,total,"The voice model could not be loaded on this phone.");return;}
        Thread.sleep(700); // let the wake word listener release the microphone
        mic=MicInput.open();mic.start();final MicInput m=mic;
        SpeechSegmenter seg=VoiceGuardian.segmenter(app,SENTENCE_MAX_MS);
        List<float[]> full=new ArrayList<>(),shortPrints=new ArrayList<>();
        for(int i=0;i<total;i++){
          float[][] got=null;
          for(int attempt=0;attempt<2&&got==null;attempt++){
            p.update("listen",i+1,total,(attempt==0?"Say: ":"Once more, a bit louder: ")+"\""+SENTENCES[i]+"\"");
            short[] u=seg.next(m::readFully,()->false,8000);
            if(u==null){p.update("error",i+1,total,"I didn't hear you. Tap Register my voice and try again in a quiet place.");return;}
            if(u.length<MIN_SENTENCE_MS*VoiceEncoder.RATE/1000)continue;
            p.update("heard",i+1,total,"Checking…");
            got=prints(enc,u);
          }
          if(got==null||got[0]==null||got[1]==null){p.update("error",i+1,total,"That was too short. Read each sentence fully, then try again.");return;}
          full.add(got[0]);shortPrints.add(got[1]);
          p.update("heard",i+1,total,i+1<total?"Got it.":"Building your voice profile…");Thread.sleep(300);
        }
        if(full.size()<MIN_SENTENCES){p.update("error",0,total,"Not enough clear sentences. Try again.");return;}
        SpeakerProfile prof;
        try{prof=SpeakerProfile.build(full,shortPrints,System.currentTimeMillis());}
        catch(IllegalArgumentException e){p.update("error",0,total,e.getMessage());return;}
        try{save(app,prof);}catch(Exception e){p.update("error",0,total,"Your voice profile could not be saved securely on this phone.");return;}
        prefs(app).edit().remove(PREF_TESTS).putBoolean(PREF_ON,true).apply();
        ControlLog.i("voice id: registered ("+prof.summary(sensitivity(app))+")");
        p.update("registered",total,total,"Your voice has been successfully registered.");
      }catch(InterruptedException e){Thread.currentThread().interrupt();p.update("error",0,total,"Recording stopped.");}
      catch(RuntimeException e){p.update("error",0,total,"The microphone is busy. End any call or recording and try again.");}
      finally{if(mic!=null)mic.close();recording=false;done.run();}
    },"friday-voice-id-enroll").start();
  }

  /**
   * Records one sentence and checks it exactly as a Live call would. who = "owner" (you) or
   * "other" (someone else). Results are counted on the phone, so Settings can show how often your
   * voice was accepted and how often other voices were refused. An "other" voice is also added to
   * the profile's calibration, so the threshold stays above it.
   */
  static void test(Context c,String who,Progress p,Runnable done){
    if(recording){p.update("busy",0,0,"Already recording.");return;}
    boolean other="other".equals(who);
    recording=true;Context app=c.getApplicationContext();
    new Thread(()->{
      MicInput mic=null;
      try{
        SpeakerProfile prof=load(app);VoiceEncoder enc=VoiceModels.encoder(app);
        if(prof==null){p.update("error",0,0,"Register your voice first.");return;}
        if(enc==null){p.update("error",0,0,"The voice model could not be loaded on this phone.");return;}
        Thread.sleep(700);
        mic=MicInput.open();mic.start();final MicInput m=mic;
        SpeechSegmenter seg=VoiceGuardian.segmenter(app,TEST_MAX_MS);
        p.update("listen",1,1,other?"Ask someone else to say any sentence now.":"Say any sentence now, for example \"FRIDAY, what's the weather?\"");
        short[] u=seg.next(m::readFully,()->false,8000);
        if(u==null){p.update("error",1,1,"I didn't hear anyone. Try again.");return;}
        float[][] e=prints(enc,u);
        double first=prof.score(e[1]),whole=prof.score(e[0]),t=prof.threshold(sensitivity(app));
        // As in a call: the first 1.2 s decides, with a second look when it is close.
        boolean accepted=first>=t||(first>=t-VoiceGate.NEAR&&whole>=t);
        recordTest(app,other,accepted);
        if(other){
          try{save(app,prof.withImpostor(first));}catch(Exception ignored){}
        }
        ControlLog.i(String.format(Locale.ROOT,"voice id: test %s %s (voice %.2f, whole %.2f, needs %.2f)",other?"other voice":"my voice",accepted?"accepted":"refused",first,whole,t));
        String msg=String.format(Locale.ROOT,"%s (voice match %.2f, needs %.2f).",
          other?(accepted?"This voice was ACCEPTED as you. FRIDAY has raised the bar for it; consider Strict sensitivity":"Correct: this voice was refused")
               :(accepted?"Recognised: this is your voice":"Not recognised. Try again at your normal distance, or choose Relaxed sensitivity"),first,other?prof.withImpostor(first).threshold(sensitivity(app)):t);
        p.update(accepted^other?"pass":"fail",1,1,msg);
      }catch(InterruptedException e){Thread.currentThread().interrupt();p.update("error",0,0,"Test stopped.");}
      catch(RuntimeException e){p.update("error",0,0,"The microphone is busy. End any call or recording and try again.");}
      finally{if(mic!=null)mic.close();recording=false;done.run();}
    },"friday-voice-id-test").start();
  }
  private static synchronized void recordTest(Context c,boolean other,boolean accepted){
    try{
      JSONObject t=new JSONObject(prefs(c).getString(PREF_TESTS,"{}"));
      String k=other?"other":"owner";
      t.put(k+"Tests",t.optInt(k+"Tests")+1).put(k+"Accepted",t.optInt(k+"Accepted")+(accepted?1:0));
      prefs(c).edit().putString(PREF_TESTS,t.toString()).apply();
    }catch(JSONException ignored){}
  }

  // ---- the Live call gate
  /** A gate for one Live call, or null with the reason in why[0] when Voice ID is off or cannot run. */
  static VoiceGateSession startGate(Context c,String[] why){
    if(!wanted(c)){why[0]="off";return null;}
    if(!registered(c)){why[0]="not registered";return null;}
    SpeakerProfile prof=load(c);if(prof==null){why[0]="profile unreadable: register again";return null;}
    SileroVad vad=VoiceModels.vad(c);VoiceEncoder enc=VoiceModels.encoder(c);
    if(vad==null||enc==null){why[0]="voice models unavailable";return null;}
    double t=prof.threshold(sensitivity(c));
    VoiceGate gate=new VoiceGate(vad,wav->prof.score(enc.embed(wav)),t,VoiceGuardian.silenceMs(c));
    ControlLog.i(String.format(Locale.ROOT,"voice id: Live call gated (needs %.2f, %s)",t,sensitivity(c)));
    why[0]="on";return new VoiceGateSession(gate);
  }
}
