package com.myraa.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A second text provider beside Gemini: OpenRouter or any OpenAI-compatible API (same /chat/completions shape).
 * Only text chat uses it. Live voice stays on Gemini. The user picks how it is used:
 *  - off: saved but never used,
 *  - backup: only when Gemini fails (quota, outage, key problem), and the reply says so,
 *  - primary: text chat goes there instead of Gemini.
 * Nothing switches provider silently: each reply that came from the second provider is labelled.
 * The key is stored encrypted on the phone like the Gemini key; it is never logged or shown again.
 * Usage (requests and tokens per provider per day, last 30 days) is counted from the providers' own replies.
 */
final class TextProviders {
  static final String OPENROUTER="https://openrouter.ai/api/v1";
  /** OpenRouter's own router: it picks a suitable model for each message, so the user only adds a key. */
  static final String AUTO="openrouter/auto";
  /** Typed as the model: only OpenRouter's free models, chosen by FRIDAY. */
  static final String FREE="free";
  private TextProviders(){}

  /** OpenRouter with no model typed (or "auto") uses the Auto router; any other provider needs its model. */
  static String effectiveModel(String kind,String model){
    String m=model==null?"":model.trim();
    if("openrouter".equals(kind)&&(m.isEmpty()||"auto".equalsIgnoreCase(m)))return AUTO;
    if("openrouter".equals(kind)&&FREE.equalsIgnoreCase(m))return FREE;
    return m;
  }
  /** True when the saved model is enough to send a request (OpenRouter needs none). */
  static boolean modelReady(String kind,String model){return "openrouter".equals(kind)?model==null||model.trim().isEmpty()||validModel(model.trim()):validModel(model);}

  /** Remembers the free model that worked last, so the next message does not look it up again. */
  interface Cache { String get(); void put(String model); }
  /** The answer and the model that actually wrote it. */
  static final class Answer { final JSONObject reply;final String model; Answer(JSONObject r,String m){reply=r;model=m;} }

  /**
   * One chat request in which FRIDAY chooses the model when the user did not: OpenRouter Auto first;
   * if that cannot answer (no credit on the account, limit reached, unavailable) or the user typed
   * "free", OpenRouter's free models, best first, until one answers. A typed model is used as it is.
   */
  static Answer chatSmart(String base,String kind,String key,JSONObject gemini,String model,int maxTokens,Opened opened,Cache cache) throws Exception {
    String m=effectiveModel(kind,model);
    GeminiAccess.Failure first=null;
    if(!FREE.equals(m)){
      try{JSONObject r=chat(base,kind,key,fromGemini(gemini,m,maxTokens),opened);return new Answer(r,r.optString("model",m));}
      catch(GeminiAccess.Failure f){
        boolean retry=AUTO.equals(m)&&("ALT_NO_CREDIT".equals(f.code)||"ALT_QUOTA".equals(f.code)||"ALT_UNAVAILABLE".equals(f.code)||"ALT_MODEL_UNAVAILABLE".equals(f.code));
        if(!retry)throw f;first=f;
      }
    }
    java.util.List<String> tries=new java.util.ArrayList<>();
    String known=cache==null?null:cache.get();if(known!=null&&validModel(known))tries.add(known);
    for(String f:freeModels())if(!tries.contains(f))tries.add(f);
    GeminiAccess.Failure last=first;
    for(int i=0;i<tries.size()&&i<4;i++){
      String f=tries.get(i);
      try{JSONObject r=chat(base,kind,key,fromGemini(gemini,f,maxTokens),opened);if(cache!=null)cache.put(f);return new Answer(r,r.optString("model",f));}
      catch(GeminiAccess.Failure e){
        if("ALT_KEY_REJECTED".equals(e.code))throw e;
        last=e;
      }
    }
    if(last!=null)throw last;
    throw new GeminiAccess.Failure("ALT_UNAVAILABLE",0,"OpenRouter has no free model available right now. Try again later or add credit on openrouter.ai.");
  }

