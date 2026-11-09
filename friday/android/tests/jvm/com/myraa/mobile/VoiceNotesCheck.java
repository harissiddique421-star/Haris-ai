package com.myraa.mobile;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/** WhatsApp voice notes: notification detection, parsing the model's answer, private store, duplicates, delete. */
public final class VoiceNotesCheck {
  private static int fails=0,passes=0;
  private static void check(boolean ok,String what){if(ok)passes++;else{fails++;System.out.println("FAIL: "+what);}}

  public static void main(String[] a) throws Exception {
    // detection from notification text
    check(VoiceNotes.voiceNoteSeconds("🎤 Voice message (0:12)")==12,"mic + length");
    check(VoiceNotes.voiceNoteSeconds("🎤 Voice message (1:05)")==65,"minutes");
    check(VoiceNotes.voiceNoteSeconds("Voice message")==0,"words, no length");
    check(VoiceNotes.voiceNoteSeconds("🎤 0:07")==7,"mic only");
    check(VoiceNotes.voiceNoteSeconds("Kal voice message bhejna")==-1,"chat text mentioning voice message is not one");
    check(VoiceNotes.voiceNoteSeconds("Hello")==-1,"plain text");
    check(VoiceNotes.voiceNoteSeconds(null)==-1,"null");
    check(VoiceNotes.whatsapp("com.whatsapp")&&VoiceNotes.whatsapp("com.whatsapp.w4b")&&!VoiceNotes.whatsapp("org.telegram.messenger"),"packages");

    // notices: memory only, duplicates of one notification merged, newest first
    long now=System.currentTimeMillis();
    VoiceNotes.noticed("Ali",12,now-5000);VoiceNotes.noticed("Ali",12,now-4000);VoiceNotes.noticed("Sara",30,now-1000);
    JSONArray n=VoiceNotes.noticed();
    check(n.length()==2,"same notification twice counted once ("+n.length()+")");
    check("Sara".equals(n.getJSONObject(0).getString("sender")),"newest first");
    check("Sara".equals(VoiceNotes.recentSender(now)),"recent sender");
    check("".equals(VoiceNotes.recentSender(now+31*60_000L)),"no sender guess after 30 minutes");

    // parsing the model's answer
    String model="```json\n{\"language\":\"Roman Urdu\",\"transcript\":\"Kal 5 baje meeting hai, file le aana.\",\"transcript_roman\":\"Kal 5 baje meeting hai, file le aana.\","
      +"\"summary\":\"Ali keh raha hai ke kal 5 baje meeting hai.\",\"details\":\"\",\"questions\":[],\"tasks\":[\"file le jana\"],\"times\":[\"kal 5 baje\"],"
      +"\"urgent\":false,\"suggested_reply\":\"Theek hai, aa jaunga.\",\"unclear\":false,\"confidence\":1.7,\"speakers\":1}\n```";
    String hash=VoiceNotes.sha256("audio-1".getBytes("UTF-8"));
    check(hash.length()==64&&hash.equals(VoiceNotes.sha256("audio-1".getBytes("UTF-8"))),"sha256 stable");
    JSONObject e=VoiceNotes.entry(model,"Ali",true,hash,12,now);
    check(e.getString("summary").startsWith("Ali keh raha"),"summary");
    check(e.getJSONArray("tasks").length()==1&&e.getJSONArray("times").getString(0).equals("kal 5 baje"),"tasks and times");
    check(e.getDouble("confidence")==1.0,"confidence clamped");
    check(e.getBoolean("senderGuess"),"sender marked as a guess");
    boolean threw=false;try{VoiceNotes.entry("Sorry, I can't.","",false,hash,0,now);}catch(org.json.JSONException x){threw=true;}
    check(threw,"non-JSON answer is refused, not stored");
    check(VoiceNotes.instruction("Ali").contains("Never invent words"),"instruction forbids guessing");

    // private store
    File dir=Files.createTempDirectory("vn").toFile();Context c=new Context(dir);
    check(VoiceNotes.all(c).length()==0&&VoiceNotes.find(c,"")==null,"empty store");
    VoiceNotes.save(c,e);VoiceNotes.save(c,e);
    check(VoiceNotes.all(c).length()==1,"same audio stored once");
    check(VoiceNotes.byHash(c,hash)!=null,"found by hash (not sent again)");
    String[] names={"Bilal","Sara Khan","Ammi","Usman","Hina"};
    for(int i=0;i<25;i++){JSONObject x=new JSONObject(e.toString());x.put("hash","h"+i).put("sender",names[i%5]).put("at",now+i);VoiceNotes.save(c,x);}
    check(VoiceNotes.all(c).length()==VoiceNotes.KEEP,"keeps at most "+VoiceNotes.KEEP);
    check(VoiceNotes.find(c,"").getLong("at")==now+24,"newest without a name");
    JSONObject sara=VoiceNotes.find(c,"sara");
    check(sara!=null&&"Sara Khan".equals(sara.optString("sender"))&&sara.getLong("at")==now+21,"by spoken name, newest of that sender");
    check(VoiceNotes.find(c,"Zubair")==null,"unknown name gives nothing, not a guess");
    check(VoiceNotes.clear(c)==VoiceNotes.KEEP&&VoiceNotes.all(c).length()==0&&!new File(dir,"voice-notes.json").exists(),"delete removes the file");

    System.out.println("VoiceNotesCheck: "+passes+" passed, "+fails+" failed");
    if(fails>0)System.exit(1);
  }
}
