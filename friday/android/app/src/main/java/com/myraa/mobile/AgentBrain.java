package com.myraa.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Agentic mode's brain: one step of a tool-calling conversation with the model the user chose.
 * The page keeps the conversation in OpenAI chat form (system, user, assistant with tool_calls, tool);
 * this class sends it to an OpenAI-compatible provider (OpenRouter, DeepSeek, OpenAI, Groq, custom) or
 * to Gemini (converted to generateContent with functionDeclarations), and returns the reply text and
 * the tool calls in one shape. Gemini's own parts (with their thought signatures) travel back in the
 * assistant message as "gemini_parts" so the next step can send them unchanged, as Gemini requires.
 * A model that does not answer within the step timeout is given up, and the caller moves to the next.
 */
final class AgentBrain {
  private AgentBrain(){}
  /** A model that takes longer than this for one step is too slow for a voice conversation. */
  static final int STEP_TIMEOUT_MS=30000;

  /** One step's answer: what to say, the tool calls to run, and the assistant message to keep. */
  static final class Step {
    final String text;final JSONArray calls;final JSONObject assistant;final String model;final long[] tokens;
    Step(String text,JSONArray calls,JSONObject assistant,String model,long[] tokens){this.text=text;this.calls=calls;this.assistant=assistant;this.model=model;this.tokens=tokens;}
    JSONObject json(String provider,long ms) throws JSONException {
      return new JSONObject().put("text",text).put("calls",calls).put("assistant",assistant).put("model",model).put("provider",provider).put("ms",ms);
    }
  }

  interface Opened{void opened(HttpsURLConnection c);}

  // ---- OpenAI-compatible providers

  static JSONObject openAiBody(String model,JSONArray messages,JSONArray tools) throws JSONException {
    JSONArray clean=new JSONArray();
    for(int i=0;i<messages.length();i++){
      JSONObject m=messages.optJSONObject(i);if(m==null)continue;
      JSONObject c=new JSONObject();
      for(Iterator<String> it=m.keys();it.hasNext();){String k=it.next();if(!"gemini_parts".equals(k))c.put(k,m.get(k));}
      // Providers want content to be a string (or null when there are tool calls).
      if(!c.has("content")||c.isNull("content"))c.put("content","assistant".equals(c.optString("role"))&&c.has("tool_calls")?JSONObject.NULL:"");
      clean.put(c);
    }
    JSONObject body=new JSONObject().put("model",model).put("messages",clean).put("max_tokens",1200).put("temperature",0.4);
    if(tools!=null&&tools.length()>0)body.put("tools",tools).put("tool_choice","auto");
    return body;
  }

