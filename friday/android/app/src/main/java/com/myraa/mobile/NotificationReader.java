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
  public interface IncomingCall { void ringing(String caller); }
  private static final ArrayDeque<JSONObject> recent=new ArrayDeque<>();
  private static volatile IncomingCall callListener;
  private static String lastCallKey;

  public static void setCallListener(IncomingCall listener){callListener=listener;}
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
      if(listener!=null&&!key.equals(lastCallKey)&&(n.flags&Notification.FLAG_ONGOING_EVENT)!=0&&n.fullScreenIntent!=null){lastCallKey=key;listener.ringing(title.toString());}
      return;
    }
    if((n.flags&Notification.FLAG_ONGOING_EVENT)!=0||(n.flags&Notification.FLAG_GROUP_SUMMARY)!=0||(title==null&&text==null))return;
    String app;try{app=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(sbn.getPackageName(),0)).toString();}catch(Exception e){app=sbn.getPackageName();}
    JSONObject item=new JSONObject();
    try{item.put("app",app).put("title",clip(title,100)).put("text",clip(text,300)).put("time",sbn.getPostTime());}catch(JSONException ignored){}
    synchronized(NotificationReader.class){recent.addLast(item);while(recent.size()>30)recent.removeFirst();}
  }
  @Override public void onNotificationRemoved(StatusBarNotification sbn){if(sbn!=null&&sbn.getKey().equals(lastCallKey))lastCallKey=null;}
  private static String clip(CharSequence value,int max){if(value==null)return "";String s=value.toString();return s.length()>max?s.substring(0,max)+"…":s;}
}
