package com.myraa.mobile;

import org.json.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * FRIDAY's self-improvement logic, kept free of Android classes so it can be checked on a plain JVM.
 *
 *  - Health ledger: daily counters (replies, failures by reason, reply time, Live drops, crashes,
 *    agent failures). Numbers only: no words, contacts or audio. Kept for 28 days.
 *  - Self-analysis: compares the last 7 days with the 7 days before and turns clear problems into
 *    findings, each with its evidence (measured numbers), a suggestion and an approval level.
 *  - Development memory: bugs, fixes, failed approaches, ideas, versions and test results, so the
 *    same problem is not solved twice. Ideas wait for the owner: proposed, approved, sent, dismissed.
 *  - Self-healing for text chat: what to do after a failed reply (retry, switch to a backup model,
 *    or stop and tell the user).
 *
 * Approval levels follow the owner's rules: 1 = FRIDAY may do it alone (analysis, suggestions),
 * 2 = a developer can build and test it on a branch, 3 = the owner must approve or do it
 * (keys, settings, permissions, installing a new version).
 */
final class SelfImprovement {
  private SelfImprovement(){}

  static final int KEEP_DAYS = 28;
  static final int MEMORY_LIMIT = 200;
  /** Text models tried in order after the saved one fails with quota or "model unavailable". */
  static final String[] TEXT_MODELS = { "gemini-3.5-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite" };
  static final int MAX_CHAT_ATTEMPTS = 3;

