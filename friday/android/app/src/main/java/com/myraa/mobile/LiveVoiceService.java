package com.myraa.mobile;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.provider.Settings;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.*;

/** Keeps only an explicitly started Live call available while opening other apps. */
public final class LiveVoiceService extends Service {
  public static final String STOP = "com.myraa.mobile.STOP_LIVE";
  public static volatile boolean running,overlayVisible;
  private WindowManager windows;
  private LinearLayout chip;
  @Override public IBinder onBind(Intent intent) { return null; }
  @Override public int onStartCommand(Intent intent, int flags, int id) {
    if (intent != null && STOP.equals(intent.getAction())) { end(); return START_NOT_STICKY; }
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel("live_voice", "MYRAA Live voice", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop = PendingIntent.getService(this,1,new Intent(this,LiveVoiceService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification notification = new Notification.Builder(this,"live_voice").setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentTitle("MYRAA Live · microphone on").setContentText("Speak while using other apps. Stop ends the call.")
      .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_CALL)
      .addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
    if (Build.VERSION.SDK_INT >= 30) startForeground(42,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    else startForeground(42,notification);
    running = true;
    if (Settings.canDrawOverlays(this) && chip == null) {
      try {
        windows=getSystemService(WindowManager.class);chip=new LinearLayout(this);chip.setPadding(6,4,6,4);chip.setBackgroundColor(0xee17252a);
        Button back=new Button(this);back.setText("MYRAA · mic on");back.setTextSize(11);back.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        Button end=new Button(this);end.setText("Stop");end.setTextSize(11);end.setOnClickListener(v->end());chip.addView(back);chip.addView(end);
        WindowManager.LayoutParams layout=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        layout.gravity=Gravity.TOP|Gravity.END;layout.y=70;windows.addView(chip,layout);overlayVisible=true;
      } catch (RuntimeException ignored) {chip=null;overlayVisible=false;}
    }
    return START_NOT_STICKY;
  }
  private void end() { running=false; sendBroadcast(new Intent(STOP).setPackage(getPackageName())); stopForeground(true); stopSelf(); }
  @Override public void onTaskRemoved(Intent root) { end(); }
  @Override public void onDestroy() { if(chip!=null&&windows!=null){try{windows.removeView(chip);}catch(RuntimeException ignored){}}chip=null;overlayVisible=false;running=false; super.onDestroy(); }
}
