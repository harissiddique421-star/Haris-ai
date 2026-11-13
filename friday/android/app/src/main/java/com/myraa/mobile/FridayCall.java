package com.myraa.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * "FRIDAY call": FRIDAY rings the owner like an incoming call (full screen, ringtone, vibration, even on the lock
 * screen) for something important: a standing order with kind "ring" (an important message, a set time). Answer
 * and FRIDAY says what happened; decline and it stays as a missed-call notification. It cannot place a real
 * WhatsApp or SIM call, because FRIDAY lives on this same phone.
 */
final class FridayCall {
  private FridayCall(){}
  static final String CHANNEL="friday_calls";
  static final int NOTIFICATION=4471;
  static final long RING_MS=45_000;

  static void ring(Context c,String title,String text){
    Intent screen=new Intent(c,FridayCallActivity.class).putExtra("title",Orders.clip(title,80)).putExtra("text",Orders.clip(text,400))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_NO_USER_ACTION);
    PendingIntent tap=PendingIntent.getActivity(c,NOTIFICATION,screen,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    NotificationManager m=c.getSystemService(NotificationManager.class);
    NotificationChannel ch=new NotificationChannel(CHANNEL,"FRIDAY calls",NotificationManager.IMPORTANCE_HIGH);
    ch.setDescription("FRIDAY rings you like a call for things you asked her to call you about.");
    ch.setSound(null,null); // the call screen plays the ringtone itself
    m.createNotificationChannel(ch);
    Notification n=new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.sym_call_incoming)
      .setContentTitle("FRIDAY is calling").setContentText(Orders.clip(title,80)).setStyle(new Notification.BigTextStyle().bigText(Orders.clip(title,80)+"\n"+Orders.clip(text,300)))
      .setCategory(Notification.CATEGORY_CALL).setPriority(Notification.PRIORITY_MAX).setContentIntent(tap).setFullScreenIntent(tap,true)
      .setAutoCancel(true).setTimeoutAfter(10*60_000L).build();
    try{m.notify(NOTIFICATION,n);}catch(RuntimeException e){ControlLog.i("friday call: notification failed");}
    // When FRIDAY may open over other apps, show the call screen straight away (the full-screen alert does it on a locked phone).
    if(MainActivity.visible||Settings.canDrawOverlays(c)){try{c.startActivity(screen);}catch(RuntimeException ignored){}}
    ControlLog.i("friday call: ringing");
  }
  /** The ringing call was answered or declined: the call notification is no longer needed. */
  static void answered(Context c){try{c.getSystemService(NotificationManager.class).cancel(NOTIFICATION);}catch(RuntimeException ignored){}}
  /** Not answered: turn the call into a missed-call notice the owner can open later. */
  static void missed(Context c,String title,String text){
    NotificationManager m=c.getSystemService(NotificationManager.class);
    Intent screen=new Intent(c,FridayCallActivity.class).putExtra("title",title).putExtra("text",text).putExtra("silent",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent tap=PendingIntent.getActivity(c,NOTIFICATION+1,screen,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    try{m.notify(NOTIFICATION,new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.sym_call_missed).setContentTitle("Missed FRIDAY call")
      .setContentText(title).setStyle(new Notification.BigTextStyle().bigText(title+"\n"+text)).setContentIntent(tap).setAutoCancel(true).build());}catch(RuntimeException ignored){}
  }
}
