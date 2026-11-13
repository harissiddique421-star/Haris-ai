package com.myraa.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.*;
import android.provider.CalendarContract;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Proactive alerts: about every 30 minutes FRIDAY quietly checks a few things that really matter
 * and posts one notification only when something needs the user: battery critically low,
 * storage almost full, the wake word stopped, notification access lost, or a calendar event
 * starting soon. Each alert repeats at most once per its own period. Turned off with
 * set_proactive. Nothing is spoken and no conversation is started.
 */
final class Proactive {
  static final String CHECK="com.myraa.mobile.PROACTIVE_CHECK";
  private static final String CHANNEL="friday_alerts";
  private Proactive(){}

  private static SharedPreferences prefs(Context c){return c.getSharedPreferences(Announcer.PREFS,Context.MODE_PRIVATE);}
  static boolean on(Context c){return prefs(c).getBoolean("proactive",true);}

  static void schedule(Context c){
    AlarmManager a=c.getSystemService(AlarmManager.class);PendingIntent p=intent(c);
    if(!on(c)){a.cancel(p);return;}
    a.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,SystemClock.elapsedRealtime()+5*60_000L,AlarmManager.INTERVAL_HALF_HOUR,p);
  }
  private static PendingIntent intent(Context c){return PendingIntent.getBroadcast(c,48,new Intent(c,ReminderReceiver.class).setAction(CHECK),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}

  static JSONObject set(Context c,boolean enable){
    prefs(c).edit().putBoolean("proactive",enable).apply();schedule(c);
    ControlLog.i("proactive: "+(enable?"on":"off"));
    return PhoneControls.result("done",enable?"Proactive alerts are on. I'll notify you only about important things: very low battery, full storage, the wake word stopping, lost notification access and meetings starting soon.":"Proactive alerts are off.");
  }

  /** One round of checks. Each alert has a key and a quiet period so it never nags. */
  static void check(Context c){
    Orders.onBattery(c);
    if(!on(c)||Autonomy.paused(c))return;
    try{
      BatteryManager b=c.getSystemService(BatteryManager.class);
      int level=b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);boolean charging=b.isCharging();
      if(charging||level>20)prefs(c).edit().remove("alert_battery").apply();
      else if(level<=10)alert(c,"battery",24*60,"Battery "+level+"%","Charge your phone soon. Battery Saver helps it last longer.");
    }catch(RuntimeException ignored){}
    try{
      StatFs fs=new StatFs(Environment.getDataDirectory().getPath());long freeMb=fs.getAvailableBytes()/(1024*1024);
      if(freeMb<500)alert(c,"storage",24*60,"Storage almost full","Only "+freeMb+" MB free. Delete old videos or apps so the phone stays fast.");
    }catch(RuntimeException ignored){}
    if(WakeWordService.wanted(c)&&!WakeWordService.running&&!LiveVoiceService.running)
      alert(c,"wake",6*60,"\"Wake up FRIDAY\" stopped","Android stopped the wake word. Tap to start it again, and allow \"Battery: no optimization\" so it keeps running.");
    if((Announcer.announceCalls(c)||Announcer.driving(c)||MeetingMode.on(c))&&!NotificationReader.enabled(c))
      alert(c,"notifaccess",24*60,"Notification access is off","FRIDAY can't say who is calling or read messages. Tap and allow it in Settings > Permissions.");
    upcoming(c);
  }
  private static void upcoming(Context c){
    if(c.checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED)return;
    long now=System.currentTimeMillis();
    android.net.Uri.Builder u=CalendarContract.Instances.CONTENT_URI.buildUpon();ContentUris.appendId(u,now+5*60_000L);ContentUris.appendId(u,now+40*60_000L);
    try(Cursor cur=c.getContentResolver().query(u.build(),new String[]{CalendarContract.Instances.EVENT_ID,CalendarContract.Instances.TITLE,CalendarContract.Instances.BEGIN,CalendarContract.Instances.ALL_DAY},null,null,CalendarContract.Instances.BEGIN+" ASC")){
      while(cur!=null&&cur.moveToNext()){
        if(cur.getInt(3)==1)continue;
        long begin=cur.getLong(2);if(begin<now)continue;
        String title=cur.getString(1);if(title==null||title.trim().isEmpty())title="An event";
        if(title.length()>60)title=title.substring(0,60);
        alert(c,"event"+cur.getLong(0)+"_"+begin,24*60,title+" at "+new SimpleDateFormat("HH:mm",Locale.ENGLISH).format(new Date(begin)),"Starts in about "+Math.max(1,(begin-now)/60_000)+" minutes.");
        break;
      }
    }catch(RuntimeException ignored){}
  }
  private static void alert(Context c,String key,int quietMinutes,String title,String text){
    SharedPreferences p=prefs(c);long last=p.getLong("alert_"+key,0),now=System.currentTimeMillis();
    if(now-last<quietMinutes*60_000L)return;
    p.edit().putLong("alert_"+key,now).apply();
    NotificationManager m=c.getSystemService(NotificationManager.class);
    m.createNotificationChannel(new NotificationChannel(CHANNEL,"FRIDAY important alerts",NotificationManager.IMPORTANCE_DEFAULT));
    PendingIntent open=PendingIntent.getActivity(c,49,new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_IMMUTABLE);
    try{m.notify(6000+Math.abs(key.hashCode()%1000),new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
      .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build());}
    catch(SecurityException ignored){}
    ControlLog.i("proactive: alert "+(key.startsWith("event")?"event":key));
  }
}
