package com.myraa.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Real photos from the internet for FRIDAY's websites, found the way a web designer looks for stock photos.
 * Three free sources without an API key, tried in order until enough pictures are downloaded:
 * Openverse (openly licensed photos from Flickr, Wikimedia and others), Wikimedia Commons (asked for a 1280 px
 * copy, so the files are small) and LoremFlickr (Creative Commons Flickr photos by keyword). Only licences that allow
 * use on a business site are kept, and every photo gets the credit line its licence asks for. When a long search
 * finds nothing, shorter searches are tried ("organic bakery lahore" → "organic bakery" → "organic" / "lahore").
 * The pure parts are plain Java, so they run on the JVM.
 */
final class WebPhotos {
  private WebPhotos(){}
  static final String API="https://api.openverse.org/v1/images/";
  static final String COMMONS="https://commons.wikimedia.org/w/api.php";
  static final String LOREM="https://loremflickr.com/";
  static final String AGENT="FRIDAY-website-builder/1.1 (+https://github.com/harissiddique421-star/Haris-ai)";
  static final int MAX_BYTES=12*1024*1024;

  static final class Photo {
    final String url,title,creator,license,version,landing,fallback,licenseName;final int width,height;
    Photo(String url,String title,String creator,String license,String version,String landing,int width,int height){
      this(url,title,creator,license,version,landing,width,height,"","");
    }
    Photo(String url,String title,String creator,String license,String version,String landing,int width,int height,String fallback,String licenseName){
      this.url=url;this.title=title;this.creator=creator;this.license=license;this.version=version;this.landing=landing;this.width=width;this.height=height;
      this.fallback=fallback==null?"":fallback;this.licenseName=licenseName==null?"":licenseName;
    }
  }

  /** A downloaded photo: the bytes and where they came from (for the credit). */
  static final class Got {
    final Photo photo;final byte[] bytes;
    Got(Photo photo,byte[] bytes){this.photo=photo;this.bytes=bytes;}
  }

  /** The Openverse search address: openly licensed photos that may be used commercially, no mature results. */
  static String searchUrl(String query,int pageSize){
    return API+"?q="+enc(clean(query))+"&license_type=commercial&mature=false&page_size="+Math.max(1,Math.min(20,pageSize));
  }

  /** Wikimedia Commons file search, asking for a 1280 px wide copy of each picture and its licence details. */
  static String commonsUrl(String query,int limit){
    return COMMONS+"?action=query&format=json&formatversion=2&generator=search&gsrnamespace=6&gsrlimit="+Math.max(1,Math.min(30,limit))
      +"&gsrsearch="+enc(clean(query)+" filetype:bitmap")+"&prop=imageinfo&iiprop=url%7Cextmetadata%7Cmime%7Csize&iiurlwidth=1280";
  }

  /** LoremFlickr picture address for some keywords; lock picks a different photo for each number. */
  static String loremUrl(String query,int lock){
    StringBuilder tags=new StringBuilder();
    for(String w:clean(query).toLowerCase(Locale.ROOT).split(" ")){
      if(w.length()<3||tags.length()>40)continue;
      if(tags.length()>0)tags.append(',');tags.append(w);
    }
    if(tags.length()==0)tags.append("business");
    return LOREM+"1280/800/"+enc(tags.toString()).replace("%2C",",")+"?lock="+lock;
  }

  /** Short keyword query from the model's idea: letters, digits and spaces, at most 80 characters. */
  static String clean(String query){
    String q=(query==null?"":query).replaceAll("[^\\p{L}\\p{N} ]"," ").replaceAll("\\s+"," ").trim();
    if(q.length()>80)q=q.substring(0,80).trim();
    return q.isEmpty()?"business":q;
  }

  /** The searches to try, longest first: the whole query, its first two words, its last word, its first word, then the backup (the site name). */
  static List<String> queries(String query,String backup){
    LinkedHashSet<String> out=new LinkedHashSet<>();
    if(query!=null&&!query.trim().isEmpty()){
      String c=clean(query);String[] w=c.split(" ");
      out.add(c);
      if(w.length>2)out.add(w[0]+" "+w[1]);
      if(w.length>1&&w[w.length-1].length()>=4)out.add(w[w.length-1]);
      if(w.length>1)out.add(w[0]);
    }
    if(backup!=null&&!backup.trim().isEmpty())out.add(clean(backup));
    if(out.isEmpty())out.add("business");
    return new ArrayList<>(out);
  }

