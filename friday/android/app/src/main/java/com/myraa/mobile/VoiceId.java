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
 * Enrollment (rc.50): twelve sentences at different distances and loudness, each also learned as
 * heard across a room (room-echo copy), unclear takes left out; more samples can be added later.
 *
 * Limits: speaker recognition is a probability, not proof. A recording of the owner's voice can
 * pass it (there is no replay or deepfake detection), and very short words are hard to identify.
 */
final class VoiceId {
  private static final String FILE="voice_id.bin",KEY_ALIAS="friday_voice_id";
  private static final String PREF_ON="voiceIdOn",PREF_SENS="voiceIdSensitivity",PREF_TESTS="voiceIdTests",PREF_LAST_CALL="voiceIdLastCall";
  /**
   * Enrollment: twelve sentences of different lengths, said in the ways the owner really talks to
   * the phone (close, at arm's length, from across the table, quietly, with a little noise), so the
   * voiceprint is about the voice, not one phrase or one distance. Some are in Roman Urdu, the
   * language most commands are given in, so the voiceprint also covers how the owner sounds in it.
   * {how, sentence}.
   */
  static final String[][] SENTENCES={
    {"Hold the phone normally and say","Hello Haris AI, this is my voice."},
    {"Normal voice","Hey Haris, open my assistant."},
    {"A short one","Torch on karo."},
    {"A longer one, at your normal pace","FRIDAY, tell me today's schedule and remind me about my meetings this afternoon."},
    {"Hold the phone at arm's length and say","Bhai ko WhatsApp par message bhejo."},
    {"Still at arm's length, a bit faster","FRIDAY, search YouTube for the latest AI news."},
    {"Put the phone on a table about one or two metres away and say","FRIDAY, stop talking and listen to me."},
    {"Still from the table","Kal mausam kaisa hoga?"},
    {"Quietly, as if someone is sleeping nearby","Set an alarm for six thirty in the morning."},
    {"Quietly","Read my latest notifications."},
    {"With a fan or the TV on low, normal voice","Ammi ko WhatsApp par call karo."},
    {"Your normal voice, a little higher or lower than usual","FRIDAY, how much battery do I have left?"},
  };
  /** "Add more samples": the situations where FRIDAY most often misses the owner. */
  static final String[][] EXTRA={
    {"From across the room (two or three metres)","FRIDAY, open Google Maps."},
    {"From across the room","Awaz thodi kam karo."},
    {"Quietly, phone on the table","Kitne baje hain?"},
    {"With some noise around you","FRIDAY, play some music."},
  };
  private static final int MIN_SENTENCES=8,MIN_EXTRA=2,MIN_SENTENCE_MS=900,SENTENCE_MAX_MS=9000,TEST_MAX_MS=9000;
  private static SpeakerProfile cached;
  static volatile boolean recording;

  // ---- settings
  private static SharedPreferences prefs(Context c){return c.getSharedPreferences(Announcer.PREFS,Context.MODE_PRIVATE);}
  static boolean wanted(Context c){return prefs(c).getBoolean(PREF_ON,true);}
  static void setWanted(Context c,boolean on){prefs(c).edit().putBoolean(PREF_ON,on).apply();}
  static String sensitivity(Context c){return SpeakerProfile.sensitivity(prefs(c).getString(PREF_SENS,"balanced"));}
  static void setSensitivity(Context c,String s){prefs(c).edit().putString(PREF_SENS,SpeakerProfile.sensitivity(s)).apply();}
  /**
   * Live call end of turn: how long the speaker must be quiet before FRIDAY answers (400-1000 ms,
   * default 600). Used by the Voice ID gate and by Gemini's own end-of-speech detection.
   */
  static int endMs(Context c){return VoiceGate.clampEnd(prefs(c).getInt("liveEndMs",VoiceGate.DEFAULT_END_MS));}
  static void setEndMs(Context c,int ms){prefs(c).edit().putInt("liveEndMs",VoiceGate.clampEnd(ms)).apply();}
  static boolean registered(Context c){return new File(c.getFilesDir(),FILE).isFile();}
  /** Live calls are gated only when Voice ID is on and a voice is registered. */
  static boolean active(Context c){return wanted(c)&&registered(c);}

  /** {"registered","on","sensitivity","threshold","summary","recording","tests":{...}} for Settings. */
  static JSONObject state(Context c){
    JSONObject o=new JSONObject();
    try{
      SpeakerProfile p=registered(c)?load(c):null;
      o.put("registered",p!=null).put("on",wanted(c)).put("sensitivity",sensitivity(c)).put("recording",recording)
        .put("unreadable",registered(c)&&p==null).put("models",!VoiceModels.encoderFailed()).put("endMs",endMs(c));
      if(p!=null)o.put("threshold",Math.round(p.threshold(sensitivity(c))*100)/100.0).put("summary",p.summary(sensitivity(c)))
        .put("samples",Math.max(p.genuine.length,p.samples.size())).put("canAdd",p.canAdd());
      o.put("tests",new JSONObject(prefs(c).getString(PREF_TESTS,"{}")));
      String last=prefs(c).getString(PREF_LAST_CALL,"");if(!last.isEmpty())o.put("lastCall",new JSONObject(last));
    }catch(JSONException ignored){}
    return o;
  }

