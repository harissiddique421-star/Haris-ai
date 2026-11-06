package com.myraa.mobile;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.provider.Settings;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.*;

/** Keeps only an explicitly started Live call available while opening other apps. */
public final class LiveVoiceService extends Service {
  public static final String STOP = "com.myraa.mobile.STOP_LIVE";
  public static final String SHARE_SCREEN = "com.myraa.mobile.SHARE_SCREEN", STOP_SHARE = "com.myraa.mobile.STOP_SHARE";
  public static final String TOGGLE_MIC = "com.myraa.mobile.TOGGLE_MIC";
  public static volatile boolean running,overlayVisible;
  /** What the Live call reports for the companion (set from the app; the overlay only displays it). */
  static volatile String companionState="connecting",companionEmotion="";
  static volatile float companionLevel;
  private static volatile LiveVoiceService self;
  private WindowManager windows;
  private LinearLayout chip;
  private CompanionView face;
  private TextView status,mic;
  private WindowManager.LayoutParams layout;
  @Override public IBinder onBind(Intent intent) { return null; }
  @Override public int onStartCommand(Intent intent, int flags, int id) {
    if (intent != null && STOP.equals(intent.getAction())) { end(); return START_NOT_STICKY; }
    if (intent != null && STOP_SHARE.equals(intent.getAction())) { ScreenCapture.stop(); return START_NOT_STICKY; }
    boolean share = intent != null && SHARE_SCREEN.equals(intent.getAction()) && running;
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel("live_voice", "FRIDAY Live voice", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop = PendingIntent.getService(this,1,new Intent(this,LiveVoiceService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification notification = new Notification.Builder(this,"live_voice").setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentTitle("FRIDAY Live · microphone on").setContentText("Speak while using other apps. Stop ends the call.")
      .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_CALL)
      .addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
    int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK|(share?ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION:0);
    if (Build.VERSION.SDK_INT >= 30) startForeground(42,notification,types);
    else startForeground(42,notification);
    if (!running) ControlLog.i("call service: started");
    running = true;
    if (share) {
      Intent data = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("data", Intent.class) : intent.getParcelableExtra("data");
      if (data != null) { try { ScreenCapture.start(this, intent.getIntExtra("resultCode", 0), data); } catch (RuntimeException e) { ScreenCapture.stop(); } }
      return START_NOT_STICKY;
    }
    if (Settings.canDrawOverlays(this) && chip == null) showCompanion();
    return START_NOT_STICKY;
  }
  /** The animated companion that replaces the plain "mic on / Stop" chip while FRIDAY is in the background. */
  private void showCompanion() {
    try {
      float d=getResources().getDisplayMetrics().density;
      windows=getSystemService(WindowManager.class);
      chip=new LinearLayout(this);chip.setOrientation(LinearLayout.VERTICAL);chip.setGravity(Gravity.CENTER_HORIZONTAL);
      face=new CompanionView(this);chip.addView(face);
      LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
      mic=pill("Mic",0xee1c2233,0xff38b6ff,d);mic.setOnClickListener(v->sendBroadcast(new Intent(TOGGLE_MIC).setPackage(getPackageName())));
      TextView stop=pill("\u25A0 Stop",0xeee5484d,0xffffffff,d);stop.setContentDescription("Stop FRIDAY Live");stop.setOnClickListener(v->end());
      LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);gap.setMargins((int)(3*d),0,(int)(3*d),0);
      row.addView(mic,gap);row.addView(stop,new LinearLayout.LayoutParams(gap));chip.addView(row);
      status=new TextView(this);status.setTextSize(10);status.setTextColor(0xffffffff);status.setPadding((int)(7*d),(int)(2*d),(int)(7*d),(int)(2*d));
      status.setBackground(round(0xcc10131c,0,d));status.setVisibility(View.GONE);
      LinearLayout.LayoutParams top=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);top.topMargin=(int)(3*d);chip.addView(status,top);
      layout=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
      layout.gravity=Gravity.TOP|Gravity.START;
      SharedPreferences p=getSharedPreferences("friday.companion",MODE_PRIVATE);
      int screenW=getResources().getDisplayMetrics().widthPixels;
      layout.x=p.getInt("x",screenW-(int)(110*d));layout.y=p.getInt("y",(int)(90*d));
      dragging(face,p);
      windows.addView(chip,layout);overlayVisible=true;self=this;
      chip.setVisibility(MainActivity.visible?View.GONE:View.VISIBLE);
      refresh();
    } catch (RuntimeException e) {chip=null;face=null;overlayVisible=false;}
  }
  private TextView pill(String text,int bg,int fg,float d){
    TextView v=new TextView(this);v.setText(text);v.setTextSize(12);v.setTextColor(fg);v.setGravity(Gravity.CENTER);
    v.setMinHeight((int)(34*d));v.setMinWidth((int)(48*d));v.setPadding((int)(10*d),0,(int)(10*d),0);
    v.setBackground(round(bg,0x8038b6ff,d));return v;
  }
  private static android.graphics.drawable.GradientDrawable round(int color,int ring,float d){
    android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(color);g.setCornerRadius(17*d);if(ring!=0)g.setStroke((int)Math.max(1,d),ring);return g;
  }
  /** Drag the companion anywhere; a short tap opens FRIDAY (never stops the call). The place is remembered. */
  private void dragging(View handle,SharedPreferences p){
    float slop=android.view.ViewConfiguration.get(this).getScaledTouchSlop();
    final float[] start=new float[4];final boolean[] moved={false};
    handle.setOnTouchListener((v,e)->{
      switch(e.getActionMasked()){
        case MotionEvent.ACTION_DOWN:start[0]=e.getRawX();start[1]=e.getRawY();start[2]=layout.x;start[3]=layout.y;moved[0]=false;return true;
        case MotionEvent.ACTION_MOVE:{
          float dx=e.getRawX()-start[0],dy=e.getRawY()-start[1];
          if(!moved[0]&&Math.hypot(dx,dy)<slop)return true;
          moved[0]=true;int w=getResources().getDisplayMetrics().widthPixels,h=getResources().getDisplayMetrics().heightPixels;
          layout.x=(int)Math.max(0,Math.min(w-chip.getWidth(),start[2]+dx));layout.y=(int)Math.max(0,Math.min(h-chip.getHeight(),start[3]+dy));
          try{windows.updateViewLayout(chip,layout);}catch(RuntimeException ignored){}
          return true;
        }
        case MotionEvent.ACTION_UP:
          if(moved[0])p.edit().putInt("x",layout.x).putInt("y",layout.y).apply();
          else{v.performClick();startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
          return true;
        default:return true;
      }
    });
  }
  /** Mic button and the short status line follow the reported state (status only when it matters). */
  private void refresh(){
    if(chip==null)return;
    String s=companionState;boolean muted="muted".equals(s);
    mic.setText(muted?"Mic off":"Mic on");mic.setTextColor(muted?0xffff8a8d:0xff38b6ff);
    mic.setContentDescription(muted?"Microphone is muted. Tap to unmute.":"Microphone is on. Tap to mute.");
    String line="connecting".equals(s)?"Connecting\u2026":"error".equals(s)?"Something went wrong":muted?"Muted":"";
    status.setText(line);status.setVisibility(line.isEmpty()?View.GONE:View.VISIBLE);
    face.setContentDescription("FRIDAY, "+(muted?"microphone muted":s)+". Tap to open FRIDAY, drag to move.");
  }
  /** Called by the app (any thread) whenever the Live call's state, tone or voice level changes. */
  static void companion(String state,String emotion,float level){
    boolean changed=!state.equals(companionState)||!emotion.equals(companionEmotion);
    companionState=state;companionEmotion=emotion;companionLevel=level;
    LiveVoiceService s=self;
    if(changed&&s!=null&&s.chip!=null)s.chip.post(()->{s.refresh();if(s.face!=null)s.face.invalidate();});
  }
  /** Hidden while FRIDAY's own screen is open (it shows the call there), back when the user leaves. */
  static void appVisible(boolean visible){
    LiveVoiceService s=self;
    if(s!=null&&s.chip!=null)s.chip.post(()->{if(s.chip==null)return;s.chip.setVisibility(visible?View.GONE:View.VISIBLE);if(!visible&&s.face!=null)s.face.invalidate();});
  }
  private void end() { ControlLog.i("call service: stopped by Stop or by closing FRIDAY from recents"); ScreenCapture.stop(); running=false; sendBroadcast(new Intent(STOP).setPackage(getPackageName())); stopForeground(true); stopSelf(); }
  @Override public void onTaskRemoved(Intent root) { end(); }
  @Override public void onDestroy() { ScreenCapture.stop(); if(chip!=null&&windows!=null){try{windows.removeView(chip);}catch(RuntimeException ignored){}}chip=null;face=null;if(self==this)self=null;companionState="connecting";companionEmotion="";companionLevel=0;overlayVisible=false;if(running)ControlLog.i("call service: stopped");running=false; super.onDestroy(); }
}
