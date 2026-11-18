package com.myraa.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Size;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * FRIDAY's gesture mode (visual intelligence phase 5): hand motions in front of the FRONT camera control music
 * and volume. Right swipe = next, left swipe = previous, up/down = volume, hand held over the lens = pause,
 * waving = play.
 *
 * The front camera runs only while this screen is open and in front, with a clear "Gesture mode ON" banner and
 * the preview, and stops by itself after ten minutes. Leaving the screen (Band, Back, Home, the phone locking)
 * closes the camera at once. Frames are shrunk to a 32 x 24 grey grid on the phone and compared there; no
 * picture is saved, uploaded or sent to any AI. Gestures need a calm moment before them, a clear motion and a
 * pause between them (GestureLogic), so ordinary movement does not trigger commands.
 */
public final class GestureActivity extends Activity implements TextureView.SurfaceTextureListener {
  /** Where to report when the screen closes, and the sensitivity; set before the screen opens, never in the Intent. */
  static final class Session {
    final Consumer<JSONObject> report;volatile String sensitivity;
    Session(Consumer<JSONObject> report,String sensitivity){this.report=report;this.sensitivity=sensitivity;}
  }
  static volatile Session session;
  private static volatile GestureActivity open;
  static boolean isOpen(){return open!=null;}
  static void close(){GestureActivity a=open;if(a!=null)a.runOnUiThread(a::finish);}

  private static final int PERMISSION=8;
  private static final long MAX_MS=10*60_000,FRAME_MS=60;
  private TextureView texture;private TextView status,last;private Button sensitivityButton;
  private HandlerThread cameraThread;private Handler cameraHandler;
  private CameraDevice camera;private CameraCaptureSession capture;private ImageReader reader;
  private Size previewSize;private int sensorOrientation=270;
  private final Handler ui=new Handler(Looper.getMainLooper());
  private volatile boolean resumed;private boolean askingPermission;
  private volatile GestureLogic.Detector detector;
  private long startedAt,lastFrameAt;
  private final java.util.Map<String,Integer> counts=new java.util.LinkedHashMap<>();
  private static final String[] NAMES={"swipe_right","swipe_left","swipe_up","swipe_down","cover","wave"};
  private static final String[] SHAPES={"Open_Palm","Thumb_Up","Victory","Pointing_Up","Thumb_Down"};
  /** The hand shape model (null until loaded, or when this APK or phone cannot run it) and its own thread. */
  private volatile HandShapes shapes;
  private final java.util.concurrent.ExecutorService shapeWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
  private volatile boolean shapeBusy;private long lastShapeAt;private volatile GestureLogic.ShapeHold hold;
  /** Any two commands (motion or shape) at least this far apart. */
  private long lastCommandAt;private static final long SHAPE_EVERY_MS=120,COMMAND_GAP_MS=1200;
  private TextView help;

