package com.myraa.mobile;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.PowerManager;
import java.net.URL;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/**
 * FRIDAY's self-diagnostics and self-healing on the phone. One check: probe (numbers and on/off facts only),
 * evaluate (Evolution), and for each problem with a safe recovery that the budget allows: mark it recovering,
 * apply the fix, test that it works, and record the result in the error knowledge base. A module whose
 * recovery fails twice in a row is left alone for six hours and reported once, so nothing loops.
 *
 * Safe recoveries only: restart the wake listener, load the voice models again, test the AI connection.
 * Nothing here changes permissions, security settings, keys or user data, sends messages or installs anything.
 * Runs every 30 minutes with the proactive check, after app start, and when the owner taps "Check now".
 */
final class SelfHealing {
  private SelfHealing(){}
  static final String PREFS="friday_health";
  interface Keys { String key() throws Exception; }
  private static final Object lock=new Object();

  private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,0);}
  /** Optional diagnostics (background checks, automatic recovery, error history). On by default. */
  static boolean on(Context c){return prefs(c).getBoolean("on",true);}
  static JSONArray kb(Context c){try{return new JSONArray(prefs(c).getString("kb","[]"));}catch(JSONException e){return new JSONArray();}}
  private static JSONObject budget(SharedPreferences p){try{return new JSONObject(p.getString("budget","{}"));}catch(JSONException e){return new JSONObject();}}
  private static JSONArray events(SharedPreferences p){try{return new JSONArray(p.getString("events","[]"));}catch(JSONException e){return new JSONArray();}}

  /** What the phone looks like right now. No words, contacts, keys or audio. */
  static JSONObject probe(Context c,boolean hasKey){
    JSONObject p=new JSONObject();
    try{
      p.put("mic",c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED);
      p.put("wakeWanted",WakeWordService.wanted(c)).put("wakeRunning",WakeWordService.running).put("liveRunning",LiveVoiceService.running);
      boolean needEncoder=VoiceId.active(c)||VoiceGuardian.active(c);
      p.put("vadOk",VoiceModels.vadOk()).put("encoderOk",!needEncoder||VoiceModels.encoderOk());
      p.put("guardianWanted",VoiceGuardian.wanted(c)).put("guardianEnrolled",VoiceGuardian.enrolled(c));
      p.put("voiceIdWanted",VoiceId.wanted(c)).put("voiceIdRegistered",VoiceId.registered(c));
      Map<String,Long> today=SelfDevelopment.counts(c,1),week=SelfDevelopment.counts(c,7);
      p.put("voiceIgnored",v(today,"voice_ignored")).put("voiceUnsure",v(today,"voice_unsure")).put("voiceDup",v(today,"voice_dup")).put("voiceNoise",v(today,"voice_noise")).put("voiceOwner",v(today,"voice_owner"));
      long ok=v(today,"chat_ok"),fail=v(today,"chat_fail");p.put("chatOk",ok).put("chatFail",fail).put("chatMs",ok>0?v(today,"chat_ms")/ok:0).put("crashes",v(week,"crash"));
      ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);Network n=cm==null?null:cm.getActiveNetwork();NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);
      p.put("online",caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).put("validated",caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
      p.put("aiKey",hasKey);
      try{p.put("memoryCount",android.database.DatabaseUtils.queryNumEntries(MemoryStore.get(c).getReadableDatabase(),"memories")).put("memoryOk",true);}catch(RuntimeException e){p.put("memoryOk",false);}
      try{BatteryManager bm=c.getSystemService(BatteryManager.class);p.put("battery",bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("charging",bm.isCharging());}catch(RuntimeException ignored){}
      try{p.put("saver",c.getSystemService(PowerManager.class).isPowerSaveMode());}catch(RuntimeException ignored){}
    }catch(JSONException ignored){}
    return p;
  }
  private static long v(Map<String,Long> m,String k){Long x=m.get(k);return x==null?0:x;}

  /** Does key presence without reading the key: the encrypted value exists in FRIDAY's settings. */
  static boolean keySaved(Context c){SharedPreferences s=c.getSharedPreferences("myraa.mobile.v1",Context.MODE_PRIVATE);return s.contains("keyCipher")||s.contains("altKeyCipher");}

  /**
   * One round: detect, analyse, pick a safe recovery, apply, test, log. keys may be null (background): then the
   * AI connection is not tested. Returns the state the dashboard shows.
   */
  static JSONObject check(Context c,boolean heal,Keys keys,String why){
    final Context app=c.getApplicationContext();
    synchronized(lock){
      long now=System.currentTimeMillis();
      JSONArray parts=Evolution.evaluate(probe(app,keySaved(app)));
      SharedPreferences p=prefs(app);
      if(heal&&on(app)){
        JSONObject budget=budget(p);JSONArray kb=kb(app);
        for(int i=0;i<parts.length();i++){
          JSONObject part=parts.optJSONObject(i);String fix=part.optString("heal");
          if(fix.isEmpty()||!Evolution.ERROR.equals(part.optString("state")))continue;
          String module=part.optString("id"),gate=Evolution.gate(budget,module,now);
          if(!"ok".equals(gate)){note(part,gate.equals("blocked")?"Automatic repair paused after failed tries; waiting for you.":"Repair already tried recently.");continue;}
          if("check_ai".equals(fix)&&keys==null)continue;
          if("restart_wake".equals(fix)&&!MainActivity.visible&&android.os.Build.VERSION.SDK_INT>=34)continue; // Android 14+ needs FRIDAY on screen; Proactive asks for one tap
          try{part.put("state",Evolution.RECOVERING);}catch(JSONException ignored){}
          p.edit().putString("last",snapshot(parts,now,why).toString()).apply();
          ControlLog.i("self-healing: "+module+" "+fix+" (detected: "+part.optString("detail")+")");
          boolean ok=apply(app,fix,keys);
          budget=Evolution.tried(budget,module,ok,now);
          kb=Evolution.learn(kb,module+"_down",module,fix,ok,part.optString("detail"),now);
          String explain=Evolution.explain(module,fix,ok);
          event(p,module,explain,ok,now);
          SelfDevelopment.record(app,ok?"heal_ok":"heal_fail",1);
          ControlLog.i("self-healing: "+module+" "+(ok?"recovered":"not recovered"));
          try{part.put("state",ok?Evolution.HEALTHY:Evolution.ERROR).put("simple",ok?"Recovered by itself: "+part.optString("simple"):part.optString("simple")).put("healed",explain);}catch(JSONException ignored){}
          if(!ok&&"blocked".equals(Evolution.gate(budget,module,now)))tell(app,module,explain);
        }
        p.edit().putString("budget",budget.toString()).putString("kb",kb.toString()).apply();
      }
      JSONObject s=snapshot(parts,now,why);
      p.edit().putString("last",s.toString()).apply();
      SelfDevelopment.record(app,"health_check",1);
      return s;
    }
  }
  private static void note(JSONObject part,String text){try{part.put("note",text);}catch(JSONException ignored){}}
  private static JSONObject snapshot(JSONArray parts,long now,String why){
    JSONObject s=new JSONObject();try{s.put("parts",parts).put("overall",Evolution.overall(parts)).put("at",now).put("why",why==null?"":why);}catch(JSONException ignored){}return s;
  }

  /** Applies one safe recovery and tests it. */
  private static boolean apply(Context c,String fix,Keys keys){
    try{
      switch(fix){
        case "restart_wake":{
          String r=WakeWordService.ensure(c);if(!"listening".equals(r))return false;
          for(int i=0;i<25&&!WakeWordService.running;i++)Thread.sleep(100);
          return WakeWordService.running;
        }
        case "reload_models":return VoiceModels.reload(c);
        case "check_ai":return aiReachable(keys.key());
        default:return false;
      }
    }catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
    catch(Exception e){ControlLog.i("self-healing: "+fix+" failed ("+e.getClass().getSimpleName()+")");return false;}
  }

  /** Lists one Gemini model with the key: proves network, key and Google work, and costs no tokens. */
  static boolean aiReachable(String key) throws Exception{
    if(key==null||key.isEmpty())return false;
    HttpsURLConnection h=(HttpsURLConnection)new URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1").openConnection();
    try{h.setConnectTimeout(6000);h.setReadTimeout(8000);h.setRequestProperty("x-goog-api-key",key);return h.getResponseCode()==200;}
    finally{h.disconnect();}
  }

  /** A recovery the page did (Live reconnect, model switch, agentic voice listener restart), or a problem it saw. */
  static void report(Context c,String module,String type,String action,Boolean ok,String detail){
    if(!on(c))return;
    synchronized(lock){
      SharedPreferences p=prefs(c);long now=System.currentTimeMillis();
      JSONArray kb=Evolution.learn(kb(c),type,module,action,ok,detail,now);
      p.edit().putString("kb",kb.toString()).apply();
      if(ok!=null)event(p,module,Evolution.explain(module,action,ok),ok,now);
    }
  }

  private static void event(SharedPreferences p,String module,String text,boolean ok,long now){
    JSONArray e=events(p);
    try{e.put(new JSONObject().put("at",now).put("module",module).put("text",text).put("ok",ok));}catch(JSONException ignored){}
    while(e.length()>30)e.remove(0);
    p.edit().putString("events",e.toString()).apply();
  }

  /** One quiet notification when automatic repair gave up (at most once a day per module). */
  private static void tell(Context c,String module,String text){
    SharedPreferences p=prefs(c);long now=System.currentTimeMillis();
    if(now-p.getLong("told_"+module,0)<24*3600_000L)return;
    p.edit().putLong("told_"+module,now).apply();
    try{
      NotificationManager m=c.getSystemService(NotificationManager.class);
      m.createNotificationChannel(new NotificationChannel("friday_health","FRIDAY self-healing",NotificationManager.IMPORTANCE_DEFAULT));
      PendingIntent open=PendingIntent.getActivity(c,47,new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
      m.notify(47,new Notification.Builder(c,"friday_health").setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("FRIDAY needs a look")
        .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text+" Open Settings > Self-development for details.")).setContentIntent(open).setAutoCancel(true).build());
    }catch(RuntimeException ignored){}
  }

  /** Everything the Evolution dashboard shows. */
  static JSONObject state(Context c){
    SharedPreferences p=prefs(c);JSONObject s;
    try{s=new JSONObject(p.getString("last","{}"));}catch(JSONException e){s=new JSONObject();}
    try{
      s.put("on",on(c)).put("kb",kb(c)).put("events",events(p));
      JSONObject budget=budget(p),blocked=new JSONObject();long now=System.currentTimeMillis();
      for(java.util.Iterator<String> it=budget.keys();it.hasNext();){String m=it.next();String g=Evolution.gate(budget,m,now);if(!"ok".equals(g))blocked.put(m,g);}
      s.put("paused",blocked);
    }catch(JSONException ignored){}
    return s;
  }

  /** Owner actions: options {on}, clear (error history), resume (allow repairs again). */
  static JSONObject act(Context c,JSONObject input){
    String what=input.optString("action");String message;
    synchronized(lock){
      SharedPreferences p=prefs(c);
      switch(what){
        case "options":p.edit().putBoolean("on",input.optBoolean("on",true)).apply();message=input.optBoolean("on",true)?"Self-diagnostics on.":"Optional diagnostics off: no background checks, automatic repairs or error history.";if(!input.optBoolean("on",true))p.edit().remove("kb").remove("events").apply();break;
        case "clear":p.edit().remove("kb").remove("events").remove("budget").apply();message="Error history cleared.";break;
        case "resume":p.edit().remove("budget").apply();message="Automatic repairs allowed again.";break;
        default:message="Unknown action.";
      }
    }
    JSONObject s=state(c);try{s.put("message",message);}catch(JSONException ignored){}
    return s;
  }
}
