package com.myraa.mobile;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.util.Base64;
import org.json.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Consumer;

/**
 * Find my phone: when the owner sends "FRIDAY dhoondo <secret code>" by SMS from any other phone, this phone
 * rings loudly for a minute (alarm sound, so it rings on silent too) and texts its location back to that number.
 * Only a salted hash of the code is kept. A wrong code gets no reply, and after 5 wrong codes in an hour FRIDAY
 * ignores every code for an hour, so nobody can guess it by trying.
 */
final class PhoneFinder {
  private PhoneFinder(){}
  static final String CHANNEL="friday_finder";
  static final int NOTIFICATION=4481;
  static final long RING_MS=60_000,TEST_MS=10_000;
  static final int MAX_FAILS=5;
  static final long FAIL_WINDOW=60*60_000L,LOCK_MS=60*60_000L;

  private static SharedPreferences prefs(Context c){return c.getSharedPreferences("friday.finder",Context.MODE_PRIVATE);}
  static boolean enabled(Context c){return prefs(c).getBoolean("enabled",false)&&hasCode(c);}
  static boolean hasCode(Context c){return !prefs(c).getString("hash","").isEmpty();}

  static JSONObject state(Context c){
    JSONObject o=new JSONObject();SharedPreferences p=prefs(c);
    try{
      o.put("enabled",p.getBoolean("enabled",false)).put("hasCode",hasCode(c)).put("ringing",ringing())
        .put("locked",p.getLong("lockUntil",0)>System.currentTimeMillis())
        .put("lastFound",p.getLong("lastFound",0));
    }catch(JSONException ignored){}
    return o;
  }
  /** Settings: {enabled}, {code} or {test}. Returns {ok,message,finder}. */
  static JSONObject options(Context c,JSONObject in){
    String message="";boolean ok=true;
    if(in.has("code")){
      String code=in.optString("code","").trim();
      if(!ProtectLogic.validCode(code)){ok=false;message="The code must be 4 to 12 letters or numbers, with no spaces.";}
      else{
        byte[] b=new byte[16];new SecureRandom().nextBytes(b);String salt=Base64.encodeToString(b,Base64.NO_WRAP);
        prefs(c).edit().putString("salt",salt).putString("hash",ProtectLogic.hash(salt,code)).putInt("fails",0).putLong("lockUntil",0).apply();
        message="Secret code saved. From another phone, send: FRIDAY dhoondo "+code;
        ControlLog.i("finder: secret code changed");
      }
    }
    if(in.has("enabled")){
      boolean on=in.optBoolean("enabled");
      if(on&&!hasCode(c)){ok=false;message="Save a secret code first.";}
      else{prefs(c).edit().putBoolean("enabled",on).apply();if(message.isEmpty())message=on?"Find my phone is on.":"Find my phone is off.";ControlLog.i("finder: "+(on?"on":"off"));}
    }
    if(in.optBoolean("test")){ring(c,TEST_MS,null);message="Test ring for 10 seconds. Tap Stop on the screen or in the notification to end it sooner.";}
    if(in.optBoolean("stop")){stop(c);message="Stopped.";}
    JSONObject out=new JSONObject();
    try{out.put("ok",ok).put("message",message).put("finder",state(c));}catch(JSONException ignored){}
    return out;
  }

  /** Every incoming SMS passes here (see FinderReceiver). Returns true when it was a correct find request. */
  static boolean onSms(Context c,String sender,String body){
    String code=ProtectLogic.findCode(body);
    if(code==null)return false;
    if(!enabled(c)){ControlLog.i("finder: find SMS ignored, feature is off");return false;}
    SharedPreferences p=prefs(c);long now=System.currentTimeMillis();
    if(p.getLong("lockUntil",0)>now){ControlLog.i("finder: find SMS ignored, locked after wrong codes");return false;}
    boolean right=ProtectLogic.sameHash(ProtectLogic.hash(p.getString("salt",""),code),p.getString("hash",""));
    if(!right){
      int fails=now-p.getLong("failsSince",0)>FAIL_WINDOW?1:p.getInt("fails",0)+1;
      SharedPreferences.Editor e=p.edit().putInt("fails",fails);if(fails==1)e.putLong("failsSince",now);
      if(fails>=MAX_FAILS){e.putLong("lockUntil",now+LOCK_MS).putInt("fails",0);ControlLog.i("finder: "+MAX_FAILS+" wrong codes, codes ignored for an hour");}
      e.apply();ControlLog.i("finder: wrong code");return false;
    }
    p.edit().putInt("fails",0).putLong("lastFound",now).apply();
    ControlLog.i("finder: correct code, ringing and sending location");
    ring(c,RING_MS,sender);
    locate(c,l->reply(c,sender,l));
    return true;
  }

