package com.myraa.mobile;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * FRIDAY's shared vision engine: every visual feature (documents, image comparison, and later camera,
 * screen and video) asks its questions through here, so they all get the same image preparation,
 * provider choice and safety.
 *
 * Providers: the user's Gemini key first (their model, then FRIDAY's other Gemini models when one is
 * busy, over quota or missing), then the second AI provider from Settings > AI providers (OpenRouter
 * or another OpenAI-compatible service) as a fallback, with images sent as image_url parts. Keys are
 * read from the Keystore-encrypted settings only when a request is made and are never logged.
 *
 * Only what the user picked is sent, once, after they asked. Requests can be cancelled (the open
 * connection is closed), identical requests within ten minutes are answered from a small memory cache
 * instead of asking again, and nothing here runs on the main thread.
 */
final class VisionEngine {
  /** The second provider from Settings, or null when it is not set up: {kind, base url, key, model}. */
  interface Alt { String[] provider() throws Exception; }

  /** One picture or PDF for the model. */
  static final class Media {
    final byte[] data;final String mime;
    Media(byte[] data,String mime){this.data=data;this.mime=mime;}
  }

  static final class Request {
    String system="",prompt="";boolean json=true;int maxTokens=8192;
    final List<Media> media=new ArrayList<>();
    /** Pictures for a provider that cannot read this media directly (PDF pages as JPEG for OpenRouter); null = same media. */
    Supplier<List<Media>> altMedia;
  }

  static final class Answer {
    final String text,provider,model;final boolean cached;
    Answer(String text,String provider,String model,boolean cached){this.text=text;this.provider=provider;this.model=model;this.cached=cached;}
  }

  static final class Cancelled extends IOException { Cancelled(){super("cancelled");} }

  private static final long CACHE_MS=10*60_000;
  private static final int CACHE_SIZE=8;
  private static final Map<String,Object[]> cache=new LinkedHashMap<String,Object[]>(16,0.75f,true){
    @Override protected boolean removeEldestEntry(Map.Entry<String,Object[]> e){return size()>CACHE_SIZE;}
  };

  private volatile HttpsURLConnection open;
  private volatile boolean cancelled;

  /** Closes the request in progress; ask() then throws Cancelled. Safe from any thread. */
  void cancel(){cancelled=true;HttpsURLConnection c=open;if(c!=null)try{c.disconnect();}catch(RuntimeException ignored){}}

  /**
   * Asks Gemini (key may be empty when only the second provider is set up), then the second provider.
   * Throws GeminiAccess.Failure with a user-safe message, IOException for network trouble, Cancelled.
   */
  Answer ask(String key,String model,Alt alt,Request r) throws Exception {
    cancelled=false;
    String id=fingerprint(r);
    synchronized(cache){Object[] hit=cache.get(id);if(hit!=null&&System.currentTimeMillis()-(Long)hit[0]<CACHE_MS){Answer a=(Answer)hit[1];return new Answer(a.text,a.provider,a.model,true);}}
    Exception geminiError=null;
    if(key!=null&&!key.isEmpty()){
      try{Answer a=gemini(key,model,r);remember(id,a);return a;}
      catch(Cancelled c){throw c;}
      catch(Exception e){if(cancelled)throw new Cancelled();geminiError=e;ControlLog.i("vision: Gemini failed ("+code(e)+")");}
    }
    String[] p=null;
    try{p=alt==null?null:alt.provider();}catch(Exception ignored){}
    if(p!=null&&p[2]!=null&&!p[2].isEmpty()){
      try{Answer a=openAi(p,r);remember(id,a);return a;}
      catch(Cancelled c){throw c;}
      catch(Exception e){
        if(cancelled)throw new Cancelled();
        ControlLog.i("vision: "+TextProviders.label(p[0])+" failed ("+code(e)+")");
        if(geminiError==null)throw e;
      }
    }
    if(geminiError!=null)throw geminiError;
    throw new GeminiAccess.Failure("VISION_NO_PROVIDER",0,"Picture and document reading needs the Gemini API key (or a second AI provider) in Settings.");
  }

  // ---- Gemini

