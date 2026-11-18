package com.myraa.mobile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure logic of FRIDAY's motion gestures (visual intelligence phase 5, no Android classes, checked on the JVM).
 *
 * The front camera's picture is shrunk on the phone to a tiny grey grid (32 x 24); nothing is kept or sent.
 * From one grid to the next FRIDAY measures where the picture changed. A hand moving across gives a moving
 * patch of change, so its path tells a swipe (left, right, up, down) or a wave (back and forth). A hand held
 * over the lens makes the picture dark: held for about a second, that is "cover".
 *
 * Guards against accidental commands: a motion gesture counts only when the scene was calm just before it,
 * when it is clear enough (confidence at or above the threshold for the chosen sensitivity), when the whole
 * picture is not moving (the phone itself moving or being picked up), and never within 1.5 s of the last one.
 * Cover must be held, and fires once until the lens is uncovered.
 */
final class GestureLogic {
  private GestureLogic(){}

  static final int W=32,H=24,N=W*H;
  static final int PIXEL_CHANGE=28;
  static final float MOTION_MIN=0.03f,SHAKE=0.55f;
  static final long COOLDOWN_MS=1500,COVER_HOLD_MS=900,TRACK_MS=1500,CALM_MS=300,PAUSE_MS=200;

  /** One recognised gesture: swipe_left, swipe_right, swipe_up, swipe_down, wave or cover, with 0..1 confidence. */
  static final class Gesture {
    final String name;final float confidence;
    Gesture(String n,float c){name=n;confidence=c;}
    @Override public String toString(){return name+String.format(Locale.ROOT," %.2f",confidence);}
  }

  /** The confidence a gesture needs: high sensitivity accepts less clear gestures, low wants very clear ones. */
  static float threshold(String sensitivity){
    if("high".equals(sensitivity))return 0.55f;
    if("low".equals(sensitivity))return 0.8f;
    return 0.65f;
  }

  /** What each gesture does by default (shown on the screen and spoken in the help). */
  static String command(String gesture){
    switch(gesture==null?"":gesture){
      case "swipe_right":return "next";
      case "swipe_left":return "previous";
      case "swipe_up":return "volume_up";
      case "swipe_down":return "volume_down";
      case "cover":return "pause";
      case "wave":return "resume";
      default:return "";
    }
  }

  /** Roman Urdu name of a gesture and its command, for the screen. */
  static String label(String gesture){
    switch(gesture==null?"":gesture){
      case "swipe_right":return "Right swipe: agla gaana";
      case "swipe_left":return "Left swipe: pichhla gaana";
      case "swipe_up":return "Upar swipe: awaz tez";
      case "swipe_down":return "Neeche swipe: awaz kam";
      case "cover":return "Camera dhakna: pause";
      case "wave":return "Haath hilana: play";
      default:return "";
    }
  }

  /**
   * A W x H grey grid from a camera luminance plane (rowStride bytes per row), turned upright by rotation
   * degrees (the sensor's orientation) and mirrored for the front camera so it moves like the user's own view.
   */
  static int[] grid(byte[] y,int width,int height,int rowStride,int rotation,boolean mirror){
    boolean sideways=rotation==90||rotation==270;
    int uw=sideways?height:width,uh=sideways?width:height;
    int[] g=new int[N];
    for(int gy=0;gy<H;gy++)for(int gx=0;gx<W;gx++){
      // Centre of this cell in upright coordinates, then back to the sensor's coordinates.
      int ux=(int)((gx+0.5)*uw/W),uy=(int)((gy+0.5)*uh/H);
      if(mirror)ux=uw-1-ux;
      int sx,sy;
      switch(rotation){
        case 90:sx=uy;sy=height-1-ux;break;
        case 180:sx=width-1-ux;sy=height-1-uy;break;
        case 270:sx=width-1-uy;sy=ux;break;
        default:sx=ux;sy=uy;
      }
      sx=Math.max(0,Math.min(width-1,sx));sy=Math.max(0,Math.min(height-1,sy));
      g[gy*W+gx]=y[sy*rowStride+sx]&0xFF;
    }
    return g;
  }

  /** Follows the grids one by one and says when a gesture was made. Not thread safe: feed it from one thread. */
  static final class Detector {
    private final float minConfidence;
    private int[] prev;private float baseline=-1;
    private long coverSince,lastFire=-COOLDOWN_MS,calmSince,lastMotion;
    private boolean coverFired,covered;
    private final List<float[]> track=new ArrayList<>();
    Detector(float minConfidence){this.minConfidence=minConfidence;}

    /** The newest brightness the detector uses as normal for this room (for the screen's hints). */
    float baseline(){return baseline;}

