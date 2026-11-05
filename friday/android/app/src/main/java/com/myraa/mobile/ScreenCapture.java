package com.myraa.mobile;

import android.content.*;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.Base64;
import android.util.DisplayMetrics;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Screen share for a Live call: after the user accepts Android's own "start recording or casting"
 * prompt, sends at most one downscaled JPEG per second to the Live call. Stops when the call ends,
 * when the user taps Stop sharing, or when Android revokes the projection.
 */
final class ScreenCapture {
  interface Frames { void frame(String jpegBase64); void stopped(); }
  private static final int MAX_EDGE=960;
  private static volatile Frames listener;
  private static MediaProjection projection;
  private static VirtualDisplay display;
  private static ImageReader reader;
  private static HandlerThread thread;
  private static Handler handler;
  private static long lastFrame;

  static void setListener(Frames frames){listener=frames;}
  static synchronized boolean active(){return projection!=null;}

  static synchronized void start(Context context,int resultCode,Intent data){
    stop();
    MediaProjectionManager manager=context.getSystemService(MediaProjectionManager.class);
    projection=manager.getMediaProjection(resultCode,data);
    if(projection==null)return;
    thread=new HandlerThread("friday-screen");thread.start();handler=new Handler(thread.getLooper());
    projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){stop();}},handler);
    DisplayMetrics metrics=context.getResources().getDisplayMetrics();
    float scale=Math.min(1f,(float)MAX_EDGE/Math.max(metrics.widthPixels,metrics.heightPixels));
    int width=Math.max(2,Math.round(metrics.widthPixels*scale)&~1),height=Math.max(2,Math.round(metrics.heightPixels*scale)&~1);
    reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
    reader.setOnImageAvailableListener(r->{
      Image image=null;
      try{
        image=r.acquireLatestImage();if(image==null)return;
        long now=SystemClock.elapsedRealtime();if(now-lastFrame<1000)return;lastFrame=now;
        Frames frames=listener;if(frames==null)return;
        frames.frame(encode(image,width,height));
      }catch(RuntimeException ignored){}finally{if(image!=null)image.close();}
    },handler);
    display=projection.createVirtualDisplay("FRIDAY screen share",width,height,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,handler);
  }

  private static String encode(Image image,int width,int height){
    Image.Plane plane=image.getPlanes()[0];ByteBuffer buffer=plane.getBuffer();
    int rowPadding=plane.getRowStride()-plane.getPixelStride()*width;
    Bitmap padded=Bitmap.createBitmap(width+rowPadding/plane.getPixelStride(),height,Bitmap.Config.ARGB_8888);
    padded.copyPixelsFromBuffer(buffer);
    Bitmap cropped=Bitmap.createBitmap(padded,0,0,width,height);
    ByteArrayOutputStream out=new ByteArrayOutputStream();cropped.compress(Bitmap.CompressFormat.JPEG,60,out);
    if(cropped!=padded)cropped.recycle();padded.recycle();
    return Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
  }

  static synchronized void stop(){
    boolean was=projection!=null;
    if(display!=null){display.release();display=null;}
    if(reader!=null){reader.close();reader=null;}
    if(projection!=null){MediaProjection p=projection;projection=null;try{p.stop();}catch(RuntimeException ignored){}}
    if(thread!=null){thread.quitSafely();thread=null;handler=null;}
    Frames frames=listener;if(was&&frames!=null)frames.stopped();
  }
}
