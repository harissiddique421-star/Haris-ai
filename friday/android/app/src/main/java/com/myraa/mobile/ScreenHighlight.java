package com.myraa.mobile;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

/**
 * Visual guidance: a glowing frame with a short label drawn over the button FRIDAY suggests pressing next,
 * on top of whatever app is open, for a few seconds. It cannot be touched (taps go through to the app),
 * presses nothing itself, and needs the "Display over other apps" permission FRIDAY already asks for;
 * without it, FRIDAY only says which button to press.
 */
final class ScreenHighlight {
  private ScreenHighlight(){}
  private static final Handler main=new Handler(Looper.getMainLooper());
  private static View shown;

  static boolean available(Context c){return Settings.canDrawOverlays(c);}

  /** Shows the frame around bounds (screen pixels) for ms milliseconds; false when overlays are not allowed. */
  static boolean show(Context context,Rect bounds,String label,long ms){
    if(bounds==null||bounds.isEmpty()||!available(context))return false;
    Context app=context.getApplicationContext();
    main.post(()->{
      hide();
      WindowManager wm=app.getSystemService(WindowManager.class);
      Frame v=new Frame(app,new Rect(bounds),label);
      WindowManager.LayoutParams lp=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT);
      lp.x=0;lp.y=0;lp.gravity=android.view.Gravity.TOP|android.view.Gravity.START;
      try{wm.addView(v,lp);shown=v;}catch(RuntimeException e){ControlLog.i("screen guide: overlay refused ("+e.getClass().getSimpleName()+")");return;}
      main.postDelayed(()->{if(shown==v)hide();},ms);
    });
    return true;
  }

  static void hide(){
    View v=shown;shown=null;if(v==null)return;
    try{v.getContext().getSystemService(WindowManager.class).removeView(v);}catch(RuntimeException ignored){}
  }

  private static final class Frame extends View {
    private final Rect target;private final String label;
    private final Paint ring=new Paint(Paint.ANTI_ALIAS_FLAG),tag=new Paint(Paint.ANTI_ALIAS_FLAG),ink=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long started=System.currentTimeMillis();
    Frame(Context c,Rect target,String label){
      super(c);this.target=target;this.label=label==null?"":label;
      float d=c.getResources().getDisplayMetrics().density;
      ring.setStyle(Paint.Style.STROKE);ring.setStrokeWidth(4*d);ring.setColor(0xFFFFC107);
      tag.setColor(0xE6202124);ink.setColor(Color.WHITE);ink.setTextSize(15*d);
    }
    @Override protected void onDraw(Canvas c){
      // The view sits at the top-left of the screen, so its coordinates are screen coordinates.
      int[] at=new int[2];getLocationOnScreen(at);
      float d=getResources().getDisplayMetrics().density;
      float pulse=(float)(0.5+0.5*Math.sin((System.currentTimeMillis()-started)/180.0));
      float grow=(4+6*pulse)*d;
      RectF r=new RectF(target.left-at[0]-grow,target.top-at[1]-grow,target.right-at[0]+grow,target.bottom-at[1]+grow);
      ring.setAlpha(150+(int)(105*pulse));
      c.drawRoundRect(r,12*d,12*d,ring);
      String text="Yahan dabayein: "+(label.length()>30?label.substring(0,30)+"…":label);
      float tw=ink.measureText(text),pad=8*d,th=ink.getTextSize();
      float x=Math.max(pad,Math.min(r.left,getWidth()-tw-3*pad));
      float y=r.top-pad>th+3*pad?r.top-pad:r.bottom+th+2*pad;
      c.drawRoundRect(new RectF(x-pad,y-th-pad,x+tw+pad,y+pad),8*d,8*d,tag);
      c.drawText(text,x,y-pad/3,ink);
      postInvalidateOnAnimation();
    }
  }
}