  /** Reads the Openverse answer: https pictures, big enough for a website, each picture once, best first. */
  static List<Photo> parse(String json,int max) throws JSONException {
    List<Photo> out=new ArrayList<>();Set<String> seen=new HashSet<>();
    JSONArray results=new JSONObject(json==null||json.trim().isEmpty()?"{}":json).optJSONArray("results");
    for(int i=0;results!=null&&i<results.length()&&out.size()<max;i++){
      JSONObject r=results.optJSONObject(i);if(r==null)continue;
      String url=r.optString("url","").trim();
      if(url.startsWith("http://"))url="https://"+url.substring(7);
      if(!url.startsWith("https://")||!seen.add(url))continue;
      String low=url.toLowerCase(Locale.ROOT);
      if(low.endsWith(".svg")||low.endsWith(".gif")||low.endsWith(".tif")||low.endsWith(".tiff"))continue;
      int w=r.optInt("width",0),h=r.optInt("height",0);
      if(w>0&&w<640)continue;
      String license=r.optString("license","").trim().toLowerCase(Locale.ROOT);
      if(!allowed(license))continue;
      String thumb=r.optString("thumbnail","").trim();if(!thumb.startsWith("https://"))thumb="";
      out.add(new Photo(url,r.optString("title","").trim(),r.optString("creator","").trim(),license,r.optString("license_version","").trim(),r.optString("foreign_landing_url","").trim(),w,h,thumb,""));
    }
    return out;
  }

  /** Reads the Wikimedia Commons answer: photos (JPEG/PNG/WebP), 1280 px copies, licences that allow business use. */
  static List<Photo> parseCommons(String json,int max) throws JSONException {
    List<Photo> out=new ArrayList<>();
    JSONObject q=new JSONObject(json==null||json.trim().isEmpty()?"{}":json).optJSONObject("query");
    JSONArray pages=q==null?null:q.optJSONArray("pages");
    if(pages==null)return out;
    List<JSONObject> sorted=new ArrayList<>();
    for(int i=0;i<pages.length();i++){JSONObject p=pages.optJSONObject(i);if(p!=null)sorted.add(p);}
    sorted.sort((a,b)->Integer.compare(a.optInt("index",999),b.optInt("index",999)));
    for(JSONObject p:sorted){
      if(out.size()>=max)break;
      JSONArray infos=p.optJSONArray("imageinfo");JSONObject in=infos==null?null:infos.optJSONObject(0);if(in==null)continue;
      String mime=in.optString("mime","").toLowerCase(Locale.ROOT);
      if(!(mime.equals("image/jpeg")||mime.equals("image/png")||mime.equals("image/webp")))continue;
      int w=in.optInt("width",0);if(w>0&&w<640)continue;
      String url=in.optString("thumburl",in.optString("url","")).trim();
      if(!url.startsWith("https://"))continue;
      JSONObject meta=in.optJSONObject("extmetadata");
      String code=metaText(meta,"License").toLowerCase(Locale.ROOT).trim(),shortName=metaText(meta,"LicenseShortName").trim();
      String sn=shortName.toLowerCase(Locale.ROOT);
      String license;String version="";
      if(code.equals("cc0")||sn.contains("cc0"))license="cc0";
      else if(code.startsWith("pd")||sn.startsWith("public domain"))license="pdm";
      else if(code.startsWith("cc-")){
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^([a-z]+(?:-[a-z]+)*)(?:-(\\d+(?:\\.\\d+)?))?").matcher(code.substring(3));
        if(!m.find())continue;
        license=m.group(1);version=m.group(2)==null?"":m.group(2);
      }else continue;
      if(!allowed(license))continue;
      String title=p.optString("title","").replaceFirst("^File:","").replaceAll("\\.[A-Za-z0-9]{2,5}$","").replace('_',' ').trim();
      String creator=metaText(meta,"Artist").replaceAll("<[^>]+>","").replaceAll("\\s+"," ").trim();
      out.add(new Photo(url,title,creator,license,version,in.optString("descriptionurl","").trim(),w,in.optInt("height",0),"",shortName));
    }
    return out;
  }
  private static String metaText(JSONObject meta,String key){
    JSONObject o=meta==null?null:meta.optJSONObject(key);return o==null?"":o.optString("value","");
  }

  /** Licences that allow use on a business website (no NonCommercial, no NoDerivatives). */
  static boolean allowed(String license){
    String l=license==null?"":license.trim().toLowerCase(Locale.ROOT);
    if(l.isEmpty()||l.contains("nc")||l.contains("nd"))return false;
    return l.equals("cc0")||l.equals("pdm")||l.equals("by")||l.equals("by-sa")||l.equals("cc");
  }

