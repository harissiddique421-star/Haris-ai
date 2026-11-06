package com.myraa.mobile;

import android.app.*;
import android.content.*;
import android.os.Build;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * FRIDAY's own reminders for medicine, bills and anything that repeats. Unlike a clock-app
 * reminder, each one asks "done?" with buttons, speaks out loud, follows up twice if nobody
 * answers, repeats daily or monthly, and is set again after the phone restarts.
 * Stored in the app's private preferences only.
 */
final class CareReminders {
  static final String FIRE="com.myraa.mobile.REMINDER_FIRE",DONE="com.myraa.mobile.REMINDER_DONE",SNOOZE="com.myraa.mobile.REMINDER_SNOOZE";
  private static final int MAX=30,FOLLOW_UPS=2,FOLLOW_MINUTES=15;
  private CareReminders(){}

  private static SharedPreferences prefs(Context c){return c.getSharedPreferences(Announcer.PREFS,Context.MODE_PRIVATE);}
  static synchronized JSONArray all(Context c){try{return new JSONArray(prefs(c).getString("careReminders","[]"));}catch(JSONException e){return new JSONArray();}}
  private static synchronized void store(Context c,JSONArray list){prefs(c).edit().putString("careReminders",list.toString()).apply();}

  /** Adds a reminder. kind: medicine, bill or general. repeat: none, daily or monthly (day = day of month). */
  static JSONObject add(Context c,String rawText,String kind,String repeat,int hour,int minute,int day,String date){
    String text=rawText==null?"":rawText.trim().replaceAll("\\s+"," ");
    if(text.isEmpty()||text.length()>150)throw new IllegalArgumentException("Say what the reminder is for, like \"BP ki dawai\" or \"bijli ka bill\".");
    if(AssistantMemory.looksSecret(text))throw new IllegalArgumentException("I don't keep passwords, PINs, OTPs or card numbers.");
    if(hour<0||hour>23||minute<0||minute>59)throw new IllegalArgumentException("Say the time, for example 9 baje subah.");
    kind=Arrays.asList("medicine","bill","general").contains(kind)?kind:"general";
    repeat=Arrays.asList("none","daily","monthly").contains(repeat)?repeat:"medicine".equals(kind)?"daily":"bill".equals(kind)?"monthly":"none";
    if("monthly".equals(repeat)&&(day<1||day>31))day=-1;
    JSONArray list=all(c);if(list.length()>=MAX)throw new IllegalArgumentException("You already have "+MAX+" reminders. Delete one first.");
    long id=System.currentTimeMillis();
    JSONObject r=new JSONObject();
    try{
      r.put("id",id).put("text",text).put("kind",kind).put("repeat",repeat).put("hour",hour).put("minute",minute).put("day",day).put("followUps",0);
      long at=firstTime(r,date);r.put("at",at);
      list.put(r);store(c,list);schedule(c,r);
      ControlLog.i("reminder: added "+kind+" "+repeat);
      String when=describe(r);
      JSONObject v=PhoneControls.result("set","Reminder set: "+text+", "+when+". I will ask whether it is done, and remind again if there's no answer."+exactNote(c));
      v.put("reminder",view(r));return v;
    }catch(JSONException e){throw new IllegalStateException(e);}
  }
  private static long firstTime(JSONObject r,String date) throws JSONException {
    Calendar when=Calendar.getInstance();when.set(Calendar.SECOND,0);when.set(Calendar.MILLISECOND,0);
    when.set(Calendar.HOUR_OF_DAY,r.getInt("hour"));when.set(Calendar.MINUTE,r.getInt("minute"));
    if(date!=null&&date.matches("\\d{4}-\\d{2}-\\d{2}")){String[] d=date.split("-");when.set(Integer.parseInt(d[0]),Integer.parseInt(d[1])-1,Integer.parseInt(d[2]));if("monthly".equals(r.getString("repeat"))&&r.getInt("day")<1)r.put("day",when.get(Calendar.DAY_OF_MONTH));}
    else if(date!=null&&!date.isEmpty()&&!date.equalsIgnoreCase("today"))throw new IllegalArgumentException("Give the date as YYYY-MM-DD.");
    if("monthly".equals(r.getString("repeat"))){
      int day=r.getInt("day");if(day<1){day=when.get(Calendar.DAY_OF_MONTH);r.put("day",day);}
      setDay(when,day);if(when.getTimeInMillis()<=System.currentTimeMillis()){when.set(Calendar.DAY_OF_MONTH,1);when.add(Calendar.MONTH,1);setDay(when,day);}
    }else if(when.getTimeInMillis()<=System.currentTimeMillis())when.add(Calendar.DAY_OF_MONTH,1);
    return when.getTimeInMillis();
  }
  private static void setDay(Calendar c,int day){c.set(Calendar.DAY_OF_MONTH,Math.min(day,c.getActualMaximum(Calendar.DAY_OF_MONTH)));}
  private static long nextTime(JSONObject r) throws JSONException {
    Calendar when=Calendar.getInstance();when.setTimeInMillis(r.getLong("at"));
    long now=System.currentTimeMillis();
    do{
      if("daily".equals(r.getString("repeat")))when.add(Calendar.DAY_OF_MONTH,1);
      else{when.set(Calendar.DAY_OF_MONTH,1);when.add(Calendar.MONTH,1);setDay(when,r.getInt("day"));}
      when.set(Calendar.HOUR_OF_DAY,r.getInt("hour"));when.set(Calendar.MINUTE,r.getInt("minute"));
    }while(when.getTimeInMillis()<=now);
    return when.getTimeInMillis();
  }
  static String describe(JSONObject r){
    String time=String.format(Locale.ROOT,"%02d:%02d",r.optInt("hour"),r.optInt("minute"));
    switch(r.optString("repeat")){
      case "daily":return "every day at "+time;
      case "monthly":return "every month on day "+r.optInt("day")+" at "+time;
      default:return new SimpleDateFormat("EEE d MMM 'at' HH:mm",Locale.ENGLISH).format(new Date(r.optLong("at")));
    }
  }
  private static JSONObject view(JSONObject r){
    JSONObject v=new JSONObject();
    try{v.put("text",r.optString("text")).put("kind",r.optString("kind")).put("when",describe(r)).put("next",new SimpleDateFormat("EEE d MMM HH:mm",Locale.ENGLISH).format(new Date(r.optLong("at"))));
      if(r.has("lastDone"))v.put("last_done",new SimpleDateFormat("EEE d MMM HH:mm",Locale.ENGLISH).format(new Date(r.optLong("lastDone"))));}catch(JSONException ignored){}
    return v;
  }
  static JSONObject list(Context c){
    JSONArray list=all(c),out=new JSONArray();for(int i=0;i<list.length();i++)out.put(view(list.optJSONObject(i)));
    JSONObject v=PhoneControls.result("done",out.length()==0?"No FRIDAY reminders yet.":"Here are your "+out.length()+" reminders. Say them briefly.");
    try{v.put("reminders",out);}catch(JSONException ignored){}
    return v;
  }
  /** Reminders due today, for the morning briefing. */
  static JSONArray today(Context c){
    JSONArray list=all(c),out=new JSONArray();Calendar end=Calendar.getInstance();end.set(Calendar.HOUR_OF_DAY,23);end.set(Calendar.MINUTE,59);
    for(int i=0;i<list.length();i++){JSONObject r=list.optJSONObject(i);if(r!=null&&r.optLong("at")<=end.getTimeInMillis())out.put(view(r));}
    return out;
  }
  private static int find(JSONArray list,String query){
    String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);if(q.isEmpty())return -1;
    for(int i=0;i<list.length();i++){String t=list.optJSONObject(i).optString("text").toLowerCase(Locale.ROOT);if(t.equals(q))return i;}
    for(int i=0;i<list.length();i++){String t=list.optJSONObject(i).optString("text").toLowerCase(Locale.ROOT);if(t.contains(q)||q.contains(t))return i;}
    return -1;
  }
  static JSONObject delete(Context c,String query){
    JSONArray list=all(c);int i=find(list,query);
    if(i<0)return PhoneControls.result("not_found","I couldn't find that reminder. Ask me to list your reminders.");
    JSONObject r=list.optJSONObject(i);cancel(c,r.optLong("id"));list.remove(i);store(c,list);
    return PhoneControls.result("deleted","Deleted the reminder: "+r.optString("text")+".");
  }
  /** "Le li" / "done" by voice: marks the most recent due reminder (or the named one) done. */
  static JSONObject done(Context c,String query){
    JSONArray list=all(c);int i=find(list,query);
    if(i<0){long latest=-1;for(int k=0;k<list.length();k++){JSONObject r=list.optJSONObject(k);if(r.optBoolean("waiting")&&r.optLong("firedAt")>latest){latest=r.optLong("firedAt");i=k;}}}
    if(i<0)return PhoneControls.result("not_found","No reminder is waiting for an answer right now.");
    String text=markDone(c,list.optJSONObject(i).optLong("id"));
    return PhoneControls.result("done","Marked done: "+text+".");
  }

  // ---- alarms
  private static PendingIntent intent(Context c,String action,long id){
    Intent i=new Intent(c,ReminderReceiver.class).setAction(action).putExtra("id",id);
    return PendingIntent.getBroadcast(c,(int)(id%100_000)*3+(FIRE.equals(action)?0:DONE.equals(action)?1:2),i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  }
  static boolean exact(Context c){AlarmManager a=c.getSystemService(AlarmManager.class);return Build.VERSION.SDK_INT<31||a.canScheduleExactAlarms();}
  private static String exactNote(Context c){return exact(c)?"":" (Allow \"Alarms & reminders\" for FRIDAY in Settings > Permissions so it rings on the exact minute.)";}
  private static void schedule(Context c,JSONObject r){
    AlarmManager a=c.getSystemService(AlarmManager.class);long at=r.optLong("at");PendingIntent p=intent(c,FIRE,r.optLong("id"));
    try{if(exact(c))a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,p);else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,p);}
    catch(SecurityException e){a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,p);}
  }
  private static void cancel(Context c,long id){c.getSystemService(AlarmManager.class).cancel(intent(c,FIRE,id));c.getSystemService(NotificationManager.class).cancel(note(id));}
  private static int note(long id){return 5000+(int)(id%100_000);}
  /** After a restart Android forgets alarms: set them all again. Missed one-time reminders fire now. */
  static void rescheduleAll(Context c){
    JSONArray list=all(c);long now=System.currentTimeMillis();
    for(int i=0;i<list.length();i++){JSONObject r=list.optJSONObject(i);try{if(r.optLong("at")<now)r.put("at",now+60_000);}catch(JSONException ignored){}schedule(c,r);}
    store(c,list);
  }

  // ---- firing
  static void fire(Context c,long id){
    JSONArray list=all(c);int i=-1;for(int k=0;k<list.length();k++)if(list.optJSONObject(k).optLong("id")==id)i=k;
    if(i<0)return;
    JSONObject r=list.optJSONObject(i);String text=r.optString("text"),kind=r.optString("kind");int follow=r.optInt("followUps");
    boolean hindi=prefs(c).getString("language","hi-IN").matches("(hi|ur).*");
    String title=follow>0?(hindi?"Yaad dilana: ":"Reminder again: ")+text:("medicine".equals(kind)?(hindi?"Dawai ka waqt: ":"Medicine time: "):"bill".equals(kind)?(hindi?"Bill ka din: ":"Bill due: "):(hindi?"Yaad dilana: ":"Reminder: "))+text;
    String ask="medicine".equals(kind)?(hindi?"Le li?":"Taken it?"):"bill".equals(kind)?(hindi?"Jama kar diya?":"Paid?"):(hindi?"Ho gaya?":"Done?");
    NotificationManager m=c.getSystemService(NotificationManager.class);
    m.createNotificationChannel(new NotificationChannel("care","FRIDAY reminders",NotificationManager.IMPORTANCE_HIGH));
    Notification n=new Notification.Builder(c,"care").setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title).setContentText(ask)
      .setCategory(Notification.CATEGORY_REMINDER).setAutoCancel(true)
      .setContentIntent(PendingIntent.getActivity(c,7,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE))
      .addAction(new Notification.Action.Builder(null,hindi?("medicine".equals(kind)?"Le li ✓":"Ho gaya ✓"):"Done ✓",intent(c,DONE,id)).build())
      .addAction(new Notification.Action.Builder(null,hindi?"10 min baad":"In 10 min",intent(c,SNOOZE,id)).build()).build();
    try{m.notify(note(id),n);}catch(SecurityException e){ControlLog.i("reminder: notifications blocked");}
    new Announcer(c).sayOnce(title+". "+ask);
    ControlLog.i("reminder: fired "+kind+(follow>0?" (follow-up "+follow+")":""));
    try{
      r.put("waiting",true).put("firedAt",System.currentTimeMillis());
      if(follow<FOLLOW_UPS&&!"general".equals(kind)){r.put("followUps",follow+1);r.put("at",System.currentTimeMillis()+FOLLOW_MINUTES*60_000L);schedule(c,r);}
      else advance(c,r,i,list);
      store(c,list);
    }catch(JSONException ignored){}
  }
  /** Moves a repeating reminder to its next day/month, or removes a one-time one. */
  private static void advance(Context c,JSONObject r,int index,JSONArray list) throws JSONException {
    r.put("followUps",0);
    if("none".equals(r.optString("repeat"))){list.remove(index);return;}
    r.put("at",nextTime(r));schedule(c,r);
  }
  static String markDone(Context c,long id){
    JSONArray list=all(c);
    for(int i=0;i<list.length();i++){JSONObject r=list.optJSONObject(i);if(r.optLong("id")!=id)continue;
      String text=r.optString("text");
      try{
        c.getSystemService(NotificationManager.class).cancel(note(id));
        r.put("waiting",false).put("lastDone",System.currentTimeMillis());
        if(r.optInt("followUps")>0){c.getSystemService(AlarmManager.class).cancel(intent(c,FIRE,id));advance(c,r,i,list);}
        else if("none".equals(r.optString("repeat"))&&r.optLong("at")<=System.currentTimeMillis())list.remove(i);
        store(c,list);
      }catch(JSONException ignored){}
      ControlLog.i("reminder: marked done");return text;
    }
    return "";
  }
  static void snooze(Context c,long id){
    JSONArray list=all(c);
    for(int i=0;i<list.length();i++){JSONObject r=list.optJSONObject(i);if(r.optLong("id")!=id)continue;
      try{
        c.getSystemService(NotificationManager.class).cancel(note(id));
        // A snoozed repeating reminder that was already moved on comes back as a one-off copy.
        JSONObject again=r;
        if(r.optInt("followUps")==0&&!"none".equals(r.optString("repeat"))){again=new JSONObject(r.toString());again.put("id",System.currentTimeMillis()).put("repeat","none").put("kind","general");list.put(again);}
        again.put("at",System.currentTimeMillis()+10*60_000L);schedule(c,again);store(c,list);
      }catch(JSONException ignored){}
      ControlLog.i("reminder: snoozed 10 min");return;
    }
  }
}
