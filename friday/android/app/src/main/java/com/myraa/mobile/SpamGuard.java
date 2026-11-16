package com.myraa.mobile;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import org.json.*;
import java.util.*;

/**
 * Spam / fraud call warning: when a number that is not in the contacts calls, FRIDAY shows a warning with a
 * Block button. Blocked numbers are rejected by FRIDAY from then on. With the "call screening" permission
 * (Android 10+) Android itself hands FRIDAY every call from a number outside the contacts (CallGuardService);
 * without it FRIDAY uses the dialer's call notification (NotificationReader). Numbers stay on this phone.
 */
final class SpamGuard {
  private SpamGuard(){}
  static final String CHANNEL="friday_spam";
  static final int NOTIFICATION=4491,MAX_BLOCKED=200;
  private static SharedPreferences prefs(Context c){return c.getSharedPreferences("friday.spam",Context.MODE_PRIVATE);}
  static boolean enabled(Context c){return prefs(c).getBoolean("enabled",true);}

  /** Android 10+: FRIDAY holds the call screening role, so CallGuardService sees unknown calls first. */
  static boolean screening(Context c){
    if(Build.VERSION.SDK_INT<29)return false;
    try{RoleManager r=c.getSystemService(RoleManager.class);return r!=null&&r.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)&&r.isRoleHeld(RoleManager.ROLE_CALL_SCREENING);}
    catch(RuntimeException e){return false;}
  }
  /** The calling code of the SIM's country, for spotting international calls. */
  static String homeCode(Context c){
    String iso="";
    try{TelephonyManager t=c.getSystemService(TelephonyManager.class);iso=t.getSimCountryIso();if(iso==null||iso.isEmpty())iso=t.getNetworkCountryIso();}catch(RuntimeException ignored){}
    switch(iso==null?"":iso.toLowerCase(Locale.ROOT)){
      case "":case "pk":return "92";
      case "in":return "91";case "ae":return "971";case "sa":return "966";case "gb":return "44";case "us":case "ca":return "1";
      case "qa":return "974";case "om":return "968";case "kw":return "965";case "bh":return "973";case "bd":return "880";
      default:return "";
    }
  }

  // ---- Blocked numbers
  private static JSONArray blockedList(Context c){try{return new JSONArray(prefs(c).getString("blocked","[]"));}catch(JSONException e){return new JSONArray();}}
  static boolean isBlocked(Context c,String number){
    String k=ProtectLogic.key(number);if(k.length()<5)return false;
    JSONArray a=blockedList(c);
    for(int i=0;i<a.length();i++)if(k.equals(a.optJSONObject(i)==null?"":a.optJSONObject(i).optString("key")))return true;
    return false;
  }
  /** Returns null when blocked, otherwise why not. */
  static String block(Context c,String number){
    String k=ProtectLogic.key(number);
    if(k.length()<5)return "That doesn't look like a phone number.";
    if(isBlocked(c,number))return null;
    JSONArray a=blockedList(c);
    if(a.length()>=MAX_BLOCKED)a.remove(0);
    try{a.put(new JSONObject().put("key",k).put("number",number.trim()).put("at",System.currentTimeMillis()));}catch(JSONException ignored){}
    prefs(c).edit().putString("blocked",a.toString()).apply();
    ControlLog.i("spam: number blocked");
    return null;
  }
  static boolean unblock(Context c,String key){
    JSONArray a=blockedList(c),out=new JSONArray();boolean removed=false;
    for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&o.optString("key").equals(key)){removed=true;continue;}if(o!=null)out.put(o);}
    prefs(c).edit().putString("blocked",out.toString()).apply();
    if(removed)ControlLog.i("spam: number unblocked");
    return removed;
  }

  // ---- Incoming calls
  private static String lastWarned="";private static long lastWarnedAt;
  /** A call from a number outside the contacts. Returns true when it should be rejected (blocked number). */
  static boolean incoming(Context c,String number){
    if(isBlocked(c,number)){ControlLog.i("spam: blocked number called, rejected");return true;}
    if(!enabled(c))return false;
    String k=ProtectLogic.key(number);long now=System.currentTimeMillis();
    synchronized(SpamGuard.class){if(k.equals(lastWarned)&&now-lastWarnedAt<60_000L)return false;lastWarned=k;lastWarnedAt=now;}
    ProtectLogic.Risk risk=ProtectLogic.callRisk(number,homeCode(c));
    if(ProtectLogic.NONE.equals(risk.level))return false;
    warn(c,number,risk);
    return false;
  }
  /** Dialer notification path: used when FRIDAY does not hold the call screening role. caller is the raw number shown. */
  static void fromNotification(Context c,String caller){
    if(screening(c))return; // CallGuardService already handled it
    String shown=caller==null?"":caller.trim();
    if(!shown.isEmpty()&&!CallerName.looksLikeNumber(shown))return; // a saved contact's name
    if(incoming(c,shown))endCall(c);
  }
  /** Spoken after "Call from 0 3 0 0…" when the warning is on. */
  static String spokenWarning(Context c,String caller,boolean hindi){
    if(!enabled(c))return "";
    String shown=caller==null?"":caller.trim();
    if(!shown.isEmpty()&&!CallerName.looksLikeNumber(shown))return "";
    ProtectLogic.Risk r=ProtectLogic.callRisk(shown,homeCode(c));
    if(ProtectLogic.NONE.equals(r.level))return "";
    return hindi?" Hoshiyar, yeh number aap ke contacts mein nahi, spam ya fraud ho sakta hai.":" Careful, this number is not in your contacts. It may be spam or fraud.";
  }

  private static void warn(Context c,String number,ProtectLogic.Risk risk){
    NotificationManager m=c.getSystemService(NotificationManager.class);if(m==null)return;
    NotificationChannel ch=new NotificationChannel(CHANNEL,"Spam call warning",NotificationManager.IMPORTANCE_HIGH);
    ch.setDescription("Warns when a number that is not in your contacts calls.");ch.setSound(null,null);
    m.createNotificationChannel(ch);
    String shown=number==null||number.trim().isEmpty()?"Private number":number.trim();
    String text=ProtectLogic.advice(risk);
    Notification.Builder b=new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_warning)
      .setContentTitle((ProtectLogic.HIGH.equals(risk.level)?"Khatra: ":"Hoshiyar: ")+shown).setContentText(text)
      .setStyle(new Notification.BigTextStyle().bigText(text)).setCategory(Notification.CATEGORY_STATUS)
      .setPriority(Notification.PRIORITY_HIGH).setAutoCancel(true).setTimeoutAfter(10*60_000L);
    if(ProtectLogic.key(number).length()>=5){
      Intent block=new Intent(c,ProtectReceiver.class).setAction(ProtectReceiver.BLOCK).putExtra("number",number.trim());
      b.addAction(new Notification.Action.Builder(null,"Block karo",PendingIntent.getBroadcast(c,NOTIFICATION,block,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build());
    }
    try{m.notify(NOTIFICATION,b.build());ControlLog.i("spam: warning shown ("+risk.level+")");}catch(RuntimeException e){ControlLog.i("spam: warning failed");}
  }
  /** "Block karo" on the warning: block the number and end the call if it is still ringing. */
  static void blockFromWarning(Context c,String number){
    if(number==null)return;
    block(c,number);
    try{c.getSystemService(NotificationManager.class).cancel(NOTIFICATION);}catch(RuntimeException ignored){}
    if(NotificationReader.ringingCaller()!=null)endCall(c);
  }
  @SuppressWarnings("deprecation")
  static boolean endCall(Context c){
    if(Build.VERSION.SDK_INT<28||c.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)!=PackageManager.PERMISSION_GRANTED)return false;
    try{TelecomManager t=c.getSystemService(TelecomManager.class);return t!=null&&t.endCall();}catch(RuntimeException e){return false;}
  }

  // ---- Settings
  static JSONObject state(Context c){
    JSONObject o=new JSONObject();
    try{
      JSONArray list=new JSONArray(),a=blockedList(c);
      for(int i=a.length()-1;i>=0;i--){JSONObject x=a.optJSONObject(i);if(x!=null)list.put(new JSONObject().put("key",x.optString("key")).put("number",x.optString("number")).put("at",x.optLong("at")));}
      o.put("enabled",enabled(c)).put("screening",screening(c)).put("screeningAvailable",Build.VERSION.SDK_INT>=29).put("blocked",list);
    }catch(JSONException ignored){}
    return o;
  }
  /** {enabled}, {block:number} or {unblock:key}. */
  static JSONObject options(Context c,JSONObject in){
    String message="";boolean ok=true;
    if(in.has("enabled")){boolean on=in.optBoolean("enabled");prefs(c).edit().putBoolean("enabled",on).apply();message=on?"Spam call warning is on.":"Spam call warning is off. Blocked numbers stay blocked.";ControlLog.i("spam: warning "+(on?"on":"off"));}
    if(in.has("block")){String why=block(c,in.optString("block"));ok=why==null;message=ok?"Blocked. FRIDAY will reject calls from this number.":why;}
    if(in.has("unblock")){ok=unblock(c,in.optString("unblock"));message=ok?"Unblocked.":"That number was not blocked.";}
    JSONObject out=new JSONObject();
    try{out.put("ok",ok).put("message",message).put("spam",state(c));}catch(JSONException ignored){}
    return out;
  }
}