  // ---- Ringing: alarm sound at full alarm volume, then the alarm volume goes back to what it was.
  private static MediaPlayer player;private static Vibrator vibrator;private static PowerManager.WakeLock wake;
  private static boolean active;private static int savedVolume=-1;private static final Handler main=new Handler(Looper.getMainLooper());
  private static final Runnable timeUp=()->stopRinging(null);
  static synchronized boolean ringing(){return active;}

  static void ring(Context c,long ms,String sender){
    Context app=c.getApplicationContext();
    main.post(()->{
      startRinging(app,ms);
      showScreen(app,sender);
    });
  }
  private static synchronized void startRinging(Context c,long ms){
    main.removeCallbacks(timeUp);main.postDelayed(timeUp,ms);
    if(active)return;
    active=true;
    try{
      PowerManager pm=c.getSystemService(PowerManager.class);
      wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"friday:finder");wake.acquire(ms+5000);
    }catch(RuntimeException ignored){wake=null;}
    AudioManager audio=c.getSystemService(AudioManager.class);
    try{if(audio!=null){savedVolume=audio.getStreamVolume(AudioManager.STREAM_ALARM);audio.setStreamVolume(AudioManager.STREAM_ALARM,audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),0);}}
    catch(RuntimeException e){savedVolume=-1;}
    try{
      Uri uri=RingtoneManager.getActualDefaultRingtoneUri(c,RingtoneManager.TYPE_ALARM);
      if(uri==null)uri=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
      if(uri==null)uri=Settings.System.DEFAULT_NOTIFICATION_URI;
      MediaPlayer mp=new MediaPlayer();
      // Alarm sound: plays even when the phone is on silent or vibrate.
      mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
      mp.setDataSource(c,uri);mp.setLooping(true);mp.prepare();mp.start();player=mp;
    }catch(Exception e){ControlLog.i("finder: alarm sound failed ("+e.getClass().getSimpleName()+")");player=null;}
    try{
      vibrator=c.getSystemService(Vibrator.class);
      if(vibrator!=null&&vibrator.hasVibrator())vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0,900,500},0));else vibrator=null;
    }catch(RuntimeException e){vibrator=null;}
    appContext=c;
  }
  private static Context appContext;
  /** Stop button, notification action or time up. */
  static void stop(Context c){main.post(()->stopRinging(c));}
  private static synchronized void stopRinging(Context c){
    main.removeCallbacks(timeUp);active=false;
    Context ctx=c!=null?c.getApplicationContext():appContext;
    try{if(player!=null){player.stop();player.release();}}catch(RuntimeException ignored){}player=null;
    try{if(vibrator!=null)vibrator.cancel();}catch(RuntimeException ignored){}vibrator=null;
    if(ctx!=null&&savedVolume>=0){try{ctx.getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_ALARM,savedVolume,0);}catch(RuntimeException ignored){}}
    savedVolume=-1;
    try{if(wake!=null&&wake.isHeld())wake.release();}catch(RuntimeException ignored){}wake=null;
    if(ctx!=null)try{ctx.getSystemService(NotificationManager.class).cancel(NOTIFICATION);}catch(RuntimeException ignored){}
    FinderActivity.closeAll();
  }

  /** Full-screen "Phone found" screen with a big Stop button, also on the lock screen. */
  private static void showScreen(Context c,String sender){
    Intent screen=new Intent(c,FinderActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_NO_USER_ACTION);
    PendingIntent tap=PendingIntent.getActivity(c,NOTIFICATION,screen,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop=PendingIntent.getBroadcast(c,NOTIFICATION,new Intent(c,ProtectReceiver.class).setAction(ProtectReceiver.STOP_RING),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    NotificationManager m=c.getSystemService(NotificationManager.class);
    NotificationChannel ch=new NotificationChannel(CHANNEL,"Find my phone",NotificationManager.IMPORTANCE_HIGH);
    ch.setDescription("Rings when someone sends your secret Find my phone code.");ch.setSound(null,null);
    m.createNotificationChannel(ch);
    String text=sender==null?"Test ring":"Find my phone: someone sent your secret code. Tap Stop when you have the phone.";
    Notification n=new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setContentTitle("FRIDAY: phone dhoonda ja raha hai").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
      .setCategory(Notification.CATEGORY_ALARM).setPriority(Notification.PRIORITY_MAX).setContentIntent(tap).setFullScreenIntent(tap,true)
      .addAction(new Notification.Action.Builder(null,"Stop",stop).build()).setOngoing(true).setTimeoutAfter(RING_MS+5000).build();
    try{m.notify(NOTIFICATION,n);}catch(RuntimeException e){ControlLog.i("finder: notification failed");}
    if(MainActivity.visible||Settings.canDrawOverlays(c)){try{c.startActivity(screen);}catch(RuntimeException ignored){}}
  }

  // ---- Location reply
  /** A fresh fix within 25 seconds when Android can give one, otherwise the newest location it has (or null). */
  static void locate(Context c,Consumer<Location> done){
    Location last=Prayer.lastKnown(c);
    LocationManager lm=c.getSystemService(LocationManager.class);
    if(lm==null||Build.VERSION.SDK_INT<30||!Sos.granted(c)){done.accept(last);return;}
    String provider=lm.isProviderEnabled(LocationManager.GPS_PROVIDER)?LocationManager.GPS_PROVIDER:lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)?LocationManager.NETWORK_PROVIDER:null;
    if(provider==null){done.accept(last);return;}
    final boolean[] answered={false};CancellationSignal cancel=new CancellationSignal();
    Runnable timeout=()->{if(answered[0])return;answered[0]=true;cancel.cancel();done.accept(Prayer.lastKnown(c));};
    main.postDelayed(timeout,25_000);
    try{
      lm.getCurrentLocation(provider,cancel,c.getMainExecutor(),l->{if(answered[0])return;answered[0]=true;main.removeCallbacks(timeout);done.accept(l!=null?l:last);});
    }catch(SecurityException|IllegalArgumentException e){if(!answered[0]){answered[0]=true;main.removeCallbacks(timeout);done.accept(last);}}
  }
  static String message(Context c,Location l){
    StringBuilder b=new StringBuilder("FRIDAY: Phone mil gaya, ab zor se baj raha hai. ");
    if(l!=null){
      long mins=Math.max(0,(System.currentTimeMillis()-l.getTime())/60_000);
      b.append("Location: https://maps.google.com/?q=").append(String.format(Locale.ROOT,"%.5f,%.5f",l.getLatitude(),l.getLongitude()));
      if(l.hasAccuracy())b.append(" (").append(Math.round(l.getAccuracy())).append(" m tak sahi)");
      if(mins>=3)b.append(", ").append(mins).append(" min pehle ki");
      b.append(". ");
    }else b.append("Location nahi mil saki (location band hai ya FRIDAY ko ijazat nahi). ");
    try{
      BatteryManager bm=c.getSystemService(BatteryManager.class);int pct=bm==null?-1:bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
      if(pct>=0&&pct<=100)b.append("Battery ").append(pct).append("%.");
    }catch(RuntimeException ignored){}
    return b.toString().trim();
  }
  private static void reply(Context c,String to,Location l){
    if(to==null||to.trim().isEmpty())return;
    if(c.checkSelfPermission(Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED){ControlLog.i("finder: no SMS permission, location not sent");return;}
    try{
      SmsManager manager=Build.VERSION.SDK_INT>=31?c.getSystemService(SmsManager.class):SmsManager.getDefault();
      manager.sendMultipartTextMessage(to,null,manager.divideMessage(message(c,l)),null,null);
      ControlLog.i("finder: location SMS sent"+(l==null?" (without location)":""));
    }catch(RuntimeException e){ControlLog.i("finder: SMS failed ("+e.getClass().getSimpleName()+")");}
  }
}
