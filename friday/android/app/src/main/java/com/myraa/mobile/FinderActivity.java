package com.myraa.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/** Find my phone: the screen shown while the phone rings, on the lock screen too, with one big Stop button. */
public final class FinderActivity extends Activity {
  private static WeakReference<FinderActivity> current=new WeakReference<>(null);
  static void closeAll(){FinderActivity a=current.get();if(a!=null)a.runOnUiThread(a::finish);}

  @Override protected void onCreate(Bundle saved){
    super.onCreate(saved);
    if(Build.VERSION.SDK_INT>=27){setShowWhenLocked(true);setTurnScreenOn(true);}
    else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    current=new WeakReference<>(this);
    if(!PhoneFinder.ringing()){finish();return;}
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);
    root.setBackgroundColor(Color.rgb(10,14,28));int pad=dp(28);root.setPadding(pad,pad,pad,pad);
    root.addView(label("Phone mil gaya?",30,Color.WHITE,true));
    TextView sub=label("FRIDAY is ringing this phone because someone sent its secret Find my phone code.",16,Color.rgb(200,205,225),false);
    LinearLayout.LayoutParams lp=wrap();lp.topMargin=dp(14);root.addView(sub,lp);
    Button stop=new Button(this);stop.setText("Stop");stop.setTextColor(Color.WHITE);stop.setAllCaps(false);stop.setTextSize(22);
    GradientDrawable bg=new GradientDrawable();bg.setCornerRadius(dp(60));bg.setColor(Color.rgb(229,57,53));stop.setBackground(bg);
    stop.setOnClickListener(v->{PhoneFinder.stop(this);finish();});
    LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(220),dp(90));sp.topMargin=dp(48);root.addView(stop,sp);
    setContentView(root);
  }
  @Override protected void onDestroy(){if(current.get()==this)current=new WeakReference<>(null);super.onDestroy();}

  private TextView label(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);t.setGravity(Gravity.CENTER);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
  private LinearLayout.LayoutParams wrap(){return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT);}
  private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