  /** "Photo: "Bread" by Ali, CC BY 2.0" with a link to the original page, as the licence asks. */
  static String credit(Photo p){
    String name=!p.licenseName.isEmpty()?p.licenseName:p.license.equals("cc0")?"CC0":p.license.equals("pdm")?"Public Domain":p.license.equals("cc")?"Creative Commons":"CC "+p.license.toUpperCase(Locale.ROOT)+(p.version.isEmpty()?"":" "+p.version);
    String title=p.title.isEmpty()?"Photo":"“"+clip(p.title,80)+"”";
    String by=p.creator.isEmpty()?"":" by "+clip(p.creator,60);
    String text=escape(title+by+", "+name);
    return p.landing.startsWith("https://")?"<a href=\""+escape(p.landing)+"\" target=\"_blank\" rel=\"noopener\">"+text+"</a>":text;
  }

  /** The small credits line put at the bottom of the page for the internet photos it uses. */
  static String credits(List<Photo> used){
    if(used==null||used.isEmpty())return "";
    StringBuilder b=new StringBuilder("<div class=\"photo-credits\" style=\"font-size:12px;opacity:.75;text-align:center;padding:12px 16px;line-height:1.5\">Photos: ");
    for(int i=0;i<used.size();i++){if(i>0)b.append(" · ");b.append(credit(used.get(i)));}
    return b.append("</div>").toString();
  }

  /** Puts the credits just before </footer> (or </body>), so they sit in the page's own footer. */
  static String addCredits(String html,List<Photo> used){
    String c=credits(used);if(c.isEmpty())return html;
    String low=html.toLowerCase(Locale.ROOT);
    int at=low.lastIndexOf("</footer>");if(at<0)at=low.lastIndexOf("</body>");
    return at<0?html+c:html.substring(0,at)+c+html.substring(at);
  }

  /**
   * When the page has no place for the photos (the model left out the photo placeholders), adds a tidy, responsive
   * photo gallery section before the footer, as a designer would. Returns the page unchanged when there is nothing to add.
   */
  static String addGallery(String html,List<String> sources,String heading){
    if(sources==null||sources.isEmpty())return html;
    String alt=escape(heading==null||heading.trim().isEmpty()?"Photo":heading.trim());
    StringBuilder g=new StringBuilder("<section class=\"friday-gallery\" style=\"max-width:1200px;margin:0 auto;padding:48px 16px\">");
    if(heading!=null&&!heading.trim().isEmpty())g.append("<h2 style=\"text-align:center;margin:0 0 24px\">").append(alt).append("</h2>");
    g.append("<div style=\"display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:16px\">");
    for(String s:sources)g.append("<img src=\"").append(s).append("\" alt=\"").append(alt).append("\" style=\"width:100%;aspect-ratio:4/3;object-fit:cover;border-radius:14px;display:block\">");
    g.append("</div></section>");
    String low=html.toLowerCase(Locale.ROOT);
    int at=low.lastIndexOf("</main>");if(at<0)at=low.lastIndexOf("<footer");if(at<0)at=low.lastIndexOf("</body>");
    return at<0?html+g:html.substring(0,at)+g+html.substring(at);
  }

  static String clip(String s,int n){String t=s==null?"":s.trim();return t.length()<=n?t:t.substring(0,n).trim()+"…";}
  static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
  private static String enc(String s){
    try{return URLEncoder.encode(s,"UTF-8");}catch(java.io.UnsupportedEncodingException e){throw new IllegalStateException(e);}
  }

  // ---- Network (on the phone)

  /** Finds up to max candidate photos from Openverse and Wikimedia Commons, trying shorter searches when needed. */
  static List<Photo> search(String query,String backup,int max){
    List<Photo> out=new ArrayList<>();Set<String> seen=new HashSet<>();
    for(String q:queries(query,backup)){
      if(out.size()>=max)break;
      try{
        for(Photo p:parse(new String(get(searchUrl(q,20),2*1024*1024,"application/json"),StandardCharsets.UTF_8),max))if(out.size()<max&&seen.add(p.url))out.add(p);
        ControlLog.i("website: openverse '"+q+"' -> "+out.size());
      }catch(Exception e){ControlLog.i("website: openverse failed ("+why(e)+")");}
      if(out.size()>=max)break;
      try{
        for(Photo p:parseCommons(new String(get(commonsUrl(q,20),2*1024*1024,"application/json"),StandardCharsets.UTF_8),max))if(out.size()<max&&seen.add(p.url))out.add(p);
        ControlLog.i("website: commons '"+q+"' -> "+out.size());
      }catch(Exception e){ControlLog.i("website: commons failed ("+why(e)+")");}
    }
    return out;
  }

