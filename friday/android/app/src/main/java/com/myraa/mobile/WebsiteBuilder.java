package com.myraa.mobile;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * "Website banao": FRIDAY writes a complete one-page website (HTML and CSS in one file), makes a
 * logo and a hero image with Gemini's image model, puts the images inside the page and saves it as
 * one .html file in Downloads/FRIDAY, which opens in the browser and can be shared like any document.
 * If image making is not available on the user's key, the page still gets a simple initials logo and
 * a colour background, and the user is told. Nothing is published online.
 */
final class WebsiteBuilder {
  private WebsiteBuilder(){}
  static final int MAX_BRIEF=1500;
  static final String LOGO="{{LOGO}}",HERO="{{HERO}}";
  /** Gemini image models, newest first; the first one the key can use makes the pictures. */
  static final String[] IMAGE_MODELS={"gemini-3.1-flash-image","gemini-2.5-flash-image","gemini-3-pro-image"};

  static final class Site {
    final String name,html,summary;final boolean logoMade,heroMade;final long ms;
    Site(String name,String html,String summary,boolean logoMade,boolean heroMade,long ms){this.name=name;this.html=html;this.summary=summary;this.logoMade=logoMade;this.heroMade=heroMade;this.ms=ms;}
  }

  // ---- What Gemini is asked

  static String instruction(String language){
    String lang=language==null||language.trim().isEmpty()?"English":language.trim();
    return "You are a senior web designer and front-end developer. Build ONE complete, professional, responsive one-page website.\n"
      +"Return JSON only, with these fields: html (the whole page), logo_prompt, hero_prompt, hero_alt, brand_color (#rrggbb), summary (one short sentence describing the site).\n"
      +"HTML rules:\n"
      +"- One file: <!doctype html>, <html lang>, <head> with charset, viewport, <title>, <meta name=\"description\">, <link rel=\"icon\" href=\""+LOGO+"\">, and ALL CSS in one <style> tag. JavaScript only a few lines for the mobile menu, in one <script> tag.\n"
      +"- Mobile-first and responsive: must look right at 360px, 768px and 1200px wide. Use CSS variables --primary, --accent, --text, --bg for the brand colours. Good contrast, readable font sizes.\n"
      +"- Semantic tags: header with nav, main, section, footer. Sections in order: Hero, About, Services (cards), Contact, unless the brief asks for others.\n"
      +"- The logo is an <img src=\""+LOGO+"\" alt=\"<name> logo\"> in the header (height about 44px). The hero has an <img src=\""+HERO+"\" alt=\"...\"> (object-fit: cover, no loading=lazy) with the headline and a call-to-action button over or beside it. Use these two placeholders exactly; do not use any other image file or image URL. Icons may be inline SVG or emoji.\n"
      +"- No external resources except Google Fonts. No analytics, no trackers, no external scripts.\n"
      +"- If a WhatsApp or phone number is given, add a WhatsApp button linking to https://wa.me/<digits only, with country code> and a tel: link.\n"
      +"- The contact form is design only: add a small line under it saying messages are not sent yet. Do not pretend it works.\n"
      +"- NEVER invent facts: no fake reviews, testimonials, client names, awards, prices or statistics. Never invent a phone, email or address; if missing, write a clear placeholder like [Your phone number].\n"
      +"- All visible text in "+lang+". Short, clear, professional copy that fits the business.\n"
      +"logo_prompt: describe a simple, flat, modern logo mark for this brand (shape, symbol, colours). hero_prompt: describe a high-quality photo for the hero that fits the business, in the brand colours and mood. Both prompts in English, and both images must contain no text or letters.";
  }

  static String brief(String name,String details){
    return "Business or brand name: "+(name==null||name.trim().isEmpty()?"(not given: choose a short, neutral name from the brief)":name.trim())+"\nBrief from the user: "+details;
  }

