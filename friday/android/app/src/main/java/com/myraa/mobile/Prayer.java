package com.myraa.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.Build;
import org.json.*;
import java.util.*;

/**
 * Namaz times, reminders before each prayer and the Qibla direction. The place is taken from the
 * phone's last known location or from a city the user names, saved on the phone only, and the
 * times are calculated on the phone (PrayerCalc), so reminders work without internet.
 */
final class Prayer {
  private Prayer(){}
  static final String PREFS="friday.prayer",FIRE="com.myraa.mobile.PRAYER";
  private static final int CODE=7301;

  static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
  static boolean hasPlace(Context c){return prefs(c).contains("lat");}

  /** Saves a place for the calculation (rounded to about 1 km; never sent anywhere). */
  static void savePlace(Context c,double lat,double lon,String name){
    prefs(c).edit().putFloat("lat",(float)(Math.round(lat*100)/100.0)).putFloat("lon",(float)(Math.round(lon*100)/100.0)).putString("place",name==null?"":name).apply();
  }
  /** The newest location Android already has (no GPS wait), or null. */
  static Location lastKnown(Context c){
    if(c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED
      &&c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return null;
    LocationManager lm=c.getSystemService(LocationManager.class);if(lm==null)return null;
    Location best=null;
    for(String p:lm.getProviders(true)){
      try{Location l=lm.getLastKnownLocation(p);if(l!=null&&(best==null||l.getTime()>best.getTime()))best=l;}catch(SecurityException|IllegalArgumentException ignored){}
    }
    return best;
  }
  /** A city or address to coordinates with Android's geocoder (needs internet once). Run off the main thread. */
  static double[] geocode(Context c,String city){
    try{
      if(!Geocoder.isPresent())return null;
      @SuppressWarnings("deprecation") List<Address> list=new Geocoder(c,Locale.ENGLISH).getFromLocationName(city,1);
      if(list==null||list.isEmpty())return null;
      return new double[]{list.get(0).getLatitude(),list.get(0).getLongitude()};
    }catch(Exception e){return null;}
  }
  private static String placeName(Context c){String p=prefs(c).getString("place","");return p.isEmpty()?"your saved location":p;}

  private static double[] timesFor(Context c,Calendar day){
    SharedPreferences p=prefs(c);
    double tz=TimeZone.getDefault().getOffset(day.getTimeInMillis())/3_600_000.0;
    return PrayerCalc.times(day.get(Calendar.YEAR),day.get(Calendar.MONTH)+1,day.get(Calendar.DAY_OF_MONTH),p.getFloat("lat",0),p.getFloat("lon",0),tz,p.getBoolean("hanafi",true));
  }

  /** Today's times and the next prayer, for FRIDAY to say. */
  static JSONObject today(Context c){
    Calendar now=Calendar.getInstance();double[] t=timesFor(c,now);
    double mins=now.get(Calendar.HOUR_OF_DAY)*60+now.get(Calendar.MINUTE)+now.get(Calendar.SECOND)/60.0;
    JSONObject times=new JSONObject();String next=null;double nextAt=0;
    try{
      for(int i=0;i<6;i++){times.put(PrayerCalc.NAMES[i],PrayerCalc.clock(t[i]));if(i!=1&&next==null&&!Double.isNaN(t[i])&&t[i]>mins){next=PrayerCalc.NAMES[i];nextAt=t[i];}}
    }catch(JSONException ignored){}
    String msg;
    if(next!=null){int left=(int)Math.round(nextAt-mins);msg="Prayer times today for "+placeName(c)+". Next is "+next+" at "+PrayerCalc.clock(nextAt)+", in "+(left>=60?left/60+" h "+left%60+" min":left+" min")+". Say the next prayer first, and the full list only if asked.";}
    else{double[] tm=timesFor(c,tomorrow(now));msg="Today's prayers are over for "+placeName(c)+". Next is Fajr tomorrow at "+PrayerCalc.clock(tm[0])+".";}
    msg+=" Times are calculated on the phone (Karachi method, Hanafi Asr); the local mosque may differ by a few minutes.";
    JSONObject v=PhoneControls.result("done",msg);
    try{v.put("times",times).put("place",placeName(c)).put("reminders",prefs(c).getBoolean("remind",false));}catch(JSONException ignored){}
    return v;
  }
  static JSONObject qibla(Context c){
    SharedPreferences p=prefs(c);double b=PrayerCalc.qibla(p.getFloat("lat",0),p.getFloat("lon",0));
    JSONObject v=PhoneControls.result("done","From "+placeName(c)+" the Qibla is "+Math.round(b)+" degrees from north, towards the "+PrayerCalc.compass(b)+". Tell the user to hold the phone flat with a compass app (or Google Maps) and face "+Math.round(b)+" degrees.");
    try{v.put("bearing",Math.round(b)).put("direction",PrayerCalc.compass(b));}catch(JSONException ignored){}
    return v;
  }

  /** Turns reminders before each prayer on or off; before is minutes ahead of the time (0 = at the time). */
  static JSONObject reminders(Context c,boolean on,int before){
    before=Math.max(0,Math.min(60,before));
    prefs(c).edit().putBoolean("remind",on).putInt("before",before).apply();
    if(on)scheduleNext(c);else cancel(c);
    return PhoneControls.result("done",on?"Namaz reminders are on: "+(before==0?"at":before+" minutes before")+" each prayer, for "+placeName(c)+"."+(CareReminders.exact(c)?"":" Android may deliver them a few minutes late; allow Alarms & reminders for FRIDAY for exact times."):"Namaz reminders are off.");
  }

  private static Calendar tomorrow(Calendar now){Calendar d=(Calendar)now.clone();d.add(Calendar.DAY_OF_MONTH,1);return d;}
  private static PendingIntent intent(Context c,String name,String at){
    Intent i=new Intent(c,ReminderReceiver.class).setAction(FIRE).putExtra("prayer",name==null?"":name).putExtra("at",at==null?"":at);
    return PendingIntent.getBroadcast(c,CODE,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  }
  static void cancel(Context c){AlarmManager a=c.getSystemService(AlarmManager.class);if(a!=null)a.cancel(intent(c,null,null));}

  /** One alarm at a time: the next reminder (five prayers, not sunrise). Called after each reminder and after a restart. */
  static void scheduleNext(Context c){
    if(!prefs(c).getBoolean("remind",false)||!hasPlace(c))return;
    int before=prefs(c).getInt("before",10);long now=System.currentTimeMillis();
    Calendar day=Calendar.getInstance();
    for(int d=0;d<2;d++){
      double[] t=timesFor(c,day);
      for(int i=0;i<6;i++){
        if(i==1||Double.isNaN(t[i]))continue;
        Calendar at=(Calendar)day.clone();at.set(Calendar.HOUR_OF_DAY,0);at.set(Calendar.MINUTE,0);at.set(Calendar.SECOND,0);at.set(Calendar.MILLISECOND,0);
        long when=at.getTimeInMillis()+Math.round((t[i]-before)*60_000);
        if(when>now+30_000){
          AlarmManager a=c.getSystemService(AlarmManager.class);if(a==null)return;
          PendingIntent p=intent(c,PrayerCalc.NAMES[i],PrayerCalc.clock(t[i]));
          try{if(CareReminders.exact(c))a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,p);else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,p);}
          catch(SecurityException e){a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,p);}
          return;
        }
      }
      day=tomorrow(day);
    }
  }
  /** The reminder itself: a notification, then the next one is scheduled. */
  static void fire(Context c,String name,String at){
    try{
      if(Build.VERSION.SDK_INT<33||c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED){
        NotificationManager m=c.getSystemService(NotificationManager.class);
        m.createNotificationChannel(new NotificationChannel("prayer","Namaz reminders",NotificationManager.IMPORTANCE_HIGH));
        int before=prefs(c).getInt("before",10);
        String title=before==0?name+" ka waqt ho gaya":name+" ka waqt "+before+" minute mein";
        PendingIntent open=PendingIntent.getActivity(c,CODE+1,new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        m.notify(CODE,new Notification.Builder(c,"prayer").setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title).setContentText(name+": "+at).setContentIntent(open).setAutoCancel(true).build());
      }
    }catch(RuntimeException e){ControlLog.i("prayer: reminder could not be shown");}
    scheduleNext(c);
  }
}
