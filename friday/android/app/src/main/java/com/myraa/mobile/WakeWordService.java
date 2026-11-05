package com.myraa.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.speech.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Listens for "Wake up FRIDAY", "FRIDAY wake up" or "Hey FRIDAY" with Android's speech
 * recognizer while the phone is unlocked, then opens FRIDAY and starts a Live call. It is off
 * until the user turns it on, shows a notification the whole time, pauses during a Live call
 * (they share the microphone) and stops listening while the phone is locked or the screen is
 * off, so nobody can wake FRIDAY on a locked phone. Heard text is only matched, never stored.
 */
public final class WakeWordService extends Service {
  static final String WAKE="com.myraa.mobile.WAKE",PAUSE="com.myraa.mobile.WAKE_PAUSE",RESUME="com.myraa.mobile.WAKE_RESUME",STOP="com.myraa.mobile.WAKE_STOP";
  private static final Pattern NAME=Pattern.compile("(friday|fri day|freddy|फ्राइडे|फ्राईडे|फ्रायडे)");
  private static final Pattern WAKE_PHRASE=Pattern.compile(
    "(wake\\s*up|wakeup|hey|hay|hi|ok|okay|वेक\\s*अप|हे|उठो|uth\\s*jao|utho)\\s*,?\\s*(friday|fri day|freddy|फ्राइडे|फ्राईडे|फ्रायडे)"
    +"|(friday|fri day|freddy|फ्राइडे|फ्राईडे|फ्रायडे)\\s*,?\\s*(wake\\s*up|wakeup|wake|वेक\\s*अप|वेक|उठो|uth\\s*jao|utho)");
  public static volatile boolean running;
  private static volatile boolean livePaused;
  private final Handler main=new Handler(Looper.getMainLooper());
  private SpeechRecognizer recognizer;
  private boolean listening,screenOk=true;
  private int failures;
  private BroadcastReceiver screen;

  // ---- settings and control from the app
  static boolean wanted(Context c){return c.getSharedPreferences(Announcer.PREFS,MODE_PRIVATE).getBoolean("wakeWord",false);}
  static void setWanted(Context c,boolean on){c.getSharedPreferences(Announcer.PREFS,MODE_PRIVATE).edit().putBoolean("wakeWord",on).apply();}
  /** Starts the listener if the user turned it on. Only call while FRIDAY is on screen: Android does not let a microphone service start from the background. */
  static String ensure(Context c){
    if(!wanted(c)){if(running)c.startService(new Intent(c,WakeWordService.class).setAction(STOP));return "off";}
    if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return "needs_microphone";
    if(!SpeechRecognizer.isRecognitionAvailable(c))return "unavailable";
    if(!running){try{c.startForegroundService(new Intent(c,WakeWordService.class));}catch(RuntimeException e){ControlLog.i("wake: could not start ("+e.getClass().getSimpleName()+")");return "failed";}}
    return "listening";
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
      NotificationManager manager=getSystemService(NotificationManager.class);
      manager.createNotificationChannel(new NotificationChannel("wake_word","FRIDAY wake word",NotificationManager.IMPORTANCE_MIN));
      PendingIntent open=PendingIntent.getActivity(this,3,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
      PendingIntent stop=PendingIntent.getService(this,4,new Intent(this,WakeWordService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
      Notification n=new Notification.Builder(this,"wake_word").setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("FRIDAY is listening for \"Wake up FRIDAY\"").setContentText("Only while your phone is unlocked. Tap Stop to turn it off.")
        .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
      try{
        if(Build.VERSION.SDK_INT>=30)startForeground(43,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);else startForeground(43,n);
      }catch(RuntimeException e){ControlLog.i("wake: foreground start refused");stopSelf();return START_NOT_STICKY;}
      running=true;
      screen=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){screenOk=unlocked();ControlLog.i("wake: "+(screenOk?"phone unlocked, listening":"phone locked, paused"));if(screenOk)listenSoon(400);else halt();}};
      IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_SCREEN_ON);
      registerReceiver(screen,filter);
      screenOk=unlocked();
      ControlLog.i("wake: started");
    }
    if(PAUSE.equals(action)){livePaused=true;halt();}
    else{if(RESUME.equals(action))livePaused=false;listenSoon(RESUME.equals(action)?1500:300);}
    return START_NOT_STICKY;
  }
  private boolean unlocked(){
    PowerManager power=getSystemService(PowerManager.class);KeyguardManager keyguard=getSystemService(KeyguardManager.class);
    return power!=null&&power.isInteractive()&&(keyguard==null||!keyguard.isKeyguardLocked());
  }
  private void listenSoon(long delay){main.removeCallbacksAndMessages(null);main.postDelayed(this::listen,delay);}
  private void listen(){
    if(!running||listening||livePaused||LiveVoiceService.running||!screenOk)return;
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ControlLog.i("wake: microphone permission missing");stopSelf();return;}
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
  private void halt(){main.removeCallbacksAndMessages(null);if(recognizer!=null&&listening){try{recognizer.cancel();}catch(RuntimeException ignored){}}listening=false;}
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
    try{startActivity(open);}catch(RuntimeException e){ControlLog.i("wake: could not open FRIDAY");}
    // If Android blocks opening from the background (no "Display over other apps"), offer a tap instead.
    main.postDelayed(()->{if(!MainActivity.visible){
      NotificationManager manager=getSystemService(NotificationManager.class);
      manager.createNotificationChannel(new NotificationChannel("wake_tap","FRIDAY wake word alerts",NotificationManager.IMPORTANCE_HIGH));
      PendingIntent tap=PendingIntent.getActivity(this,5,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
      manager.notify(44,new Notification.Builder(this,"wake_tap").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("FRIDAY is ready").setContentText("Tap to talk.").setContentIntent(tap).setAutoCancel(true).setTimeoutAfter(20000).build());
      livePaused=false;listenSoon(3000);
    }},1500);
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
    running=false;halt();
    if(recognizer!=null){try{recognizer.destroy();}catch(RuntimeException ignored){}recognizer=null;}
    if(screen!=null){try{unregisterReceiver(screen);}catch(RuntimeException ignored){}screen=null;}
    ControlLog.i("wake: stopped");super.onDestroy();
  }
}
