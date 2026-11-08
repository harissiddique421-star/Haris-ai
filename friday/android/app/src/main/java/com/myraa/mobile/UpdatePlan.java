package com.myraa.mobile;

import org.json.*;
import java.net.URI;
import java.util.*;

/**
 * Update decisions, free of Android classes so they can be checked on a plain JVM.
 *
 * A FRIDAY release is a GitHub release tagged friday-v<versionName> that the owner published by
 * running the "Publish FRIDAY update" workflow. Its body carries one machine line:
 *   <!-- friday-update {"versionCode":46,"versionName":"1.0.0-rc.46","sha256":"…","cert":"…","apk":"FRIDAY-Mobile-….apk"} -->
 * The app only offers a release that is newer than the installed version, has a well-formed SHA-256
 * and is signed by the same certificate as the installed app. The APK may only come from GitHub.
 */
final class UpdatePlan {
  private UpdatePlan(){}

  static final String MARKER="<!-- friday-update ";

  static final class Release {
    final String tag,versionName,sha256,cert,apkUrl,notes,publishedAt;final long versionCode;final long size;
    Release(String tag,long versionCode,String versionName,String sha256,String cert,String apkUrl,long size,String notes,String publishedAt){
      this.tag=tag;this.versionCode=versionCode;this.versionName=versionName;this.sha256=sha256;this.cert=cert;this.apkUrl=apkUrl;this.size=size;this.notes=notes;this.publishedAt=publishedAt;
    }
    JSONObject json(){
      JSONObject o=new JSONObject();
      try{o.put("tag",tag).put("versionCode",versionCode).put("versionName",versionName).put("sha256",sha256).put("cert",cert).put("apkUrl",apkUrl).put("size",size).put("notes",notes).put("publishedAt",publishedAt);}catch(JSONException ignored){}
      return o;
    }
    static Release from(JSONObject o){
      if(o==null)return null;
      return new Release(o.optString("tag"),o.optLong("versionCode"),o.optString("versionName"),o.optString("sha256"),o.optString("cert"),o.optString("apkUrl"),o.optLong("size"),o.optString("notes"),o.optString("publishedAt"));
    }
  }

  static boolean hex64(String s){return s!=null&&s.matches("[0-9a-fA-F]{64}");}

  /** The APK may only be downloaded over HTTPS from GitHub (release assets redirect to githubusercontent.com). */
  static boolean allowedHost(String url){
    try{
      URI u=new URI(url);String h=u.getHost();
      if(!"https".equals(u.getScheme())||h==null)return false;
      h=h.toLowerCase(Locale.ROOT);
      return h.equals("github.com")||h.equals("objects.githubusercontent.com")||h.equals("release-assets.githubusercontent.com")||h.endsWith(".githubusercontent.com");
    }catch(Exception e){return false;}
  }

  /** FRIDAY releases from the GitHub releases list, newest version first. Drafts, malformed and unmarked releases are skipped. */
  static List<Release> parse(JSONArray releases){
    List<Release> out=new ArrayList<>();
    if(releases==null)return out;
    for(int i=0;i<releases.length();i++){
      JSONObject r=releases.optJSONObject(i);if(r==null||r.optBoolean("draft"))continue;
      String tag=r.optString("tag_name");if(!tag.startsWith("friday-v"))continue;
      String body=r.optString("body","");int at=body.indexOf(MARKER);if(at<0)continue;
      int end=body.indexOf("-->",at);if(end<0)continue;
      JSONObject meta;try{meta=new JSONObject(body.substring(at+MARKER.length(),end).trim());}catch(JSONException e){continue;}
      long code=meta.optLong("versionCode",0);String sha=meta.optString("sha256"),cert=meta.optString("cert"),apk=meta.optString("apk");
      if(code<=0||!hex64(sha)||!hex64(cert)||apk.isEmpty())continue;
      String url="";long size=0;JSONArray assets=r.optJSONArray("assets");
      if(assets!=null)for(int a=0;a<assets.length();a++){JSONObject x=assets.optJSONObject(a);if(x!=null&&apk.equals(x.optString("name"))){url=x.optString("browser_download_url");size=x.optLong("size");}}
      if(!allowedHost(url))continue;
      String notes=(body.substring(0,at)+body.substring(end+3)).trim();
      out.add(new Release(tag,code,meta.optString("versionName",tag.substring(8)),sha.toLowerCase(Locale.ROOT),cert.toLowerCase(Locale.ROOT),url,size,SelfImprovement.clip(notes,4000),r.optString("published_at")));
    }
    out.sort((a,b)->Long.compare(b.versionCode,a.versionCode));
    return out;
  }

  /** The newest release above the installed version that is signed like the installed app, or null. */
  static Release newest(List<Release> releases,long installedCode,String installedCert){
    for(Release r:releases)if(r.versionCode>installedCode&&(installedCert==null||installedCert.isEmpty()||r.cert.equalsIgnoreCase(installedCert)))return r;
    return null;
  }

  /** Releases older than the installed version (what a rollback could go back to), newest first. */
  static List<Release> older(List<Release> releases,long installedCode){
    List<Release> out=new ArrayList<>();for(Release r:releases)if(r.versionCode<installedCode)out.add(r);return out;
  }

  /**
   * Checks a downloaded APK before Android is asked to install it. Returns null when it is safe to
   * offer, or the reason it is refused.
   */
  static String verify(Release r,String fileSha256,String packageName,long fileVersionCode,String fileCert,String installedPackage,long installedCode,String installedCert){
    if(r==null)return "No update was selected.";
    if(!hex64(fileSha256)||!fileSha256.equalsIgnoreCase(r.sha256))return "The downloaded file doesn't match the published SHA-256, so it was deleted. Try again on a stable connection.";
    if(!installedPackage.equals(packageName))return "The downloaded file is a different app, so it was deleted.";
    if(fileVersionCode!=r.versionCode)return "The downloaded file's version doesn't match the release, so it was deleted.";
    if(fileVersionCode<=installedCode)return "The downloaded file isn't newer than the installed FRIDAY.";
    if(fileCert==null||fileCert.isEmpty())return "Android couldn't read the downloaded file's signature, so it was deleted.";
    if(!fileCert.equalsIgnoreCase(r.cert)||(installedCert!=null&&!installedCert.isEmpty()&&!fileCert.equalsIgnoreCase(installedCert)))return "The downloaded file isn't signed with your FRIDAY key, so it was deleted.";
    return null;
  }

  static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x));return s.toString();}
}
