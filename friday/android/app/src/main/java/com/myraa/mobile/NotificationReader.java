package com.myraa.mobile;

import android.app.Notification;
import android.content.*;
import android.service.notification.*;
import android.provider.Settings;
import org.json.*;
import java.util.*;

/**
 * Keeps the last 30 notifications in memory only (never written to disk) so FRIDAY can read
 * them out when asked. Also tells the Live call about an incoming phone call, since the
 * dialer's call notification carries the caller's display name.
 */
public final class NotificationReader extends NotificationListenerService {
  public interface IncomingCall { void ringing(String caller,boolean again); default void message(String app,String from,String text){} }
  private static final ArrayDeque<JSONObject> recent=new ArrayDeque<>();
  private static volatile IncomingCall callListener;
  private static String lastCallKey;
  private static volatile NotificationReader instance;
  /** The call ringing now (contact name when saved, otherwise the number), kept in memory only. */
  private static volatile String ringingCaller;
  private static String lastCaller;private static long lastCallerAt;
  static volatile boolean unmuteRingAfterCall;
  private Announcer announcer;

  public static void setCallListener(IncomingCall listener){callListener=listener;}
  @Override public void onListenerConnected(){instance=this;if(announcer==null)announcer=new Announcer(this);}
  @Override public void onListenerDisconnected(){if(instance==this)instance=null;if(announcer!=null)announcer.shutdown();announcer=null;}
  @Override public void onDestroy(){if(announcer!=null)announcer.shutdown();announcer=null;super.onDestroy();}
  private Announcer announcer(){if(announcer==null)announcer=new Announcer(this);return announcer;}
  public static boolean enabled(Context context){
    String list=Settings.Secure.getString(context.getContentResolver(),"enabled_notification_listeners");
    if(list==null)return false;
    ComponentName me=new ComponentName(context,NotificationReader.class);
    for(String item:list.split(":")){ComponentName c=ComponentName.unflattenFromString(item);if(me.equals(c))return true;}
    return false;
  }
  public static synchronized JSONArray recent(int limit){
    JSONArray out=new JSONArray();Iterator<JSONObject> it=recent.descendingIterator();
    while(it.hasNext()&&out.length()<limit)out.put(it.next());
    return out;
  }

  @Override public void onNotificationPosted(StatusBarNotification sbn){
    if(sbn==null||getPackageName().equals(sbn.getPackageName()))return;
    Notification n=sbn.getNotification();if(n==null)return;
    CharSequence title=n.extras.getCharSequence(Notification.EXTRA_TITLE),text=n.extras.getCharSequence(Notification.EXTRA_TEXT);
    if(Notification.CATEGORY_CALL.equals(n.category)&&title!=null){
      String key=sbn.getKey();IncomingCall listener=callListener;
      if(!key.equals(lastCallKey)&&(n.flags&Notification.FLAG_ONGOING_EVENT)!=0&&n.fullScreenIntent!=null){
        lastCallKey=key;instance=this;
        String caller=CallerName.resolve(this,CallerName.pick(title,text));
        long now=System.currentTimeMillis();
        boolean again=!caller.isEmpty()&&caller.equalsIgnoreCase(lastCaller)&&now-lastCallerAt<15*60_000L;
        lastCaller=caller;lastCallerAt=now;ringingCaller=caller.isEmpty()?"":caller;
        ControlLog.i("call: ringing, "+(caller.isEmpty()?"no caller id":CallerName.isNumber(caller)?"number not in contacts":"saved contact")+(again?", again":""));
        // A Live call says who is calling itself; otherwise the phone's own voice announces it.
        if(listener!=null)listener.ringing(caller,again);else announcer().ringing(key,caller,again);
      }else if(key.equals(lastCallKey)&&n.fullScreenIntent==null){announcer().callEnded(key);callOver();}
      return;
    }
    if((n.flags&Notification.FLAG_ONGOING_EVENT)!=0||(n.flags&Notification.FLAG_GROUP_SUMMARY)!=0||(title==null&&text==null))return;
    String app;try{app=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(sbn.getPackageName(),0)).toString();}catch(Exception e){app=sbn.getPackageName();}
    JSONObject item=new JSONObject();
    try{item.put("app",app).put("title",clip(title,100)).put("text",clip(text,300)).put("time",sbn.getPostTime());}catch(JSONException ignored){}
    synchronized(NotificationReader.class){recent.addLast(item);while(recent.size()>30)recent.removeFirst();}
    if(MeetingMode.on(this)&&Announcer.isMessaging(this,sbn.getPackageName(),n))MeetingMode.onMessage(this,sbn,app,title);
    if(Announcer.driving(this)&&Announcer.isMessaging(this,sbn.getPackageName(),n)){
      IncomingCall listener=callListener;
      // During a Live call FRIDAY reads it out in the conversation; otherwise the phone's voice does.
      if(listener!=null){if(Announcer.fresh(sbn.getPackageName(),title,text))listener.message(app,clip(title,60),Announcer.readText(this)?clip(text,240):"");}
      else announcer().message(this,sbn.getPackageName(),n,app,title,text);
    }
  }
  @Override public void onNotificationRemoved(StatusBarNotification sbn){if(sbn!=null&&sbn.getKey().equals(lastCallKey)){if(announcer!=null)announcer.callEnded(lastCallKey);lastCallKey=null;callOver();}}
  /** Answered, rejected or missed: forget the ringing caller and give the ringer its sound back. */
  private void callOver(){
    ringingCaller=null;
    if(unmuteRingAfterCall){unmuteRingAfterCall=false;try{getSystemService(android.media.AudioManager.class).adjustStreamVolume(android.media.AudioManager.STREAM_RING,android.media.AudioManager.ADJUST_UNMUTE,0);}catch(RuntimeException ignored){}}
  }
  /** Who is calling right now, or null when nothing is ringing. Empty means no caller ID. */
  static String ringingCaller(){return ringingCaller;}
  /** Stops FRIDAY's own spoken announcement of the ringing call. */
  static void quietAnnouncement(){NotificationReader r=instance;String key=lastCallKey;if(r!=null&&r.announcer!=null&&key!=null)r.announcer.callEnded(key);}
  private static String clip(CharSequence value,int max){if(value==null)return "";String s=value.toString();return s.length()>max?s.substring(0,max)+"…":s;}
}