  private Answer gemini(String key,String model,Request r) throws Exception {
    JSONArray parts=new JSONArray();
    for(Media m:r.media)parts.put(new JSONObject().put("inline_data",new JSONObject().put("mime_type",m.mime).put("data",Base64.encodeToString(m.data,Base64.NO_WRAP))));
    parts.put(new JSONObject().put("text",r.prompt));
    JSONObject config=new JSONObject().put("temperature",0.2).put("maxOutputTokens",r.maxTokens);
    if(r.json)config.put("responseMimeType","application/json");
    JSONObject body=new JSONObject()
      .put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",parts)))
      .put("generationConfig",config);
    if(!r.system.isEmpty())body.put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",r.system))));
    Exception last=null;
    List<String> models=Research.models(model);
    for(int i=0;i<models.size()&&i<3;i++){
      String m=models.get(i);
      if(cancelled)throw new Cancelled();
      try{
        JSONObject res=post("https://generativelanguage.googleapis.com/v1beta/models/"+m+":generateContent",body,"x-goog-api-key",key,null);
        String text=geminiText(res);
        if(text.isEmpty())throw new GeminiAccess.Failure("EMPTY",200,"Google returned no answer for this picture. Try again.");
        if(!m.equals(model))ControlLog.i("vision: answered by "+m);
        return new Answer(text,"Gemini",m,false);
      }catch(Exception e){
        if(cancelled)throw new Cancelled();
        last=e;
        // A key problem or no internet will not be fixed by another model.
        if(!Research.retryable(e))throw e;
      }
    }
    throw last!=null?last:new IOException("no answer");
  }

  static String geminiText(JSONObject res){
    JSONArray c=res==null?null:res.optJSONArray("candidates");
    if(c==null||c.length()==0)return "";
    JSONObject content=c.optJSONObject(0)==null?null:c.optJSONObject(0).optJSONObject("content");
    JSONArray parts=content==null?null:content.optJSONArray("parts");
    StringBuilder b=new StringBuilder();
    if(parts!=null)for(int i=0;i<parts.length();i++){JSONObject p=parts.optJSONObject(i);if(p!=null&&!p.optBoolean("thought",false))b.append(p.optString("text",""));}
    return b.toString().trim();
  }

  // ---- OpenRouter / OpenAI-compatible

  private Answer openAi(String[] p,Request r) throws Exception {
    String kind=p[0],base=p[1],key=p[2];
    String model=TextProviders.effectiveModel(kind,p[3]);
    // OpenRouter's free list is text-first; for pictures let its Auto router choose a model that can see.
    if(TextProviders.FREE.equals(model)||model.isEmpty())model="openrouter".equals(kind)?TextProviders.AUTO:model;
    if(model.isEmpty())throw new GeminiAccess.Failure("ALT_NOT_READY",0,"The second AI provider has no model set.");
    List<Media> media=r.altMedia!=null?r.altMedia.get():r.media;
    JSONArray content=new JSONArray().put(new JSONObject().put("type","text").put("text",r.prompt+(r.json?"\nAnswer with the JSON object only.":"")));
    for(Media m:media){
      if(!VisionLogic.imageMime(m.mime))continue;
      content.put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:"+m.mime+";base64,"+Base64.encodeToString(m.data,Base64.NO_WRAP))));
    }
    JSONArray messages=new JSONArray();
    if(!r.system.isEmpty())messages.put(new JSONObject().put("role","system").put("content",r.system));
    messages.put(new JSONObject().put("role","user").put("content",content));
    JSONObject body=new JSONObject().put("model",model).put("messages",messages).put("max_tokens",Math.min(r.maxTokens,8192)).put("temperature",0.2);
    JSONObject res;
    try{res=post(base+"/chat/completions",body,"Authorization","Bearer "+key,kind);}
    catch(GeminiAccess.Failure f){
      // 400/404 here almost always means the chosen model cannot take pictures.
      if(f.status==400||f.status==404||f.status==415)throw new GeminiAccess.Failure("ALT_NO_VISION",f.status,TextProviders.label(kind)+" model "+model+" cannot read pictures. Leave the model empty (Auto) or choose a vision model in Settings > AI providers.");
      throw f;
    }
    String text=TextProviders.text(res);
    if(text.isEmpty())throw new GeminiAccess.Failure("EMPTY",200,TextProviders.label(kind)+" returned no answer for this picture.");
    return new Answer(text,TextProviders.label(kind),res.optString("model",model),false);
  }

  // ---- HTTP

  private JSONObject post(String url,JSONObject body,String authHeader,String auth,String altKind) throws Exception {
    if(cancelled)throw new Cancelled();
    HttpsURLConnection c=(HttpsURLConnection)new URL(url).openConnection();
    open=c;
    try{
      c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setConnectTimeout(15000);c.setReadTimeout(120000);c.setDoOutput(true);
      c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");
      c.setRequestProperty(authHeader,auth);
      if("openrouter".equals(altKind))c.setRequestProperty("X-Title","FRIDAY");
      try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
      int status=c.getResponseCode();
      if(status/100!=2){
        String err=c.getErrorStream()==null?"":new String(read(c.getErrorStream(),65536),StandardCharsets.UTF_8);
        throw altKind==null?GeminiAccess.failure(status,err):TextProviders.failure(status,altKind);
      }
      return new JSONObject(new String(read(c.getInputStream(),4*1024*1024),StandardCharsets.UTF_8));
    }catch(IOException e){
      if(cancelled)throw new Cancelled();
      throw e;
    }finally{open=null;c.disconnect();}
  }

