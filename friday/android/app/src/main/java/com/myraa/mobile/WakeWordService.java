package com.myraa.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.media.AudioRecord;
import android.os.*;
import android.provider.Settings;
import android.speech.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Listens for "Wake up FRIDAY", "FRIDAY wake up" or "Hey FRIDAY", then opens FRIDAY and starts
 * a Live call. It is off until the user turns it on, shows a notification the whole time and
 * pauses during a Live call (they share the microphone).
 *
 * Two ways of hearing: Android's speech recognizer (anyone's voice), or Voice Guardian, which
 * compares each burst of speech with the user's own recorded wake phrase on the phone.
 * By default it listens only while the phone is unlocked. With "listen when locked" on it also
 * listens with the screen off; waking then turns the screen on and still asks for the phone's
 * own unlock (fingerprint, face or PIN) before FRIDAY can do anything.
 * Heard text and audio are only matched, never stored.
 */
public final class WakeWordService extends Service {
  static final String WAKE="com.myraa.mobile.WAKE",PAUSE="com.myraa.mobile.WAKE_PAUSE",RESUME="com.myraa.mobile.WAKE_RESUME",STOP="com.myraa.mobile.WAKE_STOP",REFRESH="com.myraa.mobile.WAKE_REFRESH";
  private static final String NAMES="friday|fri day|fry day|fryday|fraiday|freddy|frida|फ्राइडे|फ्राईडे|फ्रायडे|फ्राइडेय|فرائیڈے|فرائڈے|فرائی ڈے";
  private static final Pattern NAME=Pattern.compile("("+NAMES+")");
  private static final Pattern WAKE_PHRASE=Pattern.compile(
    "(?<!\\p{L})(wake\\s*up|wakeup|wake|weak\\s*up|walk\\s*up|hey|hay|hi|ok|okay|वेक\\s*अप|वेकअप|हे|हाय|उठो|uth\\s*jao|utho|uttho|ویک\\s*اپ|ہے|ہیلو|اٹھو|اٹھ\\s*جاؤ)\\s*,?\\s*("+NAMES+")"
    +"|(?<!\\p{L})("+NAMES+")\\s*,?\\s*(wake\\s*up|wakeup|wake|weak\\s*up|वेक\\s*अप|वेक|उठो|uth\\s*jao|utho|uttho|ویک\\s*اپ|ویک|اٹھو|اٹھ\\s*جاؤ)");
  public static volatile boolean running;
  private static volatile boolean livePaused,screenAllowed=true;
  private final Handler main=new Handler(Looper.getMainLooper());
  /** Separate from main so listenSoon/halt never drop a wake or its follow-up checks. */
  private final Handler results=new Handler(Looper.getMainLooper());
  private SpeechRecognizer recognizer;
  private boolean listening,screenOk=true;
  private int failures;
  /** The phone's offline recognizer is tried first; if its language model is missing or it fails, the normal one is used. */
  private boolean onDevice=true;
  private BroadcastReceiver screen;
  private PowerManager.WakeLock cpu;
  private Thread guardian;private volatile boolean guardianRun;private volatile AudioRecord guardianMic;

