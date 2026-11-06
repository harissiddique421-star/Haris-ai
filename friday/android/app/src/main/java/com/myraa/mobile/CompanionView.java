package com.myraa.mobile;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

/**
 * FRIDAY's small chibi companion for the Live overlay: dark long hair, blue eyes, black hoodie and
 * glowing blue cat-ear headphones, drawn with plain Canvas shapes (no images, no extra library).
 * It only shows what the Live call reports: the state (connecting, listening, speaking, thinking,
 * muted, error), the tone (happy) and the real voice level from the audio analysers. It redraws at
 * about 30 frames a second while it moves and 15 when it is calm, and stops when it is detached.
 */
final class CompanionView extends View {
  private final float d;
  private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),stroke=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path=new Path();
  private final RectF r=new RectF();
  private final long born=SystemClock.uptimeMillis();
  private long nextBlink=born+2500,blinkStart=-1;
  private float shownLevel;
  private boolean attached;

  private static final int NEON=0xff38b6ff,NEON_SOFT=0xff7fd3ff,HAIR=0xff1b1d2b,HAIR_FRONT=0xff23263a,SKIN=0xffffe4d6,HOODIE=0xff14161e,HOOD=0xff1f2231,DARK=0xff2a2e45,IRIS=0xff3a8dff,PUPIL=0xff173a8a,MOUTH=0xffb4505a;

  CompanionView(Context c){
    super(c);d=c.getResources().getDisplayMetrics().density;
    stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeCap(Paint.Cap.ROUND);stroke.setStrokeJoin(Paint.Join.ROUND);
    setContentDescription("FRIDAY. Tap to open FRIDAY, drag to move.");
  }
  @Override protected void onMeasure(int w,int h){setMeasuredDimension((int)(88*d),(int)(104*d));}
  @Override protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;invalidate();}
  @Override protected void onDetachedFromWindow(){attached=false;super.onDetachedFromWindow();}

  private float x(float v){return v*d;}

  @Override protected void onDraw(Canvas c){
    long now=SystemClock.uptimeMillis();float t=(now-born)/1000f;
    String state=LiveVoiceService.companionState;boolean happy="happy".equals(LiveVoiceService.companionEmotion);
    boolean speaking="speaking".equals(state),listening="listening".equals(state),muted="muted".equals(state),thinking="thinking".equals(state)||"connecting".equals(state),error="error".equals(state);
    // The level eases toward the real one so the mouth and bars move smoothly between updates.
    float target=muted?0:Math.max(0,Math.min(1,LiveVoiceService.companionLevel));
    shownLevel+=(target-shownLevel)*0.35f;
    float level=shownLevel;

    // Blink every few seconds (not while smiling with closed eyes).
    if(blinkStart<0&&now>=nextBlink){blinkStart=now;}
    float open=1;
    if(blinkStart>=0){float p=(now-blinkStart)/150f;if(p>=1){blinkStart=-1;nextBlink=now+2200+(long)(Math.random()*3000);}else open=Math.abs(1-2*p);}
    if(muted)open=Math.min(open,0.55f);

    float breathe=(float)Math.sin(t*2*Math.PI/3.6)*0.8f;
    float bob=speaking?level*1.6f*(float)Math.sin(t*14):0;
    c.save();c.translate(0,x(breathe+bob));

    // Soft glow behind her; stronger while she listens or speaks. No panel.
    float glow=error?0.10f:muted?0.08f:0.16f+(listening||speaking?0.25f*level:0);
    fill.setShader(new RadialGradient(x(44),x(54),x(44),new int[]{withAlpha(error?0xffffb02e:NEON,glow),0x0038b6ff},null,Shader.TileMode.CLAMP));
    c.drawCircle(x(44),x(54),x(44),fill);fill.setShader(null);

    // Long hair behind.
    fill.setColor(HAIR);r.set(x(17),x(20),x(71),x(94));c.drawRoundRect(r,x(24),x(24),fill);
    // Hoodie and hood.
    fill.setColor(HOOD);r.set(x(22),x(70),x(66),x(88));c.drawOval(r,fill);
    fill.setColor(HOODIE);path.reset();path.moveTo(x(14),x(106));path.cubicTo(x(14),x(86),x(24),x(78),x(44),x(78));path.cubicTo(x(64),x(78),x(74),x(86),x(74),x(106));path.close();c.drawPath(path,fill);
    stroke.setStrokeWidth(x(1.3f));stroke.setColor(withAlpha(NEON,0.85f));
    c.drawLine(x(36),x(82),x(34),x(93),stroke);c.drawLine(x(52),x(82),x(54),x(93),stroke);
    fill.setColor(NEON_SOFT);c.drawCircle(x(34),x(94),x(1.4f),fill);c.drawCircle(x(54),x(94),x(1.4f),fill);
    // Long hair falling over the hoodie on both sides.
    fill.setColor(HAIR);
    path.reset();path.moveTo(x(18),x(62));path.cubicTo(x(16),x(80),x(20),x(92),x(27),x(101));path.cubicTo(x(27),x(90),x(30),x(80),x(29),x(66));path.close();c.drawPath(path,fill);
    path.reset();path.moveTo(x(70),x(62));path.cubicTo(x(72),x(80),x(68),x(92),x(61),x(101));path.cubicTo(x(61),x(90),x(58),x(80),x(59),x(66));path.close();c.drawPath(path,fill);

    // Face.
    fill.setColor(SKIN);r.set(x(25),x(32),x(63),x(71));c.drawOval(r,fill);
    fill.setColor(0x55ff8fa3);c.drawOval(x(28),x(57),x(35),x(61),fill);c.drawOval(x(53),x(57),x(60),x(61),fill);

    // Eyes.
    float look=thinking?-1.6f:0,lookX=thinking?1.2f:0;
    drawEye(c,36,52,open,happy&&!error&&!muted,look,lookX);
    drawEye(c,52,52,open,happy&&!error&&!muted,look,lookX);
    // Brows: worried when something went wrong.
    stroke.setColor(HAIR);stroke.setStrokeWidth(x(1.1f));
    if(error){c.drawLine(x(32),x(44),x(38.5f),x(42.5f),stroke);c.drawLine(x(49.5f),x(42.5f),x(56),x(44),stroke);}

    // Mouth.
    drawMouth(c,speaking,happy,muted,error,thinking,level,t);

    // Bangs in front of the face.
    fill.setColor(HAIR_FRONT);path.reset();
    path.moveTo(x(22),x(52));path.quadTo(x(20),x(24),x(44),x(23));path.quadTo(x(68),x(24),x(66),x(52));
    path.lineTo(x(61),x(40));path.lineTo(x(56),x(46));path.lineTo(x(51),x(36));path.lineTo(x(45),x(45));path.lineTo(x(40),x(35));path.lineTo(x(34),x(45));path.lineTo(x(29),x(38));path.close();
    c.drawPath(path,fill);
    stroke.setColor(0x445a6390);stroke.setStrokeWidth(x(1));r.set(x(28),x(26),x(60),x(46));c.drawArc(r,205,60,false,stroke);

    // Headphones with cat ears; they glow with the microphone while listening.
    float hp=muted?0.25f:listening?0.55f+0.45f*level:speaking?0.6f:0.45f+0.1f*(float)Math.sin(t*2);
    stroke.setColor(DARK);stroke.setStrokeWidth(x(3.6f));r.set(x(19),x(17),x(69),x(64));c.drawArc(r,195,150,false,stroke);
    stroke.setColor(withAlpha(NEON,hp));stroke.setStrokeWidth(x(1.2f));c.drawArc(r,200,140,false,stroke);
    drawEar(c,false,hp);drawEar(c,true,hp);
    drawCup(c,20,hp);drawCup(c,68,hp);
    c.restore();

    // Indicators for each state (outside the breathing so they read clearly).
    if(listening&&!happy)drawWaves(c,level,t);
    if(speaking)drawBars(c,level,t);
    if(thinking)drawDots(c,t);
    if(muted)drawMuted(c);
    if(error)drawWarning(c);

    if(attached&&getVisibility()==VISIBLE&&getWindowVisibility()==VISIBLE){
      boolean calm=!speaking&&!thinking&&level<0.03f&&blinkStart<0;
      postInvalidateDelayed(calm?66:33);
    }
  }

  private void drawEye(Canvas c,float cx,float cy,float open,boolean smiling,float look,float lookX){
    if(smiling){stroke.setColor(0xff2b2f45);stroke.setStrokeWidth(x(1.5f));r.set(x(cx-4),x(cy-2.5f),x(cx+4),x(cy+4.5f));c.drawArc(r,200,140,false,stroke);return;}
    if(open<0.18f){stroke.setColor(0xff2b2f45);stroke.setStrokeWidth(x(1.4f));r.set(x(cx-4.2f),x(cy-2),x(cx+4.2f),x(cy+3));c.drawArc(r,20,140,false,stroke);return;}
    float ry=5.4f*open;
    fill.setColor(0xffffffff);c.drawOval(x(cx-4.3f),x(cy-ry),x(cx+4.3f),x(cy+ry),fill);
    fill.setShader(new LinearGradient(0,x(cy-ry),0,x(cy+ry),0xff1f5fd6,IRIS,Shader.TileMode.CLAMP));
    c.drawOval(x(cx-3.5f+lookX),x(cy-ry+0.4f+look),x(cx+3.5f+lookX),x(cy+ry+look),fill);fill.setShader(null);
    fill.setColor(PUPIL);c.drawOval(x(cx-1.8f+lookX),x(cy-ry*0.35f+look),x(cx+1.8f+lookX),x(cy+ry*0.7f+look),fill);
    fill.setColor(0xffffffff);c.drawCircle(x(cx-1.3f+lookX),x(cy-ry*0.45f+look),x(1.2f),fill);c.drawCircle(x(cx+1.4f+lookX),x(cy+ry*0.35f+look),x(0.6f),fill);
    stroke.setColor(0xff1d2033);stroke.setStrokeWidth(x(1.4f));r.set(x(cx-4.8f),x(cy-ry-0.6f),x(cx+4.8f),x(cy+ry));c.drawArc(r,195,150,false,stroke);
  }
  private void drawMouth(Canvas c,boolean speaking,boolean happy,boolean muted,boolean error,boolean thinking,float level,float t){
    float cx=44,cy=63;
    if(speaking){
      float h=0.8f+level*5.2f,w=2.6f+level*1.8f+(happy?1:0);
      fill.setColor(MOUTH);c.drawOval(x(cx-w),x(cy-h*0.45f),x(cx+w),x(cy+h*0.55f),fill);
      if(h>2.4f){fill.setColor(0xffff9aa8);c.drawOval(x(cx-w*0.55f),x(cy+h*0.1f),x(cx+w*0.55f),x(cy+h*0.5f),fill);}
      return;
    }
    stroke.setColor(MOUTH);stroke.setStrokeWidth(x(1.3f));
    if(muted){c.drawLine(x(cx-2.4f),x(cy),x(cx+2.4f),x(cy),stroke);return;}
    if(error){r.set(x(cx-3),x(cy),x(cx+3),x(cy+4));c.drawArc(r,200,140,false,stroke);return;}
    if(thinking){fill.setColor(MOUTH);c.drawCircle(x(cx+2),x(cy),x(1.2f),fill);return;}
    if(happy){fill.setColor(MOUTH);path.reset();path.moveTo(x(cx-4),x(cy-1));path.quadTo(x(cx),x(cy+5),x(cx+4),x(cy-1));path.close();c.drawPath(path,fill);return;}
    r.set(x(cx-3),x(cy-3),x(cx+3),x(cy+1.5f));c.drawArc(r,30,120,false,stroke);
  }
  private void drawEar(Canvas c,boolean right,float glow){
    float s=right?-1:1,base=right?88:0;
    path.reset();path.moveTo(x(base+s*22),x(28));path.lineTo(x(base+s*21),x(9));path.lineTo(x(base+s*35),x(20));path.close();
    fill.setColor(DARK);c.drawPath(path,fill);
    path.reset();path.moveTo(x(base+s*24),x(25));path.lineTo(x(base+s*23.5f),x(14));path.lineTo(x(base+s*31.5f),x(21));path.close();
    fill.setColor(withAlpha(NEON,0.35f+0.65f*glow));c.drawPath(path,fill);
  }
  private void drawCup(Canvas c,float cx,float glow){
    fill.setShader(new RadialGradient(x(cx),x(50),x(9),new int[]{withAlpha(NEON,0.55f*glow),0x0038b6ff},null,Shader.TileMode.CLAMP));
    c.drawCircle(x(cx),x(50),x(9),fill);fill.setShader(null);
    fill.setColor(DARK);r.set(x(cx-4.5f),x(42),x(cx+4.5f),x(58));c.drawRoundRect(r,x(4),x(4),fill);
    stroke.setColor(withAlpha(NEON,0.4f+0.6f*glow));stroke.setStrokeWidth(x(1.2f));r.set(x(cx-3.2f),x(44.5f),x(cx+3.2f),x(55.5f));c.drawRoundRect(r,x(3),x(3),stroke);
  }
  /** Sound waves beside her head, as loud as the microphone really hears. */
  private void drawWaves(Canvas c,float level,float t){
    stroke.setStrokeWidth(x(1.4f));
    for(int i=0;i<3;i++){
      float a=Math.max(0.12f,Math.min(1,level*2.2f-i*0.25f))*(0.6f+0.4f*(float)Math.sin(t*5-i));
      stroke.setColor(withAlpha(NEON,a));
      r.set(x(70-4-i*4),x(40-4-i*4),x(70+4+i*4),x(40+4+i*4));c.drawArc(r,-45,90,false,stroke);
    }
  }
  /** A small visualizer on her hoodie that follows FRIDAY's real voice level. */
  private void drawBars(Canvas c,float level,float t){
    fill.setColor(withAlpha(NEON,0.9f));
    for(int i=0;i<5;i++){
      float shape=0.55f+0.45f*(float)Math.abs(Math.sin(t*9+i*1.3f));
      float h=1.2f+9f*level*shape;float bx=38.5f+i*2.75f;
      r.set(x(bx-0.9f),x(100-h),x(bx+0.9f),x(100));c.drawRoundRect(r,x(1),x(1),fill);
    }
  }
  private void drawDots(Canvas c,float t){
    fill.setColor(0xddffffff);r.set(x(62),x(6),x(84),x(18));c.drawRoundRect(r,x(6),x(6),fill);
    for(int i=0;i<3;i++){float p=(float)Math.max(0,Math.sin(t*6-i*0.9f));fill.setColor(withAlpha(NEON,0.35f+0.65f*p));c.drawCircle(x(67+i*6),x(12-p*1.2f),x(1.7f),fill);}
  }
  private void drawMuted(Canvas c){
    fill.setColor(0xffe5484d);c.drawCircle(x(72),x(70),x(8),fill);
    stroke.setColor(0xffffffff);stroke.setStrokeWidth(x(1.4f));
    r.set(x(70),x(65),x(74),x(72));c.drawRoundRect(r,x(2),x(2),stroke);
    r.set(x(68.5f),x(68),x(75.5f),x(74.5f));c.drawArc(r,0,180,false,stroke);
    c.drawLine(x(67.5f),x(64.5f),x(76.5f),x(75.5f),stroke);
  }
  private void drawWarning(Canvas c){
    fill.setColor(0xffffb02e);path.reset();path.moveTo(x(73),x(6));path.lineTo(x(83),x(22));path.lineTo(x(63),x(22));path.close();c.drawPath(path,fill);
    stroke.setColor(0xff3a2400);stroke.setStrokeWidth(x(1.6f));c.drawLine(x(73),x(11.5f),x(73),x(17),stroke);fill.setColor(0xff3a2400);c.drawCircle(x(73),x(19.6f),x(0.9f),fill);
  }
  private static int withAlpha(int color,float a){return (Math.round(Math.max(0,Math.min(1,a))*255)<<24)|(color&0xffffff);}
}
