package com.myraa.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.media.AudioRecord;
import android.os.*;
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
  private static final Pattern NAME=Pattern.compile("(friday|fri day|freddy|फ्राइडे|फ्राईडे|फ्रायडे)");
  private static final Pattern WAKE_PHRASE=Pattern.compile(
    "(wake\\s*up|wakeup|hey|hay|hi|ok|okay|वेक\\s*अप|हे|उठो|uth\\s*jao|utho)\\s*,?\\s*(friday|fri day|freddy|फ्राइडे|फ्राईडे|फ्रायडे)"
    +"|(friday|fri day|freddy|फ्राइडे|फ्राईडे|फ्रायडे)\\s*,?\\s*(wake\\s*up|wakeup|wake|वेक\\s*अप|वेक|उठो|uth\\s*jao|utho)");
  public static volatile boolean running;
  private static volatile boolean livePaused;
  private final Handler main=new Handler(Looper.getMainLooper());
  /** Separate from main so listenSoon/halt never drop a wake or its follow-up checks. */
  private final Handler results=new Handler(Looper.getMainLooper());
  private SpeechRecognizer recognizer;
  private boolean listening,screenOk=true;
  private int failures;
  private BroadcastReceiver screen;
  private PowerManager.WakeLock cpu;
  private Thread guardian;private volatile boolean guardianRun;private volatile AudioRecord guardianMic;

  // ---- settings and control from the app
  static boolean wanted(Context c){return c.getSharedPreferences(Announcer.PREFS,MODE_PRIVATE).getBoolean("wakeWord",false);}
  static void setWanted(Context c,boolean on){c.getSharedPreferences(Announcer.PREFS,MODE_PRIVATE).edit().putBoolean("wakeWord",on).apply();}
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
    if(running){try{c.startService(new Intent(c,WakeWordService.class).setAction(REFRESH));}catch(RuntimeException ignored){}}
    else ensure(c);
  }
  /** A Live call needs the microphone: pause while it runs, resume after. */
  static void livePaused(Context c,boolean paused){
    livePaused=paused;
    if(running){try{c.startService(new Intent(c,WakeWordService.class).setAction(paused?PAUSE:RESUME));}catch(RuntimeException ignored){}}
  }
  static boolean matches(String heard){
    if(heard==null)return false;
    String s=heard.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\s]"," ").replaceAll("\\s+"," ").trim();
    return NAME.matcher(s).find()&&WAKE_PHRASE.matcher(s).find();
  }

  @Override public IBinder onBind(Intent intent){return null;}
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    String action=intent==null?null:intent.getAction();
    if(STOP.equals(action)){stopSelf();return START_NOT_STICKY;}
    if(!running){
      Notification n=notification();
      try{
        if(Build.VERSION.SDK_INT>=30)startForeground(43,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);else startForeground(43,n);
      }catch(RuntimeException e){ControlLog.i("wake: foreground start refused");stopSelf();return START_NOT_STICKY;}
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
    return START_NOT_STICKY;
  }
  private Notification notification(){
    NotificationManager manager=getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel("wake_word","FRIDAY wake word",NotificationManager.IMPORTANCE_MIN));
    PendingIntent open=PendingIntent.getActivity(this,3,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop=PendingIntent.getService(this,4,new Intent(this,WakeWordService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
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
  private boolean allowedNow(){return whenLocked(this)||unlocked();}
  private boolean unlocked(){
    PowerManager power=getSystemService(PowerManager.class);KeyguardManager keyguard=getSystemService(KeyguardManager.class);
    return power!=null&&power.isInteractive()&&(keyguard==null||!keyguard.isKeyguardLocked());
  }
  private void listenSoon(long delay){main.removeCallbacksAndMessages(null);main.postDelayed(this::listen,delay);}
  private void listen(){
    if(!running||listening||livePaused||LiveVoiceService.running||!screenOk)return;
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ControlLog.i("wake: microphone permission missing");stopSelf();return;}
    if(VoiceGuardian.recording){listenSoon(2000);return;}
    if(VoiceGuardian.active(this)){listenGuardian();return;}
    if(recognizer==null){
      recognizer=Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(this)?SpeechRecognizer.createOnDeviceSpeechRecognizer(this):SpeechRecognizer.createSpeechRecognizer(this);
      recognizer.setRecognitionListener(new Listener());
    }
    String language=getSharedPreferences(Announcer.PREFS,MODE_PRIVATE).getString("language","hi-IN");
    Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE,language).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
      .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3)
      .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,getPackageName());
    try{listening=true;recognizer.startListening(intent);}catch(RuntimeException e){listening=false;recreate(2000);}
  }
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
        else if(guardianRun)listenSoon(500);
      });
    },"friday-guardian");
    guardian=me[0];guardian.start();
  }
  private void stopGuardian(){guardianRun=false;AudioRecord mic=guardianMic;if(mic!=null){try{mic.stop();}catch(RuntimeException ignored){}}}
  private void recreate(long delay){if(recognizer!=null){try{recognizer.destroy();}catch(RuntimeException ignored){}recognizer=null;}listening=false;listenSoon(delay);}
  private boolean check(Bundle results){
    ArrayList<String> heard=results==null?null:results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    if(heard==null)return false;
    for(String h:heard)if(matches(h)){woke();return true;}
    return false;
  }
  private void woke(){
    halt();livePaused=true;failures=0;ControlLog.i("wake: wake phrase heard");
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
    public void onResults(Bundle b){listening=false;failures=0;if(!check(b))listenSoon(200);}
    public void onError(int error){
      listening=false;
      switch(error){
        case SpeechRecognizer.ERROR_NO_MATCH:case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:failures=0;listenSoon(200);break;
        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:ControlLog.i("wake: microphone permission missing");stopSelf();break;
        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:listenSoon(1500);break;
        default:
          failures++;ControlLog.i("wake: recognizer error "+error);
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
