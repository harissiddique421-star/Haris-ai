package com.myraa.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Deep research for a written report: one Gemini request with Google Search grounding, asked to
 * search widely (several angles, recent and official sources) and to write a structured,
 * professional report. The web pages Google actually used come back in the grounding metadata and
 * are listed as the report's Sources, so the sources are real, not written by the model.
 * Runs on a background thread; nothing is sent anywhere except the topic to Gemini.
 */
final class Research {
  private Research(){}
  static final int MAX_TOPIC=600;

  static final class Report {
    final String title,markdown,summary;final List<String[]> sources;final int searches;final long ms;
    Report(String title,String markdown,String summary,List<String[]> sources,int searches,long ms){this.title=title;this.markdown=markdown;this.summary=summary;this.sources=sources;this.searches=searches;this.ms=ms;}
  }

  static String instruction(String language,String depth){
    String today=new SimpleDateFormat("d MMMM yyyy",Locale.ENGLISH).format(new Date());
    boolean deep=!"quick".equals(depth);
    return "You are a senior research analyst writing a professional report. Today is "+today+".\n"
      +"RESEARCH: use Google Search thoroughly before writing. Run "+(deep?"at least 6":"3 or more")+" different searches that cover: the basic facts and definitions; the latest news and data (this year); official or primary sources (government, company, standards bodies, research papers); numbers and statistics; expert views; and opposing or critical views. Prefer recent and authoritative sources. If sources disagree, say so and show both sides. Never invent facts, numbers, quotes or sources; if something could not be found, say it is not available.\n"
      +"WRITE the report in Markdown with exactly this structure:\n"
      +"# <a clear, specific title>\n"
      +"## Executive summary\n(4 to 6 bullet points with the most important findings and the bottom line)\n"
      +"## Background\n"
      +"## Key findings\n(numbered findings; each with concrete figures, dates and the source named in parentheses, for example (Reuters, March 2026))\n"
      +"## Analysis\n(what the findings mean, comparisons, trends; use a Markdown table with | columns when comparing options, prices or numbers)\n"
      +"## Risks and open questions\n"
      +"## Recommendations\n(practical next steps for the reader, numbered)\n"
      +"## Conclusion\n"
      +"Write in clear, formal, professional "+(language==null||language.isEmpty()?"English":language)+". Use **bold** only for key terms. Give the date for anything that changes over time (prices, versions, laws, rankings). "
      +(deep?"Aim for a thorough report of about 1200 to 1800 words.":"Keep it to about 500 to 800 words.")
      +" Do not add a Sources section: the app adds the real search sources itself. Do not add any text before the title.";
  }

