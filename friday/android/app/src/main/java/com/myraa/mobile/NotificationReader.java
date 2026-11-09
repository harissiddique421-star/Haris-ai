package com.myraa.mobile;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.os.Bundle;
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
  /** A chat FRIDAY can answer through its notification's own Reply button (memory only, last hour). */
  static final class ReplyTarget {
    final String key,pkg,app,sender;final Notification.Action action;final long at;
    ReplyTarget(String key,String pkg,String app,String sender,Notification.Action action,long at){this.key=key;this.pkg=pkg;this.app=app;this.sender=sender;this.action=action;this.at=at;}
  }
  private static final ArrayDeque<ReplyTarget> replies=new ArrayDeque<>();
  private static Notification.Action replyAction(Notification n){
    if(n.actions==null)return null;
    for(Notification.Action a:n.actions){
      RemoteInput[] inputs=a.getRemoteInputs();if(inputs==null||a.actionIntent==null)continue;
      for(RemoteInput r:inputs)if(r.getAllowFreeFormInput())return a;
    }
    return null;
  }
  private static void keepReply(String pkg,String app,CharSequence title,Notification n){
    Notification.Action a=replyAction(n);if(a==null||title==null)return;
    String sender=clip(title,80).replaceAll("\\s*\\(\\d+ (new )?messages?\\)$","").trim();if(sender.isEmpty())return;
    String key=pkg+"|"+sender;long now=System.currentTimeMillis();
    synchronized(NotificationReader.class){
      replies.removeIf(r->r.key.equals(key)||now-r.at>60*60_000L);
      replies.addLast(new ReplyTarget(key,pkg,app,sender,a,now));while(replies.size()>15)replies.removeFirst();
    }
  }
  /** The chat to answer: the best name match (and app, when given), or the newest message when no name is said. */
  static synchronized ReplyTarget findReply(String who,String app){
    long now=System.currentTimeMillis();replies.removeIf(r->now-r.at>60*60_000L);
    String wantApp=app==null?"":ContactMatcher.norm(app);
    ReplyTarget best=null;double bestScore=0;
    Iterator<ReplyTarget> it=replies.descendingIterator();
    while(it.hasNext()){
      ReplyTarget r=it.next();
      if(!wantApp.isEmpty()&&!ContactMatcher.norm(r.app).contains(wantApp.replace("whats app","whatsapp")))continue;
      if(who==null||who.trim().isEmpty())return r;
      double s=ContactMatcher.score(who,r.sender);
      if(s>bestScore){best=r;bestScore=s;}
    }
    return bestScore>=ContactMatcher.HIGH?best:null;
  }
  static synchronized ReplyTarget replyByKey(String key){for(ReplyTarget r:replies)if(r.key.equals(key))return r;return null;}
  /** Senders FRIDAY could answer now, newest first (for "did you mean"). */
  static synchronized List<String> replySenders(){List<String> out=new ArrayList<>();Iterator<ReplyTarget> it=replies.descendingIterator();while(it.hasNext()&&out.size()<5)out.add(it.next().sender);return out;}
  /** Fills the notification's reply box with the text and presses its Send, as the user would from the notification shade. */
  static boolean sendReply(Context c,ReplyTarget t,String text){
    try{
      RemoteInput[] inputs=t.action.getRemoteInputs();Bundle results=new Bundle();
      for(RemoteInput r:inputs)results.putCharSequence(r.getResultKey(),text);
      Intent fill=new Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND);RemoteInput.addResultsToIntent(inputs,fill,results);
      t.action.actionIntent.send(c,0,fill);
      synchronized(NotificationReader.class){replies.remove(t);}
      return true;
    }catch(PendingIntent.CanceledException|RuntimeException e){return false;}
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
    if(Announcer.isMessaging(this,sbn.getPackageName(),n))keepReply(sbn.getPackageName(),app,title,n);
    // A WhatsApp voice note: only who and how long are known from the notification (see VoiceNotes).
    if(VoiceNotes.whatsapp(sbn.getPackageName())){int sec=VoiceNotes.voiceNoteSeconds(text==null?null:text.toString());
      if(sec>=0&&title!=null)VoiceNotes.noticed(clip(title,80).replaceAll("\\s*\\(\\d+ (new )?messages?\\)$","").trim(),sec,sbn.getPostTime());}
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