    /** One grid taken at time t (ms): the gesture it completes, or null. */
    Gesture feed(int[] g,long t){
      long sum=0;for(int v:g)sum+=v;float mean=sum/(float)N;
      if(prev==null){prev=g;baseline=mean;calmSince=t;return null;}
      int changed=0;double cx=0,cy=0;
      for(int i=0;i<N;i++)if(Math.abs(g[i]-prev[i])>PIXEL_CHANGE){changed++;cx+=i%W;cy+=i/W;}
      prev=g;
      float share=changed/(float)N;

      // Cover: the picture turns dark compared with the room, and stays dark.
      float dark=Math.max(18f,baseline*0.35f);
      if(mean<dark){
        if(!covered){covered=true;coverSince=t;track.clear();}
        if(!coverFired&&t-coverSince>=COVER_HOLD_MS&&t-lastFire>=COOLDOWN_MS){
          float c=Math.min(1f,0.7f+0.3f*(dark-mean)/dark);
          coverFired=true;
          if(c>=minConfidence){lastFire=t;return new Gesture("cover",c);}
        }
        return null;
      }
      if(covered){
        // Uncovering is not a gesture; wait for the picture to settle again.
        if(mean>baseline*0.6f){covered=false;coverFired=false;calmSince=t;track.clear();}
        return null;
      }
      if(share<0.02f)baseline=baseline*0.9f+mean*0.1f;

      // The whole picture moving: the phone moved, not a hand.
      if(share>SHAKE){track.clear();calmSince=-1;return null;}

      if(share>=MOTION_MIN){
        if(track.isEmpty()&&(calmSince<0||t-calmSince<CALM_MS))return null; // not calm before: ignore
        lastMotion=t;
        track.add(new float[]{t,(float)(cx/changed/(W-1)),(float)(cy/changed/(H-1)),share});
        if(t-track.get(0)[0]>=TRACK_MS)return finish(t);
        return null;
      }
      // A hand turning round at the end of a wave stops for a moment: the gesture ends only after a short pause.
      if(!track.isEmpty()&&t-lastMotion<PAUSE_MS)return null;
      if(calmSince<0||!track.isEmpty())calmSince=t;
      return track.isEmpty()?null:finish(t);
    }

    private Gesture finish(long t){
      Gesture g=judge(track);track.clear();calmSince=t;
      if(g==null||g.confidence<minConfidence||t-lastFire<COOLDOWN_MS)return null;
      lastFire=t;return g;
    }
  }

  /** The gesture a path of motion centres makes (each point {t, x 0..1, y 0..1, share}), or null. */
  static Gesture judge(List<float[]> p){
    if(p.size()<3)return null;
    float dur=p.get(p.size()-1)[0]-p.get(0)[0];
    if(dur<120||dur>TRACK_MS+100)return null;
    float x0=p.get(0)[1],y0=p.get(0)[2],x1=p.get(p.size()-1)[1],y1=p.get(p.size()-1)[2];
    float minX=x0,maxX=x0,travelX=0,travelY=0;int reversals=0,dir=0;float anchor=x0;
    for(int i=1;i<p.size();i++){
      float x=p.get(i)[1];
      travelX+=Math.abs(x-p.get(i-1)[1]);travelY+=Math.abs(p.get(i)[2]-p.get(i-1)[2]);
      minX=Math.min(minX,x);maxX=Math.max(maxX,x);
      float step=x-anchor;
      if(Math.abs(step)>=0.08f){int d=step>0?1:-1;if(dir!=0&&d!=dir)reversals++;dir=d;anchor=x;}
    }
    float dx=x1-x0,dy=y1-y0,spanX=maxX-minX;
    if(reversals>=2&&spanX>=0.25f)return new Gesture("wave",Math.min(1f,0.55f+0.12f*reversals+0.3f*(spanX-0.25f)));
    if(Math.abs(dx)>=0.3f&&Math.abs(dx)>=1.6f*Math.abs(dy)){
      float straight=travelX<=0?0:Math.abs(dx)/travelX;
      if(straight<0.7f)return null;
      return new Gesture(dx>0?"swipe_right":"swipe_left",Math.min(1f,0.35f+0.6f*Math.abs(dx)+0.2f*(straight-0.7f)/0.3f));
    }
    if(Math.abs(dy)>=0.3f&&Math.abs(dy)>=1.6f*Math.abs(dx)){
      float straight=travelY<=0?0:Math.abs(dy)/travelY;
      if(straight<0.7f)return null;
      return new Gesture(dy>0?"swipe_down":"swipe_up",Math.min(1f,0.35f+0.6f*Math.abs(dy)+0.2f*(straight-0.7f)/0.3f));
    }
    return null;
  }
}