  /**
   * Searches and downloads up to want photos (four at a time, so it stays quick). When the searches or downloads
   * come back short, LoremFlickr fills the rest with Creative Commons Flickr photos for the same keywords.
   */
  static List<Got> fetch(String query,String backup,int want){
    List<Got> out=new ArrayList<>();
    List<Photo> found=search(query,backup,want*3);
    ExecutorService pool=Executors.newFixedThreadPool(4);
    try{
      List<Future<byte[]>> jobs=new ArrayList<>();
      for(Photo p:found)jobs.add(pool.submit(()->download(p)));
      for(int i=0;i<jobs.size()&&out.size()<want;i++){
        byte[] b=null;
        try{b=jobs.get(i).get(60,TimeUnit.SECONDS);}catch(Exception e){ControlLog.i("website: photo wait failed ("+why(e)+")");}
        if(b!=null)out.add(new Got(found.get(i),b));
      }
      if(out.size()<want){
        String q=clean(query==null||query.trim().isEmpty()?backup:query);
        List<Future<byte[]>> more=new ArrayList<>();List<Photo> extra=new ArrayList<>();
        for(int lock=1;lock<=want-out.size()+2;lock++){
          Photo p=new Photo(loremUrl(q,lock),"","","cc","","https://loremflickr.com",1280,800,loremUrl(q.split(" ")[0],lock+10),"Creative Commons (Flickr via LoremFlickr)");
          extra.add(p);more.add(pool.submit(()->download(p)));
        }
        Set<Integer> seen=new HashSet<>();
        for(Got g:out)seen.add(Arrays.hashCode(g.bytes));
        for(int i=0;i<more.size()&&out.size()<want;i++){
          byte[] b=null;
          try{b=more.get(i).get(60,TimeUnit.SECONDS);}catch(Exception e){ControlLog.i("website: loremflickr wait failed ("+why(e)+")");}
          if(b!=null&&seen.add(Arrays.hashCode(b)))out.add(new Got(extra.get(i),b));
        }
        ControlLog.i("website: loremflickr filled to "+out.size());
      }
    }finally{pool.shutdownNow();}
    ControlLog.i("website: "+out.size()+" of "+found.size()+" photos downloaded");
    return out;
  }

  /** Downloads one picture (https only, at most 12 MB); tries the small copy when the original fails; null when neither works. */
  static byte[] download(Photo p){
    try{return get(p.url,MAX_BYTES,"image/");}
    catch(Exception e){ControlLog.i("website: photo not downloaded ("+why(e)+")");}
    if(!p.fallback.isEmpty()){
      try{return get(p.fallback,MAX_BYTES,"image/");}
      catch(Exception e){ControlLog.i("website: photo copy not downloaded ("+why(e)+")");}
    }
    return null;
  }

  private static String why(Exception e){String m=e.getMessage();return e.getClass().getSimpleName()+(m==null?"":": "+clip(m,60));}

  private static byte[] get(String address,int max,String type) throws IOException {
    String url=address;
    for(int hop=0;hop<6;hop++){
      if(url.startsWith("http://"))url="https://"+url.substring(7);
      if(!url.startsWith("https://"))throw new IOException("not https");
      HttpsURLConnection c=(HttpsURLConnection)new URL(url).openConnection();
      try{
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent",AGENT);
        c.setRequestProperty("Accept",type.equals("image/")?"image/jpeg,image/png,image/webp,image/*;q=0.8,*/*;q=0.5":type);
        int status=c.getResponseCode();
        if(status/100==3){String next=c.getHeaderField("Location");if(next==null)throw new IOException("redirect without location");url=new URL(new URL(url),next).toString();continue;}
        if(status/100!=2)throw new IOException("HTTP "+status);
        String got=c.getContentType()==null?"":c.getContentType().toLowerCase(Locale.ROOT);
        // Pictures are checked by decoding them later; some hosts call them application/octet-stream.
        boolean bad=type.equals("image/")?(got.startsWith("text/")||got.contains("json")):!got.contains("json");
        if(bad)throw new IOException("wrong type "+got);
        try(InputStream in=c.getInputStream()){
          ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;
          while((n=in.read(b))>0){o.write(b,0,n);if(o.size()>max)throw new IOException("too large");}
          return o.toByteArray();
        }
      }finally{c.disconnect();}
    }
    throw new IOException("too many redirects");
  }
}
