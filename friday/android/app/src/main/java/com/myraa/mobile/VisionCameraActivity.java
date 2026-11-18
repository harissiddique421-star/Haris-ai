package com.myraa.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.util.Size;
import android.util.TypedValue;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * FRIDAY's camera screen (visual intelligence phase 2): live object recognition and scene understanding.
 *
 * The back camera runs only while this screen is open and in front; leaving it (Close, Back, Home, the
 * phone locking) closes the camera at once and stops any request. A frame is sent to the vision engine
 * only when the user taps Look or asks a question, or, with Live on, at most every 1.5 seconds and only
 * when the view has changed (picture fingerprints on the phone), for at most three minutes at a time.
 * Frames are taken small from the preview, sent once and never saved. Results show as labelled boxes
 * with confidence, a short description, and, when the user taps Speak (or Speak is on), FRIDAY's voice.
 */
public final class VisionCameraActivity extends Activity implements TextureView.SurfaceTextureListener {
  /** Set by VisionActions before the screen opens: how to reach the AI and where to report. Never in the Intent. */
  static final class Session {
    final java.util.function.Supplier<String[]> keys;final VisionEngine.Alt alt;final java.util.function.Consumer<JSONObject> report;
    final String language;volatile String mode,question;volatile boolean live,speak;
    Session(java.util.function.Supplier<String[]> keys,VisionEngine.Alt alt,java.util.function.Consumer<JSONObject> report,String language,String mode,String question,boolean live,boolean speak){
      this.keys=keys;this.alt=alt;this.report=report;this.language=language;this.mode=mode;this.question=question;this.live=live;this.speak=speak;
    }
  }
  static volatile Session session;
  private static volatile VisionCameraActivity open;
  static boolean isOpen(){return open!=null;}
  static void close(){VisionCameraActivity a=open;if(a!=null)a.runOnUiThread(a::finish);}
  /** A question from the voice call while the camera is open: look again now with it. */
  static boolean ask(String mode,String question){
    VisionCameraActivity a=open;if(a==null)return false;
    a.runOnUiThread(()->{Session s=session;if(s==null)return;if(mode!=null&&!mode.isEmpty())s.mode=mode;s.question=question;a.lookNow();});
    return true;
  }

  private static final int FRAME_EDGE=1024,LIVE_MAX_MS=3*60_000,PERMISSION=7;
  private TextureView texture;private Overlay overlay;private TextView status,result;private Button liveButton,speakButton;private EditText questionBox;
  private HandlerThread cameraThread;private Handler cameraHandler;
  private CameraDevice camera;private CameraCaptureSession capture;private Size previewSize;private int sensorOrientation;
  private final Handler ui=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private final VisionEngine engine=new VisionEngine();
  private volatile boolean inFlight,resumed;
  private long lastSentAt,liveStartedAt;private long lastHash;private int[] lastSmall;
  private TextToSpeech tts;private boolean ttsReady;
  private JSONObject lastAnswer;private String lastSpeech="";private int looks,generation;

