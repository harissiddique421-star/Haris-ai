package com.myraa.mobile;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * FRIDAY's health check, connection check, battery details and action history. Everything is
 * read from Android at the moment it is asked; nothing is sent anywhere. Each problem comes
 * with a plain-language fix.
 */
final class Diagnostics {
  private Diagnostics(){}

  // ---- action history: kept in memory only, cleared when the app closes
  private static final ArrayDeque<JSONObject> history=new ArrayDeque<>();
  static synchronized void record(String action,String status){
    if(action==null||action.isEmpty())return;
    JSONObject e=new JSONObject();
    try{e.put("time",new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date())).put("action",action).put("status",status==null?"":status);}catch(JSONException ignored){}
    history.addLast(e);while(history.size()>50)history.removeFirst();
  }
  static synchronized JSONObject history(int limit){
    JSONArray out=new JSONArray();Iterator<JSONObject> it=history.descendingIterator();
    while(it.hasNext()&&out.length()<Math.max(1,Math.min(limit<=0?10:limit,50)))out.put(it.next());
    JSONObject v=PhoneControls.result("done",out.length()==0?"No actions yet in this session.":"Recent actions, newest first, as time, action and result. Say them briefly; the user sees these exact results.");
    try{v.put("history",out);}catch(JSONException ignored){}
    return v;
  }

  // ---- network
  static JSONObject network(Context c){
    JSONObject v=new JSONObject();List<String> problems=new ArrayList<>();
    try{
      ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);
      Network active=cm.getActiveNetwork();NetworkCapabilities caps=active==null?null:cm.getNetworkCapabilities(active);
      boolean internet=caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
      boolean validated=caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
      boolean wifi=caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
      boolean cell=caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
      v.put("connected_via",wifi?"wifi":cell?"mobile_data":caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?"vpn":caps!=null?"other":"none");
      v.put("internet_working",validated);
      if(caps!=null){int down=caps.getLinkDownstreamBandwidthKbps();v.put("speed_estimate_kbps",down);if(validated&&down>0&&down<1000)problems.add("The connection is very slow right now (about "+down+" kbps).");}
      WifiStateHolder w=wifi(c);v.put("wifi_on",w.on);
      boolean airplane=Settings.Global.getInt(c.getContentResolver(),Settings.Global.AIRPLANE_MODE_ON,0)!=0;v.put("airplane_mode",airplane);
      Boolean data=mobileData(c);if(data!=null)v.put("mobile_data_on",data);
      if(airplane)problems.add("Airplane mode is on, so there is no internet. Turn it off in quick settings.");
      else if(active==null)problems.add(w.on?"Wi-Fi is on but not connected to a network, and mobile data isn't connected either. Connect to a Wi-Fi network or turn on mobile data.":"Wi-Fi is off and mobile data isn't connected. Turn on Wi-Fi or mobile data.");
      else if(internet&&!validated)problems.add(wifi?"Connected to Wi-Fi, but that Wi-Fi has no working internet (router or sign-in page problem). Try mobile data or restart the router.":"Connected, but the internet isn't working right now. Check the data balance or signal.");
    }catch(RuntimeException|JSONException e){problems.add("Android didn't share the connection state.");}
    try{v.put("bluetooth",bluetooth(c));}catch(JSONException ignored){}
    return withProblems(v,problems,"Internet is working.");
  }
  private static final class WifiStateHolder{boolean on;}
  private static WifiStateHolder wifi(Context c){
    WifiStateHolder h=new WifiStateHolder();
    try{android.net.wifi.WifiManager wm=c.getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);h.on=wm!=null&&wm.isWifiEnabled();}catch(RuntimeException ignored){}
    return h;
  }
  private static Boolean mobileData(Context c){
    try{TelephonyManager t=c.getSystemService(TelephonyManager.class);if(Build.VERSION.SDK_INT>=26)return t.isDataEnabled();}catch(RuntimeException ignored){}
    return null;
  }
  private static String bluetooth(Context c){
    try{
      BluetoothManager bm=c.getSystemService(BluetoothManager.class);BluetoothAdapter a=bm==null?null:bm.getAdapter();
      if(a==null)return "not available on this phone";
      if(Build.VERSION.SDK_INT>=31&&c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return "unknown (Nearby devices permission not given)";
      return a.isEnabled()?"on":"off";
    }catch(RuntimeException e){return "unknown";}
  }

  // ---- battery
  static JSONObject battery(Context c){
    JSONObject v=new JSONObject();List<String> tips=new ArrayList<>();
    try{
      BatteryManager b=c.getSystemService(BatteryManager.class);
      int level=b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);boolean charging=b.isCharging();
      Intent s=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      v.put("level",level).put("charging",charging);
      if(s!=null){
        int temp=s.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1);if(temp>0)v.put("temperature_c",temp/10.0);
        int health=s.getIntExtra(BatteryManager.EXTRA_HEALTH,0);v.put("health",health==BatteryManager.BATTERY_HEALTH_GOOD?"good":health==BatteryManager.BATTERY_HEALTH_OVERHEAT?"overheating":health==BatteryManager.BATTERY_HEALTH_DEAD?"dead":health==BatteryManager.BATTERY_HEALTH_COLD?"cold":"unknown");
        if(temp>=420)tips.add("The battery is hot ("+temp/10+"°C). Let the phone cool down and avoid gaming or fast charging for a while.");
      }
      if(Build.VERSION.SDK_INT>=28&&!charging){long left=b.computeChargeTimeRemaining();if(left>0)v.put("full_in_minutes",left/60000);}
      boolean saver=c.getSystemService(PowerManager.class).isPowerSaveMode();v.put("battery_saver",saver);
      boolean free=c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName());v.put("friday_unrestricted",free);
      if(!charging&&level<=15)tips.add(saver?"Battery is low; Battery Saver is already on. Lower brightness and close the camera or video apps.":"Battery is low. Turn on Battery Saver, lower brightness and close video apps.");
      if(!free&&WakeWordService.wanted(c))tips.add("Android may stop the wake word to save battery. Allow \"Battery: no optimization\" for FRIDAY in Settings > Permissions.");
      String msg="Battery is at "+level+"%"+(charging?" and charging.":".");
      v.put("status","done").put("message",msg+(tips.isEmpty()?"":" "+String.join(" ",tips)));
    }catch(RuntimeException|JSONException e){return PhoneControls.result("unavailable","Android didn't share the battery details.");}
    return v;
  }

  // ---- full health check
  static JSONObject health(Activity a,boolean keyConfigured,String liveModel){
    List<String> problems=new ArrayList<>(),ok=new ArrayList<>();JSONObject v=new JSONObject();
    try{
      boolean mic=a.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
      check(mic,"microphone",ok,problems,"FRIDAY can't use the microphone. Allow Microphone in Settings > Permissions.");
      check(keyConfigured,"Gemini key",ok,problems,"No Gemini API key is saved. Add it in Settings.");
      v.put("live_model",liveModel);
      if(WakeWordService.wanted(a))check(WakeWordService.running,"wake word",ok,problems,"The wake word is turned on but its service isn't running. Open FRIDAY once, and allow \"Battery: no optimization\" so Android doesn't stop it.");
      if(VoiceGuardian.wanted(a))check(VoiceGuardian.enrolled(a),"Voice Guardian",ok,problems,"Voice Guardian is on but no voice is recorded. Tap Record my voice in Settings.");
      check(PhoneController.enabled(a),"accessibility",ok,problems,"Phone control is off. Turn on FRIDAY in Android Accessibility settings to let FRIDAY tap, type and read screens.");
      check(NotificationReader.enabled(a),"notification access",ok,problems,"Notification access is off, so FRIDAY can't read messages or say who is calling. Allow it in Settings > Permissions.");
      check(a.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED,"contacts",ok,problems,"Contacts aren't allowed, so callers are announced by number and messages need full numbers.");
      boolean free=a.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(a.getPackageName());
      check(free,"battery optimization",ok,problems,"Android's battery optimization can stop FRIDAY in the background. Allow \"Battery: no optimization\" in Settings > Permissions.");
      JSONObject net=network(a);v.put("network",net);
      check(net.optBoolean("internet_working"),"internet",ok,problems,net.optString("message","No internet."));
      StatFs fs=new StatFs(Environment.getDataDirectory().getPath());long freeMb=fs.getAvailableBytes()/(1024*1024),totalMb=fs.getTotalBytes()/(1024*1024);
      v.put("storage_free_mb",freeMb);
      check(freeMb>=1024&&freeMb*20>=totalMb,"storage",ok,problems,"Storage is almost full ("+freeMb+" MB free). Delete old videos or apps; a full phone makes FRIDAY and other apps slow.");
      List<String> errors=new ArrayList<>();for(String line:ControlLog.recent())if(line.matches("(?i).*\\b(failed|error|crash|could not)\\b.*"))errors.add(line);
      if(!errors.isEmpty())v.put("recent_errors",new JSONArray(errors.subList(Math.max(0,errors.size()-3),errors.size())));
      v.put("ok",new JSONArray(ok)).put("problems",new JSONArray(problems));
      v.put("status",problems.isEmpty()?"healthy":"problems_found");
      v.put("message",problems.isEmpty()?"Everything checked is working ("+String.join(", ",ok)+"). FRIDAY is ready.":problems.size()+" problem"+(problems.size()==1?"":"s")+" found: "+String.join(" ",problems));
    }catch(RuntimeException|JSONException e){return PhoneControls.result("unavailable","The health check couldn't finish.");}
    ControlLog.i("health: "+problems.size()+" problems");
    return v;
  }
  private static void check(boolean good,String name,List<String> ok,List<String> problems,String fix){if(good)ok.add(name);else problems.add(fix);}
  private static JSONObject withProblems(JSONObject v,List<String> problems,String fine){
    try{v.put("status",problems.isEmpty()?"done":"problems_found").put("message",problems.isEmpty()?fine:String.join(" ",problems)).put("problems",new JSONArray(problems));}catch(JSONException ignored){}
    return v;
  }
}
