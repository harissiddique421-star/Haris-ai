package com.myraa.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * How much FRIDAY does on her own, and the master pause.
 *  - Assisted: she asks before any action that changes something (the screen code holds the approval).
 *  - Balanced (default): today's behaviour. Messages, calls and deletes always need the user's yes.
 *  - Maximum: she runs low-risk multi-step tasks without stopping to ask. The same rules for messages,
 *    calls, deletes and money stay; maximum never means bypassing Android or another app's protection.
 * Pause: no phone action runs and FRIDAY starts no conversation or check-in until it is turned off.
 */
final class Autonomy {
  private static final String PREFS="friday_autonomy";
  private Autonomy(){}
  private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}

  static String normalize(String level){return "assisted".equals(level)||"maximum".equals(level)?level:"balanced";}
  static String level(Context c){return normalize(prefs(c).getString("level","balanced"));}
  static boolean paused(Context c){return prefs(c).getBoolean("paused",false);}
  /** WhatsApp messages the owner dictated (name and words in their own sentence) are sent without a second question. On by default. */
  static boolean directMessages(Context c){return prefs(c).getBoolean("directMessages",true);}

  /**
   * True when the user's own last sentence already holds the recipient and the message: a word of the contact's
   * name (or of the name the model passed) and at least 70% of the message words. Then that sentence was the
   * confirmation. Any rewording, translation or extra text by the model fails this, so FRIDAY asks once as before.
   */
  static boolean dictated(String heard,String contactName,String asked,String text){
    String h=" "+norm(heard)+" ";if(h.trim().isEmpty()||text==null)return false;
    boolean named=false;
    for(String n:(norm(contactName)+" "+norm(asked)).split(" "))if(n.length()>=3&&h.contains(" "+n+" ")){named=true;break;}
    if(!named)return false;
    String[] words=norm(text).split(" ");int all=0,found=0;
    for(String w:words){if(w.isEmpty())continue;all++;if(h.contains(" "+w+" "))found++;}
    return all>0&&found*10>=all*7;
  }
  private static String norm(String s){return s==null?"":s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}

  static JSONObject state(Context c){
    JSONObject s=new JSONObject();
    try{s.put("level",level(c)).put("paused",paused(c)).put("directMessages",directMessages(c)).put("modulesOff",new org.json.JSONArray(modulesOff(c)));}catch(JSONException ignored){}
    return s;
  }

  static final java.util.List<String> MODULES=java.util.Arrays.asList("communication","media","device","accessibility","memory","document","research","core");
  /** Modules the owner switched off in the Command Center (the screen code refuses their actions). */
  static java.util.List<String> modulesOff(Context c){
    java.util.List<String> out=new java.util.ArrayList<>();
    for(String m:prefs(c).getString("modulesOff","").split(","))if(MODULES.contains(m)&&!out.contains(m))out.add(m);
    return out;
  }
  static JSONObject setModule(Context c,String module,boolean on){
    if(MODULES.contains(module)){
      java.util.List<String> off=modulesOff(c);off.remove(module);if(!on)off.add(module);
      prefs(c).edit().putString("modulesOff",android.text.TextUtils.join(",",off)).apply();
      ControlLog.i("command center: "+module+(on?" on":" off"));
    }
    return state(c);
  }

  /** level: assisted, balanced, maximum, pause or resume. Returns a mobile action result with the new state. */
  static JSONObject apply(Context c,String level){
    String l=level==null?"":level.trim().toLowerCase(java.util.Locale.ROOT);
    JSONObject r=new JSONObject();
    try{
      if("pause".equals(l)){prefs(c).edit().putBoolean("paused",true).apply();ControlLog.i("autonomy: paused");r.put("status","done").put("message","FRIDAY is paused: no phone actions and no check-ins until you say \"pause khatam karo\" or turn it off in Settings.");}
      else if("direct_on".equals(l)||"direct_off".equals(l)){boolean on="direct_on".equals(l);prefs(c).edit().putBoolean("directMessages",on).apply();ControlLog.i("autonomy: direct messages "+on);r.put("status","done").put("message",on?"WhatsApp messages you dictate yourself (name and words) are sent without asking again.":"FRIDAY asks once before every WhatsApp message.");}
      else if("resume".equals(l)){prefs(c).edit().putBoolean("paused",false).apply();ControlLog.i("autonomy: resumed");r.put("status","done").put("message","Pause is off. FRIDAY works normally again ("+label(level(c))+" mode).");}
      else if(l.matches("assisted|balanced|maximum")){prefs(c).edit().putString("level",l).apply();ControlLog.i("autonomy: "+l);r.put("status","done").put("message",label(l)+" mode is on. "+explain(l));}
      else r.put("status","error").put("message","Say assisted, balanced, maximum, pause or resume.");
      r.put("autonomy",state(c));
    }catch(JSONException ignored){}
    return r;
  }

  static String label(String l){return "assisted".equals(l)?"Assisted":"maximum".equals(l)?"Maximum":"Balanced";}
  static String explain(String l){
    if("assisted".equals(l))return "FRIDAY asks before every action that changes something.";
    if("maximum".equals(l))return "FRIDAY does low-risk steps without asking; messages, calls, deletes and money still need your yes.";
    return "FRIDAY does simple actions at once and asks before messages, calls and deletes.";
  }

  /** One line for the Live call's instructions. */
  static String prompt(Context c){
    String l=level(c);
    String rule="assisted".equals(l)
      ?"ASSISTED: before any mobile_action that changes something (open, play, set, type, tap, remember...), ask the user in one short line and act only after a clear yes. Answering questions and reading need no question. The phone also enforces this and may answer needs_confirmation with a confirm_token: then ask once and call again with that token after the yes."
      :"maximum".equals(l)
      ?"MAXIMUM: for a goal with several low-risk steps (open apps, search, play, set alarms or reminders, read the screen) plan the steps and do them one after another without asking between steps, then say the result. Messages, calls, deletes, payments, purchases and security settings still follow their confirmation rules exactly."
      :"BALANCED: do simple, low-risk actions at once; messages, calls and deletes follow their confirmation rules.";
    return "\nAUTONOMY: "+rule+(paused(c)?" FRIDAY IS PAUSED: run no mobile_action except set_autonomy with level resume when the user asks to end the pause; do not start topics or check-ins.":"");
  }
}