  static String day(long millis){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);f.setTimeZone(TimeZone.getDefault());return f.format(new Date(millis));}
  private static String dayBefore(String day,int n){
    try{SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);Calendar c=Calendar.getInstance();c.setTime(f.parse(day));c.add(Calendar.DAY_OF_MONTH,-n);return f.format(c.getTime());}
    catch(java.text.ParseException e){return day;}
  }

  // ---------------------------------------------------------------- health ledger
  /** Adds value to one counter for that day. Kind names: letters, digits, '_' and ':' only. */
  static JSONObject record(JSONObject ledger,String day,String kind,long value){
    if(ledger==null)ledger=new JSONObject();
    if(kind==null||!kind.matches("[A-Za-z0-9_:]{1,60}"))return ledger;
    try{
      JSONObject days=ledger.optJSONObject("days");if(days==null){days=new JSONObject();ledger.put("days",days);}
      JSONObject d=days.optJSONObject(day);if(d==null){d=new JSONObject();days.put(day,d);}
      d.put(kind,d.optLong(kind,0)+value);
      // forget days older than KEEP_DAYS
      String oldest=dayBefore(day,KEEP_DAYS-1);List<String> drop=new ArrayList<>();
      for(Iterator<String> it=days.keys();it.hasNext();){String k=it.next();if(k.compareTo(oldest)<0)drop.add(k);}
      for(String k:drop)days.remove(k);
    }catch(JSONException ignored){}
    return ledger;
  }

  /** Sum of every counter over `days` days ending `endsDaysAgo` days before today (0 = including today). */
  static Map<String,Long> window(JSONObject ledger,String today,int endsDaysAgo,int days){
    Map<String,Long> sum=new TreeMap<>();
    JSONObject all=ledger==null?null:ledger.optJSONObject("days");if(all==null)return sum;
    for(int i=endsDaysAgo;i<endsDaysAgo+days;i++){
      JSONObject d=all.optJSONObject(dayBefore(today,i));if(d==null)continue;
      for(Iterator<String> it=d.keys();it.hasNext();){String k=it.next();sum.put(k,sum.getOrDefault(k,0L)+d.optLong(k,0));}
    }
    return sum;
  }
  private static long n(Map<String,Long> m,String k){Long v=m.get(k);return v==null?0:v;}

  // ---------------------------------------------------------------- self-analysis
  static final class Finding {
    final String id,area,severity,evidence,suggestion;final int level;final double score;
    Finding(String id,String area,String severity,double score,int level,String evidence,String suggestion){this.id=id;this.area=area;this.severity=severity;this.score=score;this.level=level;this.evidence=evidence;this.suggestion=suggestion;}
    JSONObject json(){JSONObject o=new JSONObject();try{o.put("id",id).put("area",area).put("severity",severity).put("score",Math.round(score*10)/10.0).put("level",level).put("evidence",evidence).put("suggestion",suggestion);}catch(JSONException ignored){}return o;}
  }

  private static String pct(long part,long whole){return whole<=0?"0%":Math.round(part*100.0/whole)+"%";}
  private static String trend(double now,double before,boolean haveBefore){
    if(!haveBefore)return "";
    if(before<=0)return now>0?" (none the week before)":"";
    long change=Math.round((now-before)*100/before);
    return change==0?" (same as the week before)":" ("+(change>0?"up ":"down ")+Math.abs(change)+"% from the week before)";
  }

  /** Findings for the last 7 days, worst first. Only clear, measured problems; nothing is guessed. */
  static List<Finding> analyze(JSONObject ledger,String today){
    Map<String,Long> w=window(ledger,today,0,7),p=window(ledger,today,7,7);
    boolean hasPrev=!p.isEmpty();
    List<Finding> out=new ArrayList<>();

    long ok=n(w,"chat_ok"),fail=n(w,"chat_fail"),total=ok+fail;
    long pOk=n(p,"chat_ok"),pFail=n(p,"chat_fail"),pTotal=pOk+pFail;
    if(total>=5&&fail*100>=total*15){
      String top="";long topN=0;
      for(Map.Entry<String,Long> e:w.entrySet())if(e.getKey().startsWith("chat_fail:")&&e.getValue()>topN){topN=e.getValue();top=e.getKey().substring(10);}
      String advice;int level;
      if(top.startsWith("API_KEY")||top.equals("ACCOUNT_STATE_INVALID")||top.equals("CONSUMER_SUSPENDED")||top.equals("GOOGLE_ACCESS_DENIED")||top.equals("SERVICE_DISABLED")){advice="Most failures are a key or Google account problem ("+top+"). Only you can fix this: replace or repair the key in Google AI Studio, then check it in Settings.";level=3;}
      else if(top.equals("QUOTA_EXCEEDED")){advice="Most failures are Google's quota running out. FRIDAY now switches to a backup model by itself; choosing a lighter default text model (for example gemini-3.5-flash-lite) in Settings would avoid it.";level=3;}
      else if(top.equals("PROVIDER_UNAVAILABLE")||top.equals("TIMEOUT")||top.equals("NETWORK")){advice="Most failures are Google outages or a weak connection ("+top+"). FRIDAY retries once by itself; a developer could add a short offline queue so messages send when the connection returns.";level=2;}
      else{advice="Failures are mixed"+(top.isEmpty()?"":" (most often "+top+")")+". A developer should look at the failure log in this report.";level=2;}
      double rate=fail*1.0/total,pRate=pTotal==0?0:pFail*1.0/pTotal;
      out.add(new Finding("chat_failures","Text replies",rate>=0.4?"high":"medium",50+rate*50,level,
        fail+" of "+total+" text replies failed ("+pct(fail,total)+")"+trend(rate,pRate,pTotal>0)+".",advice));
    }
    long ms=n(w,"chat_ms"),pMs=n(p,"chat_ms");
    if(ok>=5&&ms/ok>8000){
      double avg=ms/1000.0/ok,pAvg=pOk==0?0:pMs/1000.0/pOk;
      out.add(new Finding("chat_slow","Reply speed","medium",30+Math.min(30,avg),2,
        "Text replies took "+String.format(Locale.ROOT,"%.1f",avg)+" s on average"+trend(avg,pAvg,pOk>0)+".",
        "Send fewer old messages with each request (the rolling summary already covers them) or use a faster text model. Measure again after the change."));
    }
    long lr=n(w,"live_reply"),lrMs=n(w,"live_reply_ms"),plr=n(p,"live_reply"),plrMs=n(p,"live_reply_ms");
    if(lr>=5&&lrMs/lr>2500){
      double avg=lrMs/1000.0/lr,pAvg=plr==0?0:plrMs/1000.0/plr;
      out.add(new Finding("voice_slow","Voice response speed","medium",30+Math.min(30,avg*5),2,
        "Live voice replies started "+String.format(Locale.ROOT,"%.1f",avg)+" s after you stopped speaking, on average"+trend(avg,pAvg,plr>0)+".",
        "Check the end-of-turn timing and Voice ID pause in the timing log; a developer can tune them on a test build."));
    }
    long le=n(w,"live_error"),lrc=n(w,"live_reconnect"),ls=n(w,"live_switch");
    if(le>=3||lrc>=5){
      out.add(new Finding("live_drops","Live call reliability",le>=8?"high":"medium",40+Math.min(40,le*3+lrc*2),2,
        le+" Live call errors, "+lrc+" automatic reconnects and "+ls+" model switches in 7 days"+trend(le,n(p,"live_error"),hasPrev)+".",
        "FRIDAY already reconnects by itself. If drops keep happening on Wi-Fi and mobile data alike, a developer should compare Live models and the reconnect timing."));
    }
    long fb=n(w,"heal_fallback");
    if(fb>=3){
      out.add(new Finding("model_fallbacks","Model choice","medium",35+Math.min(30,fb*3),3,
        "FRIDAY had to switch to a backup text model "+fb+" times in 7 days.",
        "Your saved text model is often unavailable or out of quota. Pick the model FRIDAY falls back to most in Settings > Personal."));
    }
    long crash=n(w,"crash");
    if(crash>0){
      out.add(new Finding("crashes","App stability",crash>=3?"high":"medium",70+Math.min(30,crash*5),2,
        "FRIDAY closed unexpectedly "+crash+" time"+(crash==1?"":"s")+" in 7 days"+trend(crash,n(p,"crash"),hasPrev)+". The crash place is in the development memory.",
        "A developer should reproduce the crash from the recorded place and add a test for it."));
    }
    long unclear=n(w,"voice_unclear");
    if(unclear>=10){
      out.add(new Finding("voice_unclear","Voice recognition","low",20+Math.min(30,unclear),3,
        "FRIDAY could not make out what was said "+unclear+" times in 7 days"+trend(unclear,n(p,"voice_unclear"),hasPrev)+".",
        "Record your voice again in Settings > FRIDAY (Voice ID) in a quiet room, or lower the wake sensitivity if the TV or music is near."));
    }
    for(Map.Entry<String,Long> e:w.entrySet()){
      if(!e.getKey().startsWith("agent_run:"))continue;
      String agent=e.getKey().substring(10);long runs=e.getValue(),failed=n(w,"agent_fail:"+agent);
      if(runs>=3&&failed*100>=runs*30)out.add(new Finding("agent_"+agent,"Phone actions ("+agent+")","medium",30+failed*100.0/runs/3,2,
        failed+" of "+runs+" "+agent+" actions failed ("+pct(failed,runs)+").",
        "Check the permissions this agent needs in Settings > Permissions; if they are all allowed, a developer should look at the action history for the failing steps."));
    }
    long hp=n(w,"health_problem"),hc=n(w,"health_check");
    if(hc>0&&hp>0)out.add(new Finding("health_problems","Setup","low",15+Math.min(20,hp),3,
      "The last health checks found "+hp+" problem"+(hp==1?"":"s")+" across "+hc+" check"+(hc==1?"":"s")+".",
      "Say \"health check\" to hear each problem and its fix. Most need a permission or setting only you can change."));
    out.sort((a,b)->Double.compare(b.score,a.score));
    return out;
  }

  /** Measured numbers for the dashboard: this week and the week before. */
  static JSONObject metrics(JSONObject ledger,String today){
    Map<String,Long> w=window(ledger,today,0,7),p=window(ledger,today,7,7);JSONObject o=new JSONObject();
    try{
      o.put("this_week",metricRow(w)).put("last_week",metricRow(p));
      o.put("days_measured",ledger==null||ledger.optJSONObject("days")==null?0:ledger.optJSONObject("days").length());
    }catch(JSONException ignored){}
    return o;
  }
  private static JSONObject metricRow(Map<String,Long> m) throws JSONException{
    long ok=n(m,"chat_ok"),fail=n(m,"chat_fail"),lr=n(m,"live_reply");
    return new JSONObject().put("text_replies",ok).put("text_failures",fail)
      .put("text_avg_ms",ok==0?0:n(m,"chat_ms")/ok).put("voice_avg_ms",lr==0?0:n(m,"live_reply_ms")/lr)
      .put("live_errors",n(m,"live_error")).put("reconnects",n(m,"live_reconnect")).put("backup_model_used",n(m,"heal_fallback"))
      .put("retries_fixed",n(m,"heal_recovered")).put("crashes",n(m,"crash")).put("voice_unclear",n(m,"voice_unclear"));
  }

  /** One spoken/typed paragraph: what works, what fails, what to improve. */
  static String report(JSONObject ledger,String today,List<Finding> findings){
    Map<String,Long> w=window(ledger,today,0,7);
    long ok=n(w,"chat_ok"),fail=n(w,"chat_fail"),live=n(w,"live_reply");
    if(ok+fail+live+n(w,"live_error")+n(w,"crash")==0)return "I don't have enough measurements yet. I record reply times, failures and crashes as you use me, and I'll have a real self-analysis after a few days.";
    StringBuilder b=new StringBuilder("In the last 7 days: "+ok+" text replies worked"+(fail>0?", "+fail+" failed":"")+(live>0?", "+live+" voice replies measured":"")+".");
    long healed=n(w,"heal_recovered");if(healed>0)b.append(" I fixed ").append(healed).append(" failed replies myself with a retry or backup model.");
    if(findings.isEmpty())b.append(" Nothing measured needs fixing right now.");
    else{b.append(" ").append(findings.size()).append(" thing").append(findings.size()==1?"":"s").append(" to improve. Most important: ").append(findings.get(0).evidence).append(" ").append(findings.get(0).suggestion);}
    return b.toString();
  }

  // ---------------------------------------------------------------- development memory
  static final Set<String> KINDS=new HashSet<>(Arrays.asList("bug","fix","failed","idea","version","test","note"));

  /** Adds or updates an entry (same kind and title = same entry; its count goes up). */
  static JSONArray remember(JSONArray mem,String kind,String title,String detail,long now){
    if(mem==null)mem=new JSONArray();
    if(!KINDS.contains(kind)||title==null||title.trim().isEmpty())return mem;
    title=clip(title.trim(),120);detail=clip(detail==null?"":detail.trim(),600);
    try{
      for(int i=0;i<mem.length();i++){
        JSONObject e=mem.getJSONObject(i);
        if(kind.equals(e.optString("kind"))&&title.equals(e.optString("title"))){
          e.put("last",now).put("count",e.optInt("count",1)+1);if(!detail.isEmpty())e.put("detail",detail);
          if("bug".equals(kind)&&"quiet".equals(e.optString("status")))e.put("status","open");
          return mem;
        }
      }
      JSONObject e=new JSONObject().put("id",kind+"-"+Long.toString(now,36)+"-"+mem.length()).put("kind",kind).put("title",title).put("detail",detail).put("first",now).put("last",now).put("count",1);
      if("idea".equals(kind))e.put("status","proposed");
      if("bug".equals(kind))e.put("status","open");
      mem.put(e);
      while(mem.length()>MEMORY_LIMIT){
        int drop=0;for(int i=0;i<mem.length();i++){JSONObject x=mem.getJSONObject(i);String s=x.optString("status");if(!"open".equals(s)&&!"approved".equals(s)&&!"proposed".equals(s)){drop=i;break;}}
        mem.remove(drop);
      }
    }catch(JSONException ignored){}
    return mem;
  }

  /** Each finding becomes (or refreshes) a proposed idea. Ideas the owner dismissed stay dismissed. */
  static JSONArray proposeFrom(JSONArray mem,List<Finding> findings,long now){
    if(mem==null)mem=new JSONArray();
    for(Finding f:findings){
      JSONObject existing=find(mem,"idea:"+f.id);
      if(existing!=null){
        try{existing.put("detail",clip(f.evidence+" "+f.suggestion,600)).put("last",now).put("level",f.level).put("severity",f.severity).put("area",f.area);}catch(JSONException ignored){}
        continue;
      }
      try{mem.put(new JSONObject().put("id","idea:"+f.id).put("kind","idea").put("title",f.area+": "+f.suggestion.split("(?<=\\.) ")[0]).put("detail",clip(f.evidence+" "+f.suggestion,600))
        .put("first",now).put("last",now).put("count",1).put("status","proposed").put("level",f.level).put("severity",f.severity).put("area",f.area));}catch(JSONException ignored){}
    }
    return mem;
  }

  /** Bugs with no new occurrence for 7 days are marked "quiet" (not "fixed": that needs a verified fix). */
  static JSONArray settle(JSONArray mem,long now){
    if(mem==null)return new JSONArray();
    for(int i=0;i<mem.length();i++){JSONObject e=mem.optJSONObject(i);if(e!=null&&"bug".equals(e.optString("kind"))&&"open".equals(e.optString("status"))&&now-e.optLong("last",now)>7L*86400000L)try{e.put("status","quiet");}catch(JSONException ignored){}}
    return mem;
  }

  static JSONObject find(JSONArray mem,String id){
    if(mem==null||id==null)return null;
    for(int i=0;i<mem.length();i++){JSONObject e=mem.optJSONObject(i);if(e!=null&&id.equals(e.optString("id")))return e;}
    return null;
  }

  /** Owner decisions on an idea. Returns a message, or null when the change is not allowed. */
  static String decide(JSONArray mem,String id,String decision,long now){
    JSONObject e=find(mem,id);if(e==null||!"idea".equals(e.optString("kind")))return null;
    String s=e.optString("status");
    try{
      if("approve".equals(decision)&&("proposed".equals(s)||"dismissed".equals(s))){e.put("status","approved").put("decided",now);return "Approved. Tap \"Send to developer\" to open it as a GitHub issue.";}
      if("dismiss".equals(decision)&&!"sent".equals(s)){e.put("status","dismissed").put("decided",now);return "Dismissed. FRIDAY won't suggest it again.";}
      if("sent".equals(decision)&&"approved".equals(s)){e.put("status","sent").put("sent",now);return "Opened on GitHub. Submit the issue there to send it.";}
      if("done".equals(decision)&&("sent".equals(s)||"approved".equals(s))){e.put("status","done").put("decided",now);return "Marked as done.";}
    }catch(JSONException ignored){}
    return null;
  }

  /**
   * A prefilled "new issue" link for an approved idea. Only the idea and measured numbers go in it:
   * no names, messages, contacts or keys. The owner still has to press Submit on GitHub.
   */
  static String issueUrl(String repo,JSONObject idea,String version,JSONObject metrics){
    if(repo==null||!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")||idea==null)return "";
    String title="[FRIDAY self-improvement] "+idea.optString("title");
    String body="Proposed by FRIDAY's self-analysis and approved by the owner on the phone.\n\n"
      +"**Area:** "+idea.optString("area","-")+"\n**Severity:** "+idea.optString("severity","-")+"\n**Approval level:** "+idea.optInt("level",2)+"\n**App version:** "+version+"\n\n"
      +"### Evidence and suggestion\n"+idea.optString("detail")+"\n\n"
      +"### Measured numbers (this week / last week)\n```\n"+(metrics==null?"{}":metrics.toString())+"\n```\n\n"
      +"### Done when\n- The change is built on a branch, tests pass and a release candidate APK is produced.\n- The same numbers are measured again after installing it.\n";
    try{
      return "https://github.com/"+repo+"/issues/new?labels=friday-self-improvement&title="+URLEncoder.encode(clip(title,200),"UTF-8")+"&body="+URLEncoder.encode(clip(body,6000),"UTF-8");
    }catch(java.io.UnsupportedEncodingException e){return "";}
  }

  // ---------------------------------------------------------------- self-healing (text chat)
  /** What to do after a failed text reply: "retry" the same model, "switch" to a backup model, or "stop". */
  static String heal(String code,int attempt){
    if(attempt>=MAX_CHAT_ATTEMPTS)return "stop";
    if(code==null)return "stop";
    switch(code){
      case "TIMEOUT": case "NETWORK": case "PROVIDER_UNAVAILABLE": return attempt==1?"retry":"stop";
      case "QUOTA_EXCEEDED": case "MODEL_UNAVAILABLE": case "MODEL_CHAT_UNSUPPORTED": return "switch";
      default: return "stop";
    }
  }
  /** The next text model to try, or "" when every backup was tried. */
  static String nextTextModel(String saved,Collection<String> tried){
    for(String m:TEXT_MODELS)if(!m.equals(saved)&&!tried.contains(m))return m;
    return "";
  }

  static String clip(String s,int max){return s==null?"":s.length()<=max?s:s.substring(0,max);}
}