  @Override protected void onCreate(Bundle b){
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if(session==null){finish();return;}
    open=this;
    FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
    texture=new TextureView(this);texture.setSurfaceTextureListener(this);texture.setAlpha(0.55f);
    root.addView(texture,new FrameLayout.LayoutParams(-1,-1));
    LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setPadding(dp(16),dp(28),dp(16),dp(10));top.setBackgroundColor(0xCC0B3D2E);
    TextView banner=text(18,Color.WHITE);banner.setText("● Gesture mode ON (front camera)");top.addView(banner);
    status=text(13,0xFFB0E0FF);top.addView(status);
    last=text(22,0xFFFFD54F);top.addView(last);
    root.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
    LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);bottom.setPadding(dp(16),dp(10),dp(16),dp(20));bottom.setBackgroundColor(0xAA000000);
    help=text(14,Color.WHITE);helpText(false);bottom.addView(help);
    LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER);
    sensitivityButton=button("",v->{Session s=session;if(s==null)return;s.sensitivity="normal".equals(s.sensitivity)?"high":"high".equals(s.sensitivity)?"low":"normal";newDetector();labels();});
    row.addView(sensitivityButton);
    row.addView(button("Band",v->finish()));
    bottom.addView(row);
    root.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
    setContentView(root);
    newDetector();labels();
    status.setText("Haath se gesture karein. Gesture ke darmiyan thora ruk jayein.");
    // The hand shape model loads off the main thread; until then (or without it) motion gestures work alone.
    shapeWorker.execute(()->{
      HandShapes m=HandShapes.open(this);
      if(isFinishing()||isDestroyed()){if(m!=null)m.close();return;}
      shapes=m;
      ui.post(()->{if(m!=null){helpText(true);status.setText("Haath ke shapes bhi pehchan rahi hoon. Shape 1 second tak rokein.");}});
    });
    startedAt=SystemClock.elapsedRealtime();
    ControlLog.i("gestures: mode on ("+session.sensitivity+")");
  }

  private void newDetector(){
    Session s=session;float th=GestureLogic.threshold(s==null?"normal":s.sensitivity);
    detector=new GestureLogic.Detector(th);hold=new GestureLogic.ShapeHold(Math.min(0.9f,th+0.05f));
  }
  private void helpText(boolean withShapes){
    StringBuilder h=new StringBuilder();for(String n:NAMES)h.append(GestureLogic.label(n)).append('\n');
    if(withShapes)for(String n:SHAPES)h.append(GestureLogic.shapeLabel(n)).append('\n');
    help.setText(h.append("Phone ko seedha rakhein, haath camera se 20-50 cm door.").toString());
  }
  private void labels(){Session s=session;if(s==null)return;sensitivityButton.setText("Sensitivity: "+("high".equals(s.sensitivity)?"Zyada":"low".equals(s.sensitivity)?"Kam":"Normal"));}

  @Override protected void onResume(){
    super.onResume();resumed=true;
    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){askingPermission=true;requestPermissions(new String[]{Manifest.permission.CAMERA},PERMISSION);return;}
    startCameraThread();
    if(texture.isAvailable())openCamera();
    ui.postDelayed(timeout,1000);
  }
  @Override protected void onPause(){
    // Leaving the screen always stops the camera: gestures never run in the background.
    resumed=false;ui.removeCallbacks(timeout);closeCamera();stopCameraThread();
    super.onPause();
    // The camera permission question pauses this screen too; only a real leave ends gesture mode.
    if(!isChangingConfigurations()&&!askingPermission)finish();
  }
  @Override protected void onDestroy(){
    shapeWorker.execute(()->{HandShapes m=shapes;shapes=null;if(m!=null)m.close();});shapeWorker.shutdown();
    if(open==this)open=null;
    report();
    super.onDestroy();
  }
  @Override public void onRequestPermissionsResult(int code,String[] p,int[] r){
    askingPermission=false;
    if(code==PERMISSION&&(r.length==0||r[0]!=PackageManager.PERMISSION_GRANTED)){Toast.makeText(this,"Camera ki ijazat nahi mili.",Toast.LENGTH_LONG).show();finish();}
  }

  private final Runnable timeout=new Runnable(){public void run(){
    if(!resumed)return;
    if(SystemClock.elapsedRealtime()-startedAt>MAX_MS){Toast.makeText(GestureActivity.this,"Gesture mode 10 minute baad band ho gaya.",Toast.LENGTH_LONG).show();finish();return;}
    ui.postDelayed(this,5000);
  }};

  // ---- Camera2: front camera, preview plus a small luminance stream

  private void startCameraThread(){if(cameraThread!=null)return;cameraThread=new HandlerThread("friday-gesture-camera");cameraThread.start();cameraHandler=new Handler(cameraThread.getLooper());}
  private void stopCameraThread(){if(cameraThread==null)return;cameraThread.quitSafely();cameraThread=null;cameraHandler=null;}

  private void openCamera(){
    if(camera!=null||cameraHandler==null||!resumed)return;
    CameraManager manager=getSystemService(CameraManager.class);
    try{
      String id=null;
      for(String c:manager.getCameraIdList()){Integer f=manager.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);if(f!=null&&f==CameraCharacteristics.LENS_FACING_FRONT){id=c;break;}}
      if(id==null){fail("Is phone par front camera nahi mila.");return;}
      CameraCharacteristics cc=manager.getCameraCharacteristics(id);
      Integer so=cc.get(CameraCharacteristics.SENSOR_ORIENTATION);sensorOrientation=so==null?270:so;
      StreamConfigurationMap map=cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      previewSize=small(map==null?new Size[0]:map.getOutputSizes(SurfaceTexture.class),640);
      Size analysis=small(map==null?new Size[0]:map.getOutputSizes(ImageFormat.YUV_420_888),320);
      reader=ImageReader.newInstance(analysis.getWidth(),analysis.getHeight(),ImageFormat.YUV_420_888,2);
      reader.setOnImageAvailableListener(this::frame,cameraHandler);
      manager.openCamera(id,new CameraDevice.StateCallback(){
        @Override public void onOpened(CameraDevice d){if(!resumed){d.close();return;}camera=d;startPreview();}
        @Override public void onDisconnected(CameraDevice d){d.close();if(camera==d)camera=null;}
        @Override public void onError(CameraDevice d,int e){d.close();if(camera==d)camera=null;ui.post(()->fail("Camera khul nahi saka (koi aur app use kar rahi ho sakti hai)."));}
      },cameraHandler);
    }catch(CameraAccessException|SecurityException|IllegalArgumentException e){fail("Camera khul nahi saka.");}
  }

  /** The smallest size whose long side is at least min (gestures need little detail), else the largest. */
  static Size small(Size[] sizes,int min){
    Size best=null,largest=null;
    for(Size s:sizes){
      int l=Math.max(s.getWidth(),s.getHeight());
      if(largest==null||l>Math.max(largest.getWidth(),largest.getHeight()))largest=s;
      if(l>=min&&(best==null||l<Math.max(best.getWidth(),best.getHeight())))best=s;
    }
    return best!=null?best:largest!=null?largest:new Size(min,min*3/4);
  }

  private void startPreview(){
    try{
      SurfaceTexture st=texture.getSurfaceTexture();if(st==null||camera==null||reader==null)return;
      st.setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());
      Surface surface=new Surface(st),analysis=reader.getSurface();
      CaptureRequest.Builder req=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);req.addTarget(surface);req.addTarget(analysis);
      final CameraDevice cam=camera;
      cam.createCaptureSession(Arrays.asList(surface,analysis),new CameraCaptureSession.StateCallback(){
        @Override public void onConfigured(CameraCaptureSession s){
          if(camera!=cam){s.close();return;}
          capture=s;
          try{s.setRepeatingRequest(req.build(),null,cameraHandler);}catch(CameraAccessException|IllegalStateException e){ui.post(()->fail("Camera shuru nahi hua."));}
        }
        @Override public void onConfigureFailed(CameraCaptureSession s){ui.post(()->fail("Camera shuru nahi hua."));}
      },cameraHandler);
      ui.post(this::transform);
    }catch(CameraAccessException|IllegalStateException e){fail("Camera shuru nahi hua.");}
  }

  /** Fills the screen with the preview and mirrors it like a selfie, so motions on screen match the user's own. */
  private void transform(){
    if(previewSize==null)return;
    int vw=texture.getWidth(),vh=texture.getHeight();if(vw==0||vh==0)return;
    boolean sideways=sensorOrientation==90||sensorOrientation==270;
    float pw=sideways?previewSize.getHeight():previewSize.getWidth(),ph=sideways?previewSize.getWidth():previewSize.getHeight();
    float scale=Math.max(vw/pw,vh/ph);
    Matrix m=new Matrix();m.setScale(pw*scale/vw,ph*scale/vh,vw/2f,vh/2f);
    texture.setTransform(m);
  }

  private void closeCamera(){
    if(capture!=null){try{capture.close();}catch(RuntimeException ignored){}capture=null;}
    if(camera!=null){try{camera.close();}catch(RuntimeException ignored){}camera=null;}
    if(reader!=null){try{reader.close();}catch(RuntimeException ignored){}reader=null;}
  }

  @Override public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){openCamera();}
  @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){transform();}
  @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){closeCamera();return true;}
  @Override public void onSurfaceTextureUpdated(SurfaceTexture s){}

  // ---- Frames (camera thread, never the main thread)

  private byte[] plane;

  private void frame(ImageReader r){
    Image img;
    try{img=r.acquireLatestImage();}catch(RuntimeException e){return;}
    if(img==null)return;
    try{
      long now=SystemClock.elapsedRealtime();
      if(now-lastFrameAt<FRAME_MS||!resumed)return;
      lastFrameAt=now;
      Image.Plane y=img.getPlanes()[0];ByteBuffer buf=y.getBuffer();
      int rowStride=y.getRowStride(),w=img.getWidth(),h=img.getHeight();
      if(y.getPixelStride()!=1)return;
      int need=buf.remaining();if(plane==null||plane.length<need)plane=new byte[need];
      buf.get(plane,0,need);
      int[] grid=GestureLogic.grid(plane,w,Math.min(h,(need+rowStride-1)/rowStride),rowStride,sensorOrientation,true);
      GestureLogic.Detector d=detector;
      GestureLogic.Gesture g=d==null?null:d.feed(grid,now);
      if(g!=null)ui.post(()->perform(g.name,GestureLogic.command(g.name),GestureLogic.label(g.name),g.confidence));
      if(shapes!=null&&!shapeBusy&&now-lastShapeAt>=SHAPE_EVERY_MS){lastShapeAt=now;shape(img,h,now);}
    }catch(RuntimeException ignored){
    }finally{img.close();}
  }

  private byte[] uPlane,vPlane;

  /** Hands a small upright colour copy of the frame to the hand shape model on its own thread (frames are skipped while it is busy). */
  private void shape(Image img,int h,long now){
    Image.Plane[] p=img.getPlanes();if(p.length<3)return;
    ByteBuffer ub=p[1].getBuffer(),vb=p[2].getBuffer();
    int un=ub.remaining(),vn=vb.remaining();
    if(uPlane==null||uPlane.length<un)uPlane=new byte[un];if(vPlane==null||vPlane.length<vn)vPlane=new byte[vn];
    ub.get(uPlane,0,un);vb.get(vPlane,0,vn);
    int w=img.getWidth();boolean sideways=sensorOrientation==90||sensorOrientation==270;
    int uw=sideways?h:w,uh=sideways?w:h;float k=256f/Math.max(uw,uh);
    int ow=Math.max(1,Math.round(uw*k)),oh=Math.max(1,Math.round(uh*k));
    int[] px=GestureLogic.argb(plane,uPlane,vPlane,w,h,p[0].getRowStride(),p[1].getRowStride(),p[1].getPixelStride(),sensorOrientation,true,ow,oh);
    Bitmap bmp=Bitmap.createBitmap(px,ow,oh,Bitmap.Config.ARGB_8888);
    shapeBusy=true;
    try{
      shapeWorker.execute(()->{
        try{
          HandShapes m=shapes;GestureLogic.ShapeHold sh=hold;
          Object[] best=m==null?null:m.detect(bmp,now);
          String fired=sh==null?null:best==null?sh.feed("",0,now):sh.feed((String)best[0],(Float)best[1],now);
          if(fired!=null){float score=best==null?0:(Float)best[1];ui.post(()->perform(fired,GestureLogic.shapeCommand(fired),GestureLogic.shapeLabel(fired),score));}
        }catch(Throwable t){ControlLog.i("gestures: hand shape check failed ("+t.getClass().getSimpleName()+")");}
        finally{bmp.recycle();shapeBusy=false;}
      });
    }catch(java.util.concurrent.RejectedExecutionException e){bmp.recycle();shapeBusy=false;}
  }

  /** Carries out the gesture's command and shows it. */
  private void perform(String name,String cmd,String label,float confidence){
    if(!resumed||cmd.isEmpty())return;
    long now=SystemClock.elapsedRealtime();
    if(now-lastCommandAt<COMMAND_GAP_MS)return;
    lastCommandAt=now;
    Integer n=counts.get(name);counts.put(name,n==null?1:n+1);
    last.setText(label+"  ("+Math.round(confidence*100)+"%)");
    getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    ControlLog.i("gestures: "+name+String.format(java.util.Locale.ROOT," %.2f",confidence)+" -> "+cmd);
    try{
      if("volume_up".equals(cmd)||"volume_down".equals(cmd)){
        AudioManager a=getSystemService(AudioManager.class);
        a.adjustStreamVolume(AudioManager.STREAM_MUSIC,"volume_up".equals(cmd)?AudioManager.ADJUST_RAISE:AudioManager.ADJUST_LOWER,AudioManager.FLAG_SHOW_UI);
      }else{
        // Same media control FRIDAY's media action uses (the playing app's session, else a media key).
        new Thread(()->{try{MediaPlayback.command(getApplicationContext(),cmd,null,false);}catch(RuntimeException e){ControlLog.i("gestures: media "+cmd+" failed ("+e.getClass().getSimpleName()+")");}},"friday-gesture-media").start();
      }
    }catch(RuntimeException e){status.setText("Ye command nahi chala.");}
  }

  /** On close, tells FRIDAY that gesture mode ended and what was used. */
  private void report(){
    Session s=session;if(s==null)return;
    JSONObject e=new JSONObject();int total=0;
    try{
      JSONObject used=new JSONObject();
      for(java.util.Map.Entry<String,Integer> c:counts.entrySet()){used.put(c.getKey(),c.getValue());total+=c.getValue();}
      e.put("type","vision-done").put("kind","gesture").put("ok",true).put("gestures",total).put("used",used)
        .put("minutes",Math.max(0,(SystemClock.elapsedRealtime()-startedAt)/60_000));
    }catch(JSONException ignored){}
    ControlLog.i("gestures: mode off after "+total+" gesture(s)");
    try{s.report.accept(e);}catch(RuntimeException ignored){}
  }

  private void fail(String why){ui.post(()->{if(status!=null)status.setText(why);});ControlLog.i("gestures: "+why);}

  // ---- Small UI helpers

  private int dp(int v){return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,v,getResources().getDisplayMetrics()));}
  private TextView text(int sp,int color){TextView t=new TextView(this);t.setTextSize(sp);t.setTextColor(color);t.setPadding(0,dp(2),0,dp(2));return t;}
  private Button button(String label,View.OnClickListener l){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(l);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMargins(dp(4),0,dp(4),0);b.setLayoutParams(lp);return b;}
}
