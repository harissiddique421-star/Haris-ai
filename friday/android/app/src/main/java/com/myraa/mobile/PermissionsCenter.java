package com.myraa.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import org.json.*;

/**
 * One place for every permission FRIDAY's features use: its live granted state (read from
 * Android each time, never cached) and how to ask for it, either the normal runtime dialog or
 * the matching system settings page for special access.
 */
final class PermissionsCenter {
  static final int REQUEST=80;
  private final Activity activity;
  PermissionsCenter(Activity activity){this.activity=activity;}

  private static final String[][] ITEMS={
    // id, title, why
    {"microphone","Microphone","So you can talk to FRIDAY (required)."},
    {"camera","Camera","So FRIDAY can see through the camera during a Live call."},
    {"contacts","Contacts","So FRIDAY can find a contact's number or WhatsApp when you say a name."},
    {"phone","Phone calls","So FRIDAY can place a call for you after you confirm."},
    {"answer_calls","Answer calls","So FRIDAY can pick up or reject a ringing call when you say so."},
    {"sms","SMS","So FRIDAY can send a text message after you confirm."},
    {"notifications_post","App notifications","FRIDAY's notification that keeps a Live call running in the background."},
    {"notification_access","Notification access","To read your notifications out loud and say who is calling."},
    {"accessibility","Accessibility (phone control)","For Back/Home, reading the screen, tapping, typing and WhatsApp send. Turn on \"FRIDAY phone control\"."},
    {"dnd","Do Not Disturb access","So FRIDAY can switch silent and vibrate mode."},
    {"system_settings","Modify system settings","So FRIDAY can change screen brightness."},
    {"overlay","Display over other apps","For the floating FRIDAY button while you use other apps."},
    {"battery","Battery: no optimization","So a Live call keeps running with the screen off or in the background."},
  };

  private boolean runtime(String permission){return activity.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;}
  boolean granted(String id){
    switch(id){
      case "microphone":return runtime(Manifest.permission.RECORD_AUDIO);
      case "camera":return runtime(Manifest.permission.CAMERA);
      case "contacts":return runtime(Manifest.permission.READ_CONTACTS);
      case "phone":return runtime(Manifest.permission.CALL_PHONE);
      case "answer_calls":return runtime(Manifest.permission.ANSWER_PHONE_CALLS);
      case "sms":return runtime(Manifest.permission.SEND_SMS);
      case "notifications_post":return Build.VERSION.SDK_INT<33||runtime(Manifest.permission.POST_NOTIFICATIONS);
      case "notification_access":return NotificationReader.enabled(activity);
      case "accessibility":return PhoneController.enabled(activity);
      case "dnd":return activity.getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();
      case "system_settings":return Settings.System.canWrite(activity);
      case "overlay":return Settings.canDrawOverlays(activity);
      case "battery":return activity.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(activity.getPackageName());
      default:return false;
    }
  }
  JSONArray state(){
    JSONArray out=new JSONArray();
    for(String[] item:ITEMS){
      JSONObject v=new JSONObject();
      try{v.put("id",item[0]).put("title",item[1]).put("why",item[2]).put("granted",granted(item[0]));
        if("accessibility".equals(item[0]))v.put("connected",PhoneController.get()!=null);}catch(JSONException ignored){}
      out.put(v);
    }
    return out;
  }
  /** Shows the runtime dialog or opens the right settings page. Returns false for an unknown id. */
  boolean request(String id){
    String runtime=null;
    switch(id){
      case "microphone":runtime=Manifest.permission.RECORD_AUDIO;break;
      case "camera":runtime=Manifest.permission.CAMERA;break;
      case "contacts":runtime=Manifest.permission.READ_CONTACTS;break;
      case "phone":runtime=Manifest.permission.CALL_PHONE;break;
      case "answer_calls":runtime=Manifest.permission.ANSWER_PHONE_CALLS;break;
      case "sms":runtime=Manifest.permission.SEND_SMS;break;
      case "notifications_post":if(Build.VERSION.SDK_INT>=33)runtime=Manifest.permission.POST_NOTIFICATIONS;else return open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.getPackageName()));break;
      case "notification_access":return open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
      case "accessibility":return open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
      case "dnd":return open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
      case "system_settings":return open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,Uri.parse("package:"+activity.getPackageName())));
      case "overlay":return open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+activity.getPackageName())));
      case "battery":return open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
      default:return false;
    }
    // A runtime permission the user denied twice no longer shows a dialog; send them to app settings instead.
    if(!runtime(runtime)&&!activity.shouldShowRequestPermissionRationale(runtime)&&activity.getSharedPreferences("myraa.mobile.v1",0).getBoolean("asked."+runtime,false))
      return open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+activity.getPackageName())));
    activity.getSharedPreferences("myraa.mobile.v1",0).edit().putBoolean("asked."+runtime,true).apply();
    activity.requestPermissions(new String[]{runtime},REQUEST);return true;
  }
  private boolean open(Intent intent){try{activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true;}catch(ActivityNotFoundException|SecurityException e){return false;}}
}