  private static byte[] read(InputStream in,int max) throws IOException {
    try(InputStream i=in){ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=i.read(b))>0){o.write(b,0,n);if(o.size()>max)throw new IOException("too large");}return o.toByteArray();}
  }

  // ---- Request de-duplication

  private static void remember(String id,Answer a){synchronized(cache){cache.put(id,new Object[]{System.currentTimeMillis(),a});}}

  private static String fingerprint(Request r){
    try{
      MessageDigest d=MessageDigest.getInstance("SHA-256");
      d.update(r.system.getBytes(StandardCharsets.UTF_8));d.update((byte)0);d.update(r.prompt.getBytes(StandardCharsets.UTF_8));
      for(Media m:r.media){d.update((byte)1);d.update(m.mime.getBytes(StandardCharsets.UTF_8));d.update(m.data);}
      return Base64.encodeToString(d.digest(),Base64.NO_WRAP);
    }catch(Exception e){return String.valueOf(System.nanoTime());}
  }

  static String code(Exception e){return e instanceof GeminiAccess.Failure?((GeminiAccess.Failure)e).code:e.getClass().getSimpleName();}

  /** A user-safe sentence for any failure of ask(). */
  static String explain(Exception e){
    if(e instanceof Cancelled)return "Stopped.";
    if(e instanceof GeminiAccess.Failure)return e.getMessage();
    if(e instanceof java.net.UnknownHostException)return "There is no internet connection, so the picture could not be read. Nothing was saved.";
    if(e instanceof java.net.SocketTimeoutException)return "The AI took too long to answer. Try again, or with fewer pages.";
    if(e instanceof IOException)return "The network failed while reading the picture. Try again.";
    return "The picture could not be read ("+e.getClass().getSimpleName()+").";
  }

  /** The file's own name as the picker shows it ("bill.pdf"), or "file". */
  static String name(ContentResolver resolver,Uri uri){
    try(android.database.Cursor c=resolver.query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
      if(c!=null&&c.moveToFirst()){String n=c.getString(0);if(n!=null&&!n.trim().isEmpty())return VisionLogic.clip(n.trim(),80);}
    }catch(RuntimeException ignored){}
    return "file";
  }

  // ---- Image preparation (memory-safe: decoded at a reduced size, bitmaps recycled)

  /**
   * Reads a picture the user picked, turned upright (EXIF), scaled so its longer side is at most maxEdge,
   * as JPEG. Big photos are decoded at a fraction of their size first, so a 50 MP photo never sits in memory.
   */
  static Media prepare(ContentResolver resolver,Uri uri,int maxEdge,int quality) throws IOException {
    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
    try(InputStream in=resolver.openInputStream(uri)){if(in==null)throw new IOException("This picture cannot be opened.");BitmapFactory.decodeStream(in,null,bounds);}
    if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("This file is not a picture FRIDAY can read.");
    int sample=1;while(Math.max(bounds.outWidth,bounds.outHeight)/(sample*2)>=maxEdge)sample*=2;
    BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=sample;opts.inPreferredConfig=Bitmap.Config.ARGB_8888;
    Bitmap bmp;
    try(InputStream in=resolver.openInputStream(uri)){bmp=BitmapFactory.decodeStream(in,null,opts);}
    catch(OutOfMemoryError e){throw new IOException("This picture is too big for the phone's free memory.");}
    if(bmp==null)throw new IOException("This picture cannot be decoded.");
    int rotation=0;
    try(InputStream in=resolver.openInputStream(uri)){
      if(in!=null){int o=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
        rotation=o==ExifInterface.ORIENTATION_ROTATE_90?90:o==ExifInterface.ORIENTATION_ROTATE_180?180:o==ExifInterface.ORIENTATION_ROTATE_270?270:0;}
    }catch(IOException|RuntimeException ignored){}
    try{
      Bitmap out=fit(bmp,maxEdge,rotation);
      try{return new Media(jpeg(out,quality),"image/jpeg");}finally{if(out!=bmp)out.recycle();}
    }catch(OutOfMemoryError e){throw new IOException("This picture is too big for the phone's free memory.");}
    finally{bmp.recycle();}
  }

  /** The bitmap scaled to fit maxEdge and rotated; the same object when nothing changes. */
  static Bitmap fit(Bitmap b,int maxEdge,int rotation){
    float scale=Math.min(1f,(float)maxEdge/Math.max(b.getWidth(),b.getHeight()));
    if(scale>=1f&&rotation==0)return b;
    Matrix m=new Matrix();m.postScale(scale,scale);if(rotation!=0)m.postRotate(rotation);
    return Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);
  }

  static byte[] jpeg(Bitmap b,int quality){ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,quality,out);return out.toByteArray();}
}