  /** The last Live call's Voice ID numbers (counts, scores, mic level; no audio, no words), for Settings. */
  static void saveLastCall(Context c,JSONObject summary){
    if(summary==null||summary.optInt("owner")+summary.optInt("ignored")==0)return;
    prefs(c).edit().putString(PREF_LAST_CALL,summary.toString()).apply();
    ControlLog.i("voice id: call summary "+summary);
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
    prefs(c).edit().remove(PREF_TESTS).remove(PREF_LAST_CALL).apply();
    try{KeyStore s=KeyStore.getInstance("AndroidKeyStore");s.load(null);if(s.containsAlias(KEY_ALIAS))s.deleteEntry(KEY_ALIAS);}catch(Exception ignored){}
    ControlLog.i("voice id: profile deleted");
  }

  // ---- voiceprints
  /**
   * Voiceprints of one recording: the whole sentence, its first CHECK_MS (the part the Live gate
   * checks first) and a room-echo copy (SpeakerProfile.roomEcho) so the owner is also recognised
   * from across a room.
   */
  private static float[][] prints(VoiceEncoder enc,short[] pcm){
    float[] wav=VoiceEncoder.toFloat(pcm);
    int cut=Math.min(wav.length,SpeakerProfile.CHECK_MS*VoiceEncoder.RATE/1000+10*SileroVad.CHUNK);
    return new float[][]{enc.embed(wav),enc.embed(Arrays.copyOf(wav,cut)),enc.embed(SpeakerProfile.roomEcho(wav))};
  }

  // ---- recording (background thread; progress goes to the WebView)
  interface Progress { void update(String status,int step,int total,String message); }

  /** Records the enrollment sentences and saves the profile. done runs at the end either way. */
  static void register(Context c,CallMicPcm pcm,Progress p,Runnable done){record(c,pcm,false,p,done);}
  /** Records a few more sentences (far, quiet, noisy) and adds them to the saved profile. */
  static void addSamples(Context c,CallMicPcm pcm,Progress p,Runnable done){record(c,pcm,true,p,done);}

  private static void record(Context c,CallMicPcm pcm,boolean adding,Progress p,Runnable done){
    if(recording){pcm.close();p.update("busy",0,0,"Already recording.");return;}
    recording=true;Context app=c.getApplicationContext();
    new Thread(()->{
      CallMicPcm mic=pcm;String[][] list=adding?EXTRA:SENTENCES;int total=list.length;
      try{
        SpeakerProfile old=adding?load(app):null;
        if(adding&&(old==null||!old.canAdd())){p.update("error",0,total,old==null?"Register your voice first.":"This voice profile was made by an older FRIDAY. Tap Re-register once; after that you can add samples.");return;}
        VoiceEncoder enc=VoiceModels.encoder(app);
        if(enc==null){p.update("error",0,total,"The voice model could not be loaded on this phone.");return;}
        mic=pcm;final CallMicPcm m=mic;
        ControlLog.i("voice id: recording through the Live call microphone");
        SpeechSegmenter seg=VoiceGuardian.segmenter(app,SENTENCE_MAX_MS);
        List<float[]> full=new ArrayList<>(),shortPrints=new ArrayList<>(),echo=new ArrayList<>();
        int skipped=0;
        for(int i=0;i<total;i++){
          float[][] got=null;
          for(int attempt=0;attempt<2&&got==null;attempt++){
            p.update("listen",i+1,total,(attempt==0?list[i][0]+": ":"Once more, a little louder: ")+"\""+list[i][1]+"\"");
            short[] u=seg.next(m::read,()->false,8000);
            if(u==null&&m.failed!=null){p.update("error",i+1,total,"FRIDAY couldn't use the microphone ("+m.failed+"). Allow the microphone for FRIDAY and try again.");return;}
            if(u==null||u.length<MIN_SENTENCE_MS*VoiceEncoder.RATE/1000)continue;
            p.update("heard",i+1,total,"Checking…");
            got=prints(enc,u);
          }
          // A sentence that could not be recorded is skipped, not the whole enrollment.
          if(got==null||got[0]==null||got[1]==null||got[2]==null){skipped++;p.update("heard",i+1,total,"Skipped that one. Next…");Thread.sleep(400);continue;}
          full.add(got[0]);shortPrints.add(got[1]);echo.add(got[2]);
          p.update("heard",i+1,total,i+1<total?"Got it.":"Building your voice profile…");Thread.sleep(300);
        }
        if(full.size()<(adding?MIN_EXTRA:MIN_SENTENCES)){p.update("error",0,total,"Not enough clear sentences ("+full.size()+" of "+total+"). Try again somewhere a little quieter.");return;}
        SpeakerProfile prof;
        try{prof=adding?old.withSamples(full,shortPrints,echo,System.currentTimeMillis()):SpeakerProfile.build(full,shortPrints,echo,System.currentTimeMillis());}
        catch(IllegalArgumentException e){p.update("error",0,total,e.getMessage());return;}
        try{save(app,prof);}catch(Exception e){p.update("error",0,total,"Your voice profile could not be saved securely on this phone.");return;}
        if(!adding)prefs(app).edit().remove(PREF_TESTS).remove(PREF_LAST_CALL).putBoolean(PREF_ON,true).apply();
        ControlLog.i("voice id: "+(adding?"samples added":"registered")+" ("+prof.summary(sensitivity(app))+(skipped>0?", "+skipped+" not recorded":"")+")");
        String left=prof.dropped>0?" "+prof.dropped+" unclear recording"+(prof.dropped==1?" was":"s were")+" left out.":"";
        p.update("registered",total,total,(adding?"Added. Your voice profile now has "+prof.genuine.length+" samples.":"Your voice has been successfully registered from "+prof.genuine.length+" samples.")+left);
      }catch(InterruptedException e){Thread.currentThread().interrupt();p.update("error",0,total,"Recording stopped.");}
      catch(RuntimeException e){p.update("error",0,total,"The microphone is busy. End any call or recording and try again.");}
      finally{if(mic!=null)mic.close();recording=false;done.run();}
    },adding?"friday-voice-id-add":"friday-voice-id-enroll").start();
  }

