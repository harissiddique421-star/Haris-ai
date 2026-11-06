package com.myraa.mobile;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import org.json.*;
import java.util.*;

/**
 * Meeting mode: the phone goes to vibrate, and a person who messages on WhatsApp, SMS or
 * another chat app gets one polite auto-reply (once per chat per meeting) through the
 * notification's own Reply button. Group chats never get a reply. It ends by itself after
 * the chosen time. FRIDAY keeps only the names of who wrote, in memory, to tell the user after.
 */
final class MeetingMode {
  static final String END="com.myraa.mobile.MEETING_END";
  static final String DEFAULT_REPLY="Abhi meeting mein hoon, thodi der baad baat hogi. (FRIDAY auto-reply)";
  private static final Set<String> replied=new LinkedHashSet<>();
  private static final List<String> missed=new ArrayList<>();
  private MeetingMode(){}

  private static SharedPreferences prefs(Context c){return c.getSharedPreferences(Announcer.PREFS,Context.MODE_PRIVATE);}
  static boolean on(Context c){SharedPreferences p=prefs(c);return p.getBoolean("meeting",false)&&p.getLong("meetingUntil",0)>System.currentTimeMillis();}

  static JSONObject start(Context c,int minutes,String message){
    if(minutes<=0)minutes=60;if(minutes>8*60)minutes=8*60;
    String reply=message==null||message.trim().isEmpty()?DEFAULT_REPLY:message.trim().replaceAll("\\s+"," ");
    if(reply.length()>300)reply=reply.substring(0,300);
    if(AssistantMemory.looksSecret(reply))throw new IllegalArgumentException("The auto-reply can't contain passwords, PINs, OTPs or card numbers.");
    AudioManager audio=c.getSystemService(AudioManager.class);SharedPreferences p=prefs(c);
    if(!p.getBoolean("meeting",false))p.edit().putInt("meetingRinger",audio.getRingerMode()).apply();
    boolean quiet=true;try{audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);}catch(SecurityException e){quiet=false;}
    long until=System.currentTimeMillis()+minutes*60_000L;
    p.edit().putBoolean("meeting",true).putLong("meetingUntil",until).putString("meetingReply",reply).apply();
    synchronized(replied){replied.clear();missed.clear();}
    AlarmManager a=c.getSystemService(AlarmManager.class);
    a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,until,endIntent(c));
    ControlLog.i("meeting: on for "+minutes+" min");
    boolean access=NotificationReader.enabled(c);
    return PhoneControls.result("on","Meeting mode is on for "+minutes+" minutes."+(quiet?" The phone is on vibrate.":" I couldn't switch to vibrate.")
      +(access?" People who message will get: \""+reply+"\"":" Auto-reply needs Notification access for FRIDAY (Settings > Permissions); until then only vibrate is on."));
  }
  static JSONObject stop(Context c,boolean auto){
    SharedPreferences p=prefs(c);boolean was=p.getBoolean("meeting",false);
    p.edit().putBoolean("meeting",false).remove("meetingUntil").apply();
    c.getSystemService(AlarmManager.class).cancel(endIntent(c));
    if(was){int ringer=p.getInt("meetingRinger",AudioManager.RINGER_MODE_NORMAL);try{c.getSystemService(AudioManager.class).setRingerMode(ringer);}catch(SecurityException ignored){}}
    List<String> who;synchronized(replied){who=new ArrayList<>(missed);replied.clear();missed.clear();}
    ControlLog.i("meeting: off"+(auto?" (time over)":"")+", "+who.size()+" auto-replies");
    if(auto&&was){
      NotificationManager m=c.getSystemService(NotificationManager.class);
      m.createNotificationChannel(new NotificationChannel("care","FRIDAY reminders",NotificationManager.IMPORTANCE_HIGH));
      try{m.notify(46,new Notification.Builder(c,"care").setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Meeting mode ended").setContentText(who.isEmpty()?"Nobody messaged.":"Auto-replied to: "+String.join(", ",who)).setAutoCancel(true).build());}catch(SecurityException ignored){}
    }
    JSONObject v=PhoneControls.result("off",!was?"Meeting mode was already off.":"Meeting mode is off and the sound is back."+(who.isEmpty()?" Nobody messaged.":" I auto-replied to "+who.size()+": "+String.join(", ",who)+"."));
    try{v.put("messaged",new JSONArray(who));}catch(JSONException ignored){}
    return v;
  }
  /** After a restart: end a meeting whose time is over. */
  static void afterRestart(Context c){if(prefs(c).getBoolean("meeting",false)&&!on(c))stop(c,true);}
  private static PendingIntent endIntent(Context c){return PendingIntent.getBroadcast(c,47,new Intent(c,ReminderReceiver.class).setAction(END),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}

  /** Called for each chat notification. Replies once per chat, never to groups. */
  static void onMessage(Context c,StatusBarNotification sbn,String app,CharSequence title){
    if(!on(c)||title==null)return;
    Notification n=sbn.getNotification();
    if(Build.VERSION.SDK_INT>=28&&n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION,false))return;
    String who=title.toString().trim();
    if(who.isEmpty()||who.matches("(?i).*(\\d+ (new )?messages|messages from \\d+ chats).*")||who.contains(": ")||who.contains(" @ "))return;
    String key=sbn.getPackageName()+"|"+who.toLowerCase(Locale.ROOT);
    synchronized(replied){if(replied.contains(key))return;}
    Notification.Action action=replyAction(n);if(action==null)return;
    String text=prefs(c).getString("meetingReply",DEFAULT_REPLY);
    RemoteInput[] inputs=action.getRemoteInputs();Intent fill=new Intent();Bundle values=new Bundle();
    for(RemoteInput in:inputs)values.putCharSequence(in.getResultKey(),text);
    RemoteInput.addResultsToIntent(inputs,fill,values);
    try{
      action.actionIntent.send(c,0,fill);
      synchronized(replied){replied.add(key);if(missed.size()<30)missed.add(who.length()>40?who.substring(0,40):who);}
      ControlLog.i("meeting: auto-replied in "+app);
    }catch(PendingIntent.CanceledException e){ControlLog.i("meeting: reply button no longer works in "+app);}
  }
  private static Notification.Action replyAction(Notification n){
    if(n.actions==null)return null;Notification.Action fallback=null;
    for(Notification.Action a:n.actions){
      if(a.getRemoteInputs()==null||a.getRemoteInputs().length==0||a.actionIntent==null)continue;
      if(Build.VERSION.SDK_INT>=28&&a.getSemanticAction()==Notification.Action.SEMANTIC_ACTION_REPLY)return a;
      if(fallback==null)fallback=a;
    }
    return fallback;
  }
}
