package com.myraa.mobile;

import org.json.*;
import java.util.*;

/**
 * FRIDAY Evolution and self-healing logic, free of Android classes so it can be checked on a plain JVM.
 *
 *  - Health: turns one probe of the phone (numbers and on/off facts only) into components with a state
 *    (healthy, warning, error, recovering), a plain sentence for the owner, technical detail for Diagnostics,
 *    and the safe recovery that may be tried, if any.
 *  - Recovery budget: at most MAX_TRIES tries per module in WINDOW_MS; after MAX_TRIES failed tries in a row
 *    the module is left alone for BLOCK_MS and reported instead. This is what stops restart loops.
 *  - Error knowledge base: what went wrong, where, which recovery was tried, and how often it worked. Only
 *    module names, counters and fixed explanations: never words, contacts, keys or audio.
 *  - Proposals: problems that keep coming back become improvement ideas that wait for the owner's approval.
 */
final class Evolution {
  private Evolution(){}

  static final String HEALTHY="healthy",WARNING="warning",ERROR="error",RECOVERING="recovering";
  static final int MAX_TRIES=2;
  static final long WINDOW_MS=30*60_000L,BLOCK_MS=6*3600_000L;
  static final int KB_LIMIT=60;

  // ---------------------------------------------------------------- health
  private static JSONObject part(String id,String group,String label,String state,String simple,String detail,String heal){
    JSONObject o=new JSONObject();
    try{o.put("id",id).put("group",group).put("label",label).put("state",state).put("simple",simple).put("detail",detail).put("heal",heal==null?"":heal);}catch(JSONException ignored){}
    return o;
  }
  private static boolean b(JSONObject p,String k){return p.optBoolean(k,false);}
  private static long n(JSONObject p,String k){return p.optLong(k,0);}

