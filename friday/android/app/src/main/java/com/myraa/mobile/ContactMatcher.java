package com.myraa.mobile;

import java.text.Normalizer;
import java.util.*;

/**
 * Matches a spoken contact name or number against saved contacts, on the phone only.
 * Names are compared word by word: exact, any word order, the start of a word ("Talha" for
 * "Talha Anjum"), the same sound in Roman Urdu spelling ("Anjam" / "Anjum", "Usman" / "Osman")
 * and small spelling slips. Polite words such as bhai or sahab are ignored. A confidence score
 * decides: high is used (risky actions still ask for confirmation), medium is offered to the user
 * as "did you mean", low is not used. Saved contacts are never changed.
 */
final class ContactMatcher {
  private ContactMatcher(){}

  static final double HIGH=0.84, MEDIUM=0.66;

  private static final Set<String> FILLER=new HashSet<>(Arrays.asList(
    "bhai","bhaiya","bhaijan","bhai jan","sahab","sahib","saab","sb","ji","jee","uncle","aunty","auntie","baji","api","appi",
    "mr","mrs","ms","miss","sir","madam","dr","ko","se","ka","ki","ke","wala","wali","wale","contact","number","ka number"));

  /** Command words that are never part of a name when looking through what the user said. */
  private static final Set<String> COMMAND=new HashSet<>(Arrays.asList(
    "whatsapp","whats","app","message","msg","call","calling","video","voice","sms","text","email","mail","karo","kar","kardo","kar do","do","dena",
    "bhejo","bhej","send","to","please","phone","number","open","kholo","contact","friday","hey","wake","up","aur","bhi","abhi","jaldi","usko","isko"));

  /** Lower case, accents and punctuation removed, single spaces. */
  static String norm(String s){
    if(s==null)return "";
    String d=Normalizer.normalize(s,Normalizer.Form.NFD).replaceAll("\\p{M}+","");
    return d.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll(" +"," ");
  }

  static List<String> words(String s){
    List<String> out=new ArrayList<>();
    for(String w:norm(s).split(" "))if(!w.isEmpty()&&!FILLER.contains(w))out.add(w);
    if(out.isEmpty())for(String w:norm(s).split(" "))if(!w.isEmpty())out.add(w);
    return out;
  }

  /** A sound key for Roman Urdu / English spellings of the same name. */
  static String sound(String word){
    String w=norm(word).replace(" ","");
    if(w.isEmpty())return "";
    w=w.replace("ph","f").replace("kh","k").replace("gh","g").replace("sh","s").replace("ch","c").replace("th","t").replace("dh","d").replace("bh","b")
       .replace('q','k').replace('c','k').replace('w','v').replace('y','i');
    char first=w.charAt(0);
    StringBuilder out=new StringBuilder();
    out.append("aeiou".indexOf(first)>=0?'a':first);
    char last=out.charAt(0);
    for(int i=1;i<w.length();i++){
      char c=w.charAt(i);
      if("aeiouh".indexOf(c)>=0)continue;
      if(c==last)continue;
      out.append(c);last=c;
    }
    return out.toString();
  }

