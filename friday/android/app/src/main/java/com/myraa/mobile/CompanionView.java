package com.myraa.mobile;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

/**
 * FRIDAY's companion for the Live overlay, drawn from the user's own anime artwork (one picture per
 * mood in res/drawable-nodpi, the old "Mic On" labels cut off because the real controls sit below).
 * It only shows what the Live call reports: the state (connecting, listening, speaking, thinking,
 * muted, error), the tone (happy) and the real voice level from the audio analysers. On top of the
 * pictures it adds a soft breathing motion, blinking, a mouth that opens with FRIDAY's real voice,
 * sound waves that follow the microphone and a small voice visualizer. It redraws at about
 * 30 frames a second while something moves and 15 when calm, and stops when it is hidden.
 */
final class CompanionView extends View {
  /** Size of the artwork (all mood pictures share it), in source pixels. */
  private static final float SRC_W=402,SRC_H=252;
  private static final String[] MOODS={"idle","listening","speaking","thinking","happy","muted","error"};
  private final Bitmap[] art=new Bitmap[MOODS.length];
  private final float d,scale;
  private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),stroke=new Paint(Paint.ANTI_ALIAS_FLAG),image=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
  private final RectF r=new RectF();
  private final Rect src=new Rect();
  private final long born=SystemClock.uptimeMillis();
  private long nextBlink=born+2500,blinkStart=-1,heardUntil;
  private float shownLevel;
  private boolean attached;

  private static final int NEON=0xff38b6ff,SKIN=0xfffce0d2,LASH=0xff2a1c26,MOUTH=0xffd8604f,MOUTH_IN=0xff8e2f35;
  private static final int VIEW_W=136,VIEW_H=96,ART_X=4,ART_Y=2,ART_W=128;

  CompanionView(Context c){
    super(c);d=c.getResources().getDisplayMetrics().density;scale=ART_W*d/SRC_W;
    stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeCap(Paint.Cap.ROUND);
    BitmapFactory.Options o=new BitmapFactory.Options();o.inScaled=false;
    for(int i=0;i<MOODS.length;i++){
      int id=c.getResources().getIdentifier("companion_"+MOODS[i],"drawable",c.getPackageName());
      if(id!=0)try{art[i]=BitmapFactory.decodeResource(c.getResources(),id,o);}catch(RuntimeException|OutOfMemoryError ignored){}
    }
    setContentDescription("FRIDAY. Tap to open FRIDAY, drag to move.");
  }
  @Override protected void onMeasure(int w,int h){setMeasuredDimension((int)(VIEW_W*d),(int)(VIEW_H*d));}
  @Override protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;invalidate();}
  @Override protected void onDetachedFromWindow(){attached=false;super.onDetachedFromWindow();}

  /** Source-pixel coordinates of the artwork to view pixels. */
  private float ax(float v){return ART_X*d+v*scale;}
  private float ay(float v){return ART_Y*d+v*scale;}
  private float s(float v){return v*scale;}

  @Override protected void onDraw(Canvas c){
    long now=SystemClock.uptimeMillis();float t=(now-born)/1000f;
    String state=LiveVoiceService.companionState;boolean happy="happy".equals(LiveVoiceService.companionEmotion);
    boolean speaking="speaking".equals(state),listening="listening".equals(state),muted="muted".equals(state),error="error".equals(state);
    boolean thinking="thinking".equals(state)||"connecting".equals(state);
    // The level eases toward the real one so the mouth and bars move smoothly between updates.
    float target=muted?0:Math.max(0,Math.min(1,LiveVoiceService.companionLevel));
    shownLevel+=(target-shownLevel)*0.35f;float level=shownLevel;
    // While the microphone really hears the user she switches to her "listening" pose, held a moment so it does not flicker.
    if(listening&&target>0.12f)heardUntil=now+1200;
    String mood=error?"error":muted?"muted":thinking?"thinking":speaking?"speaking":listening&&now<heardUntil?"listening":happy?"happy":"idle";
    Bitmap pic=art[indexOf(mood)];if(pic==null)pic=art[0];

    // Soft glow behind her; brighter while she listens or speaks. No panel.
    float glow=error?0.12f:muted?0.06f:0.14f+(listening||speaking?0.3f*level:0);
    fill.setShader(new RadialGradient(ax(SRC_W/2),ay(SRC_H*0.55f),s(SRC_W*0.48f),new int[]{withAlpha(error?0xffffb02e:NEON,glow),0x0038b6ff},null,Shader.TileMode.CLAMP));
    c.drawCircle(ax(SRC_W/2),ay(SRC_H*0.55f),s(SRC_W*0.48f),fill);fill.setShader(null);

    // Gentle breathing (and a small nod with her voice), scaled from the bottom so the controls below stay put.
    float breathe=1+0.012f*(float)Math.sin(t*2*Math.PI/3.6);
    float nod=speaking?level*s(3)*(float)Math.sin(t*13):0;
    c.save();c.scale(breathe,breathe,ax(SRC_W/2),ay(SRC_H));c.translate(0,nod);
    if(pic!=null){src.set(0,0,pic.getWidth(),pic.getHeight());r.set(ax(0),ay(0),ax(SRC_W),ay(SRC_H));c.drawBitmap(pic,src,r,image);}
    if("idle".equals(mood))blink(c,now);
    if("speaking".equals(mood))mouth(c,level);
    c.restore();

    if(listening)waves(c,level,t);
    if(speaking)bars(c,level,t);

    if(attached&&getVisibility()==VISIBLE&&getWindowVisibility()==VISIBLE){
      boolean calm=!speaking&&!thinking&&level<0.03f&&blinkStart<0;
      postInvalidateDelayed(calm?66:33);
    }
  }
  private static int indexOf(String mood){for(int i=0;i<MOODS.length;i++)if(MOODS[i].equals(mood))return i;return 0;}

  /** A quick blink every few seconds on the calm picture (eyes open in that artwork). */
  private void blink(Canvas c,long now){
    if(blinkStart<0&&now>=nextBlink)blinkStart=now;
    if(blinkStart<0)return;
    float p=(now-blinkStart)/170f;
    if(p>=1){blinkStart=-1;nextBlink=now+2400+(long)(Math.random()*3200);return;}
    float closed=1-Math.abs(1-2*p);if(closed<0.35f)return;
    lid(c,200,154,22,20);lid(c,271.5f,162,23,20);
  }
  private void lid(Canvas c,float cx,float cy,float rx,float ry){
    // Skin from the lid down to the blush under her eyes, so the closed eye blends into the artwork.
    fill.setShader(new RadialGradient(ax(cx),ay(cy),s(Math.max(rx,ry)),new int[]{SKIN,SKIN,0xfff8cdbd,0x00f8cdbd},new float[]{0,0.55f,0.8f,1},Shader.TileMode.CLAMP));
    c.drawOval(ax(cx-rx),ay(cy-ry),ax(cx+rx),ay(cy+ry),fill);fill.setShader(null);
    stroke.setColor(LASH);stroke.setStrokeWidth(s(3.4f));
    r.set(ax(cx-rx+1),ay(cy-ry*0.2f),ax(cx+rx-1),ay(cy+ry*0.75f));c.drawArc(r,15,150,false,stroke);
  }
  /** The open mouth in the speaking picture is covered and redrawn as wide as FRIDAY's real voice. */
  private void mouth(Canvas c,float level){
    float cx=186.5f,cy=184;
    fill.setColor(SKIN);c.drawOval(ax(cx-11),ay(cy-10),ax(cx+11),ay(cy+10),fill);
    float h=1.2f+level*13f,w=5.5f+level*3.5f;
    if(h<2.6f){stroke.setColor(MOUTH_IN);stroke.setStrokeWidth(s(2.2f));r.set(ax(cx-4.5f),ay(cy-4),ax(cx+4.5f),ay(cy+1.5f));c.drawArc(r,30,120,false,stroke);return;}
    fill.setColor(MOUTH_IN);c.drawOval(ax(cx-w),ay(cy-h/2),ax(cx+w),ay(cy+h/2),fill);
    fill.setColor(MOUTH);c.drawOval(ax(cx-w*0.6f),ay(cy),ax(cx+w*0.6f),ay(cy+h/2),fill);
  }
  /** Sound waves by her headphones, as loud as the microphone really hears. */
  private void waves(Canvas c,float level,float t){
    stroke.setStrokeWidth(1.5f*d);
    for(int i=0;i<3;i++){
      float a=Math.max(0.15f,Math.min(1,level*2.2f-i*0.25f))*(0.65f+0.35f*(float)Math.sin(t*5-i));
      stroke.setColor(withAlpha(NEON,a));float rad=(5+i*4.5f)*d;
      r.set(ax(352)-rad,ay(120)-rad,ax(352)+rad,ay(120)+rad);c.drawArc(r,-50,100,false,stroke);
    }
  }
  /** A small visualizer under her that follows FRIDAY's real voice level. */
  private void bars(Canvas c,float level,float t){
    fill.setColor(withAlpha(NEON,0.95f));float base=ay(SRC_H)+4*d;
    for(int i=0;i<7;i++){
      float shape=0.5f+0.5f*(float)Math.abs(Math.sin(t*9+i*1.1f))*(1-Math.abs(i-3)/5f);
      float h=(1.5f+11f*level*shape)*d;float bx=ax(SRC_W/2)+(i-3)*4.5f*d;
      r.set(bx-1.3f*d,base-h,bx+1.3f*d,base);c.drawRoundRect(r,d,d,fill);
    }
  }
  private static int withAlpha(int color,float a){return (Math.round(Math.max(0,Math.min(1,a))*255)<<24)|(color&0xffffff);}
}