  /** Reads a chat/completions answer. */
  static Step fromOpenAi(JSONObject r,String fallbackModel) throws Exception {
    JSONArray choices=r.optJSONArray("choices");
    if(choices==null||choices.length()==0)throw new GeminiAccess.Failure("AGENT_EMPTY",200,"The model returned no answer.");
    JSONObject msg=choices.getJSONObject(0).optJSONObject("message");if(msg==null)msg=new JSONObject();
    String text=TextProviders.text(r);
    JSONArray calls=new JSONArray(),keep=new JSONArray();
    JSONArray tc=msg.optJSONArray("tool_calls");
    if(tc!=null)for(int i=0;i<tc.length();i++){
      JSONObject t=tc.optJSONObject(i);if(t==null)continue;JSONObject f=t.optJSONObject("function");if(f==null)continue;
      String id=t.optString("id","");if(id.isEmpty())id="call_"+i+"_"+System.nanoTime();
      String name=f.optString("name","");Object rawArgs=f.opt("arguments");
      JSONObject args=parseArgs(rawArgs);
      calls.put(new JSONObject().put("id",id).put("name",name).put("args",args));
      keep.put(new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString())));
    }
    JSONObject assistant=new JSONObject().put("role","assistant").put("content",text.isEmpty()&&keep.length()>0?JSONObject.NULL:text);
    if(keep.length()>0)assistant.put("tool_calls",keep);
    return new Step(text,calls,assistant,r.optString("model",fallbackModel),TextProviders.openAiTokens(r));
  }
  static JSONObject parseArgs(Object raw){
    if(raw instanceof JSONObject)return (JSONObject)raw;
    if(raw instanceof String){String s=((String)raw).trim();if(s.isEmpty())return new JSONObject();try{return new JSONObject(s);}catch(JSONException e){return new JSONObject();}}
    return new JSONObject();
  }

  // ---- Gemini

  /** The conversation as a Gemini generateContent request. */
  static JSONObject geminiBody(JSONArray messages,JSONArray tools) throws JSONException {
    JSONObject body=new JSONObject();
    JSONArray contents=new JSONArray();StringBuilder system=new StringBuilder();
    JSONArray pendingResponses=null;
    for(int i=0;i<messages.length();i++){
      JSONObject m=messages.optJSONObject(i);if(m==null)continue;String role=m.optString("role");
      if(!"tool".equals(role)&&pendingResponses!=null){contents.put(new JSONObject().put("role","user").put("parts",pendingResponses));pendingResponses=null;}
      if("system".equals(role)){if(system.length()>0)system.append("\n");system.append(m.optString("content",""));continue;}
      if("user".equals(role)){String t=m.optString("content","");if(!t.isEmpty())contents.put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",t))));continue;}
      if("assistant".equals(role)){
        JSONArray raw=m.optJSONArray("gemini_parts");JSONArray parts;
        if(raw!=null&&raw.length()>0)parts=raw;
        else{
          parts=new JSONArray();String t=m.isNull("content")?"":m.optString("content","");if(!t.isEmpty())parts.put(new JSONObject().put("text",t));
          JSONArray tc=m.optJSONArray("tool_calls");
          if(tc!=null)for(int k=0;k<tc.length();k++){JSONObject f=tc.optJSONObject(k)==null?null:tc.optJSONObject(k).optJSONObject("function");if(f!=null)parts.put(new JSONObject().put("functionCall",new JSONObject().put("name",f.optString("name")).put("args",parseArgs(f.opt("arguments"))))
            // A call another provider made has no Gemini thought signature; Gemini documents this value for that case.
            .put("thoughtSignature","skip_thought_signature_validator"));}
        }
        if(parts.length()>0)contents.put(new JSONObject().put("role","model").put("parts",parts));
        continue;
      }
      if("tool".equals(role)){
        if(pendingResponses==null)pendingResponses=new JSONArray();
        Object result;String c=m.optString("content","");
        try{result=new JSONObject(c);}catch(JSONException e){result=new JSONObject().put("result",c);}
        pendingResponses.put(new JSONObject().put("functionResponse",new JSONObject().put("name",m.optString("name","mobile_action")).put("response",result)));
      }
    }
    if(pendingResponses!=null)contents.put(new JSONObject().put("role","user").put("parts",pendingResponses));
    if(system.length()>0)body.put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",system.toString()))));
    body.put("contents",contents);
    if(tools!=null&&tools.length()>0){
      JSONArray decl=new JSONArray();
      for(int i=0;i<tools.length();i++){JSONObject f=tools.optJSONObject(i)==null?null:tools.optJSONObject(i).optJSONObject("function");if(f==null)continue;
        JSONObject d=new JSONObject().put("name",f.optString("name")).put("description",f.optString("description",""));
        if(f.optJSONObject("parameters")!=null)d.put("parameters",geminiSchema(f.getJSONObject("parameters")));
        decl.put(d);}
      body.put("tools",new JSONArray().put(new JSONObject().put("functionDeclarations",decl)));
    }
    body.put("generationConfig",new JSONObject().put("maxOutputTokens",1200).put("temperature",0.4));
    return body;
  }
  /** JSON Schema types (lower case) as Gemini's OpenAPI types (upper case); unsupported keys are dropped. */
  static JSONObject geminiSchema(JSONObject s) throws JSONException {
    JSONObject out=new JSONObject();
    for(Iterator<String> it=s.keys();it.hasNext();){
      String k=it.next();Object v=s.get(k);
      if("type".equals(k))out.put("type",String.valueOf(v).toUpperCase(java.util.Locale.ROOT));
      else if("properties".equals(k)&&v instanceof JSONObject){JSONObject p=new JSONObject(),src=(JSONObject)v;for(Iterator<String> i2=src.keys();i2.hasNext();){String n=i2.next();p.put(n,geminiSchema(src.getJSONObject(n)));}out.put("properties",p);}
      else if("items".equals(k)&&v instanceof JSONObject)out.put("items",geminiSchema((JSONObject)v));
      else if("description".equals(k)||"enum".equals(k)||"required".equals(k)||"nullable".equals(k)||"format".equals(k))out.put(k,v);
    }
    return out;
  }
  /** Reads a generateContent answer; the model's parts are kept as they came. */
  static Step fromGemini(JSONObject r,String model) throws Exception {
    JSONArray cands=r.optJSONArray("candidates");
    if(cands==null||cands.length()==0)throw new GeminiAccess.Failure("AGENT_EMPTY",200,"Gemini returned no answer.");
    JSONObject content=cands.getJSONObject(0).optJSONObject("content");
    JSONArray parts=content==null?null:content.optJSONArray("parts");if(parts==null)parts=new JSONArray();
    StringBuilder text=new StringBuilder();JSONArray calls=new JSONArray(),keep=new JSONArray();
    for(int i=0;i<parts.length();i++){
      JSONObject p=parts.optJSONObject(i);if(p==null)continue;
      if(p.has("text")&&!p.optBoolean("thought",false))text.append(p.optString("text"));
      JSONObject fc=p.optJSONObject("functionCall");
      if(fc!=null){
        String id=fc.optString("id","");if(id.isEmpty())id="gcall_"+i+"_"+System.nanoTime();
        JSONObject args=fc.optJSONObject("args")==null?new JSONObject():fc.getJSONObject("args");
        calls.put(new JSONObject().put("id",id).put("name",fc.optString("name")).put("args",args));
        keep.put(new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",fc.optString("name")).put("arguments",args.toString())));
      }
    }
    String t=text.toString().trim();
    JSONObject assistant=new JSONObject().put("role","assistant").put("content",t.isEmpty()&&keep.length()>0?JSONObject.NULL:t).put("gemini_parts",parts);
    if(keep.length()>0)assistant.put("tool_calls",keep);
    return new Step(t,calls,assistant,model,TextProviders.geminiTokens(r));
  }

  // ---- HTTP

  static Step openAi(String base,String kind,String key,String model,JSONArray messages,JSONArray tools,Opened opened) throws Exception {
    JSONObject r=post(base+"/chat/completions",openAiBody(model,messages,tools),"Bearer "+key,null,kind,opened);
    return fromOpenAi(r,model);
  }
  static Step gemini(String key,String model,JSONArray messages,JSONArray tools,Opened opened) throws Exception {
    JSONObject r=post("https://generativelanguage.googleapis.com/v1beta/models/"+model+":generateContent",geminiBody(messages,tools),null,key,"gemini",opened);
    return fromGemini(r,model);
  }

  private static JSONObject post(String url,JSONObject body,String bearer,String googKey,String kind,Opened opened) throws Exception {
    HttpsURLConnection c=(HttpsURLConnection)new URL(url).openConnection();
    try{
      if(opened!=null)opened.opened(c);
      c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setConnectTimeout(10000);c.setReadTimeout(STEP_TIMEOUT_MS);c.setDoOutput(true);
      c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");
      if(bearer!=null)c.setRequestProperty("Authorization",bearer);
      if(googKey!=null)c.setRequestProperty("x-goog-api-key",googKey);
      if("openrouter".equals(kind))c.setRequestProperty("X-Title","FRIDAY");
      try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
      int status=c.getResponseCode();
      if(status<200||status>=300){
        String err="";try{err=read(c.getErrorStream(),65536);}catch(IOException ignored){}
        throw "gemini".equals(kind)?GeminiAccess.failure(status,err):TextProviders.failure(status,kind);
      }
      return new JSONObject(read(c.getInputStream(),2*1024*1024));
    }finally{c.disconnect();if(opened!=null)opened.opened(null);}
  }
  private static String read(InputStream in,int limit) throws IOException {
    if(in==null)return "";
    try(InputStream s=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=s.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Response exceeds the size limit.");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}
  }

  /** True when another model may answer: slow, busy, out of quota or credit, or the model is missing. Key problems are not. */
  static boolean tryNext(Exception e){
    if(e instanceof java.net.SocketTimeoutException)return true;
    if(e instanceof java.net.UnknownHostException)return false;
    if(e instanceof GeminiAccess.Failure){
      String c=((GeminiAccess.Failure)e).code;
      return !(c.contains("KEY")||c.contains("SUSPENDED")||c.contains("ACCOUNT")||c.contains("DENIED")||c.contains("SERVICE_DISABLED")||c.contains("BLOCKED"));
    }
    return e instanceof IOException;
  }
}
