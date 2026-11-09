package com.myraa.mobile;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * WhatsApp voice notes, within what Android and WhatsApp allow.
 *
 * WhatsApp keeps voice notes in its own storage and end-to-end encrypts them in transit, so FRIDAY never reads
 * them by itself. Two honest paths:
 *  - Notification: with notification access FRIDAY sees "🎤 Voice message (0:12)" from a sender. That is only
 *    a notice that a voice note came: FRIDAY knows who and how long, never what was said.
 *  - Share: the user shares the voice note to FRIDAY from WhatsApp (long-press, Share, FRIDAY). Only then FRIDAY
 *    has the audio, sends it once to Gemini with the user's own key for a transcript and summary, and keeps the
 *    text result. The audio itself is not stored.
 *
 * Results live in the app's private storage (at most 20, newest first) and can be deleted from Settings or by
 * voice. The same audio shared twice is recognised by its SHA-256 and not sent again.
 */
final class VoiceNotes {
  static final int MAX_BYTES=14*1024*1024,KEEP=20;
  private static final String FILE="voice-notes.json";
  private static final ArrayDeque<JSONObject> noticed=new ArrayDeque<>();

  private VoiceNotes(){}

  static boolean whatsapp(String pkg){return "com.whatsapp".equals(pkg)||"com.whatsapp.w4b".equals(pkg);}

  /** Seconds when the notification text is a WhatsApp voice note (0 when the length is not shown), -1 when it is not one. */
  static int voiceNoteSeconds(String text){
    if(text==null)return -1;
    String t=text.trim();
    boolean mic=t.contains("\uD83C\uDFA4");
    boolean words=t.toLowerCase(Locale.ROOT).matches("^(🎤\\s*)?(voice message|voice note)\\b.*");
    if(!mic&&!words)return -1;
    Matcher m=Pattern.compile("\\(?\\b(\\d{1,2}):(\\d{2})\\b\\)?").matcher(t);
    return m.find()?Integer.parseInt(m.group(1))*60+Integer.parseInt(m.group(2)):0;
  }

  /** A voice note notification (memory only, last 2 hours, at most 10): who sent it and how long it is. */
  static synchronized void noticed(String sender,int seconds,long at){
    noticed.removeIf(n->at-n.optLong("at")>2*3600_000L||n.optString("sender").equals(sender)&&Math.abs(at-n.optLong("at"))<3000);
    try{noticed.addLast(new JSONObject().put("sender",sender).put("seconds",seconds).put("at",at));}catch(JSONException ignored){}
    while(noticed.size()>10)noticed.removeFirst();
    ControlLog.i("voice note: notification"+(seconds>0?" ("+seconds+" s)":"")+", not shared to FRIDAY yet");
  }
  static synchronized JSONArray noticed(){
    JSONArray out=new JSONArray();Iterator<JSONObject> it=noticed.descendingIterator();
    long now=System.currentTimeMillis();while(it.hasNext()){JSONObject n=it.next();if(now-n.optLong("at")<=2*3600_000L)out.put(n);}
    return out;
  }
  /** Sender of the newest voice note notification in the last 30 minutes (a guess for a shared note), or "". */
  static synchronized String recentSender(long now){
    Iterator<JSONObject> it=noticed.descendingIterator();
    while(it.hasNext()){JSONObject n=it.next();if(now-n.optLong("at")<=30*60_000L)return n.optString("sender");}
    return "";
  }