  /** Components for one probe. Keys missing from the probe count as false or 0. */
  static JSONArray evaluate(JSONObject p){
    JSONArray out=new JSONArray();
    // voice
    if(!b(p,"mic"))out.put(part("microphone","voice","Microphone",ERROR,"FRIDAY is not allowed to use the microphone.","RECORD_AUDIO not granted. Allow it in Settings > Permissions.",""));
    else out.put(part("microphone","voice","Microphone",HEALTHY,"Microphone allowed.","RECORD_AUDIO granted.",""));
    if(b(p,"wakeWanted")&&!b(p,"wakeRunning")&&!b(p,"liveRunning")&&b(p,"mic"))
      out.put(part("voice_service","voice","Wake listener",ERROR,"\"Wake up FRIDAY\" stopped listening.","WakeWordService wanted but not running; no Live call holds the microphone.","restart_wake"));
    else out.put(part("voice_service","voice","Wake listener",HEALTHY,b(p,"wakeWanted")?(b(p,"liveRunning")?"Paused while a call uses the microphone.":"Listening for \"Wake up FRIDAY\"."):"Wake word is off.","wanted="+b(p,"wakeWanted")+" running="+b(p,"wakeRunning")+" live="+b(p,"liveRunning"),""));
    if(!b(p,"vadOk")||!b(p,"encoderOk"))
      out.put(part("voice_models","voice","Voice models",ERROR,"A voice model did not load, so speech detection is weaker.","speech detector "+(b(p,"vadOk")?"ok":"failed")+", speaker model "+(b(p,"encoderOk")?"ok":"failed")+".","reload_models"));
    else out.put(part("voice_models","voice","Voice models",HEALTHY,"Speech detection and speaker models are ready.","Silero VAD and speaker encoder loaded.",""));
    if(b(p,"guardianWanted")&&!b(p,"guardianEnrolled"))out.put(part("guardian","voice","Voice Guardian",WARNING,"Voice Guardian is on but your voice is not recorded yet.","guardian wanted, no enrollment file.",""));
    else out.put(part("guardian","voice","Voice Guardian",HEALTHY,b(p,"guardianWanted")?"Only your voice can wake FRIDAY.":"Voice Guardian is off.","wanted="+b(p,"guardianWanted")+" enrolled="+b(p,"guardianEnrolled"),""));
    if(b(p,"voiceIdWanted")&&!b(p,"voiceIdRegistered"))out.put(part("voice_id","voice","Voice ID",WARNING,"Voice ID is on but not registered, so every voice is heard.","Voice ID wanted, no profile.",""));
    else out.put(part("voice_id","voice","Voice ID",HEALTHY,b(p,"voiceIdWanted")?"FRIDAY answers only your voice.":"Voice ID is off.","wanted="+b(p,"voiceIdWanted")+" registered="+b(p,"voiceIdRegistered"),""));
    long ignored=n(p,"voiceIgnored"),unsure=n(p,"voiceUnsure"),dup=n(p,"voiceDup"),noise=n(p,"voiceNoise"),owner=n(p,"voiceOwner");
    String noiseDetail="today: other voices ignored "+ignored+", unsure "+unsure+", noise dropped "+noise+", repeats dropped "+dup+", owner turns "+owner+".";
    if(unsure>=15&&unsure>owner)out.put(part("noise","voice","Noise handling",WARNING,"Many sounds today were not clearly your voice. A noisy place or a weak Voice ID recording can cause this.",noiseDetail,""));
    else out.put(part("noise","voice","Noise handling",HEALTHY,ignored+noise>0?"Background sound and other voices were ignored quietly.":"No noise problems today.",noiseDetail,""));
    // network and AI
    if(!b(p,"online"))out.put(part("network","ai","Network",ERROR,"No internet, so FRIDAY cannot think or talk live.","no active network.",""));
    else if(!b(p,"validated"))out.put(part("network","ai","Network",WARNING,"Connected, but the internet may not be working (login page or weak signal).","network not validated by Android.",""));
    else out.put(part("network","ai","Network",HEALTHY,"Internet works.","validated network.",""));
    long ok=n(p,"chatOk"),fail=n(p,"chatFail");
    if(!b(p,"aiKey"))out.put(part("ai","ai","AI connection",ERROR,"No AI key is saved.","no Gemini or provider key.",""));
    else if(fail>=3&&fail>ok&&b(p,"online"))out.put(part("ai","ai","AI connection",ERROR,"Most AI replies failed today.","today: "+ok+" ok, "+fail+" failed.","check_ai"));
    else if(fail>0)out.put(part("ai","ai","AI connection",WARNING,"Some AI replies failed today; FRIDAY retried them.","today: "+ok+" ok, "+fail+" failed.",""));
    else out.put(part("ai","ai","AI connection",HEALTHY,"AI replies work.","today: "+ok+" ok.",""));
    long ms=n(p,"chatMs");
    if(ms>=15000)out.put(part("latency","ai","Response speed",WARNING,"Replies are slow today (about "+Math.round(ms/1000.0)+" seconds).","average text reply "+ms+" ms.",""));
    else out.put(part("latency","ai","Response speed",HEALTHY,ms>0?"Replies take about "+Math.max(1,Math.round(ms/1000.0))+" seconds.":"No replies measured today.","average text reply "+ms+" ms.",""));
    // system
    if(!b(p,"memoryOk"))out.put(part("memory","system","Memory",ERROR,"FRIDAY's memory could not be read.","memory database query failed.",""));
    else out.put(part("memory","system","Memory",HEALTHY,"Memory works ("+n(p,"memoryCount")+" things remembered).","memories="+n(p,"memoryCount"),""));
    long crash=n(p,"crashes");
    if(crash>=3)out.put(part("stability","system","Stability",ERROR,"FRIDAY closed unexpectedly "+crash+" times this week.","crashes in 7 days: "+crash+". Places are in the development memory.",""));
    else if(crash>0)out.put(part("stability","system","Stability",WARNING,"FRIDAY closed unexpectedly "+crash+" time"+(crash==1?"":"s")+" this week.","crashes in 7 days: "+crash+".",""));
    else out.put(part("stability","system","Stability",HEALTHY,"No crashes this week.","crashes in 7 days: 0.",""));
    long level=p.optLong("battery",-1);
    if(level>=0&&level<=15&&!b(p,"charging"))out.put(part("battery","system","Battery",WARNING,"Battery is low ("+level+"%). Android may stop FRIDAY's listeners.","battery "+level+"%, not charging, saver="+b(p,"saver"),""));
    else if(b(p,"saver"))out.put(part("battery","system","Battery",WARNING,"Battery Saver is on and can stop the wake word.","battery "+level+"%, saver on.",""));
    else out.put(part("battery","system","Battery",HEALTHY,level>=0?"Battery "+level+"%.":"Battery fine.","battery "+level+"%, charging="+b(p,"charging"),""));
    return out;
  }

  /** The worst state of the components (recovering counts as a warning). */
  static String overall(JSONArray parts){
    int worst=0;
    for(int i=0;i<parts.length();i++){JSONObject o=parts.optJSONObject(i);if(o==null)continue;String s=o.optString("state");worst=Math.max(worst,ERROR.equals(s)?3:RECOVERING.equals(s)?2:WARNING.equals(s)?1:0);}
    return worst==3?ERROR:worst==2?RECOVERING:worst==1?WARNING:HEALTHY;
  }

