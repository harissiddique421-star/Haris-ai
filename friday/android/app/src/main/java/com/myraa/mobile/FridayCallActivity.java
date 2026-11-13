package com.myraa.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The FRIDAY call screen: rings like an incoming call, shows on the lock screen; Answer and FRIDAY says it aloud. */
public final class FridayCallActivity extends Activity {
  private final Handler main=new Handler(Looper.getMainLooper());
  private Ringtone ringtone;private Vibrator vibrator;private boolean done;
  private String title="",text="";private TextView status;private LinearLayout buttons;

  @Override protected void onCreate(Bundle saved){
    super.onCreate(saved);
    if(Build.VERSION.SDK_INT>=27){setShowWhenLocked(true);setTurnScreenOn(true);}
    else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    title=str(getIntent().getStringExtra("title"));text=str(getIntent().getStringExtra("text"));
    build();
    if(getIntent().getBooleanExtra("silent",false)){showAnswered(false);return;}
    startRinging();
    main.postDelayed(()->{if(!done){done=true;stopRinging();FridayCall.missed(this,title,text);finish();}},FridayCall.RING_MS);
  }

  private void build(){
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER_HORIZONTAL);
    root.setBackgroundColor(Color.rgb(10,14,28));int pad=dp(28);root.setPadding(pad,dp(90),pad,dp(60));
    TextView avatar=label("F",44,Color.WHITE,true);avatar.setGravity(Gravity.CENTER);
    GradientDrawable circle=new GradientDrawable();circle.setShape(GradientDrawable.OVAL);circle.setColor(Color.rgb(108,76,255));avatar.setBackground(circle);
    root.addView(avatar,new LinearLayout.LayoutParams(dp(110),dp(110)));
    root.addView(label("FRIDAY",30,Color.WHITE,true),wrap(dp(22)));
    status=label("is calling you…",16,Color.rgb(170,180,210),false);root.addView(status,wrap(dp(6)));
    TextView what=label(title,18,Color.WHITE,false);what.setGravity(Gravity.CENTER);root.addView(what,wrap(dp(36)));
    TextView more=label(text,15,Color.rgb(200,205,225),false);more.setGravity(Gravity.CENTER);more.setMaxLines(8);root.addView(more,wrap(dp(10)));
    LinearLayout.LayoutParams grow=new LinearLayout.LayoutParams(1,0,1f);root.addView(new TextView(this),grow);
    buttons=new LinearLayout(this);buttons.setOrientation(LinearLayout.HORIZONTAL);buttons.setGravity(Gravity.CENTER);
    buttons.addView(round("Decline",Color.rgb(229,57,53),v->decline()));
    buttons.addView(round("Answer",Color.rgb(46,160,67),v->answer()));
    root.addView(buttons,wrap(0));
    setContentView(root);
  }
  private void answer(){
    if(done&&ringtone==null&&vibrator==null){return;}
    done=true;stopRinging();showAnswered(true);
  }
  private void showAnswered(boolean speak){
    done=true;FridayCall.answered(this);
    status.setText(speak?"On the call":"Missed call");
    buttons.removeAllViews();
    buttons.addView(round("Close",Color.rgb(90,96,120),v->finish()));
    buttons.addView(round("Talk to FRIDAY",Color.rgb(108,76,255),v->{
      startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}));
    if(speak)new Announcer(this).sayAnswered(title+". "+text);
  }
  private void decline(){done=true;stopRinging();FridayCall.answered(this);finish();}

  private void startRinging(){
    try{
      Uri uri=RingtoneManager.getActualDefaultRingtoneUri(this,RingtoneManager.TYPE_RINGTONE);
      if(uri==null)uri=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
      ringtone=RingtoneManager.getRingtone(this,uri);
      if(ringtone!=null){
        // Alarm sound: the owner asked FRIDAY to call about this, so it rings even when the phone is on silent.
        ringtone.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        if(Build.VERSION.SDK_INT>=28)ringtone.setLooping(true);
        ringtone.play();
      }
    }catch(RuntimeException e){ControlLog.i("friday call: ringtone failed");}
    try{
      vibrator=getSystemService(Vibrator.class);
      if(vibrator!=null&&vibrator.hasVibrator())vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0,800,700},0));else vibrator=null;
    }catch(RuntimeException e){vibrator=null;}
  }
  private void stopRinging(){
    try{if(ringtone!=null)ringtone.stop();}catch(RuntimeException ignored){}ringtone=null;
    try{if(vibrator!=null)vibrator.cancel();}catch(RuntimeException ignored){}vibrator=null;
  }
  @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);stopRinging();super.onDestroy();}

  private TextView label(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
  private Button round(String s,int color,android.view.View.OnClickListener l){
    Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setTextSize(16);
    GradientDrawable bg=new GradientDrawable();bg.setCornerRadius(dp(40));bg.setColor(color);b.setBackground(bg);b.setOnClickListener(l);
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(150),dp(64));p.setMargins(dp(10),0,dp(10),0);b.setLayoutParams(p);return b;
  }
  private LinearLayout.LayoutParams wrap(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT);p.topMargin=top;return p;}
  private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
  private static String str(String s){return s==null?"":s;}
}