  static String sha256(byte[] data){
    try{byte[] d=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format(Locale.ROOT,"%02x",x));return b.toString();}
    catch(Exception e){return Integer.toHexString(java.util.Arrays.hashCode(data));}
  }

  /** The instruction for one voice note: a JSON answer, no guessing. */
  static String instruction(String sender){
    return "You transcribe and explain one WhatsApp voice note for its receiver. The speech may be Urdu, Hindi, English, Roman Urdu or Hinglish, mixed. "
      +"Reply with JSON only: {\"language\":string,\"transcript\":string (the exact words with punctuation, in the script spoken: Urdu in Urdu script, Hindi in Devanagari, Roman Urdu/Hinglish/English in Latin; mark words you cannot hear as [unclear]; if several people speak, prefix lines with Speaker 1:, Speaker 2:),"
      +"\"transcript_roman\":string (the same words in Roman Urdu/English letters),\"summary\":string (one short line in Roman Urdu, third person, e.g. Ali keh raha hai ke...),\"details\":string,"
      +"\"questions\":[string],\"tasks\":[string],\"times\":[string],\"urgent\":boolean,\"suggested_reply\":string (short, Roman Urdu, friendly),\"unclear\":boolean,\"confidence\":number 0-1,\"speakers\":number}. "
      +"Never invent words that are not in the audio. If there is no speech, say so in summary and leave transcript empty."
      +(sender==null||sender.isEmpty()?"":" The sender is probably "+sender+".");
  }

  /** Parses the model's text into a stored entry, or throws when it is not the JSON asked for. */
  static JSONObject entry(String modelText,String sender,boolean senderGuess,String hash,int seconds,long at) throws JSONException {
    String t=modelText==null?"":modelText.trim();
    if(t.startsWith("```")){t=t.replaceFirst("^```(json)?\\s*","");int end=t.lastIndexOf("```");if(end>=0)t=t.substring(0,end);}
    int a=t.indexOf('{'),b=t.lastIndexOf('}');if(a<0||b<a)throw new JSONException("no JSON");
    JSONObject r=new JSONObject(t.substring(a,b+1));
    JSONObject e=new JSONObject();
    e.put("id",hash.substring(0,Math.min(12,hash.length()))).put("hash",hash).put("at",at).put("sender",sender==null?"":sender).put("senderGuess",senderGuess).put("seconds",seconds);
    for(String k:new String[]{"language","transcript","transcript_roman","summary","details","suggested_reply"})e.put(k,clip(r.optString(k,""),k.startsWith("transcript")?6000:1500));
    for(String k:new String[]{"questions","tasks","times"}){JSONArray in=r.optJSONArray(k),out=new JSONArray();if(in!=null)for(int i=0;i<in.length()&&i<10;i++)out.put(clip(in.optString(i),300));e.put(k,out);}
    e.put("urgent",r.optBoolean("urgent")).put("unclear",r.optBoolean("unclear")).put("speakers",r.optInt("speakers",1));
    double c=r.optDouble("confidence",-1);e.put("confidence",Double.isNaN(c)?-1:Math.max(-1,Math.min(1,c)));
    return e;
  }
  private static String clip(String s,int max){return s==null?"":s.length()>max?s.substring(0,max)+"…":s;}

  // ---- private store (app storage only)
  static synchronized JSONArray all(Context c){
    File f=new File(c.getFilesDir(),FILE);
    try{if(f.isFile()&&f.length()<2_000_000)return new JSONArray(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}catch(Exception ignored){}
    return new JSONArray();
  }
  static synchronized JSONObject byHash(Context c,String hash){JSONArray a=all(c);for(int i=0;i<a.length();i++)if(hash.equals(a.optJSONObject(i).optString("hash")))return a.optJSONObject(i);return null;}
  static synchronized void save(Context c,JSONObject e){
    JSONArray old=all(c),out=new JSONArray().put(e);
    for(int i=0;i<old.length()&&out.length()<KEEP;i++)if(!e.optString("hash").equals(old.optJSONObject(i).optString("hash")))out.put(old.optJSONObject(i));
    write(c,out);
  }
  static synchronized int clear(Context c){int n=all(c).length();new File(c.getFilesDir(),FILE).delete();ControlLog.i("voice note: "+n+" saved results deleted");return n;}
  private static void write(Context c,JSONArray a){
    File f=new File(c.getFilesDir(),FILE),tmp=new File(c.getFilesDir(),FILE+".tmp");
    try(FileOutputStream out=new FileOutputStream(tmp)){out.write(a.toString().getBytes(StandardCharsets.UTF_8));}catch(Exception e){return;}
    if(!tmp.renameTo(f)){f.delete();tmp.renameTo(f);}
  }

  /** The best stored voice note for a spoken sender name (newest when no name), or null. */
  static JSONObject find(Context c,String who){
    JSONArray a=all(c);if(a.length()==0)return null;
    if(who==null||who.trim().isEmpty())return a.optJSONObject(0);
    JSONObject best=null;double bestScore=0;
    for(int i=0;i<a.length();i++){JSONObject e=a.optJSONObject(i);double s=ContactMatcher.score(who,e.optString("sender"));if(s>bestScore){bestScore=s;best=e;}}
    return bestScore>=ContactMatcher.HIGH?best:null;
  }
}