  // ---------------------------------------------------------------- recovery budget
  /** "ok" when a try is allowed now, "wait" when the tries in this window are used, "blocked" after repeated failures. */
  static String gate(JSONObject budget,String module,long now){
    JSONObject m=budget==null?null:budget.optJSONObject(module);if(m==null)return "ok";
    if(now<m.optLong("blockedUntil",0))return "blocked";
    JSONArray tries=m.optJSONArray("tries");int recent=0;
    if(tries!=null)for(int i=0;i<tries.length();i++)if(now-tries.optLong(i)<WINDOW_MS)recent++;
    return recent>=MAX_TRIES?"wait":"ok";
  }
  /** Records one try and its result; returns the budget. Blocks the module after MAX_TRIES failures in a row. */
  static JSONObject tried(JSONObject budget,String module,boolean ok,long now){
    if(budget==null)budget=new JSONObject();
    try{
      JSONObject m=budget.optJSONObject(module);if(m==null){m=new JSONObject();budget.put(module,m);}
      JSONArray tries=m.optJSONArray("tries"),keep=new JSONArray();
      if(tries!=null)for(int i=0;i<tries.length();i++)if(now-tries.optLong(i)<WINDOW_MS)keep.put(tries.optLong(i));
      keep.put(now);m.put("tries",keep);
      int fails=ok?0:m.optInt("failsInRow",0)+1;m.put("failsInRow",fails);
      if(fails>=MAX_TRIES)m.put("blockedUntil",now+BLOCK_MS);else if(ok)m.remove("blockedUntil");
    }catch(JSONException ignored){}
    return budget;
  }

  // ---------------------------------------------------------------- error knowledge base
  /** Adds one occurrence (same type, module and action = same entry). ok: true worked, false failed, null not tried. */
  static JSONArray learn(JSONArray kb,String type,String module,String action,Boolean ok,String detail,long now){
    if(kb==null)kb=new JSONArray();
    if(type==null||module==null)return kb;
    type=SelfImprovement.clip(type.replaceAll("[^A-Za-z0-9_:.-]",""),60);module=SelfImprovement.clip(module.replaceAll("[^A-Za-z0-9_]",""),40);
    action=action==null?"":SelfImprovement.clip(action.replaceAll("[^A-Za-z0-9_]",""),40);
    if(type.isEmpty()||module.isEmpty())return kb;
    try{
      JSONObject e=null;
      for(int i=0;i<kb.length();i++){JSONObject x=kb.optJSONObject(i);if(x!=null&&type.equals(x.optString("type"))&&module.equals(x.optString("module"))&&action.equals(x.optString("action"))){e=x;break;}}
      if(e==null){e=new JSONObject().put("type",type).put("module",module).put("action",action).put("first",now).put("count",0).put("ok",0).put("fail",0);kb.put(e);}
      e.put("count",e.optInt("count")+1).put("last",now);
      if(ok!=null)e.put(ok?"ok":"fail",e.optInt(ok?"ok":"fail")+1).put("lastOk",ok.booleanValue());
      if(detail!=null&&!detail.isEmpty())e.put("detail",SelfImprovement.clip(detail,200));
      e.put("explain",explain(module,action,ok));
      while(kb.length()>KB_LIMIT){int oldest=0;for(int i=1;i<kb.length();i++)if(kb.optJSONObject(i).optLong("last")<kb.optJSONObject(oldest).optLong("last"))oldest=i;kb.remove(oldest);}
    }catch(JSONException ignored){}
    return kb;
  }

  /** The recovery that worked best before for this problem (unknown actions count as even odds; ties keep the given order). */
  static String best(JSONArray kb,String type,String module,String[] actions){
    if(actions==null||actions.length==0)return "";
    String pick=actions[0];double top=-1;
    for(String a:actions){
      int ok=0,fail=0;
      if(kb!=null)for(int i=0;i<kb.length();i++){JSONObject x=kb.optJSONObject(i);if(x!=null&&module.equals(x.optString("module"))&&a.equals(x.optString("action"))&&(type==null||type.equals(x.optString("type")))){ok+=x.optInt("ok");fail+=x.optInt("fail");}}
      double rate=(ok+1.0)/(ok+fail+2.0);if(rate>top){top=rate;pick=a;}
    }
    return pick;
  }

