package com.myraa.mobile;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Words and names the user often says that speech recognition gets wrong (a show like "Lookism", a friend's
 * name, a brand). They go into FRIDAY's voice instructions so the Live model hears them as these words.
 * Kept only on the phone, one entry per line, like "Lookism - Korean webtoon/anime".
 */
final class MyWords {
  private MyWords(){}
  static final String KEY="myWords";
  static final int MAX_WORDS=60,MAX_LEN=80;

  static List<String> list(Context c){return parse(c.getSharedPreferences(Announcer.PREFS,0).getString(KEY,""),false);}
  static String text(Context c){return String.join("\n",list(c));}

  /** Replaces the whole list with the user's text from Settings (one word or name per line, or commas). */
  static void set(Context c,String raw){save(c,parse(raw,true));}

  /** Adds one word (with an optional short meaning); returns false when it was already there. */
  static boolean add(Context c,String raw){
    List<String> words=list(c);List<String> one=parse(raw,false);
    if(one.isEmpty())throw new IllegalArgumentException("Say the word or name to add.");
    boolean added=false;
    for(String w:one){int at=indexOf(words,w);if(at>=0)words.set(at,w.length()>words.get(at).length()?w:words.get(at));else{words.add(w);added=true;}}
    while(words.size()>MAX_WORDS)words.remove(0);
    save(c,words);return added;
  }
  static boolean remove(Context c,String raw){
    List<String> words=list(c);int at=indexOf(words,raw==null?"":raw.trim());
    if(at<0)return false;words.remove(at);save(c,words);return true;
  }

  /** The part of the voice instructions that lists the words; empty when there are none. */
  static String prompt(Context c){return prompt(list(c));}
  static String prompt(List<String> words){
    String base="\nMISHEARD WORDS: when the user corrects a word you heard wrong (\"maine Lookism kaha tha\", \"Africa nahi, Lookism\") or asks you to learn a word or name, call mobile_action my_words with command add and text the correct word (and a few words on what it is), then carry on with what they asked.";
    if(words.isEmpty())return base;
    return base+"\nTHE USER'S OWN WORDS AND NAMES (said in a Pakistani Roman Urdu accent; speech recognition often turns them into other, more common words). When you hear something that sounds similar, it is most likely one of these, so use the spelling below: "+String.join("; ",words)+".";
  }

  /** One entry per line; with commas true (the Settings box) commas and semicolons also separate entries. */
  static List<String> parse(String raw,boolean commas){
    List<String> out=new ArrayList<>();if(raw==null)return out;
    for(String part:raw.split(commas?"[\\n,;]+":"\\n+")){
      String w=part.replaceAll("[\\p{Cntrl}\"<>{}]"," ").replaceAll("\\s+"," ").trim();
      if(w.isEmpty())continue;if(w.length()>MAX_LEN)w=w.substring(0,MAX_LEN).trim();
      if(indexOf(out,w)<0)out.add(w);if(out.size()>=MAX_WORDS)break;
    }
    return out;
  }
  /** Same word: the part before any " - " meaning, compared without case. */
  private static int indexOf(List<String> words,String w){
    String k=head(w);for(int i=0;i<words.size();i++)if(head(words.get(i)).equals(k))return i;return -1;
  }
  private static String head(String w){int d=w.indexOf(" - ");return (d>0?w.substring(0,d):w).trim().toLowerCase(Locale.ROOT);}
  private static void save(Context c,List<String> words){c.getSharedPreferences(Announcer.PREFS,0).edit().putString(KEY,String.join("\n",words)).apply();}
}
