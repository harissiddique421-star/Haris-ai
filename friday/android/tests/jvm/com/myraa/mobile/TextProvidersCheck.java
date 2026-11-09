package com.myraa.mobile;

import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Second text provider: address checks, request conversion, reply parsing, safe errors and the usage ledger. */
public final class TextProvidersCheck {
  private static int fails=0,passes=0;
  private static void check(boolean ok,String what){if(ok)passes++;else{fails++;System.out.println("FAIL: "+what);}}
  private static boolean refused(String kind,String base){try{TextProviders.base(kind,base);return false;}catch(IllegalArgumentException e){return true;}}

  public static void main(String[] a) throws Exception {
    check(TextProviders.base("openrouter","ignored").equals("https://openrouter.ai/api/v1"),"OpenRouter address");
    check(TextProviders.base("custom","https://api.example.com/v1/").equals("https://api.example.com/v1"),"trailing slash");
    check(TextProviders.base("custom","https://api.example.com/v1/chat/completions").equals("https://api.example.com/v1"),"full endpoint pasted");
    check(refused("custom","http://api.example.com/v1"),"plain http refused");
    check(refused("custom","https://user:pw@api.example.com/v1"),"credentials in address refused");
    check(refused("custom","https://api.example.com/v1?key=abc"),"key in query refused");
    check(refused("none",""),"no provider");
    check(TextProviders.validModel("openai/gpt-4o-mini")&&TextProviders.validModel("meta-llama/llama-3.1-8b-instruct:free")&&!TextProviders.validModel("bad model")&&!TextProviders.validModel(""),"model names");
    check(TextProviders.validKey("sk-or-v1-abcdef123456")&&!TextProviders.validKey("has space key")&&!TextProviders.validKey("short"),"key format");
    check(TextProviders.kind("weird").equals("none")&&TextProviders.use("weird").equals("off"),"unknown values fall back safely");

    JSONObject gemini=new JSONObject()
      .put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text","You are FRIDAY. For news, weather, scores, prices or anything recent, use Google Search and say where the answer came from."))))
      .put("contents",new JSONArray()
        .put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text","Salam"))))
        .put(new JSONObject().put("role","model").put("parts",new JSONArray().put(new JSONObject().put("text","thinking").put("thought",true)).put(new JSONObject().put("text","Wa alaikum salam"))))
        .put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text","Kya haal hai?")))))
      .put("tools",new JSONArray().put(new JSONObject().put("google_search",new JSONObject())));
    JSONObject r=TextProviders.fromGemini(gemini,"openai/gpt-4o-mini",2048);
    JSONArray m=r.getJSONArray("messages");
    check(r.getString("model").equals("openai/gpt-4o-mini")&&r.getInt("max_tokens")==2048&&!r.has("tools"),"request shape, no Google tools");
    check(m.length()==4&&m.getJSONObject(0).getString("role").equals("system"),"system first");
    check(m.getJSONObject(0).getString("content").contains("cannot search the web")&&!m.getJSONObject(0).getString("content").contains("use Google Search"),"no false search promise");
    check(m.getJSONObject(2).getString("role").equals("assistant")&&m.getJSONObject(2).getString("content").equals("Wa alaikum salam"),"model turn becomes assistant, thoughts dropped");
    check(m.getJSONObject(3).getString("content").equals("Kya haal hai?"),"last user turn");

    JSONObject reply=new JSONObject("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\" Theek hoon! \"}}],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":8}}");
    check(TextProviders.text(reply).equals("Theek hoon!"),"reply text");
    check(Arrays.equals(TextProviders.openAiTokens(reply),new long[]{120,8}),"reply tokens");
    check(TextProviders.text(new JSONObject("{\"choices\":[]}")).isEmpty()&&TextProviders.text(null).isEmpty(),"empty reply");
    check(TextProviders.text(new JSONObject("{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"A\"},{\"type\":\"text\",\"text\":\"B\"}]}}]}")).equals("AB"),"content parts");
    check(Arrays.equals(TextProviders.geminiTokens(new JSONObject("{\"usageMetadata\":{\"promptTokenCount\":50,\"candidatesTokenCount\":10,\"thoughtsTokenCount\":5}}")),new long[]{50,15}),"Gemini tokens");

    check(TextProviders.failure(401,"openrouter").code.equals("ALT_KEY_REJECTED")&&TextProviders.failure(401,"openrouter").getMessage().startsWith("OpenRouter"),"401");
    check(TextProviders.failure(402,"openrouter").code.equals("ALT_NO_CREDIT"),"402");
    check(TextProviders.failure(429,"custom").code.equals("ALT_QUOTA"),"429");
    check(TextProviders.failure(503,"custom").code.equals("ALT_UNAVAILABLE"),"503");

    JSONObject ledger=new JSONObject();
    TextProviders.addUsage(ledger,"2026-11-09","gemini",100,20,false);
    TextProviders.addUsage(ledger,"2026-11-09","gemini",0,0,true);
    TextProviders.addUsage(ledger,"2026-11-08","openrouter",50,5,false);
    JSONObject today=ledger.getJSONObject("2026-11-09").getJSONObject("gemini");
    check(today.getLong("requests")==2&&today.getLong("failed")==1&&today.getLong("in")==100&&today.getLong("out")==20,"daily counts");
    JSONObject sum=TextProviders.summary(ledger,"2026-11-09",Arrays.asList("2026-11-09","2026-11-08"));
    check(sum.getJSONObject("week").getJSONObject("openrouter").getLong("requests")==1&&sum.getJSONObject("today").has("gemini")&&!sum.getJSONObject("today").has("openrouter"),"today and week");
    for(int d=1;d<=40;d++)TextProviders.addUsage(ledger,String.format("2026-10-%02d",d%31+1).replace("-00","-01"),"gemini",1,1,false);
    check(ledger.length()<=30,"keeps at most 30 days ("+ledger.length()+")");
    check(TextProviders.lastDays(System.currentTimeMillis(),7).size()==7,"last 7 days");

    System.out.println("TextProvidersCheck: "+passes+" passed, "+fails+" failed");
    if(fails>0)System.exit(1);
  }
}