  static String logoPrompt(String name,String idea,String color){
    return "A clean, professional, flat vector logo icon for a brand called \""+name+"\". "+clip(idea,500)
      +" Main colour "+color+". Simple geometric mark, centered, on a plain white background, no mockup, no shadow, no text, no letters, no words.";
  }
  static String heroPrompt(String idea,String color){
    return "A high-quality, professional website hero photograph, 16:9, wide composition with calm empty space on one side for a headline. "+clip(idea,600)
      +" Colour mood matching "+color+". Absolutely no text, no letters, no logos, no watermarks.";
  }

  // ---- Pure helpers (tested on the JVM)

  /** Reads the model's JSON answer, even when it is wrapped in a ```json fence. */
  static JSONObject parse(String text) throws JSONException {
    String t=text==null?"":text.trim();
    if(t.startsWith("```")){int nl=t.indexOf('\n');t=nl<0?"":t.substring(nl+1);int end=t.lastIndexOf("```");if(end>=0)t=t.substring(0,end);}
    int a=t.indexOf('{'),b=t.lastIndexOf('}');
    if(a<0||b<a)throw new JSONException("no JSON object");
    return new JSONObject(t.substring(a,b+1));
  }

  /** Checks the page and makes it safe to open: a real HTML page, the placeholders present, no external scripts. */
  static String clean(String html){
    if(html==null)throw new IllegalArgumentException("The website could not be written. Try again.");
    String h=html.trim();
    String low=h.toLowerCase(Locale.ROOT);
    if(!low.contains("<html")||!low.contains("</html>")||h.length()<400)throw new IllegalArgumentException("The website came back incomplete. Try again, or ask for a simpler page.");
    h=h.replaceAll("(?is)<script[^>]*\\bsrc\\s*=[^>]*>\\s*</script>","");
    h=h.replaceAll("(?is)<iframe\\b.*?(</iframe>|/>)","");
    if(!h.contains(LOGO)){
      int header=h.toLowerCase(Locale.ROOT).indexOf("<header");
      int close=header<0?-1:h.indexOf('>',header);
      if(close>0)h=h.substring(0,close+1)+"<img src=\""+LOGO+"\" alt=\"logo\" style=\"height:44px\">"+h.substring(close+1);
    }
    return h;
  }

  static String color(String c){return c!=null&&c.trim().matches("#[0-9a-fA-F]{6}")?c.trim():"#1e3a8a";}