  /** Runs the research; throws GeminiAccess.Failure for API errors and IOException for network problems. */
  static Report run(String key,String model,String topic,String language,String depth) throws Exception {
    long started=System.currentTimeMillis();
    JSONObject request=new JSONObject()
      .put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",instruction(language,depth)))))
      .put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text","Research topic: "+topic)))))
      .put("tools",new JSONArray().put(new JSONObject().put("google_search",new JSONObject())))
      .put("generationConfig",new JSONObject().put("temperature",0.3).put("maxOutputTokens",8192));
    JSONObject result=null;IOException last=null;
    for(int attempt=0;attempt<2&&result==null;attempt++){
      try{result=post(key,model,request);}catch(java.net.SocketTimeoutException e){last=e;}
    }
    if(result==null)throw last!=null?last:new IOException("no answer");
    JSONObject cand=result.getJSONArray("candidates").getJSONObject(0);
    JSONArray parts=cand.optJSONObject("content")==null?new JSONArray():cand.getJSONObject("content").optJSONArray("parts");
    StringBuilder b=new StringBuilder();
    if(parts!=null)for(int i=0;i<parts.length();i++)if(!parts.getJSONObject(i).optBoolean("thought",false))b.append(parts.getJSONObject(i).optString("text",""));
    String md=b.toString().trim();
    if(md.length()<200)throw new IOException("The research answer was empty. Try again.");
    // The pages Google Search actually used.
    Map<String,String[]> seen=new LinkedHashMap<>();int searches=0;
    JSONObject meta=cand.optJSONObject("groundingMetadata");
    if(meta!=null){
      JSONArray q=meta.optJSONArray("webSearchQueries");searches=q==null?0:q.length();
      JSONArray chunks=meta.optJSONArray("groundingChunks");
      if(chunks!=null)for(int i=0;i<chunks.length()&&seen.size()<30;i++){
        JSONObject web=chunks.optJSONObject(i)==null?null:chunks.optJSONObject(i).optJSONObject("web");
        if(web==null)continue;String uri=web.optString("uri",""),title=web.optString("title","").trim();
        if(!uri.startsWith("https://"))continue;
        String k=title.isEmpty()?uri:title.toLowerCase(Locale.ROOT);
        if(!seen.containsKey(k))seen.put(k,new String[]{title.isEmpty()?"Web source":title,uri});
      }
    }
    String title=titleOf(md,topic);
    String body=stripTitle(md);
    StringBuilder full=new StringBuilder(body);
    List<String[]> sources=new ArrayList<>(seen.values());
    full.append("\n\n## Sources\n");
    if(sources.isEmpty())full.append("Google Search did not report the pages it used for this answer. Check important facts before relying on them.\n");
    else{int n=1;for(String[] s:sources)full.append(n++).append(". ").append(s[0]).append(" - ").append(s[1]).append('\n');}
    full.append("\nResearched with Google Search on ").append(new SimpleDateFormat("d MMMM yyyy, HH:mm",Locale.ENGLISH).format(new Date()))
      .append(searches>0?" ("+searches+" searches)":"").append(". Prepared by FRIDAY; check important facts before acting on them.\n");
    return new Report(title,full.toString(),summaryOf(body),sources,searches,System.currentTimeMillis()-started);
  }

  static String titleOf(String md,String topic){
    for(String line:md.split("\n")){String t=line.trim();if(t.startsWith("# "))return clip(t.substring(2).replace("**","").trim(),80);if(!t.isEmpty())break;}
    return clip(topic.trim(),80);
  }
  static String stripTitle(String md){
    String t=md.replace("\r\n","\n").trim();
    if(t.startsWith("# ")){int nl=t.indexOf('\n');return nl<0?"":t.substring(nl+1).trim();}
    return t;
  }
  /** The executive summary's bullets as one short paragraph, for FRIDAY to say. */
  static String summaryOf(String body){
    int at=body.toLowerCase(Locale.ROOT).indexOf("## executive summary");
    String part=at<0?body:body.substring(at+20);
    int next=part.indexOf("\n## ");if(next>0)part=part.substring(0,next);
    StringBuilder b=new StringBuilder();
    for(String line:part.split("\n")){String t=line.trim().replaceFirst("^([-*•]|\\d+[.)])\\s*","").replace("**","");if(t.isEmpty()||t.startsWith("#"))continue;if(b.length()>0)b.append(' ');b.append(t);if(b.length()>700)break;}
    return clip(b.toString(),700);
  }
  private static String clip(String s,int n){return s.length()<=n?s:s.substring(0,n).trim();}

  private static JSONObject post(String key,String model,JSONObject request) throws Exception {
    HttpsURLConnection c=(HttpsURLConnection)new URL("https://generativelanguage.googleapis.com/v1beta/models/"+model+":generateContent").openConnection();
    try{
      c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setConnectTimeout(15000);c.setReadTimeout(150000);c.setDoOutput(true);
      c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("x-goog-api-key",key);
      try(OutputStream out=c.getOutputStream()){out.write(request.toString().getBytes(StandardCharsets.UTF_8));}
      int status=c.getResponseCode();
      if(status/100!=2){String body=c.getErrorStream()==null?"":new String(read(c.getErrorStream(),65536),StandardCharsets.UTF_8);throw GeminiAccess.failure(status,body);}
      return new JSONObject(new String(read(c.getInputStream(),2*1024*1024),StandardCharsets.UTF_8));
    }finally{c.disconnect();}
  }
  private static byte[] read(InputStream in,int max) throws IOException {
    try(InputStream i=in){ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=i.read(b))>0){o.write(b,0,n);if(o.size()>max)throw new IOException("too large");}return o.toByteArray();}
  }
}
