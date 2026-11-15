package com.myraa.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Real photos from the internet for FRIDAY's websites. Searches Openverse (free, no API key; openly licensed
 * images from Flickr, Wikimedia and others), keeps only licences that allow use on a business site, downloads
 * the pictures and gives the credit line each licence asks for. The pure parts are plain Java, so they run on the JVM.
 */
final class WebPhotos {
  private WebPhotos(){}
  static final String API="https://api.openverse.org/v1/images/";
  static final int MAX_BYTES=8*1024*1024;

  static final class Photo {
    final String url,title,creator,license,version,landing;final int width,height;
    Photo(String url,String title,String creator,String license,String version,String landing,int width,int height){
      this.url=url;this.title=title;this.creator=creator;this.license=license;this.version=version;this.landing=landing;this.width=width;this.height=height;
    }
  }

  /** The search address: openly licensed photos that may be used commercially, no mature results. */
  static String searchUrl(String query,int pageSize){
    String q=clean(query);
    try{return API+"?q="+URLEncoder.encode(q,"UTF-8")+"&license_type=commercial&mature=false&page_size="+Math.max(1,Math.min(20,pageSize));}
    catch(java.io.UnsupportedEncodingException e){throw new IllegalStateException(e);}
  }

  /** Short keyword query from the model's idea: letters, digits and spaces, at most 80 characters. */
  static String clean(String query){
    String q=(query==null?"":query).replaceAll("[^\\p{L}\\p{N} ]"," ").replaceAll("\\s+"," ").trim();
    if(q.length()>80)q=q.substring(0,80).trim();
    return q.isEmpty()?"business":q;
  }

  /** Reads the search answer: https pictures, big enough for a website, each picture once, best first. */
  static List<Photo> parse(String json,int max) throws JSONException {
    List<Photo> out=new ArrayList<>();Set<String> seen=new HashSet<>();
    JSONArray results=new JSONObject(json==null||json.trim().isEmpty()?"{}":json).optJSONArray("results");
    for(int i=0;results!=null&&i<results.length()&&out.size()<max;i++){
      JSONObject r=results.optJSONObject(i);if(r==null)continue;
      String url=r.optString("url","").trim();
      if(!url.startsWith("https://")||!seen.add(url))continue;
      String low=url.toLowerCase(Locale.ROOT);
      if(low.endsWith(".svg")||low.endsWith(".gif")||low.endsWith(".tif")||low.endsWith(".tiff"))continue;
      int w=r.optInt("width",0),h=r.optInt("height",0);
      if(w>0&&w<640)continue;
      String license=r.optString("license","").trim().toLowerCase(Locale.ROOT);
      if(license.isEmpty()||license.contains("nc")||license.contains("nd"))continue;
      out.add(new Photo(url,r.optString("title","").trim(),r.optString("creator","").trim(),license,r.optString("license_version","").trim(),r.optString("foreign_landing_url","").trim(),w,h));
    }
    return out;
  }

  /** "Photo: "Bread" by Ali, CC BY 2.0" with a link to the original page, as the licence asks. */
  static String credit(Photo p){
    String name=p.license.equals("cc0")?"CC0":p.license.equals("pdm")?"Public Domain":"CC "+p.license.toUpperCase(Locale.ROOT)+(p.version.isEmpty()?"":" "+p.version);
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

  static String clip(String s,int n){String t=s==null?"":s.trim();return t.length()<=n?t:t.substring(0,n).trim()+"…";}
  static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}

  // ---- Network (on the phone)

  /** Finds up to max photos for the query; an empty list when the search is not reachable. */
  static List<Photo> search(String query,int max){
    try{return parse(new String(get(searchUrl(query,Math.min(20,max*4)),1024*1024,"application/json"),StandardCharsets.UTF_8),max);}
    catch(Exception e){ControlLog.i("website: photo search failed ("+e.getClass().getSimpleName()+")");return new ArrayList<>();}
  }

  /** Downloads one picture (https only, at most 8 MB, an image type); null when it can't be fetched. */
  static byte[] download(Photo p){
    try{return get(p.url,MAX_BYTES,"image/");}
    catch(Exception e){ControlLog.i("website: photo not downloaded ("+e.getClass().getSimpleName()+")");return null;}
  }

  private static byte[] get(String address,int max,String type) throws IOException {
    String url=address;
    for(int hop=0;hop<4;hop++){
      if(!url.startsWith("https://"))throw new IOException("not https");
      HttpsURLConnection c=(HttpsURLConnection)new URL(url).openConnection();
      try{
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent","FRIDAY-website-builder/1.0");c.setRequestProperty("Accept",type.equals("image/")?"image/*":type);
        int status=c.getResponseCode();
        if(status/100==3){String next=c.getHeaderField("Location");if(next==null)throw new IOException("redirect without location");url=new URL(new URL(url),next).toString();continue;}
        if(status/100!=2)throw new IOException("HTTP "+status);
        String got=c.getContentType()==null?"":c.getContentType().toLowerCase(Locale.ROOT);
        if(!got.startsWith(type))throw new IOException("wrong type "+got);
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
