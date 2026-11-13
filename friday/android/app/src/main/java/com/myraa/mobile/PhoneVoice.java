package com.myraa.mobile;

import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FRIDAY's phone voice (the Android speech engine). The voice itself stays exactly as it is (the owner asked
 * for that); it is made clearer around it: the assistant audio stream, a natural pace, text cleaned of things
 * that sound bad when read out (markdown, links, emoji), and long replies spoken sentence by sentence.
 */
final class PhoneVoice {
  static final float RATE=0.97f,PITCH=1.0f;
  private PhoneVoice(){}

  /** Keeps the voice the phone already uses; only the audio stream and the pace are set. */
  static void tune(TextToSpeech tts){
    try{
      tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
      tts.setSpeechRate(RATE);tts.setPitch(PITCH);
    }catch(RuntimeException ignored){}
  }

  private static final Pattern LINK=Pattern.compile("https?://\\S+");
  private static final Pattern SENTENCE=Pattern.compile("[^.!?।؟\\n]+[.!?।؟]*");

  /** Text as it should be read out: no markdown marks, links named "link", no emoji, single spaces. */
  static String clean(String text){
    if(text==null)return "";
    String t=LINK.matcher(text).replaceAll("link");
    t=t.replaceAll("[*_#`~>|]+"," ").replaceAll("\\[([^\\]]*)]\\([^)]*\\)","$1");
    t=t.replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]","");
    return t.replaceAll("[ \\t]+"," ").replaceAll(" ([.,!?])","$1").replaceAll(" *\\n+ *","\n").trim();
  }

  /** The cleaned text as sentences (short ones joined), each under max characters. */
  static List<String> sentences(String text,int max){
    List<String> out=new ArrayList<>();
    String t=clean(text);if(t.isEmpty())return out;
    int limit=Math.max(50,Math.min(max-1,400));
    StringBuilder cur=new StringBuilder();
    Matcher m=SENTENCE.matcher(t);
    while(m.find()){
      String s=m.group().trim();if(s.isEmpty())continue;
      while(s.length()>limit){int cut=s.lastIndexOf(' ',limit-1);if(cut<limit/3)cut=limit;add(out,cur);out.add(s.substring(0,cut).trim());s=s.substring(cut).trim();}
      if(cur.length()>0&&cur.length()+1+s.length()>Math.min(limit,160))add(out,cur);
      if(cur.length()>0)cur.append(' ');
      cur.append(s);
    }
    add(out,cur);
    return out;
  }
  private static void add(List<String> out,StringBuilder cur){if(cur.length()>0){out.add(cur.toString().trim());cur.setLength(0);}}
}
