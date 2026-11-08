package com.myraa.mobile;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;

/**
 * FRIDAY's update and rollback engine.
 *  - Check: reads the public GitHub releases list (no account or key), at most once a day on its own
 *    when allowed, or when the owner taps Check.
 *  - Download: only from GitHub, size-limited, then SHA-256, package name, version and signing
 *    certificate are checked against the release and the installed app. A file that fails is deleted.
 *  - Install: before Android is asked, the installed APK and a FRIDAY data backup are saved to
 *    Downloads/FRIDAY for rollback. Android's own installer then asks the owner to confirm;
 *    FRIDAY never installs silently. App data stays, because the signature matches.
 *  - Rollback: Android doesn't allow installing an older version over a newer one, so a rollback is
 *    "uninstall, install the saved older APK, restore the saved backup"; FRIDAY shows these steps.
 */
final class Updater {
  private Updater(){}
  static final String PREFS="friday_update";
  static final String RELEASES="https://api.github.com/repos/"+SelfDevelopment.REPO+"/releases?per_page=15";
  static final String ACTION_RESULT="com.myraa.mobile.UPDATE_RESULT";
  private static final long MAX_APK=150L*1024*1024;

  static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,0);}

  static long installedCode(Context c){
    try{PackageInfo p=c.getPackageManager().getPackageInfo(c.getPackageName(),0);return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}catch(Exception e){return 0;}
  }

  /** SHA-256 of the first signing certificate, lower-case hex, or "" when Android doesn't share it. */
  @SuppressWarnings("deprecation")
  static String certOf(PackageInfo p){
    try{
      Signature[] s=null;
      if(Build.VERSION.SDK_INT>=28&&p.signingInfo!=null)s=p.signingInfo.hasMultipleSigners()?p.signingInfo.getApkContentsSigners():p.signingInfo.getSigningCertificateHistory();
      if((s==null||s.length==0)&&p.signatures!=null)s=p.signatures;
      if(s==null||s.length==0)return "";
      // The current certificate is the last entry of the history (rotation) and the only one otherwise.
      return UpdatePlan.hex(MessageDigest.getInstance("SHA-256").digest(s[s.length-1].toByteArray()));
    }catch(Exception e){return "";}
  }
  @SuppressWarnings("deprecation")
  static String installedCert(Context c){
    try{int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;return certOf(c.getPackageManager().getPackageInfo(c.getPackageName(),flags));}catch(Exception e){return "";}
  }

  /** What the Updates card shows. */
  static JSONObject state(Context c){
    SharedPreferences p=prefs(c);JSONObject v=new JSONObject();
    try{
      v.put("installedCode",installedCode(c)).put("installedVersion",MainActivity.VERSION).put("auto",p.getBoolean("auto",true))
        .put("checkedAt",p.getLong("checkedAt",0)).put("status",p.getString("status","idle")).put("message",p.getString("message",""))
        .put("canInstall",Build.VERSION.SDK_INT<26||c.getPackageManager().canRequestPackageInstalls())
        .put("rollbackCopies",Build.VERSION.SDK_INT>=29);
      String latest=p.getString("latest","");if(!latest.isEmpty())v.put("latest",new JSONObject(latest));
      String older=p.getString("older","");if(!older.isEmpty())v.put("older",new JSONArray(older));
      File f=downloaded(c);v.put("downloaded",f.exists()&&p.getBoolean("verified",false));
      v.put("rollback",new JSONArray(p.getString("rollback","[]")));
    }catch(JSONException ignored){}
    return v;
  }
  private static void status(Context c,String status,String message){prefs(c).edit().putString("status",status).putString("message",message).apply();}

  static boolean dueForAutoCheck(Context c){SharedPreferences p=prefs(c);return p.getBoolean("auto",true)&&System.currentTimeMillis()-p.getLong("checkedAt",0)>20L*3600_000L;}

  /** Reads the releases list. Network: call off the main thread. */
  static JSONObject check(Context c){
    HttpsURLConnection conn=null;
    try{
      status(c,"checking","Checking GitHub for a new FRIDAY…");
      conn=(HttpsURLConnection)new URL(RELEASES).openConnection();
      conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(12000);conn.setReadTimeout(15000);
      conn.setRequestProperty("Accept","application/vnd.github+json");conn.setRequestProperty("User-Agent","FRIDAY-Android");
      int code=conn.getResponseCode();
      if(code==403||code==429)throw new IOException("GitHub is limiting checks right now. Try again in an hour.");
      if(code/100!=2)throw new IOException("GitHub answered "+code+".");
      JSONArray list=new JSONArray(new String(read(conn.getInputStream(),2*1024*1024),"UTF-8"));
      List<UpdatePlan.Release> all=UpdatePlan.parse(list);long installed=installedCode(c);String cert=installedCert(c);
      UpdatePlan.Release next=UpdatePlan.newest(all,installed,cert);
      JSONArray older=new JSONArray();for(UpdatePlan.Release r:UpdatePlan.older(all,installed))if(older.length()<5)older.put(r.json());
      SharedPreferences.Editor e=prefs(c).edit().putLong("checkedAt",System.currentTimeMillis()).putString("older",older.toString());
      String previous=prefs(c).getString("latest","");
      if(next==null){
        e.remove("latest").putBoolean("verified",false).apply();downloaded(c).delete();
        boolean unsigned=false;for(UpdatePlan.Release r:all)if(r.versionCode>installed&&!r.cert.equalsIgnoreCase(cert))unsigned=true;
        status(c,"current",unsigned?"A newer release exists but it isn't signed with the key this FRIDAY was installed with, so it isn't offered.":"FRIDAY "+MainActivity.VERSION+" is the newest published version.");
      }else{
        if(previous.isEmpty()||UpdatePlan.Release.from(new JSONObject(previous)).versionCode!=next.versionCode){e.putBoolean("verified",false);downloaded(c).delete();}
        e.putString("latest",next.json().toString()).apply();
        status(c,"available","FRIDAY "+next.versionName+" is ready to download. Read what changed, then tap Download and check.");
        SelfDevelopment.remember(c,"note","Update available: "+next.versionName,"Found on GitHub; not installed.");
      }
    }catch(Exception ex){
      prefs(c).edit().putLong("checkedAt",System.currentTimeMillis()).apply();
      status(c,"error",ex instanceof IOException&&ex.getMessage()!=null&&!ex.getMessage().contains("://")?ex.getMessage():"Couldn't reach GitHub. Check the internet and try again.");
      SelfDevelopment.record(c,"update_check_fail",1);
    }finally{if(conn!=null)conn.disconnect();}
    return state(c);
  }

  static File downloaded(Context c){return new File(new File(c.getCacheDir(),"updates"),"FRIDAY-update.apk");}

  /** Downloads the offered APK and checks it. Network: call off the main thread. */
  @SuppressWarnings("deprecation")
  static JSONObject download(Context c){
    SharedPreferences p=prefs(c);File out=downloaded(c);
    try{
      UpdatePlan.Release r=UpdatePlan.Release.from(new JSONObject(p.getString("latest","{}")));
      if(r==null||r.apkUrl.isEmpty())throw new IOException("Check for updates first.");
      status(c,"downloading","Downloading FRIDAY "+r.versionName+"…");
      out.getParentFile().mkdirs();out.delete();
      MessageDigest sha=MessageDigest.getInstance("SHA-256");
      String url=r.apkUrl;HttpsURLConnection conn=null;
      try{
        for(int hop=0;;hop++){
          if(hop>4||!UpdatePlan.allowedHost(url))throw new IOException("The download was redirected away from GitHub, so it was stopped.");
          conn=(HttpsURLConnection)new URL(url).openConnection();
          conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(15000);conn.setReadTimeout(30000);conn.setRequestProperty("User-Agent","FRIDAY-Android");
          int code=conn.getResponseCode();
          if(code>=300&&code<400){url=conn.getHeaderField("Location");conn.disconnect();conn=null;if(url==null)throw new IOException("GitHub sent an empty redirect.");continue;}
          if(code/100!=2)throw new IOException("GitHub answered "+code+" for the APK.");
          break;
        }
        long total=0;byte[] buf=new byte[64*1024];int n;
        try(InputStream in=conn.getInputStream();OutputStream o=new FileOutputStream(out)){
          while((n=in.read(buf))!=-1){total+=n;if(total>MAX_APK)throw new IOException("The APK is larger than expected, so it was stopped.");sha.update(buf,0,n);o.write(buf,0,n);}
        }
      }finally{if(conn!=null)conn.disconnect();}
      PackageManager pm=c.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
      PackageInfo info=pm.getPackageArchiveInfo(out.getAbsolutePath(),flags);
      String problem=info==null?"Android couldn't read the downloaded file as an app, so it was deleted.":
        UpdatePlan.verify(r,UpdatePlan.hex(sha.digest()),info.packageName,Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode,certOf(info),c.getPackageName(),installedCode(c),installedCert(c));
      if(problem!=null){out.delete();p.edit().putBoolean("verified",false).apply();status(c,"error",problem);SelfDevelopment.remember(c,"failed","Update "+r.versionName+" refused",problem);return state(c);}
      p.edit().putBoolean("verified",true).apply();
      status(c,"verified","FRIDAY "+r.versionName+" is downloaded and checked: published SHA-256, same app, newer version and signed with your key. Tap Install; Android will ask you to confirm.");
    }catch(Exception e){
      out.delete();p.edit().putBoolean("verified",false).apply();
      status(c,"error",e instanceof IOException&&e.getMessage()!=null&&!e.getMessage().contains("://")?e.getMessage():"The download failed. Check the internet and try again.");
    }
    return state(c);
  }

  /**
   * Saves rollback copies, then hands the checked APK to Android's installer. Android shows its own
   * confirmation; nothing is installed without the owner's tap there.
   */
  static JSONObject install(Activity a,JSONObject backup){
    SharedPreferences p=prefs(a);File apk=downloaded(a);
    try{
      if(!p.getBoolean("verified",false)||!apk.exists())throw new IOException("Download and check the update first.");
      if(Build.VERSION.SDK_INT>=26&&!a.getPackageManager().canRequestPackageInstalls()){
        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.getPackageName())));
        status(a,"needs_permission","Allow \"Install unknown apps\" for FRIDAY on the screen that opened, come back and tap Install again.");
        return state(a);
      }
      UpdatePlan.Release r=UpdatePlan.Release.from(new JSONObject(p.getString("latest","{}")));
      String saved=saveRollbackCopies(a,backup);
      PackageInstaller installer=a.getPackageManager().getPackageInstaller();
      PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
      params.setAppPackageName(a.getPackageName());params.setSize(apk.length());
      int id=installer.createSession(params);
      try(PackageInstaller.Session s=installer.openSession(id)){
        try(InputStream in=new FileInputStream(apk);OutputStream out=s.openWrite("friday.apk",0,apk.length())){byte[] b=new byte[64*1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);s.fsync(out);}
        Intent done=new Intent(a,Result.class).setAction(ACTION_RESULT).putExtra("version",r==null?"":r.versionName);
        int flags=PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0);
        s.commit(PendingIntent.getBroadcast(a,id,done,flags).getIntentSender());
      }
      p.edit().putString("installingFrom",MainActivity.VERSION).apply();
      status(a,"installing","Android is asking you to confirm the update."+(saved.isEmpty()?"":" "+saved));
      SelfDevelopment.remember(a,"note","Update to "+(r==null?"?":r.versionName)+" started","Owner tapped Install. "+saved);
    }catch(Exception e){status(a,"error",e instanceof IOException&&e.getMessage()!=null?e.getMessage():"Android couldn't start the installer.");}
    return state(a);
  }

  /** Copies the installed APK and a FRIDAY data backup to Downloads/FRIDAY (Android 10+), and lists them for rollback. */
  private static String saveRollbackCopies(Context c,JSONObject backup){
    if(Build.VERSION.SDK_INT<29)return "Rollback copies need Android 10 or newer; save a backup from Settings > Advanced first if you may want to go back.";
    String version=MainActivity.VERSION;List<String> names=new ArrayList<>();
    try{
      String apkName="FRIDAY-"+version+".apk";
      try(InputStream in=new FileInputStream(c.getApplicationInfo().sourceDir)){if(saveToDownloads(c,apkName,"application/vnd.android.package-archive",in))names.add(apkName);}
      if(backup!=null){String bName="FRIDAY-backup-before-update-"+version+".json";if(saveToDownloads(c,bName,"application/json",new ByteArrayInputStream(backup.toString(2).getBytes("UTF-8"))))names.add(bName);}
      JSONArray list=new JSONArray(prefs(c).getString("rollback","[]"));
      list.put(new JSONObject().put("version",version).put("files",new JSONArray(names)).put("at",System.currentTimeMillis()));
      while(list.length()>5)list.remove(0);
      prefs(c).edit().putString("rollback",list.toString()).apply();
    }catch(Exception ignored){}
    return names.isEmpty()?"The rollback copies couldn't be saved.":"Saved for rollback in Downloads/FRIDAY: "+String.join(", ",names)+".";
  }
  private static boolean saveToDownloads(Context c,String name,String mime,InputStream in) throws IOException{
    ContentValues v=new ContentValues();
    v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);v.put(MediaStore.MediaColumns.MIME_TYPE,mime);v.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/FRIDAY");
    Uri uri=c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(uri==null)return false;
    try(OutputStream out=c.getContentResolver().openOutputStream(uri)){if(out==null)return false;byte[] b=new byte[64*1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    return true;
  }

  static JSONObject options(Context c,JSONObject o){if(o.has("auto"))prefs(c).edit().putBoolean("auto",o.optBoolean("auto",true)).apply();return state(c);}

  /** After the app starts again: an update that was being installed has finished when the version changed. */
  static void afterStart(Context c){
    SharedPreferences p=prefs(c);String from=p.getString("installingFrom","");
    if(from.isEmpty())return;
    if(!from.equals(MainActivity.VERSION)){
      p.edit().remove("installingFrom").remove("latest").putBoolean("verified",false).apply();downloaded(c).delete();
      status(c,"installed","Updated from "+from+" to "+MainActivity.VERSION+". Your data was kept. The older version is saved in Downloads/FRIDAY if you need to roll back.");
      SelfDevelopment.remember(c,"version","Installed update "+MainActivity.VERSION,"Updated from "+from+" by the owner. Measurements before and after can now be compared.");
    }
  }

  private static byte[] read(InputStream in,int limit) throws IOException{
    try(InputStream s=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=s.read(b))!=-1){if(o.size()+n>limit)throw new IOException("GitHub's answer was too large.");o.write(b,0,n);}return o.toByteArray();}
  }

  /** Android's installer result: confirmation needed, success, or a failure the owner should see. */
  public static final class Result extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
      int st=i.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
      if(st==PackageInstaller.STATUS_PENDING_USER_ACTION){
        Intent confirm=i.getParcelableExtra(Intent.EXTRA_INTENT);
        if(confirm!=null){confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);try{c.startActivity(confirm);}catch(RuntimeException ignored){}}
        return;
      }
      String version=i.getStringExtra("version");
      if(st==PackageInstaller.STATUS_SUCCESS){status(c,"installed","FRIDAY "+version+" is installed.");return;}
      String why=i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
      prefs(c).edit().remove("installingFrom").apply();
      String message=st==PackageInstaller.STATUS_FAILURE_ABORTED?"The update was cancelled. Nothing changed.":"Android didn't install the update"+(why==null||why.isEmpty()?".":": "+SelfImprovement.clip(why,160)+".")+" Nothing changed.";
      status(c,"error",message);
      SelfDevelopment.remember(c,st==PackageInstaller.STATUS_FAILURE_ABORTED?"note":"failed","Update "+version+(st==PackageInstaller.STATUS_FAILURE_ABORTED?" cancelled":" not installed"),message);
    }
  }
}