  // ---- settings and control from the app
  static boolean wanted(Context c){return c.getSharedPreferences(Announcer.PREFS,MODE_PRIVATE).getBoolean("wakeWord",false);}
  static void setWanted(Context c,boolean on){c.getSharedPreferences(Announcer.PREFS,MODE_PRIVATE).edit().putBoolean("wakeWord",on).apply();}
  /** low = fewer false wakes (stricter), balanced, high = wakes more easily. */
  static String sensitivity(Context c){String s=c.getSharedPreferences(Announcer.PREFS,MODE_PRIVATE).getString("wakeSensitivity","balanced");return Arrays.asList("low","balanced","high").contains(s)?s:"balanced";}
  static void setSensitivity(Context c,String s){c.getSharedPreferences(Announcer.PREFS,MODE_PRIVATE).edit().putString("wakeSensitivity",Arrays.asList("low","balanced","high").contains(s)?s:"balanced").apply();}
  /** Optional: just saying "FRIDAY" wakes it (needs a clear, confident match). */
  static boolean nameOnly(Context c){return c.getSharedPreferences(Announcer.PREFS,MODE_PRIVATE).getBoolean("wakeNameOnly",false);}
  static void setNameOnly(Context c,boolean on){c.getSharedPreferences(Announcer.PREFS,MODE_PRIVATE).edit().putBoolean("wakeNameOnly",on).apply();}
  /** Lowest recognizer confidence accepted (when the recognizer reports one). */
  static float minConfidence(Context c,boolean nameOnlyMatch){String s=sensitivity(c);float base="low".equals(s)?0.3f:"high".equals(s)?0f:0.08f;return nameOnlyMatch?Math.max(base,0.5f):base;}
  /** The wake phrase is English, so an English (India) recognizer hears it best, also with a Pakistani or Indian accent. */
  static final String LANGUAGE="en-IN";
  static Intent recognizerIntent(Context c){
    return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE,LANGUAGE).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
      .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5).putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,c.getPackageName());
  }
  /** One word for the settings screen: active, paused, permission_required, battery_restricted, unavailable, stopped or off. */
  static String status(Context c){
    if(!wanted(c))return "off";
    if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return "permission_required";
    if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return "permission_required";
    if(!VoiceGuardian.active(c)&&!SpeechRecognizer.isRecognitionAvailable(c))return "unavailable";
    if(!running)return "stopped";
    if(livePaused||LiveVoiceService.running||!screenAllowed)return "paused";
    PowerManager p=c.getSystemService(PowerManager.class);
    if(p!=null&&!p.isIgnoringBatteryOptimizations(c.getPackageName()))return "battery_restricted";
    return "active";
  }
  /** After a restart or update: Android 14+ does not let a microphone service start from the boot broadcast, so ask for one tap instead. */
  static void afterBoot(Context c){
    if(!wanted(c))return;
    if(Build.VERSION.SDK_INT<34&&c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){
      try{c.startForegroundService(new Intent(c,WakeWordService.class));ControlLog.i("wake: started after restart");return;}
      catch(RuntimeException e){ControlLog.i("wake: start after restart refused ("+e.getClass().getSimpleName()+")");}
    }
    tapToStart(c,"Phone restarted","Tap once to turn \"Wake up FRIDAY\" back on. Android needs one tap after a restart.");
  }
  /** When Android won't let the microphone listener start by itself: one notification, one tap opens FRIDAY and starts it. */
  static void tapToStart(Context c,String title,String text){
    NotificationManager m=c.getSystemService(NotificationManager.class);
    m.createNotificationChannel(new NotificationChannel("wake_tap","FRIDAY wake word alerts",NotificationManager.IMPORTANCE_HIGH));
    PendingIntent open=PendingIntent.getActivity(c,6,new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    try{m.notify(45,new Notification.Builder(c,"wake_tap").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(title).setContentText(text)
      .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build());}catch(SecurityException ignored){}
  }
  static boolean whenLocked(Context c){return c.getSharedPreferences(Announcer.PREFS,MODE_PRIVATE).getBoolean("wakeLocked",false);}
  static void setWhenLocked(Context c,boolean on){c.getSharedPreferences(Announcer.PREFS,MODE_PRIVATE).edit().putBoolean("wakeLocked",on).apply();}
  /** Starts the listener if the user turned it on. Only call while FRIDAY is on screen: Android does not let a microphone service start from the background. */
  static String ensure(Context c){
    if(!wanted(c)){if(running)c.startService(new Intent(c,WakeWordService.class).setAction(STOP));return "off";}
    if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return "needs_microphone";
    if(!VoiceGuardian.active(c)&&!SpeechRecognizer.isRecognitionAvailable(c))return "unavailable";
    if(!running){try{c.startForegroundService(new Intent(c,WakeWordService.class));}catch(RuntimeException e){ControlLog.i("wake: could not start ("+e.getClass().getSimpleName()+")");return "failed";}}
    return "listening";
  }
  /** Settings changed (Guardian, listen when locked): the running listener picks them up. */
  static void refresh(Context c){
    if(running){try{c.startService(new Intent(c,WakeWordService.class).setAction(REFRESH));}catch(RuntimeException e){ControlLog.i("wake: settings refresh not delivered ("+e.getClass().getSimpleName()+")");}}
    else ensure(c);
  }
  /** A Live call needs the microphone: pause while it runs, resume after. */
  static void livePaused(Context c,boolean paused){
    livePaused=paused;
    if(running){try{c.startService(new Intent(c,WakeWordService.class).setAction(paused?PAUSE:RESUME));}catch(RuntimeException e){ControlLog.i("wake: "+(paused?"pause":"resume")+" not delivered ("+e.getClass().getSimpleName()+")");}}
  }
  static boolean matches(String heard){
    if(heard==null)return false;
    String s=clean(heard);
    return NAME.matcher(s).find()&&WAKE_PHRASE.matcher(s).find();
  }
  /** Just the name on its own ("FRIDAY", "hey FRIDAY" is already a full phrase), nothing else in the sentence. */
  static boolean nameAlone(String heard){
    if(heard==null)return false;
    return clean(heard).matches("("+NAMES+")");
  }
  private static String clean(String heard){return heard.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\s]"," ").replaceAll("\\s+"," ").trim();}

  @Override public IBinder onBind(Intent intent){return null;}
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    String action=intent==null?null:intent.getAction();
    if(STOP.equals(action)){if(intent!=null&&intent.getBooleanExtra("user",false))setWanted(this,false);stopSelf();return START_NOT_STICKY;}
    if(!wanted(this)||checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){stopSelf();return START_NOT_STICKY;}
    if(!running){
      Notification n=notification();
      try{
        if(Build.VERSION.SDK_INT>=30)startForeground(43,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);else startForeground(43,n);
      }catch(RuntimeException e){
        // Restarted by Android in the background, where a microphone service may not start: ask for one tap.
        ControlLog.i("wake: foreground start refused");
        if(intent==null)tapToStart(this,"\"Wake up FRIDAY\" stopped","Android closed FRIDAY in the background. Tap once to start listening again.");
        stopSelf();return START_NOT_STICKY;
      }
      running=true;
      screen=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){screenOk=allowedNow();ControlLog.i("wake: "+(screenOk?"listening":"phone locked, paused"));if(screenOk)listenSoon(400);else halt();}};
      IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_SCREEN_ON);
      registerReceiver(screen,filter);
      screenOk=allowedNow();
      ControlLog.i("wake: started");
      applySettings();
    }
    if(REFRESH.equals(action)){halt();applySettings();getSystemService(NotificationManager.class).notify(43,notification());screenOk=allowedNow();}
    if(PAUSE.equals(action)){livePaused=true;halt();}
    else{if(RESUME.equals(action))livePaused=false;listenSoon(RESUME.equals(action)?1500:300);}
    // If Android kills FRIDAY to save memory, it restarts this light listener (not the AI) when it can.
    return START_STICKY;
  }
  private Notification notification(){
    NotificationManager manager=getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel("wake_word","FRIDAY wake word",NotificationManager.IMPORTANCE_MIN));
    PendingIntent open=PendingIntent.getActivity(this,3,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop=PendingIntent.getService(this,4,new Intent(this,WakeWordService.class).setAction(STOP).putExtra("user",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    String who=VoiceGuardian.active(this)?"Only your voice (Voice Guardian). ":"";
    String when=whenLocked(this)?"Also with the screen off; unlock is still needed.":"Only while your phone is unlocked.";
    return new Notification.Builder(this,"wake_word").setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentTitle("FRIDAY is listening for \"Wake up FRIDAY\"").setContentText(who+when+" Tap Stop to turn it off.")
      .setStyle(new Notification.BigTextStyle().bigText(who+when+" Tap Stop to turn it off."))
      .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
  }
  /** Keeps the processor awake for the listener while the screen is off, only when listening when locked is on. */
  private void applySettings(){
    boolean need=whenLocked(this);
    if(need&&cpu==null){PowerManager p=getSystemService(PowerManager.class);if(p!=null){cpu=p.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"friday:wakeword");cpu.setReferenceCounted(false);cpu.acquire();}}
    else if(!need&&cpu!=null){if(cpu.isHeld())cpu.release();cpu=null;}
    if(VoiceGuardian.active(this)&&recognizer!=null){try{recognizer.destroy();}catch(RuntimeException ignored){}recognizer=null;}
  }
  private boolean allowedNow(){boolean ok=whenLocked(this)||unlocked();screenAllowed=ok;return ok;}
  private boolean unlocked(){
    PowerManager power=getSystemService(PowerManager.class);KeyguardManager keyguard=getSystemService(KeyguardManager.class);
    return power!=null&&power.isInteractive()&&(keyguard==null||!keyguard.isKeyguardLocked());
  }
  private void listenSoon(long delay){main.removeCallbacksAndMessages(null);main.postDelayed(this::listen,delay);}
  private void listen(){
    if(!running||listening||livePaused||!screenOk)return;
    // The Live call is still closing (its service stops a moment after the resume): check again instead of staying silent.
    if(LiveVoiceService.running){listenSoon(2000);return;}
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ControlLog.i("wake: microphone permission missing");stopSelf();return;}
    if(VoiceGuardian.recording){listenSoon(2000);return;}
    if(VoiceGuardian.active(this)){listenGuardian();return;}
    // The phone's speech recognizer takes audio focus, so each restart paused the user's song or video.
    // While other audio plays, wait instead (Voice Guardian above uses its own microphone and keeps listening).
    if(otherAudioPlaying()){if(!musicWait){musicWait=true;ControlLog.i("wake: waiting while music or video plays");}listenSoon(2000);return;}
    musicWait=false;
    if(recognizer==null){
      boolean local=onDevice&&Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
      if(!local)onDevice=false;
      recognizer=local?SpeechRecognizer.createOnDeviceSpeechRecognizer(this):SpeechRecognizer.createSpeechRecognizer(this);
      recognizer.setRecognitionListener(new Listener());
    }
    Intent intent=recognizerIntent(this);
    if(onDevice)intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
    try{listening=true;recognizer.startListening(intent);main.postDelayed(stalled,RECOGNIZER_STALL_MS);}catch(RuntimeException e){listening=false;if(onDevice){onDevice=false;ControlLog.i("wake: offline recognizer unavailable, using the normal one");}recreate(2000);}
  }
  private boolean musicWait;
  /** Some phones' recognizers stop calling back: if a session gives no result or error in time, start a fresh one. */
  private static final long RECOGNIZER_STALL_MS=25_000;
  private final Runnable stalled=()->{if(running&&listening&&recognizer!=null){ControlLog.i("wake: recognizer stopped responding, restarting it");recreate(500);}};
  private boolean otherAudioPlaying(){android.media.AudioManager audio=getSystemService(android.media.AudioManager.class);return audio!=null&&audio.isMusicActive();}
  private void halt(){main.removeCallbacksAndMessages(null);stopGuardian();if(recognizer!=null&&listening){try{recognizer.cancel();}catch(RuntimeException ignored){}}listening=false;}
  /** Voice Guardian: our own microphone loop; each burst of speech is compared with the user's saved wake phrase. */
  private void listenGuardian(){
    if(guardian!=null&&!guardian.isAlive())guardian=null;
    if(guardian!=null){listenSoon(400);return;} // the previous loop is still closing
    listening=true;guardianRun=true;
    final Thread[] me=new Thread[1];
    me[0]=new Thread(()->{
      AudioRecord mic=null;boolean heard=false,failed=false;
      try{
        mic=VoiceGuardian.open();guardianMic=mic;mic.startRecording();
        if(mic.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("not recording");
        while(guardianRun){
          short[] burst=VoiceGuardian.nextUtterance(mic,()->!guardianRun,0);
          if(burst==null)break;
          if(VoiceGuardian.matches(this,burst)){heard=true;break;}
        }
      }catch(RuntimeException e){failed=true;ControlLog.i("wake: guardian microphone unavailable");}
      finally{guardianMic=null;if(mic!=null){try{mic.stop();}catch(RuntimeException ignored){}mic.release();}}
      final boolean woke=heard,bad=failed;
      results.post(()->{
        if(guardian!=me[0])return;
        guardian=null;listening=false;
        if(woke){woke();return;}
        if(bad){failures++;listenSoon(Math.min(60_000,1000L*(1L<<Math.min(failures,6))));}
        else{failures=0;if(guardianRun)listenSoon(500);}
      });
    },"friday-guardian");
    guardian=me[0];guardian.start();
  }
  private void stopGuardian(){guardianRun=false;AudioRecord mic=guardianMic;if(mic!=null){try{mic.stop();}catch(RuntimeException ignored){}}}
  private void recreate(long delay){if(recognizer!=null){try{recognizer.destroy();}catch(RuntimeException ignored){}recognizer=null;}listening=false;listenSoon(delay);}
  private boolean check(Bundle results){return check(results,false);}
  private boolean check(Bundle results,boolean finalResult){
    ArrayList<String> heard=results==null?null:results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    if(heard==null)return false;
    // Low recognizer confidence (often TV, music or someone far away) is ignored; unknown confidence (0 or missing) is kept as before.
    float[] conf=results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
    boolean name=nameOnly(this);
    for(int i=0;i<heard.size();i++){
      boolean full=matches(heard.get(i)),alone=!full&&name&&finalResult&&nameAlone(heard.get(i));
      if(!full&&!alone)continue;
      float c=conf!=null&&i<conf.length?conf[i]:0f;
      // Unknown confidence (0) is accepted for the full phrase, but "FRIDAY" alone needs a reported, high confidence.
      if((c>0f||alone)&&c<minConfidence(this,alone)){ControlLog.i("wake: phrase ignored, low confidence");continue;}
      woke();return true;
    }
    return false;
  }
  private void woke(){
    halt();livePaused=true;failures=0;ControlLog.i("wake: wake phrase heard");
    chime();
    boolean canOpen=MainActivity.visible||Settings.canDrawOverlays(this)||!unlocked();
    if(!canOpen)ControlLog.i("wake: Android blocks opening from the background without \"Display over other apps\"");
    Intent open=new Intent(this,MainActivity.class).setAction(WAKE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    boolean locked=!unlocked();
    if(locked)lockScreenAlert(open);
    try{startActivity(open);}catch(RuntimeException e){ControlLog.i("wake: could not open FRIDAY");}
    // If Android blocks opening from the background (no "Display over other apps"), offer a tap instead.
    results.postDelayed(()->{if(!MainActivity.visible&&!locked){
      NotificationManager manager=getSystemService(NotificationManager.class);
      manager.createNotificationChannel(new NotificationChannel("wake_tap","FRIDAY wake word alerts",NotificationManager.IMPORTANCE_HIGH));
      PendingIntent tap=PendingIntent.getActivity(this,5,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
      manager.notify(44,new Notification.Builder(this,"wake_tap").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("FRIDAY is ready").setContentText("Tap to talk.").setContentIntent(tap).setAutoCancel(true).setTimeoutAfter(20000).build());
      livePaused=false;listenSoon(3000);
    }},1500);
    // Locked phone: if FRIDAY never came up (unlock cancelled), go back to listening.
    if(locked)results.postDelayed(()->{if(!MainActivity.visible&&!LiveVoiceService.running){livePaused=false;listenSoon(500);}},30_000);
  }
  /** A short, quiet tone and buzz so the user knows FRIDAY heard them, even before the screen opens. */
  private void chime(){
    try{android.media.ToneGenerator t=new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION,60);t.startTone(android.media.ToneGenerator.TONE_PROP_ACK,180);results.postDelayed(t::release,400);}catch(RuntimeException ignored){}
    try{Vibrator v=getSystemService(Vibrator.class);if(v!=null&&v.hasVibrator())v.vibrate(VibrationEffect.createOneShot(40,VibrationEffect.DEFAULT_AMPLITUDE));}catch(RuntimeException ignored){}
  }
  /** Locked phone: a full-screen alert turns the screen on and shows FRIDAY, which then asks for the phone's unlock. */
  private void lockScreenAlert(Intent open){
    NotificationManager manager=getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel("wake_tap","FRIDAY wake word alerts",NotificationManager.IMPORTANCE_HIGH));
    PendingIntent tap=PendingIntent.getActivity(this,5,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    manager.notify(44,new Notification.Builder(this,"wake_tap").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("FRIDAY is ready").setContentText("Unlock your phone to talk.")
      .setCategory(Notification.CATEGORY_ALARM).setContentIntent(tap).setFullScreenIntent(tap,true).setAutoCancel(true).setTimeoutAfter(30000).build());
  }
  private final class Listener implements RecognitionListener{
    public void onReadyForSpeech(Bundle b){}
    public void onBeginningOfSpeech(){}
    public void onRmsChanged(float v){}
    public void onBufferReceived(byte[] b){}
    public void onEndOfSpeech(){}
    public void onEvent(int t,Bundle b){}
    public void onPartialResults(Bundle b){if(listening)check(b);}
    public void onResults(Bundle b){listening=false;failures=0;if(!check(b,true))listenSoon(200);}
    public void onError(int error){
      listening=false;
      switch(error){
        case SpeechRecognizer.ERROR_NO_MATCH:case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:failures=0;listenSoon(200);break;
        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:ControlLog.i("wake: microphone permission missing");stopSelf();break;
        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:listenSoon(1500);break;
        default:
          failures++;ControlLog.i("wake: recognizer error "+error);
          // The offline recognizer often lacks the language model (errors 12/13) or fails on some phones: switch to the normal recognizer right away.
          if(onDevice){onDevice=false;failures=0;ControlLog.i("wake: switching to the normal speech recognizer");recreate(500);break;}
          recreate(Math.min(60_000,1000L*(1L<<Math.min(failures,6))));
      }
    }
  }
  @Override public void onDestroy(){
    running=false;halt();results.removeCallbacksAndMessages(null);
    if(cpu!=null){if(cpu.isHeld())cpu.release();cpu=null;}
    if(recognizer!=null){try{recognizer.destroy();}catch(RuntimeException ignored){}recognizer=null;}
    if(screen!=null){try{unregisterReceiver(screen);}catch(RuntimeException ignored){}screen=null;}
    ControlLog.i("wake: stopped");super.onDestroy();
  }
}
