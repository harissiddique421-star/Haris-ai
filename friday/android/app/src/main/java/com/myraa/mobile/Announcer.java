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
  private String ringingKey;
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
    b.append("\nUse set_driving_mode (on true/false) when the user asks to turn driving mode on or off, and set_call_announce (on true/false) to turn spoken caller names on or off. Use set_wake_word (on true/false) to turn the \"Wake up FRIDAY\" wake phrase on or off. If this call started from the wake phrase, greet the user in one short line, for example \"Yes, Haris. I'm listening.\" (in the user's language and persona), and wait for the command. Waking never does anything else by itself. If the phone is still locked and a request needs it unlocked, say \"Phone unlock karna hoga, Haris.\""
      +" Email: use the email action with recipient (a contact name, an email address, or \"me\" for the user's own saved address), subject and message. It only opens a draft; tell the user to check it and tap Send."
      +" Routines: when the user says run my <name> routine, call run_routine; it returns steps, which are the user's own saved commands. Do them one by one in order with mobile_action, keep each spoken update short, and stop at any needs_confirmation until the user answers. To save one, call save_routine with a short name and steps as short plain commands (for example \"battery batao\", \"WhatsApp par Ammi ko message karo: good morning\"); after doing something step by step the user may say save this as <name>. list_routines and delete_routine manage them."
      +" YouTube and music: for any song or video use play_youtube with query (or url when the user gives a YouTube link; kind music for songs). It opens the exact video, then checks with the phone that it is really playing and tries the play button itself if needed. Never use open_app, search_youtube, read_screen or tap to start YouTube playback unless play_youtube already failed. When status is playing say one short line like Playing the song now. If status is failed, say I couldn't start the video, I'll try another method, and try exactly once more (play_youtube with a slightly different query, or search_youtube); never more. Status opened or buffering means it is open but not confirmed playing: say so honestly. Controls: media with command pause, resume, stop, next or previous; volume with direction up, down, mute or unmute."
      +" Sleep: when the user says Bye FRIDAY, so jao, good night FRIDAY or go to sleep, say one short goodbye and call sleep; the Live call then ends."
      +" Timers: set_timer with in_minutes and/or seconds and an optional label. Calendar: add_event with text (title), date YYYY-MM-DD (omit for today or the next time that hour comes), hour, minute and optional duration_minutes; it opens the calendar for the user to tap Save.");
    return b.toString();
  }

  // ---- speaking
  private boolean hindi(){String l=prefs(context).getString("language","hi-IN");return l.startsWith("hi")||l.startsWith("ur");}
  private void speak(String text,boolean flush){
    main.post(()->{
      if(tts==null){
        waiting.add(text);
        tts=new TextToSpeech(context,status->main.post(()->{
          ready=status==TextToSpeech.SUCCESS;
          if(ready){
            tts.setSpeechRate(0.95f);
            // FRIDAY's own voice must not wake FRIDAY: the wake listener ignores speech while this talks.
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener(){
              @Override public void onStart(String id){SelfSpeech.started();}
              @Override public void onDone(String id){SelfSpeech.finished();}
              @Override public void onStop(String id,boolean interrupted){SelfSpeech.finished();}
              @Override public void onError(String id){SelfSpeech.finished();}
            });
            tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            Locale l=Locale.forLanguageTag(prefs(context).getString("language","hi-IN"));
            int r=tts.setLanguage(l);if((r==TextToSpeech.LANG_MISSING_DATA||r==TextToSpeech.LANG_NOT_SUPPORTED)&&l.getLanguage().equals("ur"))r=tts.setLanguage(Locale.forLanguageTag("hi-IN"));if(r==TextToSpeech.LANG_MISSING_DATA||r==TextToSpeech.LANG_NOT_SUPPORTED)tts.setLanguage(Locale.ENGLISH);
            while(!waiting.isEmpty())tts.speak(waiting.poll(),TextToSpeech.QUEUE_ADD,null,"friday-announce");
          }else{ControlLog.i("announce: speech engine unavailable");waiting.clear();}
        }));
        return;
      }
      if(!ready){waiting.add(text);return;}
      tts.speak(text,flush?TextToSpeech.QUEUE_FLUSH:TextToSpeech.QUEUE_ADD,null,"friday-announce");
    });
  }
  /** Speaks one line for a reminder (not on silent/vibrate unless driving), then frees the speech engine. */
  void sayOnce(String text){
    AudioManager audio=context.getSystemService(AudioManager.class);
    if(audio!=null&&audio.getRingerMode()!=AudioManager.RINGER_MODE_NORMAL&&!driving(context))return;
    speak(clean(text,200),false);main.postDelayed(this::shutdown,25_000);
  }
  /** Speaks now even on silent or vibrate: the owner just picked up a FRIDAY call to hear this. */
  void sayAnswered(String text){speak(clean(text,400),true);main.postDelayed(this::shutdown,40_000);}
  void stop(){main.post(()->{ringingKey=null;main.removeCallbacksAndMessages(null);if(tts!=null&&ready)tts.stop();});}
  void shutdown(){main.post(()->{main.removeCallbacksAndMessages(null);if(tts!=null)tts.shutdown();tts=null;ready=false;});}

  /** A call is ringing and no Live call is handling it: say who is calling, once per call. */
  void ringing(String key,String caller,boolean again){
    if(!announceCalls(context)&&!driving(context))return;
    AudioManager audio=context.getSystemService(AudioManager.class);
    if(audio!=null&&audio.getRingerMode()!=AudioManager.RINGER_MODE_NORMAL&&!driving(context)){ControlLog.i("announce: call skipped, phone is silent/vibrate");return;}
    String line=callLine(caller,again,hindi(),new Random().nextInt(5)==0);
    main.post(()->{
      ringingKey=key;ControlLog.i("announce: incoming call");
      main.postDelayed(()->{if(key.equals(ringingKey))speak(line,true);},1200);
    });
  }
  /** One short, natural line: the saved name when there is one, otherwise the number read digit by digit. */
  static String callLine(String caller,boolean again,boolean hindi,boolean friendly){
    String name=clean(caller,60);boolean number=CallerName.looksLikeNumber(name);
    if(name.isEmpty())return hindi?"Unknown number ki call aa rahi hai":"Call from an unknown number";
    if(number){String digits=CallerName.speakable(name);return hindi?digits+" ki call"+(again?" phir se":"")+" aa rahi hai":"Call from "+digits+(again?", again.":".");}
    if(again)return hindi?name+" phir se call kar rahe hain":name+" is calling again.";
    if(friendly)return hindi?"Hey, "+name+" ki call aa rahi hai":"Hey, "+name+" is calling you.";
    return hindi?name+" ki call aa rahi hai":name+" is calling you.";
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