  /**
   * Records one sentence and checks it exactly as a Live call would. who = "owner" (you) or
   * "other" (someone else). Results are counted on the phone, so Settings can show how often your
   * voice was accepted and how often other voices were refused. An "other" voice is also added to
   * the profile's calibration, so the threshold stays above it.
   */
  static void test(Context c,CallMicPcm pcm,String who,Progress p,Runnable done){
    if(recording){pcm.close();p.update("busy",0,0,"Already recording.");return;}
    boolean other="other".equals(who);
    recording=true;Context app=c.getApplicationContext();
    new Thread(()->{
      CallMicPcm mic=pcm;
      try{
        SpeakerProfile prof=load(app);VoiceEncoder enc=VoiceModels.encoder(app);
        if(prof==null){p.update("error",0,0,"Register your voice first.");return;}
        if(enc==null){p.update("error",0,0,"The voice model could not be loaded on this phone.");return;}
        final CallMicPcm m=mic;
        SpeechSegmenter seg=VoiceGuardian.segmenter(app,TEST_MAX_MS);
        p.update("listen",1,1,other?"Ask someone else to say any sentence now.":"Say any sentence now, for example \"FRIDAY, what's the weather?\"");
        short[] u=seg.next(m::read,()->false,8000);
        if(u==null){p.update("error",1,1,m.failed!=null?"FRIDAY couldn't use the microphone ("+m.failed+"). Try again.":"I didn't hear anyone. Try again.");return;}
        float[][] e=prints(enc,u);
        double first=prof.score(e[1]),whole=prof.score(e[0]),t=prof.threshold(sensitivity(app));
        // As in a call: the first 1.2 s decides, with a second look when it is close.
        boolean accepted=first>=t||(first>=t-VoiceGate.NEAR&&whole>=t);
        recordTest(app,other,accepted);
        // A score as high as the owner's own recordings is most likely the owner; storing it would lock the owner out.
        boolean ownerLike=other&&prof.tooCloseToOwner(first);
        if(other&&!ownerLike){
          try{save(app,prof.withImpostor(first));}catch(Exception ignored){}
        }
        ControlLog.i(String.format(Locale.ROOT,"voice id: test %s %s (voice %.2f, whole %.2f, needs %.2f)",other?"other voice":"my voice",accepted?"accepted":"refused",first,whole,t));
        String msg=String.format(Locale.ROOT,"%s (voice match %.2f, needs %.2f).",
          other?(ownerLike?"This sounds just like your own voice, so FRIDAY did not raise the bar (that would lock you out). If this really was someone else, choose Strict sensitivity":accepted?"This voice was ACCEPTED as you. FRIDAY has raised the bar for it; consider Strict sensitivity":"Correct: this voice was refused")
               :(accepted?"Recognised: this is your voice":"Not recognised. Try again at your normal distance, or choose Relaxed sensitivity"),first,other&&!ownerLike?prof.withImpostor(first).threshold(sensitivity(app)):t);
        p.update(accepted^other?"pass":"fail",1,1,msg);
      }catch(RuntimeException e){p.update("error",0,0,"The microphone is busy. End any call or recording and try again.");}
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
    VoiceGate gate=new VoiceGate(vad,wav->prof.score(enc.embed(wav)),t,endMs(c));
    ControlLog.i(String.format(Locale.ROOT,"voice id: Live call gated (needs %.2f, %s, turn ends after %d ms of quiet)",t,sensitivity(c),gate.endMs()));
    why[0]="on";return new VoiceGateSession(gate);
  }
}