  static int distance(String a,String b){
    int[] prev=new int[b.length()+1],cur=new int[b.length()+1];
    for(int j=0;j<=b.length();j++)prev[j]=j;
    for(int i=1;i<=a.length();i++){
      cur[0]=i;
      for(int j=1;j<=b.length();j++)cur[j]=Math.min(Math.min(cur[j-1]+1,prev[j]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
      int[] t=prev;prev=cur;cur=t;
    }
    return prev[b.length()];
  }

  static double similar(String a,String b){
    if(a.isEmpty()||b.isEmpty())return 0;
    return 1.0-(double)distance(a,b)/Math.max(a.length(),b.length());
  }

  /** How well one spoken word fits one saved word, 0 to 1. */
  static double word(String q,String n){
    if(q.equals(n))return 1;
    if(q.length()>=4&&n.startsWith(q))return 0.9;
    if(q.length()==3&&n.startsWith(q))return 0.75;
    String sq=sound(q),sn=sound(n);
    if(sq.length()>=2&&sq.equals(sn))return 0.88;
    double spell=similar(q,n),snd=sq.length()>=2&&sn.length()>=2?similar(sq,sn)*0.9:0;
    // Short words must be (nearly) exact: "Ali" is not "Adil".
    if(Math.min(q.length(),n.length())<=3)spell=spell>=0.99?spell:spell*0.7;
    return Math.max(spell,snd);
  }

  /**
   * Confidence that the spoken name means this saved name. Every spoken word must fit a
   * different saved word; a saved name may have extra words ("Talha" for "Talha Anjum").
   */
  static double score(String spoken,String saved){
    List<String> q=words(spoken),n=words(saved);
    if(q.isEmpty()||n.isEmpty())return 0;
    if(norm(spoken).equals(norm(saved)))return 1;
    List<String> sq=new ArrayList<>(q),sn=new ArrayList<>(n);Collections.sort(sq);Collections.sort(sn);
    if(sq.equals(sn))return 0.98;
    // The recognizer split or joined a name ("Tal ha Anjum", "TalhaAnjum").
    String cq=String.join("",q),cn=String.join("",n);
    if(cq.equals(cn))return 0.95;
    if(q.size()>n.size()+1)return 0;
    boolean[] used=new boolean[n.size()];double total=0;
    for(String w:q){
      double best=0;int at=-1;
      for(int j=0;j<n.size();j++){if(used[j])continue;double s=word(w,n.get(j));if(s>best){best=s;at=j;}}
      if(at>=0)used[at]=true;
      total+=best;
    }
    double avg=total/q.size();
    // A spoken word with nothing like it in the saved name is a strong sign of a different person.
    if(q.size()>n.size())avg*=0.8;
    // Only part of the saved name was said: still good, a little less sure.
    if(q.size()<n.size())avg*=0.97;
    return Math.max(0,Math.min(1,avg));
  }

  /** Digits of a phone number in international form for comparing; Pakistani 03xx, +92 and 0092 forms are the same. */
  static String digits(String raw,String country){
    if(raw==null)return "";
    String d=raw.replaceAll("[^0-9+]","");
    if(d.startsWith("+"))return d.substring(1).replace("+","");
    d=d.replace("+","");
    if(d.startsWith("00"))return d.substring(2);
    if(d.startsWith("0")&&"PK".equalsIgnoreCase(country==null?"":country))return "92"+d.substring(1);
    if(d.startsWith("03")&&d.length()==11)return "92"+d.substring(1);
    return d;
  }

  /** Same number when the last 10 digits agree (country code and leading 0 differ between forms). */
  static boolean sameNumber(String a,String b){
    String x=a==null?"":a.replaceAll("[^0-9]",""),y=b==null?"":b.replaceAll("[^0-9]","");
    if(x.length()<7||y.length()<7)return false;
    int n=Math.min(10,Math.min(x.length(),y.length()));
    return x.substring(x.length()-n).equals(y.substring(y.length()-n));
  }

  /** Index and score of each saved name, best first, at or above the given score. */
  static List<double[]> rank(String spoken,List<String> savedNames,double atLeast){
    List<double[]> out=new ArrayList<>();
    for(int i=0;i<savedNames.size();i++){double s=score(spoken,savedNames.get(i));if(s>=atLeast)out.add(new double[]{i,s});}
    out.sort((a,b)->Double.compare(b[1],a[1]));
    return out;
  }

  /** One- to three-word pieces of what the user said, so a name the model misheard can still be found in the raw transcript. */
  static List<String> pieces(String heard){
    List<String> w=new ArrayList<>(Arrays.asList(norm(heard).split(" ")));w.removeIf(String::isEmpty);
    List<String> out=new ArrayList<>();
    for(int size=3;size>=1;size--)for(int i=0;i+size<=w.size()&&out.size()<60;i++){
      List<String> part=w.subList(i,i+size);boolean filler=true;for(String x:part)if(!FILLER.contains(x)&&!COMMAND.contains(x)&&x.length()>2)filler=false;
      if(COMMAND.contains(part.get(0))||COMMAND.contains(part.get(part.size()-1))||FILLER.contains(part.get(part.size()-1)))filler=true;
      if(!filler)out.add(String.join(" ",part));
    }
    return out;
  }

  /**
   * Several saved contacts fit the name the model passed ("Anas"). Picks one only when it is clearly the one meant:
   * its full name was given, or the user's own words name it (two or more words, "sm anas") far better than any other.
   * Returns -1 when FRIDAY must ask the user instead.
   */
  static int pick(String recipient,String heard,List<String> names){
    if(names.size()==1)return 0;
    int exact=-1,count=0;
    for(int i=0;i<names.size();i++)if(norm(names.get(i)).equals(norm(recipient))||norm(names.get(i)).replace(" ","").equals(norm(recipient).replace(" ",""))){exact=i;count++;}
    if(count==1)return exact;
    // A one-word name ("Anas") fits all of them equally, so only longer names, said or passed, can tell them apart.
    List<String> said=new ArrayList<>();if(words(recipient).size()>1)said.add(recipient);
    for(String p:pieces(heard==null?"":heard))if(p.contains(" "))said.add(p);
    double top=0,second=0;int at=-1;
    for(int i=0;i<names.size();i++){
      double best=0;for(String q:said)best=Math.max(best,score(q,names.get(i)));
      if(best>top){second=top;top=best;at=i;}else if(best>second)second=best;
    }
    return top>=0.9&&top-second>=0.15?at:-1;
  }
}
