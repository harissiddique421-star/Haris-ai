package com.myraa.mobile;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.Build;
import android.os.CancellationSignal;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Emergency (SOS): up to three family members the user chose, saved on the phone only. FRIDAY
 * sends them an SMS with the phone's location only after the user confirms; nothing is sent
 * automatically or from unclear speech.
 */
final class Sos {
  private Sos(){}
  static final int MAX=3;
  private static SharedPreferences prefs(Context c){return c.getSharedPreferences("friday.sos",Context.MODE_PRIVATE);}

  static List<MobileActions.Recipient> contacts(Context c){
    List<MobileActions.Recipient> out=new ArrayList<>();
    try{JSONArray a=new JSONArray(prefs(c).getString("contacts","[]"));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);out.add(new MobileActions.Recipient(o.getString("name"),o.getString("phone")));}}catch(JSONException ignored){}
    return out;
  }
  private static void save(Context c,List<MobileActions.Recipient> list){
    JSONArray a=new JSONArray();
    for(MobileActions.Recipient r:list)try{a.put(new JSONObject().put("name",r.name).put("phone",r.phone));}catch(JSONException ignored){}
    prefs(c).edit().putString("contacts",a.toString()).apply();
  }
  /** Adds a contact; returns null when added, or why not. */
  static String add(Context c,MobileActions.Recipient r){
    List<MobileActions.Recipient> list=contacts(c);
    for(MobileActions.Recipient x:list)if(ContactMatcher.sameNumber(x.phone,r.phone))return r.name+" is already an emergency contact.";
    if(list.size()>=MAX)return "There are already "+MAX+" emergency contacts ("+names(list)+"). Remove one first.";
    list.add(r);save(c,list);return null;
  }
  /** Removes the best name match; returns the removed name or null. */
  static String remove(Context c,String who){
    List<MobileActions.Recipient> list=contacts(c);int at=-1;double best=0;
    for(int i=0;i<list.size();i++){double s=ContactMatcher.score(who,list.get(i).name);if(s>best){best=s;at=i;}}
    if(at<0||best<ContactMatcher.HIGH)return null;
    String name=list.remove(at).name;save(c,list);return name;
  }
  static String names(List<MobileActions.Recipient> list){
    StringBuilder b=new StringBuilder();
    for(int i=0;i<list.size();i++){if(i>0)b.append(i==list.size()-1?" and ":", ");b.append(list.get(i).name);}
    return b.toString();
  }

  /** A location for the message: a fresh fix when Android can give one in a few seconds, otherwise the newest one it has (or null). */
  static void locate(Context c,Consumer<Location> done){
    Location last=Prayer.lastKnown(c);
    boolean fresh=last!=null&&System.currentTimeMillis()-last.getTime()<2*60_000L;
    LocationManager lm=c.getSystemService(LocationManager.class);
    if(fresh||lm==null||Build.VERSION.SDK_INT<30||!granted(c)){done.accept(last);return;}
    String provider=lm.isProviderEnabled(LocationManager.GPS_PROVIDER)?LocationManager.GPS_PROVIDER:lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)?LocationManager.NETWORK_PROVIDER:null;
    if(provider==null){done.accept(last);return;}
    final boolean[] answered={false};CancellationSignal cancel=new CancellationSignal();
    android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    Runnable timeout=()->{if(answered[0])return;answered[0]=true;cancel.cancel();done.accept(last);};
    main.postDelayed(timeout,8000);
    try{
      lm.getCurrentLocation(provider,cancel,c.getMainExecutor(),l->{if(answered[0])return;answered[0]=true;main.removeCallbacks(timeout);done.accept(l!=null?l:last);});
    }catch(SecurityException|IllegalArgumentException e){if(!answered[0]){answered[0]=true;main.removeCallbacks(timeout);done.accept(last);}}
  }
  static boolean granted(Context c){
    return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
  }
  static String message(Location l){
    String text="EMERGENCY: I need help. ";
    if(l!=null){
      long mins=Math.max(0,(System.currentTimeMillis()-l.getTime())/60_000);
      text+="My location: https://maps.google.com/?q="+String.format(Locale.ROOT,"%.5f,%.5f",l.getLatitude(),l.getLongitude())+(mins>=5?" (from "+mins+" min ago)":"")+". ";
    }else text+="My location is not available. ";
    return text+"Please call me. (Sent by FRIDAY)";
  }
}
