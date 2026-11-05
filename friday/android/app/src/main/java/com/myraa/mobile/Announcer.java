package com.myraa.mobile;

import android.app.Notification;
import android.content.*;
import android.media.*;
import android.os.*;
import android.provider.Telephony;
import android.speech.tts.TextToSpeech;
import org.json.*;
import java.util.*;

/**
 * Speaks incoming calls and, in driving mode, incoming messages with the phone's own speech
 * engine, so it works without a Live call and without internet. Settings live in the app's
 * private preferences. Message text is spoken only in driving mode with "read messages" on,
 * and is never logged or stored.
 */
final class Announcer {
  static final String PREFS="myraa.mobile.v1";
  private static final Set<String> MESSAGING=new HashSet<>(Arrays.asList(
    "com.whatsapp","com.whatsapp.w4b","com.google.android.apps.messaging","com.samsung.android.messaging",
    "org.telegram.messenger","com.facebook.orca","com.instagram.android","com.android.mms","com.oneplus.mms","com.android.messaging"));
  private final Context context;
  private final Handler main=new Handler(Looper.getMainLooper());
  private TextToSpeech tts;private boolean ready;
  private final ArrayDeque<String> waiting=new ArrayDeque<>();
  private String ringingKey;private int rings;
  private static final LinkedHashMap<String,Long> spoken=new LinkedHashMap<>();

  Announcer(Context context){this.context=context.getApplicationContext();}

  // ---- settings
  private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
  static boolean announceCalls(Context c){return prefs(c).getBoolean("announceCalls",true);}
  static boolean driving(Context c){return prefs(c).getBoolean("drivingMode",false);}
  static boolean readText(Context c){return prefs(c).getBoolean("drivingReadText",true);}
  static void set(Context c,String key,boolean on){prefs(c).edit().putBoolean(key,on).apply();}
  static JSONObject state(Context c){
    JSONObject v=new JSONObject();
    try{v.put("calls",announceCalls(c)).put("driving",driving(c)).put("readText",readText(c)).put("notificationAccess",NotificationReader.enabled(c));}catch(JSONException ignored){}
    return v;
  }
  static String prompt(Context c){
    StringBuilder b=new StringBuilder();
    if(driving(c))b.append("\nDriving mode is ON: the user is driving. Keep every reply to one or two short sentences, never ask them to look at or tap the screen, and prefer voice actions. When a [Phone] message reports a new message, read it out and ask if they want to reply; send a reply only with whatsapp_message or send_sms and the usual confirmation.");
    b.append("\nUse set_driving_mode (on true/false) when the user asks to turn driving mode on or off, and set_call_announce (on true/false) to turn spoken caller names on or off.");
    return b.toString();
  }

  // ---- speaking
  private boolean hindi(){return prefs(context).getString("language","hi-IN").startsWith("hi");}
  private void speak(String text,boolean flush){
    main.post(()->{
      if(tts==null){
        waiting.add(text);
        tts=new TextToSpeech(context,status->main.post(()->{
          ready=status==TextToSpeech.SUCCESS;
          if(ready){
            tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            Locale l=Locale.forLanguageTag(prefs(context).getString("language","hi-IN"));
            int r=tts.setLanguage(l);if(r==TextToSpeech.LANG_MISSING_DATA||r==TextToSpeech.LANG_NOT_SUPPORTED)tts.setLanguage(Locale.ENGLISH);
            while(!waiting.isEmpty())tts.speak(waiting.poll(),TextToSpeech.QUEUE_ADD,null,"friday-announce");
          }else{ControlLog.i("announce: speech engine unavailable");waiting.clear();}
        }));
        return;
      }
      if(!ready){waiting.add(text);return;}
      tts.speak(text,flush?TextToSpeech.QUEUE_FLUSH:TextToSpeech.QUEUE_ADD,null,"friday-announce");
    });
  }
  void stop(){main.post(()->{ringingKey=null;main.removeCallbacksAndMessages(null);if(tts!=null&&ready)tts.stop();});}
  void shutdown(){main.post(()->{main.removeCallbacksAndMessages(null);if(tts!=null)tts.shutdown();tts=null;ready=false;});}

  /** A call is ringing and no Live call is handling it: say who is calling, up to three times. */
  void ringing(String key,String caller){
    if(!announceCalls(context)&&!driving(context))return;
    AudioManager audio=context.getSystemService(AudioManager.class);
    if(audio!=null&&audio.getRingerMode()!=AudioManager.RINGER_MODE_NORMAL&&!driving(context)){ControlLog.i("announce: call skipped, phone is silent/vibrate");return;}
    String name=clean(caller,60);if(name.isEmpty())name=hindi()?"Unknown number":"an unknown number";
    String line=hindi()?name+" ki call aa rahi hai":"Call from "+name;
    main.post(()->{
      ringingKey=key;rings=0;ControlLog.i("announce: incoming call");
      Runnable again=new Runnable(){public void run(){if(!key.equals(ringingKey)||rings>=3)return;rings++;speak(line,true);main.postDelayed(this,6000);}};
      main.postDelayed(again,1200);
    });
  }
  void callEnded(String key){main.post(()->{if(key.equals(ringingKey)){ringingKey=null;if(tts!=null&&ready)tts.stop();}});}

  /** Driving mode: say who messaged, and the message if "read messages" is on. */
  boolean message(Context from,String pkg,Notification n,String app,CharSequence title,CharSequence text){
    if(!driving(context)||!isMessaging(from,pkg,n))return false;
    String who=clean(title,60),body=clean(text,240);if(who.isEmpty()&&body.isEmpty())return false;
    if(!fresh(pkg,who,body))return false;
    String line=hindi()?app+" par "+(who.isEmpty()?"ek":who+" ka")+" message"+(readText(context)&&!body.isEmpty()?": "+body:" aaya hai")
      :app+" message"+(who.isEmpty()?"":" from "+who)+(readText(context)&&!body.isEmpty()?": "+body:"");
    ControlLog.i("announce: message from "+app);
    speak(line,false);return true;
  }
  /** WhatsApp and others repost the same message while it updates; announce each one once a minute, and skip "3 new messages" summaries. */
  static boolean fresh(String pkg,CharSequence who,CharSequence body){
    String w=clean(who,60),b=clean(body,240);
    if(b.matches("(?i)\\d+ new messages?|.*\\bmessages? from \\d+ chats\\b.*"))return false;
    String sig=pkg+"|"+w+"|"+b;long now=System.currentTimeMillis();
    synchronized(spoken){
      Long at=spoken.get(sig);if(at!=null&&now-at<60_000)return false;
      spoken.put(sig,now);if(spoken.size()>40){Iterator<String> it=spoken.keySet().iterator();it.next();it.remove();}
    }
    return true;
  }
  static boolean isMessaging(Context c,String pkg,Notification n){
    if(Notification.CATEGORY_MESSAGE.equals(n.category))return true;
    if(MESSAGING.contains(pkg))return true;
    try{return pkg.equals(Telephony.Sms.getDefaultSmsPackage(c));}catch(RuntimeException e){return false;}
  }
  private static String clean(CharSequence value,int max){
    if(value==null)return "";String s=value.toString().replaceAll("[\\p{Cntrl}\\s]+"," ").trim();
    return s.length()>max?s.substring(0,max):s;
  }
}