  private static final Map<String,String> PROBLEM=new HashMap<>();
  private static final Map<String,String> FIX=new HashMap<>();
  static{
    PROBLEM.put("voice_service","Voice listening was interrupted because the wake listener stopped");
    PROBLEM.put("voice_models","A voice model did not load");
    PROBLEM.put("agent_ear","The microphone in agentic mode stopped responding");
    PROBLEM.put("live","The Live voice connection dropped");
    PROBLEM.put("ai","AI replies were failing");
    PROBLEM.put("network","The internet connection was lost");
    PROBLEM.put("microphone","The microphone was not available");
    PROBLEM.put("stability","FRIDAY closed unexpectedly");
    FIX.put("restart_wake","I restarted the wake listener and checked that it is listening again");
    FIX.put("reload_models","I loaded the voice models again and checked that they work");
    FIX.put("restart_ear","I restarted the voice listener and checked the microphone connection");
    FIX.put("reconnect","I reconnected the Live session");
    FIX.put("switch_model","I switched to another Live model");
    FIX.put("check_ai","I tested the AI connection again");
  }
  /** One plain sentence for the owner, like "Voice listening was interrupted ... I restarted ...". */
  static String explain(String module,String action,Boolean ok){
    String problem=PROBLEM.getOrDefault(module,"Something in "+module.replace('_',' ')+" went wrong");
    if(action==null||action.isEmpty()||ok==null)return problem+". Nothing was changed automatically.";
    String fix=FIX.getOrDefault(action,"I tried "+action.replace('_',' '));
    return problem+". "+fix+(ok?"; it works again.":", but it did not help. I stopped trying and noted it for you.");
  }

  /** Problems that keep coming back, as findings that become ideas waiting for the owner's approval. */
  static List<SelfImprovement.Finding> proposals(JSONArray kb){
    List<SelfImprovement.Finding> out=new ArrayList<>();
    Map<String,int[]> byModule=new LinkedHashMap<>();
    if(kb!=null)for(int i=0;i<kb.length();i++){JSONObject x=kb.optJSONObject(i);if(x==null)continue;int[] t=byModule.computeIfAbsent(x.optString("module"),k->new int[3]);t[0]+=x.optInt("count");t[1]+=x.optInt("ok");t[2]+=x.optInt("fail");}
    for(Map.Entry<String,int[]> e:byModule.entrySet()){
      String m=e.getKey();int count=e.getValue()[0],ok=e.getValue()[1],fail=e.getValue()[2];
      if(count<5&&fail<3)continue;
      String cause,fix,benefit;int level=2;
      switch(m){
        case "voice_service":cause="Android stops background microphone services, usually because of battery optimisation.";fix="Allow \"Battery: no optimization\" for FRIDAY; a developer could also restart the listener earlier after Android stops it.";benefit="The wake word keeps working all day.";level=3;break;
        case "agent_ear":case "microphone":cause="Another app took the microphone, or the speech recogniser stopped.";fix="A developer should make the voice listener take the microphone back after other apps release it.";benefit="Agentic mode keeps hearing you without a restart.";break;
        case "live":cause="Weak connection or Google ending sessions.";fix="A developer could resume Live sessions faster and keep the conversation when the network returns.";benefit="Fewer dropped calls.";break;
        case "voice_models":cause="The model file could not be read, often when the phone was short on memory.";fix="A developer should load voice models again on the next call instead of once per app start.";benefit="Voice ID and noise filtering stay on.";break;
        case "ai":cause="Quota, key or Google outages.";fix="Choose a lighter default model or add a backup AI provider in Settings.";benefit="Replies keep working when one model fails.";level=3;break;
        default:cause="Not clear yet from the measurements.";fix="A developer should look at the error history for "+m.replace('_',' ')+".";benefit="Fewer interruptions.";
      }
      String risk=level==3?"Low (a setting you change)":"Low to medium (a new app version, tested before you install it)";
      out.add(new SelfImprovement.Finding("heal_"+m,"Self-healing: "+m.replace('_',' '),fail>=3?"high":"medium",40+Math.min(40,count*2+fail*5),level,
        "Problem detected: "+PROBLEM.getOrDefault(m,m.replace('_',' ')+" kept failing")+" "+count+" times; automatic recovery worked "+ok+" of "+(ok+fail)+". Possible cause: "+cause,
        "Recommended: "+fix+" Expected benefit: "+benefit+" Risk: "+risk+"."));
    }
    return out;
  }
}
