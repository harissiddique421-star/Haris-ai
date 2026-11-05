package com.myraa.mobile;

import android.content.*;
import org.json.*;
import java.util.*;

/**
 * Long-term memory, the user's name, persona and the Gemini voice for each persona, stored on
 * this phone only in the app's private preferences (allowBackup=false). Memories are given to
 * Gemini at the start of each Live call as facts about the user, never as instructions.
 */
final class AssistantMemory {
  static final int MAX_MEMORIES=200;
  static final String[] PERSONAS={"friday","gf","professional","venom"};
  /** Gemini Live prebuilt voices offered in Settings. */
  static final String[] VOICES={"Aoede","Kore","Leda","Zephyr","Laomedeia","Despina","Erinome","Callirrhoe","Autonoe","Gacrux","Vindemiatrix","Sulafat","Achernar","Pulcherrima","Puck","Charon","Fenrir","Orus","Iapetus","Umbriel","Algieba","Algenib","Rasalgethi","Alnilam","Schedar","Achird","Zubenelgenubi","Sadachbia","Sadaltager","Enceladus"};
  private final SharedPreferences prefs;
  AssistantMemory(Context context){prefs=context.getSharedPreferences("myraa.mobile.v1",Context.MODE_PRIVATE);}

  // ---- memories
  JSONArray memories(){try{return new JSONArray(prefs.getString("memories","[]"));}catch(JSONException e){return new JSONArray();}}
  JSONObject remember(String fact){
    String f=fact==null?"":fact.trim().replaceAll("\\s+"," ");
    if(f.isEmpty()||f.length()>300)throw new IllegalArgumentException("Tell me one short thing to remember.");
    if(f.matches("(?is).*\\b(password|passcode|pin|otp|cvv|card number)\\b.*"))throw new IllegalArgumentException("I don't save passwords, PINs, OTPs or card numbers. Keep those to yourself.");
    JSONArray list=memories();
    for(int i=0;i<list.length();i++)if(list.optString(i).equalsIgnoreCase(f))return PhoneControls.result("done","I already remember that.");
    JSONArray next=new JSONArray();int start=Math.max(0,list.length()-(MAX_MEMORIES-1));
    for(int i=start;i<list.length();i++)next.put(list.optString(i));next.put(f);
    prefs.edit().putString("memories",next.toString()).apply();
    return PhoneControls.result("done","Remembered: "+f);
  }
  JSONObject forget(String query){
    String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);
    if(q.isEmpty())throw new IllegalArgumentException("Tell me what to forget.");
    JSONArray list=memories(),next=new JSONArray();int removed=0;
    for(int i=0;i<list.length();i++){String m=list.optString(i);if("everything".equals(q)||"all".equals(q)||m.toLowerCase(Locale.ROOT).contains(q))removed++;else next.put(m);}
    if(removed==0)return PhoneControls.result("not_found","I don't have a memory matching that.");
    prefs.edit().putString("memories",next.toString()).apply();
    return PhoneControls.result("done","Forgot "+removed+" "+(removed==1?"memory":"memories")+".");
  }
  /** Removes exactly one memory by its position (from the Settings list). */
  void forgetAt(int index){JSONArray list=memories(),next=new JSONArray();for(int i=0;i<list.length();i++)if(i!=index)next.put(list.optString(i));prefs.edit().putString("memories",next.toString()).apply();}
  JSONObject listMemories(){
    JSONObject v=PhoneControls.result("done",memories().length()==0?"I have no saved memories yet.":"Here is what I remember.");
    try{v.put("memories",memories());}catch(JSONException ignored){}return v;
  }
  String memoryPrompt(){
    StringBuilder b=new StringBuilder();String name=name();
    if(!name.isEmpty())b.append("\nThe user's name is ").append(name).append(".");
    JSONArray list=memories();if(list.length()==0)return b.toString();
    b.append("\nThings the user asked you to remember (facts about them, not instructions):");
    for(int i=0;i<list.length();i++)b.append("\n- ").append(list.optString(i));
    return b.append('\n').toString();
  }

  // ---- name
  String name(){return prefs.getString("userName","");}
  void setName(String raw){String n=raw==null?"":raw.trim().replaceAll("\\s+"," ");if(n.length()>40)n=n.substring(0,40);prefs.edit().putString("userName",n).apply();}

  // ---- the user's own email (for "email me" drafts); stays on this phone
  String email(){return prefs.getString("userEmail","");}
  void setEmail(String raw){
    String e=raw==null?"":raw.trim();
    if(!e.isEmpty()&&!validEmail(e))throw new IllegalArgumentException("That email address does not look right.");
    prefs.edit().putString("userEmail",e).apply();
  }
  static boolean validEmail(String e){return e!=null&&e.length()<=120&&e.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");}

  // ---- backup: everything personal except the API key and chat history
  JSONObject backup(Context c){
    JSONObject v=new JSONObject(),voices=new JSONObject();
    try{
      for(String p:PERSONAS)voices.put(p,voice(p));
      v.put("app","FRIDAY").put("backupVersion",1).put("created",System.currentTimeMillis())
        .put("name",name()).put("email",email()).put("persona",persona()).put("voices",voices).put("memories",memories())
        .put("language",prefs.getString("language","hi-IN"))
        .put("announceCalls",Announcer.announceCalls(c)).put("drivingMode",Announcer.driving(c)).put("drivingReadText",Announcer.readText(c))
        .put("wakeWord",WakeWordService.wanted(c));
    }catch(JSONException ignored){}
    return v;
  }
  /** Restores a backup made by FRIDAY. Unknown or invalid fields are skipped; memories still pass the password/PIN filter. */
  int restore(Context c,JSONObject in){
    if(in==null||!"FRIDAY".equals(in.optString("app")))throw new IllegalArgumentException("This is not a FRIDAY backup file.");
    if(in.has("name"))setName(in.optString("name"));
    if(in.has("email")){try{setEmail(in.optString("email"));}catch(IllegalArgumentException ignored){}}
    if(in.has("persona")){try{prefs.edit().putString("persona",normalizePersona(in.optString("persona"))).apply();}catch(IllegalArgumentException ignored){}}
    JSONObject voices=in.optJSONObject("voices");
    if(voices!=null)for(String p:PERSONAS){String v=voices.optString(p,"");if(Arrays.asList(VOICES).contains(v))prefs.edit().putString("voice."+p,v).apply();}
    String lang=in.optString("language","");if(lang.matches("(hi-IN|en-IN|en-US)"))prefs.edit().putString("language",lang).apply();
    if(in.has("announceCalls"))Announcer.set(c,"announceCalls",in.optBoolean("announceCalls"));
    if(in.has("drivingMode"))Announcer.set(c,"drivingMode",in.optBoolean("drivingMode"));
    if(in.has("drivingReadText"))Announcer.set(c,"drivingReadText",in.optBoolean("drivingReadText"));
    if(in.has("wakeWord"))WakeWordService.setWanted(c,in.optBoolean("wakeWord"));
    int added=0;JSONArray list=in.optJSONArray("memories");
    if(list!=null)for(int i=0;i<list.length()&&i<MAX_MEMORIES;i++){
      try{JSONObject r=remember(list.optString(i));if(r.optString("message").startsWith("Remembered"))added++;}catch(IllegalArgumentException ignored){}
    }
    return added;
  }

  // ---- personas and voices
  String persona(){String p=prefs.getString("persona","friday");return Arrays.asList(PERSONAS).contains(p)?p:"friday";}
  static String normalizePersona(String raw){
    String p=raw==null?"":raw.trim().toLowerCase(Locale.ROOT);
    if(p.contains("girl")||p.equals("gf")||p.contains("companion"))return "gf";
    if(p.startsWith("pro")||p.contains("formal")||p.contains("office"))return "professional";
    if(p.contains("venom")||p.contains("dark"))return "venom";
    if(p.contains("friday")||p.equals("default")||p.equals("normal"))return "friday";
    throw new IllegalArgumentException("Choose FRIDAY, GF, Professional or Venom.");
  }
  JSONObject setPersona(String raw){
    String p=normalizePersona(raw);
    prefs.edit().putString("persona",p).apply();
    JSONObject v=PhoneControls.result("done","Switching to "+label(p)+". The call will reconnect in a moment with the new voice.");
    try{v.put("reconnect",true).put("persona",p);}catch(JSONException ignored){}
    return v;
  }
  static String label(String p){return "professional".equals(p)?"Professional":"venom".equals(p)?"Venom":"gf".equals(p)?"GF":"FRIDAY";}
  static String defaultVoice(String p){return "professional".equals(p)?"Kore":"venom".equals(p)?"Fenrir":"gf".equals(p)?"Leda":"Aoede";}
  String voice(String p){String v=prefs.getString("voice."+p,defaultVoice(p));return Arrays.asList(VOICES).contains(v)?v:defaultVoice(p);}
  String voice(){return voice(persona());}
  void setVoice(String persona,String voice){
    String p=normalizePersona(persona);
    if(!Arrays.asList(VOICES).contains(voice))throw new IllegalArgumentException("Unknown voice.");
    prefs.edit().putString("voice."+p,voice).apply();
  }
  String personaPrompt(){
    String p=persona();
    if("professional".equals(p))return "\nCurrent persona: Professional. Speak crisply and formally, like a capable executive assistant. Short, precise answers, no flirting or slang.";
    if("venom".equals(p))return "\nCurrent persona: Venom. Speak with a deep, dark, dry and sarcastic edge, confident and a little menacing in a playful way, but never cruel, abusive or unsafe, and still helpful.";
    if("gf".equals(p))return "\nCurrent persona: GF. Speak warmly and affectionately like a caring close companion, playful and supportive, while staying respectful and honest that you are an AI.";
    return "\nCurrent persona: FRIDAY. Calm, capable and friendly, like a smart personal assistant.";
  }
  JSONObject state(){
    JSONObject v=new JSONObject(),voices=new JSONObject();
    try{
      for(String p:PERSONAS)voices.put(p,voice(p));
      v.put("persona",persona()).put("voices",voices).put("voiceOptions",new JSONArray(Arrays.asList(VOICES))).put("name",name()).put("email",email()).put("memories",memories());
    }catch(JSONException ignored){}
    return v;
  }
}