  /** OpenRouter's free text models, best first (strong model families, then the longest context). Empty on any failure. */
  static java.util.List<String> freeModels(){
    java.util.List<String> out=new java.util.ArrayList<>();
    HttpsURLConnection c=null;
    try{
      c=(HttpsURLConnection)new URL(OPENROUTER+"/models").openConnection();
      c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(20000);c.setRequestProperty("Accept","application/json");
      if(c.getResponseCode()/100!=2)return out;
      return freeFrom(new JSONObject(read(c.getInputStream(),8*1024*1024)).optJSONArray("data"));
    }catch(Exception ignored){}
    finally{if(c!=null)c.disconnect();}
    return out;
  }
  /** The free text models in OpenRouter's /models list, best first, at most 8. */
  static java.util.List<String> freeFrom(JSONArray data){
    java.util.List<String> out=new java.util.ArrayList<>();
    if(data==null)return out;
    {
      java.util.List<JSONObject> free=new java.util.ArrayList<>();
      for(int i=0;i<data.length();i++){
        JSONObject m=data.optJSONObject(i);if(m==null)continue;
        String id=m.optString("id","");JSONObject price=m.optJSONObject("pricing");
        if(!id.endsWith(":free")||!validModel(id)||price==null)continue;
        if(!isZero(price.optString("prompt","1"))||!isZero(price.optString("completion","1")))continue;
        JSONObject arch=m.optJSONObject("architecture");
        if(arch!=null&&arch.optString("modality","text->text").contains("->")&&!arch.optString("modality").endsWith("->text"))continue;
        free.add(m);
      }
      java.util.Collections.sort(free,(a,b)->{int r=Integer.compare(rank(b.optString("id")),rank(a.optString("id")));return r!=0?r:Long.compare(b.optLong("context_length"),a.optLong("context_length"));});
      for(JSONObject m:free){out.add(m.optString("id"));if(out.size()>=8)break;}
    }
    return out;
  }
  private static boolean isZero(String v){try{return Double.parseDouble(v)==0;}catch(NumberFormatException e){return false;}}
  /** Rough quality order for chat in Urdu/Hindi/English: bigger, newer general models first. */
  static int rank(String id){
    String s=id.toLowerCase(Locale.ROOT);int r=0;
    String[] good={"deepseek","qwen3","llama-4","llama-3.3-70b","gemini","gpt-oss","mistral-small","gemma-3-27b","kimi","glm"};
    for(int i=0;i<good.length;i++)if(s.contains(good[i])){r=good.length-i+10;break;}
    if(s.contains("70b")||s.contains("235b")||s.contains("120b")||s.contains("405b"))r+=3;
    if(s.contains("vision")||s.contains("-vl")||s.contains("coder")||s.contains("embed"))r-=6;
    return r;
  }

  static String kind(String k){return "openrouter".equals(k)||"custom".equals(k)?k:"none";}
  static String use(String u){return "backup".equals(u)||"primary".equals(u)?u:"off";}
  static String label(String kind){return "openrouter".equals(kind)?"OpenRouter":"custom".equals(kind)?"Custom provider":"Gemini";}

