package com.myraa.mobile;

import java.util.*;

/**
 * Picks which YouTube result to play for a song, the way a person would: the real artist's
 * upload (official video or audio, the artist's own channel, VEVO or the "- Topic" channel)
 * before covers, karaoke, lyrics, slowed/reverb edits, reactions or tutorials, unless the user
 * asked for one of those. YouTube's own order breaks ties, so a plain query still gets the top
 * result. Plain Java (no Android) so the JVM check in CI can test it.
 */
final class SongPick {
  private SongPick(){}
  private static final String[] NOT_ORIGINAL={"cover","karaoke","lyrics","lyric video","lyrical","slowed","reverb","sped up","speed up","8d","remix","mashup","reaction","reacts","tutorial","lesson","how to play","instrumental","ringtone","whatsapp status","#shorts","female version","male version","nightcore","bass boosted","lofi","lo-fi","1 hour","10 hours","dance video","choreography","parody"};
  private static final String[] OFFICIAL={"official video","official music video","official audio","official song","official lyric video","(official)","[official]","full video","full song","video song","audio song"};
  private static final String[] OFFICIAL_CHANNEL={"vevo","- topic","t-series","tseries","coke studio","sony music","zee music","saregama","tips official","speed records","yrf","eros now music","velo sound station","times music","geet mp3","white hill music","desi melodies","universal music","atlantic records","columbia records","republic records","interscope","warner records","emi pakistan","patari","nescafe basement","lyca music"};

  /** Index of the result to play, 0 when nothing better than YouTube's top result is found. */
  static int best(String query,List<String> titles,List<String> channels){
    if(titles==null||titles.isEmpty())return 0;
    String q=" "+norm(query)+" ";
    int bestIndex=0,bestScore=Integer.MIN_VALUE;
    for(int i=0;i<titles.size();i++){
      String t=" "+norm(titles.get(i))+" ",c=channels!=null&&i<channels.size()?norm(channels.get(i)):"";
      int s=-i*2;                                   // YouTube's ranking, gently
      for(String w:NOT_ORIGINAL)if(t.contains(w)&&!q.contains(w)){s-=30;break;}
      for(String w:NOT_ORIGINAL)if(q.contains(w)&&t.contains(w)){s+=30;break;}  // the user asked for a cover, lyrics, remix...
      for(String w:OFFICIAL)if(t.contains(w)){s+=12;break;}
      if(c.endsWith(" - topic")||c.endsWith("vevo"))s+=14;
      else for(String w:OFFICIAL_CHANNEL)if(c.contains(w)){s+=10;break;}
      if(!c.isEmpty()&&c.length()>=3&&q.contains(" "+c.replace(" - topic","").replace("vevo","").trim()+" "))s+=10; // the artist the user named
      int hit=0,words=0;for(String w:q.trim().split(" ")){if(w.length()<2||FILLER.contains(w))continue;words++;if(t.contains(w)||c.contains(w))hit++;}
      if(words>0)s+=8*hit/words;                    // the title really is the song asked for
      if(s>bestScore){bestScore=s;bestIndex=i;}
    }
    return bestIndex;
  }
  private static final Set<String> FILLER=new HashSet<>(Arrays.asList("song","songs","gaana","gana","video","by","ka","ki","ke","the","a","of","play","official","new"));
  static String norm(String s){return s==null?"":s.toLowerCase(Locale.ROOT).replaceAll("[|\"“”'’]"," ").replaceAll("\\s+"," ").trim();}
}
