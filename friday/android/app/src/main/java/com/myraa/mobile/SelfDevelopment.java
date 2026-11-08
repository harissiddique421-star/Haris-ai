package com.myraa.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

/**
 * Android side of FRIDAY's self-development: stores the health ledger and development memory on
 * the phone (private app storage, never uploaded), records crashes, versions and health checks,
 * and runs the self-analysis at most once a day. Sending an idea to the developer only opens a
 * prefilled GitHub page; the owner submits it there. Nothing here installs or changes the app.
 */
final class SelfDevelopment {
  private SelfDevelopment(){}
  static final String PREFS="friday_selfdev";
  static final String REPO="harissiddique421-star/Haris-ai";
  private static final Object lock=new Object();
  private static volatile boolean crashHandlerInstalled=false;

  private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,0);}
  private static JSONObject ledger(SharedPreferences p){try{return new JSONObject(p.getString("ledger","{}"));}catch(JSONException e){return new JSONObject();}}
  private static JSONArray memory(SharedPreferences p){try{return new JSONArray(p.getString("memory","[]"));}catch(JSONException e){return new JSONArray();}}

  /** Adds a measurement for today. Safe from any thread; never throws. */
  static void record(Context c,String kind,long value){
    if(c==null)return;
    try{synchronized(lock){SharedPreferences p=prefs(c);p.edit().putString("ledger",SelfImprovement.record(ledger(p),SelfImprovement.day(System.currentTimeMillis()),kind,value).toString()).apply();}}
    catch(RuntimeException ignored){}
  }
  static void remember(Context c,String kind,String title,String detail){
    if(c==null)return;
    try{synchronized(lock){SharedPreferences p=prefs(c);p.edit().putString("memory",SelfImprovement.remember(memory(p),kind,title,detail,System.currentTimeMillis()).toString()).apply();}}
    catch(RuntimeException ignored){}
  }

  /**
   * Called when FRIDAY starts: installs the crash recorder, records a crash from the last run and
   * notes a new version in the development memory.
   */
  static void start(Context c,String version){
    final Context app=c.getApplicationContext();
    if(!crashHandlerInstalled){
      crashHandlerInstalled=true;
      final Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
      Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
        try{
          // Only the error type and where it happened: no message text, which can hold user data.
          StackTraceElement at=null;
          for(StackTraceElement e:error.getStackTrace())if(e.getClassName().startsWith("com.myraa.")){at=e;break;}
          if(at==null&&error.getStackTrace().length>0)at=error.getStackTrace()[0];
          String place=error.getClass().getSimpleName()+(at==null?"":" at "+at.getClassName().replace("com.myraa.mobile.","")+"."+at.getMethodName()+":"+at.getLineNumber());
          prefs(app).edit().putString("pendingCrash",SelfImprovement.clip(place,200)).putString("pendingCrashThread",SelfImprovement.clip(thread.getName(),60)).commit();
        }catch(Throwable ignored){}
        if(previous!=null)previous.uncaughtException(thread,error);
      });
    }
    try{
      SharedPreferences p=prefs(app);String crash=p.getString("pendingCrash","");
      if(!crash.isEmpty()){
        p.edit().remove("pendingCrash").remove("pendingCrashThread").apply();
        record(app,"crash",1);
        remember(app,"bug","Crash: "+crash,"FRIDAY closed unexpectedly in version "+version+". Recorded on the next start.");
      }
      if(!version.equals(p.getString("version",""))){
        String before=p.getString("version","");
        p.edit().putString("version",version).apply();
        remember(app,"version",version,before.isEmpty()?"First version measured on this phone.":"Updated from "+before+". Numbers before and after this date can be compared.");
      }
    }catch(RuntimeException ignored){}
  }

  /** Self-analysis: findings for the last 7 days; refreshes ideas and quiet bugs at most once a day unless forced. */
  static JSONObject analyze(Context c,boolean force){
    synchronized(lock){
      SharedPreferences p=prefs(c);long now=System.currentTimeMillis();String today=SelfImprovement.day(now);
      JSONObject l=ledger(p);List<SelfImprovement.Finding> findings=SelfImprovement.analyze(l,today);
      if(force||!today.equals(p.getString("analyzedOn",""))){
        JSONArray mem=SelfImprovement.settle(SelfImprovement.proposeFrom(memory(p),findings,now),now);
        p.edit().putString("memory",mem.toString()).putString("analyzedOn",today).putLong("analyzedAt",now).apply();
      }
      JSONObject v=new JSONObject();
      try{
        JSONArray f=new JSONArray();for(SelfImprovement.Finding x:findings)f.put(x.json());
        v.put("status","done").put("findings",f).put("metrics",SelfImprovement.metrics(l,today))
          .put("message",SelfImprovement.report(l,today,findings)).put("analyzed_at",p.getLong("analyzedAt",0));
      }catch(JSONException ignored){}
      return v;
    }
  }

  /** Everything the Self-development page shows. */
  static JSONObject state(Context c,String version){
    JSONObject v=analyze(c,false);
    synchronized(lock){
      SharedPreferences p=prefs(c);
      try{v.put("memory",memory(p)).put("version",version).put("repo",REPO).put("deep_ideas",p.getString("deepIdeas","")).put("deep_ideas_at",p.getLong("deepIdeasAt",0));}catch(JSONException ignored){}
    }
    return v;
  }

  /** Owner actions from the page: approve, dismiss, sent, done, note, clear. Returns the new state plus a message (and a URL for "send"). */
  static JSONObject act(Context c,String version,JSONObject input){
    String what=input.optString("action"),id=input.optString("id");String message;String url="";
    synchronized(lock){
      SharedPreferences p=prefs(c);JSONArray mem=memory(p);long now=System.currentTimeMillis();
      switch(what){
        case "send": {
          JSONObject idea=SelfImprovement.find(mem,id);
          if(idea==null||!"approved".equals(idea.optString("status"))){message="Approve the idea first.";break;}
          url=SelfImprovement.issueUrl(REPO,idea,version,SelfImprovement.metrics(ledger(p),SelfImprovement.day(now)));
          message=SelfImprovement.decide(mem,id,"sent",now);
          break;
        }
        case "approve": case "dismiss": case "done": {
          String m=SelfImprovement.decide(mem,id,what,now);message=m==null?"That idea can't change to that state.":m;break;
        }
        case "note": {
          String kind=input.optString("kind","note");
          mem=SelfImprovement.remember(mem,kind,input.optString("title"),input.optString("detail"),now);message="Saved in the development memory.";break;
        }
        case "clear": {
          p.edit().remove("ledger").remove("memory").remove("analyzedOn").remove("deepIdeas").remove("deepIdeasAt").apply();
          remember(c,"note","Self-development data cleared","The owner cleared measurements and the development memory.");
          mem=memory(p);message="Measurements and development memory cleared.";break;
        }
        default: message="Unknown action.";
      }
      p.edit().putString("memory",mem.toString()).apply();
    }
    JSONObject v=state(c,version);
    try{v.put("message",message).put("url",url);}catch(JSONException ignored){}
    return v;
  }

  /** Saves the deeper ideas Gemini wrote from the measured numbers (Level 1: text only, nothing changes). */
  static void saveDeepIdeas(Context c,String text){
    synchronized(lock){prefs(c).edit().putString("deepIdeas",SelfImprovement.clip(text,4000)).putLong("deepIdeasAt",System.currentTimeMillis()).apply();}
    remember(c,"note","Asked Gemini for improvement ideas",SelfImprovement.clip(text,300));
  }

  /** What Gemini may see for deeper ideas: findings, numbers, open bugs and pending idea titles. No personal data. */
  static String researchBrief(Context c,String version){
    JSONObject s=state(c,version);StringBuilder b=new StringBuilder("FRIDAY version ").append(version).append("\nMeasured numbers: ").append(s.optJSONObject("metrics")).append("\nFindings: ").append(s.optJSONArray("findings")).append("\nDevelopment memory:\n");
    JSONArray mem=s.optJSONArray("memory");
    if(mem!=null)for(int i=Math.max(0,mem.length()-40);i<mem.length();i++){JSONObject e=mem.optJSONObject(i);if(e!=null)b.append("- [").append(e.optString("kind")).append(e.has("status")?"/"+e.optString("status"):"").append("] ").append(e.optString("title")).append('\n');}
    return SelfImprovement.clip(b.toString(),12000);
  }
}