  /** The API base for a provider: OpenRouter's, or a custom https URL without user, query or fragment. */
  static String base(String kind,String custom){
    if("openrouter".equals(kind))return OPENROUTER;
    if(!"custom".equals(kind))throw new IllegalArgumentException("No second provider is chosen.");
    String b=custom==null?"":custom.trim();
    while(b.endsWith("/"))b=b.substring(0,b.length()-1);
    if(b.endsWith("/chat/completions"))b=b.substring(0,b.length()-"/chat/completions".length());
    URI u;
    try{u=new URI(b);}catch(Exception e){throw new IllegalArgumentException("Enter the provider's API address, for example https://api.example.com/v1");}
    if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getHost().isEmpty())throw new IllegalArgumentException("The provider address must start with https://");
    if(u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null)throw new IllegalArgumentException("Put the key in the key field, not in the address.");
    if(b.length()>300)throw new IllegalArgumentException("The provider address is too long.");
    return b;
  }
  static boolean validModel(String m){return m!=null&&m.matches("[A-Za-z0-9._:/@+-]{1,120}");}
  static boolean validKey(String k){return k!=null&&k.matches("[\\x21-\\x7E]{8,400}");}

  /** The same chat as a Gemini generateContent request, in OpenAI chat/completions form. Google Search is not available there. */
  static JSONObject fromGemini(JSONObject gemini,String model,int maxTokens) throws JSONException {
    JSONArray messages=new JSONArray();
    JSONObject sys=gemini.optJSONObject("systemInstruction");
    String system=sys==null?"":join(sys.optJSONArray("parts"));
    system=system.replace("For news, weather, scores, prices or anything recent, use Google Search and say where the answer came from.",
      "You cannot search the web in this chat. For news, weather, scores, prices or anything recent, say that you can't check live information here and suggest a Live call or a search.");
    if(!system.isEmpty())messages.put(new JSONObject().put("role","system").put("content",system));
    JSONArray contents=gemini.optJSONArray("contents");
    if(contents!=null)for(int i=0;i<contents.length();i++){
      JSONObject c=contents.optJSONObject(i);if(c==null)continue;
      String text=join(c.optJSONArray("parts"));if(text.isEmpty())continue;
      messages.put(new JSONObject().put("role","model".equals(c.optString("role"))?"assistant":"user").put("content",text));
    }
    return new JSONObject().put("model",model).put("messages",messages).put("max_tokens",maxTokens);
  }
  private static String join(JSONArray parts){
    if(parts==null)return "";StringBuilder b=new StringBuilder();
    for(int i=0;i<parts.length();i++){JSONObject p=parts.optJSONObject(i);if(p!=null&&!p.optBoolean("thought",false))b.append(p.optString("text",""));}
    return b.toString();
  }

  /** The reply text of a chat/completions answer, or "" when there is none. */
  static String text(JSONObject r){
    JSONArray choices=r==null?null:r.optJSONArray("choices");
    if(choices==null||choices.length()==0)return "";
    JSONObject m=choices.optJSONObject(0)==null?null:choices.optJSONObject(0).optJSONObject("message");
    if(m==null)return "";
    Object content=m.opt("content");
    if(content instanceof String)return ((String)content).trim();
    if(content instanceof JSONArray){StringBuilder b=new StringBuilder();JSONArray a=(JSONArray)content;for(int i=0;i<a.length();i++){JSONObject p=a.optJSONObject(i);if(p!=null)b.append(p.optString("text",""));}return b.toString().trim();}
    return "";
  }
  /** Prompt and reply tokens as the provider reported them ({0,0} when it did not). */
  static long[] openAiTokens(JSONObject r){JSONObject u=r==null?null:r.optJSONObject("usage");return u==null?new long[]{0,0}:new long[]{Math.max(0,u.optLong("prompt_tokens")),Math.max(0,u.optLong("completion_tokens"))};}
  static long[] geminiTokens(JSONObject r){JSONObject u=r==null?null:r.optJSONObject("usageMetadata");return u==null?new long[]{0,0}:new long[]{Math.max(0,u.optLong("promptTokenCount")),Math.max(0,u.optLong("candidatesTokenCount")+u.optLong("thoughtsTokenCount"))};}

  /** A safe error for the user: the provider's own body is never shown or logged. */
  static GeminiAccess.Failure failure(int status,String kind){
    String who=label(kind);
    if(status==401||status==403)return new GeminiAccess.Failure("ALT_KEY_REJECTED",status,who+" did not accept the key. Check it in Settings > AI providers.");
    if(status==402)return new GeminiAccess.Failure("ALT_NO_CREDIT",status,who+" says the account has no credit left for this model.");
    if(status==404)return new GeminiAccess.Failure("ALT_MODEL_UNAVAILABLE",status,who+" could not find this model. Leave the model empty to let FRIDAY choose, or check the name in Settings > AI providers.");
    if(status==429)return new GeminiAccess.Failure("ALT_QUOTA",status,who+"'s limit for this model is reached. Try again later.");
    if(status>=500)return new GeminiAccess.Failure("ALT_UNAVAILABLE",status,who+" is temporarily unavailable.");
    return new GeminiAccess.Failure("ALT_HTTP_ERROR",status,who+" returned HTTP "+status+".");
  }

  interface Opened{void opened(HttpsURLConnection c);}
  /** One chat/completions request. Throws GeminiAccess.Failure for an HTTP error, IOException for network trouble. */
  static JSONObject chat(String base,String kind,String key,JSONObject body,Opened opened) throws Exception {
    HttpsURLConnection c=(HttpsURLConnection)new URL(base+"/chat/completions").openConnection();
    try{
      if(opened!=null)opened.opened(c);
      c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setDoOutput(true);
      c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");
      c.setRequestProperty("Authorization","Bearer "+key);
      if("openrouter".equals(kind)){c.setRequestProperty("X-Title","FRIDAY");}
      try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
      int status=c.getResponseCode();
      if(status<200||status>=300){try{read(c.getErrorStream(),65536);}catch(IOException ignored){}throw failure(status,kind);}
      return new JSONObject(read(c.getInputStream(),1024*1024));
    }finally{c.disconnect();if(opened!=null)opened.opened(null);}
  }
  private static String read(InputStream in,int limit) throws IOException {
    if(in==null)return "";
    try(InputStream s=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=s.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Response exceeds the size limit.");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}
  }

  // ---- usage ledger: {"2026-11-09":{"gemini":{"requests":3,"failed":0,"in":1200,"out":300}}}
  static JSONObject addUsage(JSONObject ledger,String day,String provider,long in,long out,boolean failed){
    JSONObject l=ledger==null?new JSONObject():ledger;
    try{
      JSONObject d=l.optJSONObject(day);if(d==null){d=new JSONObject();l.put(day,d);}
      JSONObject p=d.optJSONObject(provider);if(p==null){p=new JSONObject();d.put(provider,p);}
      p.put("requests",p.optLong("requests")+1).put("failed",p.optLong("failed")+(failed?1:0)).put("in",p.optLong("in")+Math.max(0,in)).put("out",p.optLong("out")+Math.max(0,out));
      java.util.List<String> days=new java.util.ArrayList<>();Iterator<String> it=l.keys();while(it.hasNext())days.add(it.next());
      java.util.Collections.sort(days);while(days.size()>30)l.remove(days.remove(0));
    }catch(JSONException ignored){}
    return l;
  }
  /** Today's and the last 7 days' totals per provider, for Settings. */
  static JSONObject summary(JSONObject ledger,String today,java.util.List<String> last7){
    JSONObject out=new JSONObject();
    try{
      out.put("today",ledger==null||ledger.optJSONObject(today)==null?new JSONObject():ledger.optJSONObject(today));
      JSONObject week=new JSONObject();
      if(ledger!=null)for(String day:last7){JSONObject d=ledger.optJSONObject(day);if(d==null)continue;Iterator<String> it=d.keys();
        while(it.hasNext()){String p=it.next();JSONObject s=d.optJSONObject(p),w=week.optJSONObject(p);if(s==null)continue;if(w==null){w=new JSONObject();week.put(p,w);}
          for(String k:new String[]{"requests","failed","in","out"})w.put(k,w.optLong(k)+s.optLong(k));}}
      out.put("week",week);
    }catch(JSONException ignored){}
    return out;
  }
  static String day(long at){java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);return f.format(new java.util.Date(at));}
  static java.util.List<String> lastDays(long now,int n){java.util.List<String> d=new java.util.ArrayList<>();for(int i=0;i<n;i++)d.add(day(now-i*86_400_000L));return d;}
}