  @Override protected void onCreate(Bundle b){
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if(session==null){finish();return;}
    open=this;
    FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
    texture=new TextureView(this);texture.setSurfaceTextureListener(this);
    root.addView(texture,new FrameLayout.LayoutParams(-1,-1));
    overlay=new Overlay(this);root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
    LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setPadding(dp(16),dp(28),dp(16),dp(8));top.setBackgroundColor(0x88000000);
    status=text(13,0xFFB0E0FF);top.addView(status);
    result=text(15,Color.WHITE);result.setMaxLines(6);top.addView(result);
    root.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
    LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);bottom.setPadding(dp(12),dp(8),dp(12),dp(20));bottom.setBackgroundColor(0x99000000);
    LinearLayout askRow=new LinearLayout(this);
    questionBox=new EditText(this);questionBox.setHint("Sawal likhein (ye kya hai?)");questionBox.setTextColor(Color.WHITE);questionBox.setHintTextColor(0xFF9AA4B2);questionBox.setSingleLine(true);questionBox.setImeOptions(EditorInfo.IME_ACTION_SEND);
    questionBox.setOnEditorActionListener((v,id,e)->{askTyped();return true;});
    askRow.addView(questionBox,new LinearLayout.LayoutParams(0,-2,1));
    Button askButton=button("Poochho",v->askTyped());askRow.addView(askButton);
    bottom.addView(askRow);
    LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER);
    row.addView(button("Objects",v->{session.mode="objects";lookNow();}));
    row.addView(button("Scene",v->{session.mode="scene";lookNow();}));
    liveButton=button("",v->setLive(!session.live));row.addView(liveButton);
    speakButton=button("",v->{session.speak=!session.speak;labels();if(session.speak)say(lastSpeech);else stopSpeaking();});row.addView(speakButton);
    row.addView(button("Band",v->finish()));
    bottom.addView(row);
    root.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
    setContentView(root);
    labels();
    status.setText("Camera ko cheez ki taraf karein, phir Objects ya Scene dabayein.");
    tts=new TextToSpeech(this,s->{if(s==TextToSpeech.SUCCESS){ttsReady=true;try{Locale l=Locale.forLanguageTag(getSharedPreferences(Announcer.PREFS,0).getString("language","hi-IN"));if(tts.isLanguageAvailable(l)>=TextToSpeech.LANG_AVAILABLE)tts.setLanguage(l);}catch(RuntimeException ignored){}}});
    if(session.live)liveStartedAt=SystemClock.elapsedRealtime();
    ControlLog.i("camera vision: opened ("+session.mode+(session.live?", live":"")+")");
  }

  @Override protected void onResume(){
    super.onResume();resumed=true;
    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},PERMISSION);return;}
    startCameraThread();
    if(texture.isAvailable())openCamera();
    ui.postDelayed(tick,1200);
  }
  @Override protected void onPause(){
    // Leaving the screen always stops the camera and any request: nothing runs in the background.
    resumed=false;ui.removeCallbacks(tick);engine.cancel();closeCamera();stopCameraThread();stopSpeaking();
    super.onPause();
  }
  @Override protected void onDestroy(){
    if(open==this)open=null;
    engine.cancel();worker.shutdownNow();
    if(tts!=null){tts.shutdown();tts=null;}
    report();
    ControlLog.i("camera vision: closed after "+looks+" look(s)");
    super.onDestroy();
  }
  @Override public void onRequestPermissionsResult(int code,String[] p,int[] r){
    if(code==PERMISSION&&(r.length==0||r[0]!=PackageManager.PERMISSION_GRANTED)){Toast.makeText(this,"Camera ki ijazat nahi mili.",Toast.LENGTH_LONG).show();finish();}
  }

  // ---- Camera2 preview

  private void startCameraThread(){if(cameraThread!=null)return;cameraThread=new HandlerThread("friday-vision-camera");cameraThread.start();cameraHandler=new Handler(cameraThread.getLooper());}
  private void stopCameraThread(){if(cameraThread==null)return;cameraThread.quitSafely();cameraThread=null;cameraHandler=null;}

  private void openCamera(){
    if(camera!=null||cameraHandler==null||!resumed)return;
    CameraManager manager=getSystemService(CameraManager.class);
    try{
      String id=null;
      for(String c:manager.getCameraIdList()){Integer f=manager.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);if(f!=null&&f==CameraCharacteristics.LENS_FACING_BACK){id=c;break;}}
      if(id==null&&manager.getCameraIdList().length>0)id=manager.getCameraIdList()[0];
      if(id==null){fail("Is phone par camera nahi mila.");return;}
      CameraCharacteristics cc=manager.getCameraCharacteristics(id);
      Integer so=cc.get(CameraCharacteristics.SENSOR_ORIENTATION);sensorOrientation=so==null?90:so;
      StreamConfigurationMap map=cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      previewSize=choose(map==null?new Size[0]:map.getOutputSizes(SurfaceTexture.class));
      manager.openCamera(id,new CameraDevice.StateCallback(){
        @Override public void onOpened(CameraDevice d){if(!resumed){d.close();return;}camera=d;startPreview();}
        @Override public void onDisconnected(CameraDevice d){d.close();if(camera==d)camera=null;}
        @Override public void onError(CameraDevice d,int e){d.close();if(camera==d)camera=null;ui.post(()->fail("Camera khul nahi saka (koi aur app use kar rahi ho sakti hai)."));}
      },cameraHandler);
    }catch(CameraAccessException|SecurityException|IllegalArgumentException e){fail("Camera khul nahi saka.");}
  }

  /** A 4:3 preview size no bigger than 1920 on its long side (enough to read labels, light on memory). */
  static Size choose(Size[] sizes){
    Size best=null;
    for(Size s:sizes){
      int l=Math.max(s.getWidth(),s.getHeight()),sh=Math.min(s.getWidth(),s.getHeight());
      if(l>1920||Math.abs(l*3-sh*4)>l/20)continue;
      if(best==null||l>Math.max(best.getWidth(),best.getHeight()))best=s;
    }
    if(best!=null)return best;
    for(Size s:sizes)if(Math.max(s.getWidth(),s.getHeight())<=1920&&(best==null||s.getWidth()*s.getHeight()>best.getWidth()*best.getHeight()))best=s;
    return best!=null?best:new Size(1280,960);
  }

  private void startPreview(){
    try{
      SurfaceTexture st=texture.getSurfaceTexture();if(st==null||camera==null)return;
      st.setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());
      Surface surface=new Surface(st);
      CaptureRequest.Builder req=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);req.addTarget(surface);
      req.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
      final CameraDevice cam=camera;
      cam.createCaptureSession(Collections.singletonList(surface),new CameraCaptureSession.StateCallback(){
        @Override public void onConfigured(CameraCaptureSession s){
          if(camera!=cam){s.close();return;}
          capture=s;
          try{s.setRepeatingRequest(req.build(),null,cameraHandler);}catch(CameraAccessException|IllegalStateException e){ui.post(()->fail("Camera preview shuru nahi hua."));}
        }
        @Override public void onConfigureFailed(CameraCaptureSession s){ui.post(()->fail("Camera preview shuru nahi hua."));}
      },cameraHandler);
      ui.post(this::transform);
    }catch(CameraAccessException|IllegalStateException e){fail("Camera preview shuru nahi hua.");}
  }

  /** Fills the screen with the preview (centre crop) in portrait, so boxes on the view match the frame taken from it. */
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
  }

  @Override public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){openCamera();}
  @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){transform();}
  @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){closeCamera();return true;}
  @Override public void onSurfaceTextureUpdated(SurfaceTexture s){}

  // ---- Looking

  private final Runnable tick=new Runnable(){public void run(){
    if(!resumed)return;
    Session s=session;
    if(s!=null&&s.live){
      if(SystemClock.elapsedRealtime()-liveStartedAt>LIVE_MAX_MS){setLive(false);status.setText("Live 3 minute baad ruk gaya (battery aur quota bachane ke liye). Dobara Live dabayein.");}
      else if(!inFlight)look(false);
    }
    ui.postDelayed(this,1500);
  }};

  void lookNow(){if(inFlight)engine.cancel();look(true);}
  private void askTyped(){
    String q=questionBox.getText().toString().trim();if(q.isEmpty())return;
    session.question=q;questionBox.setText("");
    ((android.view.inputmethod.InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(questionBox.getWindowToken(),0);
    lookNow();
  }

  /** Takes the current preview as a small picture and asks the vision engine. forced = the user asked now. */
  private void look(boolean forced){
    if(!resumed||camera==null||!texture.isAvailable()){if(forced)status.setText("Camera abhi tayyar nahi.");return;}
    Bitmap frame;
    try{
      int vw=texture.getWidth(),vh=texture.getHeight();float k=Math.min(1f,(float)FRAME_EDGE/Math.max(vw,vh));
      frame=texture.getBitmap(Math.max(1,Math.round(vw*k)),Math.max(1,Math.round(vh*k)));
    }catch(RuntimeException|OutOfMemoryError e){if(forced)status.setText("Frame nahi mila, dobara koshish karein.");return;}
    if(frame==null)return;
    byte[] jpeg;long hash;int[] small;
    try{
      jpeg=VisionEngine.jpeg(frame,82);
      Bitmap h=Bitmap.createScaledBitmap(frame,9,8,true),sm=Bitmap.createScaledBitmap(frame,32,32,true);
      int[] hp=new int[72];h.getPixels(hp,0,9,0,0,9,8);int[] sp=new int[1024];sm.getPixels(sp,0,32,0,0,32,32);
      h.recycle();sm.recycle();
      hash=VisionLogic.dHash(VisionLogic.grey(hp));small=VisionLogic.grey(sp);
    }finally{frame.recycle();}
    long now=SystemClock.elapsedRealtime();
    if(!forced){
      int likeness=lastSmall==null?0:VisionLogic.similarity(lastHash,hash,lastSmall,small);
      if(!VisionLogic.worthSending(likeness,now-lastSentAt))return;
    }
    lastHash=hash;lastSmall=small;lastSentAt=now;
    Session s=session;if(s==null)return;
    String mode=s.mode,question=s.question;
    VisionEngine.Request r=new VisionEngine.Request();
    r.system=VisionLogic.cameraInstruction(s.language);r.prompt=VisionLogic.cameraPrompt(mode,question);r.maxTokens=2048;
    r.media.add(new VisionEngine.Media(jpeg,"image/jpeg"));
    inFlight=true;looks++;final int gen=++generation;
    status.setText(question==null||question.isEmpty()?("scene".equals(mode)?"Scene samajh rahi hoon...":"Dekh rahi hoon..."):"\""+question+"\" dekh rahi hoon...");
    worker.execute(()->{
      String[] k=s.keys.get();
      JSONObject answer=null;String error=null;String provider="";
      try{VisionEngine.Answer a=engine.ask(k[0],k[1],s.alt,r);answer=VisionLogic.parseJson(a.text);provider=a.provider;if(answer==null)answer=new JSONObject().put("summary",VisionLogic.clip(a.text,300));}
      catch(VisionEngine.Cancelled c){error="";}
      catch(Exception e){error=VisionEngine.explain(e);ControlLog.i("camera vision: failed ("+VisionEngine.code(e)+")");}
      JSONObject ans=answer;String err=error,prov=provider;
      ui.post(()->{
        if(gen!=generation)return; // a newer look replaced this one
        inFlight=false;
        if(isFinishing()||isDestroyed())return;
        if(err!=null){if(!err.isEmpty()){status.setText(err);if(s.live)setLive(false);}return;}
        show(ans,question,prov);
      });
    });
  }

  private void show(JSONObject answer,String question,String provider){
    lastAnswer=answer;
    List<VisionLogic.Seen> seen=VisionLogic.parseSeen(answer);
    overlay.set(seen);
    String speech=VisionLogic.cameraSpeech(answer,seen);
    StringBuilder b=new StringBuilder();
    String ans=answer.optString("answer","").trim();
    if(!ans.isEmpty())b.append(ans).append('\n');
    b.append(answer.optString("summary","").trim());
    List<String> hazards=VisionLogic.strings(answer.optJSONArray("hazards"),3);
    if(!hazards.isEmpty())b.append("\n⚠ ").append(android.text.TextUtils.join("; ",hazards));
    result.setText(b.toString().trim());
    status.setText(seen.isEmpty()?"Kuch saaf nazar nahi aaya.":seen.size()+" cheezen mili ("+provider+")");
    if(session!=null&&session.speak&&!speech.equals(lastSpeech))say(speech);
    lastSpeech=speech;
  }

  private void say(String text){
    if(tts==null||!ttsReady||text==null||text.isEmpty())return;
    tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"friday-vision");
  }
  private void stopSpeaking(){if(tts!=null)try{tts.stop();}catch(RuntimeException ignored){}}

  private void setLive(boolean on){
    Session s=session;if(s==null)return;
    s.live=on;if(on){liveStartedAt=SystemClock.elapsedRealtime();lastSmall=null;}
    labels();
    status.setText(on?"Live on: jab manzar badlega FRIDAY dobara dekhegi.":"Live band.");
  }
  private void labels(){
    Session s=session;if(s==null)return;
    liveButton.setText(s.live?"Live ■":"Live ▶");speakButton.setText(s.speak?"Awaz ✓":"Awaz");
  }

  /** On close, tells FRIDAY what was seen last, so the conversation can continue about it. */
  private void report(){
    Session s=session;if(s==null)return;
    JSONObject e=new JSONObject();
    try{
      e.put("type","vision-done").put("kind","camera").put("ok",lastAnswer!=null).put("looks",looks);
      if(lastAnswer!=null){
        List<VisionLogic.Seen> seen=VisionLogic.parseSeen(lastAnswer);
        StringBuilder objs=new StringBuilder();
        for(int i=0;i<seen.size()&&i<8;i++){VisionLogic.Seen x=seen.get(i);objs.append(i==0?"":", ").append(x.label);String w=x.where();if(!w.isEmpty())objs.append(" (").append(w).append(')');}
        e.put("summary",VisionLogic.clip(lastAnswer.optString("summary",""),600)).put("answer",VisionLogic.clip(lastAnswer.optString("answer",""),400)).put("objects",objs.toString())
          .put("question",s.question==null?"":s.question);
      }else e.put("message","The camera was closed before FRIDAY looked at anything.");
    }catch(JSONException ignored){}
    try{s.report.accept(e);}catch(RuntimeException ignored){}
  }

  private void fail(String why){if(status!=null)status.setText(why);ControlLog.i("camera vision: "+why);}

  // ---- Small UI helpers

  private int dp(int v){return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,v,getResources().getDisplayMetrics()));}
  private TextView text(int sp,int color){TextView t=new TextView(this);t.setTextSize(sp);t.setTextColor(color);return t;}
  private Button button(String label,View.OnClickListener l){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setOnClickListener(l);b.setMinWidth(0);b.setPadding(dp(8),0,dp(8),0);return b;}

  /** Draws the boxes and labels (with confidence) over the preview. */
  static final class Overlay extends View {
    private List<VisionLogic.Seen> seen=new ArrayList<>();
    private final Paint box=new Paint(Paint.ANTI_ALIAS_FLAG),tag=new Paint(Paint.ANTI_ALIAS_FLAG),ink=new Paint(Paint.ANTI_ALIAS_FLAG);
    Overlay(Context c){
      super(c);
      float d=c.getResources().getDisplayMetrics().density;
      box.setStyle(Paint.Style.STROKE);box.setStrokeWidth(3*d);box.setColor(0xFF4FC3F7);
      tag.setColor(0xCC0B3C5D);ink.setColor(Color.WHITE);ink.setTextSize(14*d);
    }
    void set(List<VisionLogic.Seen> s){seen=s;invalidate();}
    @Override protected void onDraw(Canvas c){
      int w=getWidth(),h=getHeight();float pad=6*getResources().getDisplayMetrics().density;
      for(VisionLogic.Seen s:seen){
        if(!s.hasBox())continue;
        RectF r=new RectF(s.left*w/1000f,s.top*h/1000f,s.right*w/1000f,s.bottom*h/1000f);
        c.drawRect(r,box);
        String label=s.label+" "+Math.round(s.confidence*100)+"%";
        float tw=ink.measureText(label),th=ink.getTextSize();
        float y=Math.max(th+pad,r.top);
        c.drawRect(r.left,y-th-pad,r.left+tw+2*pad,y,tag);
        c.drawText(label,r.left+pad,y-pad/1.5f,ink);
      }
    }
  }
}