  /** Initials logo used when image making is not available. */
  static String fallbackLogo(String name,String color){
    StringBuilder ini=new StringBuilder();
    for(String w:(name==null?"":name).trim().split("\\s+")){if(!w.isEmpty()&&Character.isLetterOrDigit(w.codePointAt(0)))ini.appendCodePoint(Character.toUpperCase(w.codePointAt(0)));if(ini.length()>=2)break;}
    if(ini.length()==0)ini.append('F');
    String svg="<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 120 120'><rect width='120' height='120' rx='28' fill='"+color(color)+"'/>"
      +"<text x='60' y='60' dy='.35em' text-anchor='middle' font-family='Arial,sans-serif' font-size='52' font-weight='700' fill='#fff'>"+escape(ini.toString())+"</text></svg>";
    return svgUri(svg);
  }
  /** Soft brand-colour background used when the hero photo could not be made. */
  static String fallbackHero(String color){
    String c=color(color);
    String svg="<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1600 900' preserveAspectRatio='xMidYMid slice'><defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'>"
      +"<stop offset='0' stop-color='"+c+"'/><stop offset='1' stop-color='#0f172a'/></linearGradient></defs><rect width='1600' height='900' fill='url(#g)'/>"
      +"<circle cx='1250' cy='200' r='260' fill='#fff' fill-opacity='.08'/><circle cx='300' cy='780' r='340' fill='#fff' fill-opacity='.06'/></svg>";
    return svgUri(svg);
  }
  private static String svgUri(String svg){
    StringBuilder b=new StringBuilder("data:image/svg+xml,");
    for(char ch:svg.toCharArray()){if(ch=='#')b.append("%23");else if(ch=='<')b.append("%3C");else if(ch=='>')b.append("%3E");else if(ch=='"')b.append("%22");else if(ch=='%')b.append("%25");else b.append(ch);}
    return b.toString();
  }
  private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("'","&#39;");}

  static String fill(String html,String logo,String hero){return html.replace(LOGO,logo).replace(HERO,hero);}

  static String fileName(String name,Date when){
    String safe=(name==null?"":name).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","").replaceAll("\\s+"," ").trim();
    if(safe.isEmpty())safe="FRIDAY";
    if(safe.length()>60)safe=safe.substring(0,60).trim();
    return safe+" website "+new SimpleDateFormat("yyyy-MM-dd HHmm",Locale.ROOT).format(when)+".html";
  }

  static String clip(String s,int n){String t=s==null?"":s.trim();return t.length()<=n?t:t.substring(0,n).trim();}

  // ---- Running it

  /** Writes the page and makes the pictures; throws GeminiAccess.Failure for API errors and IOException for network problems. */
  static Site run(String key,String model,String name,String details,String language) throws Exception {
    long started=System.currentTimeMillis();
    JSONObject request=new JSONObject()
      .put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",instruction(language)))))
      .put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",brief(name,details))))))
      .put("generationConfig",new JSONObject().put("temperature",0.7).put("maxOutputTokens",24000).put("responseMimeType","application/json"));
    JSONObject answer=Research.call(key,model,request);
    JSONObject site;
    try{site=parse(geminiText(answer));}catch(JSONException e){throw new IllegalArgumentException("The website came back in a broken form. Try again.");}
    String html=clean(site.optString("html"));
    String brand=color(site.optString("brand_color"));
    String title=name==null||name.trim().isEmpty()?titleOf(html):name.trim();
    ControlLog.i("website: page written ("+(html.length()/1024)+" KB)");

    String logo=null,hero=null;boolean imagesWork=true;
    try{byte[] png=image(key,logoPrompt(title,site.optString("logo_prompt"),brand),"1:1");if(png!=null)logo=dataUri(png,512,true);}
    catch(Exception e){imagesWork=false;ControlLog.i("website: logo not made ("+code(e)+")");}
    // When no image model worked for the logo (no access or quota), the hero would fail the same way: do not wait for it.
    if(imagesWork){
      try{byte[] jpg=image(key,heroPrompt(site.optString("hero_prompt"),brand),"16:9");if(jpg!=null)hero=dataUri(jpg,1600,false);}
      catch(Exception e){ControlLog.i("website: hero not made ("+code(e)+")");}
    }
    boolean logoMade=logo!=null,heroMade=hero!=null;
    String page=fill(html,logoMade?logo:fallbackLogo(title,brand),heroMade?hero:fallbackHero(brand));
    return new Site(title,page,clip(site.optString("summary"),300),logoMade,heroMade,System.currentTimeMillis()-started);
  }

  static String geminiText(JSONObject r){
    JSONArray c=r==null?null:r.optJSONArray("candidates");
    JSONObject content=c==null||c.optJSONObject(0)==null?null:c.optJSONObject(0).optJSONObject("content");
    JSONArray parts=content==null?null:content.optJSONArray("parts");
    StringBuilder b=new StringBuilder();
    for(int i=0;parts!=null&&i<parts.length();i++){JSONObject p=parts.optJSONObject(i);if(p!=null&&!p.optBoolean("thought"))b.append(p.optString("text",""));}
    return b.toString();
  }

  private static String titleOf(String html){
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?is)<title>(.*?)</title>").matcher(html);
    return m.find()?clip(m.group(1).replaceAll("<[^>]+>","").split("[|\\-–]")[0],60):"FRIDAY";
  }
  private static String code(Exception e){return e instanceof GeminiAccess.Failure?((GeminiAccess.Failure)e).code:e.getClass().getSimpleName();}

  /** One picture from the first image model the key can use; null when none returned an image. */
  static byte[] image(String key,String prompt,String aspect) throws Exception {
    Exception last=null;
    for(String m:IMAGE_MODELS){
      try{
        JSONObject request=new JSONObject()
          .put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",prompt)))))
          .put("generationConfig",new JSONObject().put("responseModalities",new JSONArray().put("TEXT").put("IMAGE")).put("imageConfig",new JSONObject().put("aspectRatio",aspect)));
        byte[] b=inlineImage(post(key,m,request));
        if(b!=null){ControlLog.i("website: image by "+m);return b;}
      }catch(Exception e){
        last=e;
        if(e instanceof GeminiAccess.Failure&&((GeminiAccess.Failure)e).code.startsWith("API_KEY"))throw e;
        if(e instanceof java.net.UnknownHostException)throw e;
      }
    }
    if(last!=null)throw last;
    return null;
  }

  static byte[] inlineImage(JSONObject r){
    JSONArray c=r==null?null:r.optJSONArray("candidates");
    for(int i=0;c!=null&&i<c.length();i++){
      JSONObject content=c.optJSONObject(i)==null?null:c.optJSONObject(i).optJSONObject("content");
      JSONArray parts=content==null?null:content.optJSONArray("parts");
      for(int j=0;parts!=null&&j<parts.length();j++){
        JSONObject p=parts.optJSONObject(j);JSONObject d=p==null?null:p.optJSONObject("inlineData");if(d==null&&p!=null)d=p.optJSONObject("inline_data");
        if(d!=null&&d.optString("mimeType",d.optString("mime_type","")).startsWith("image/")&&!d.optString("data").isEmpty())return Base64.decode(d.optString("data"),Base64.DEFAULT);
      }
    }
    return null;
  }

  /** Shrinks a picture for the web (logo PNG 512 px, hero JPEG 1600 px) and returns it as a data: URI. */
  private static String dataUri(byte[] raw,int max,boolean png){
    Bitmap b=BitmapFactory.decodeByteArray(raw,0,raw.length);
    if(b==null)return null;
    int w=b.getWidth(),h=b.getHeight();
    if(Math.max(w,h)>max){float s=max/(float)Math.max(w,h);Bitmap scaled=Bitmap.createScaledBitmap(b,Math.round(w*s),Math.round(h*s),true);if(scaled!=b){b.recycle();b=scaled;}}
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    b.compress(png?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,png?100:80,out);b.recycle();
    return "data:image/"+(png?"png":"jpeg")+";base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
  }

  private static JSONObject post(String key,String model,JSONObject request) throws Exception {
    HttpsURLConnection c=(HttpsURLConnection)new URL("https://generativelanguage.googleapis.com/v1beta/models/"+model+":generateContent").openConnection();
    try{
      c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setConnectTimeout(15000);c.setReadTimeout(120000);c.setDoOutput(true);
      c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("x-goog-api-key",key);
      try(OutputStream out=c.getOutputStream()){out.write(request.toString().getBytes(StandardCharsets.UTF_8));}
      int status=c.getResponseCode();
      if(status/100!=2){String body=c.getErrorStream()==null?"":new String(read(c.getErrorStream(),65536),StandardCharsets.UTF_8);throw GeminiAccess.failure(status,body);}
      return new JSONObject(new String(read(c.getInputStream(),16*1024*1024),StandardCharsets.UTF_8));
    }finally{c.disconnect();}
  }
  private static byte[] read(InputStream in,int max) throws IOException {
    try(InputStream i=in){ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=i.read(b))>0){o.write(b,0,n);if(o.size()>max)throw new IOException("too large");}return o.toByteArray();}
  }

  /** Saves the page in Downloads/FRIDAY, where FRIDAY's open, share and send-document actions find it. */
  static Documents.Saved save(Context c,Site site) throws IOException {
    if(Build.VERSION.SDK_INT<29)throw new IllegalArgumentException("Saving websites needs Android 10 or newer.");
    String name=fileName(site.name,new Date());
    ContentValues values=new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,"text/html");
    values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/FRIDAY");values.put(MediaStore.MediaColumns.IS_PENDING,1);
    ContentResolver resolver=c.getContentResolver();
    Uri uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
    if(uri==null)throw new IOException("Downloads is not available.");
    try(OutputStream out=resolver.openOutputStream(uri,"w")){
      if(out==null)throw new IOException("no stream");
      out.write(site.html.getBytes(StandardCharsets.UTF_8));
    }catch(IOException|RuntimeException e){resolver.delete(uri,null,null);throw e;}
    values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);resolver.update(uri,values,null,null);
    return new Documents.Saved(uri,name,"text/html");
  }
}
