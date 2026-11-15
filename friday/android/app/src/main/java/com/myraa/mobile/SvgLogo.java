package com.myraa.mobile;

import java.util.Locale;

/**
 * The logo FRIDAY designs herself as vector art (SVG): checked so it is a real, self-contained picture with
 * nothing that could run or load from the internet, then turned into a data: URI for the page. Plain Java.
 */
final class SvgLogo {
  private SvgLogo(){}
  static final int MAX=20000;

  /** The cleaned SVG, or null when it is missing, too big, broken or unsafe (then another logo is used). */
  static String safe(String svg){
    if(svg==null)return null;
    String s=svg.trim();
    if(s.startsWith("```")){int nl=s.indexOf('\n');s=nl<0?"":s.substring(nl+1);int end=s.lastIndexOf("```");if(end>=0)s=s.substring(0,end).trim();}
    int a=s.indexOf("<svg");if(a<0)return null;s=s.substring(a);
    int b=s.lastIndexOf("</svg>");if(b<0)return null;s=s.substring(0,b+6);
    if(s.length()<80||s.length()>MAX)return null;
    String low=s.toLowerCase(Locale.ROOT);
    if(low.contains("<script")||low.contains("<foreignobject")||low.contains("javascript:")||low.contains("<iframe")||low.contains("<image")||low.contains("<use")&&low.contains("href=\"http")
      ||low.matches("(?s).*\\son[a-z]+\\s*=.*")||low.contains("href=\"http")||low.contains("href='http")||low.contains("url(http")||low.contains("@import"))return null;
    if(!low.contains("viewbox"))return null;
    if(!low.contains("xmlns=")) s="<svg xmlns=\"http://www.w3.org/2000/svg\""+s.substring(4);
    // Must actually draw something.
    if(!(low.contains("<path")||low.contains("<circle")||low.contains("<rect")||low.contains("<polygon")||low.contains("<ellipse")||low.contains("<text")))return null;
    return s;
  }

  /** data:image/svg+xml URI usable in <img src> and the favicon. */
  static String uri(String svg){
    StringBuilder b=new StringBuilder("data:image/svg+xml,");
    for(char ch:svg.toCharArray()){
      if(ch=='#')b.append("%23");else if(ch=='<')b.append("%3C");else if(ch=='>')b.append("%3E");else if(ch=='"')b.append("%22");
      else if(ch=='%')b.append("%25");else if(ch=='\n'||ch=='\r'||ch=='\t')b.append(' ');else b.append(ch);
    }
    return b.toString();
  }
}
