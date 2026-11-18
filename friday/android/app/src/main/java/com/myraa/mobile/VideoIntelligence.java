package com.myraa.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Video intelligence: one video the user picked. The phone looks at about thirty moments spread over the video
 * (small frames, nothing uploaded), keeps up to twelve where the picture really changes, and sends only those
 * stills with their times to the vision engine, which returns a summary, a timeline, the visible text, objects
 * and activities. The video itself is never uploaded, copied or kept, and the sound is not used.
 */
final class VideoIntelligence {
  private VideoIntelligence(){}
  static final int EDGE=768;

  static final class Result {
    final JSONObject data;final long durationMs;final int frames;final String provider,fileName;
    Result(JSONObject d,long ms,int f,String p,String n){data=d;durationMs=ms;frames=f;provider=p;fileName=n;}
  }

  static Result analyze(Context context,Uri uri,String question,VisionEngine engine,String key,String model,VisionEngine.Alt alt,BooleanSupplier stopped) throws Exception {
    String mime=context.getContentResolver().getType(uri);
    if(mime!=null&&!mime.toLowerCase(java.util.Locale.ROOT).startsWith("video/"))throw new IllegalArgumentException("That file is not a video. Pick a video, or use analyze_document for a PDF or picture.");
    MediaMetadataRetriever mmr=new MediaMetadataRetriever();
    List<byte[]> kept=new ArrayList<>();List<String> labels=new ArrayList<>();long duration;
    try{
      try{mmr.setDataSource(context,uri);}catch(RuntimeException e){throw new IllegalArgumentException("This video cannot be opened on the phone.");}
      duration=parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
      if(duration<=0)throw new IllegalArgumentException("This video has no length the phone can read.");
      long[] times=VisionLogic.candidateTimes(duration,VisionLogic.VIDEO_CANDIDATES);
      int n=times.length;long[] hash=new long[n];int[][] small=new int[n][];byte[][] jpeg=new byte[n][];long[] got=new long[n];int ok=0;
      for(int i=0;i<n;i++){
        if(stopped.getAsBoolean())throw new VisionEngine.Cancelled();
        Bitmap b=frame(mmr,times[i]*1000);
        if(b==null)continue;
        try{
          Bitmap h=Bitmap.createScaledBitmap(b,9,8,true),s=Bitmap.createScaledBitmap(b,32,32,true);
          try{
            int[] hp=new int[72];h.getPixels(hp,0,9,0,0,9,8);int[] sp=new int[1024];s.getPixels(sp,0,32,0,0,32,32);
            hash[ok]=VisionLogic.dHash(VisionLogic.grey(hp));small[ok]=VisionLogic.grey(sp);
          }finally{if(h!=b)h.recycle();if(s!=b)s.recycle();}
          jpeg[ok]=VisionEngine.jpeg(b,80);got[ok]=times[i];ok++;
        }finally{b.recycle();}
      }
      if(ok==0)throw new IllegalArgumentException("No pictures could be taken from this video (its format may not be supported on this phone).");
      long[] t2=java.util.Arrays.copyOf(got,ok),h2=java.util.Arrays.copyOf(hash,ok);int[][] s2=java.util.Arrays.copyOf(small,ok);
      for(int i:VisionLogic.keepFrames(t2,h2,s2,VisionLogic.MAX_VIDEO_FRAMES)){kept.add(jpeg[i]);labels.add(VisionLogic.timeLabel(t2[i]));}
    }finally{try{mmr.release();}catch(Exception ignored){}}
    if(stopped.getAsBoolean())throw new VisionEngine.Cancelled();
    VisionEngine.Request r=new VisionEngine.Request();
    r.system=VisionLogic.videoInstruction();r.prompt=VisionLogic.videoPrompt(question,labels,duration);r.maxTokens=4096;
    for(byte[] j:kept)r.media.add(new VisionEngine.Media(j,"image/jpeg"));
    VisionEngine.Answer a=engine.ask(key,model,alt,r);
    JSONObject d=VisionLogic.parseJson(a.text);
    if(d==null)d=new JSONObject().put("summary",VisionLogic.clip(a.text,800)).put("timeline",new JSONArray());
    ControlLog.i("video: "+kept.size()+" frames of "+VisionLogic.timeLabel(duration)+" with "+a.provider+(a.cached?" (cached)":""));
    return new Result(d,duration,kept.size(),a.provider,VisionEngine.name(context.getContentResolver(),uri));
  }

  /** The frame nearest to the time, already small (EDGE), or null. */
  private static Bitmap frame(MediaMetadataRetriever mmr,long us){
    try{
      Bitmap b;
      if(Build.VERSION.SDK_INT>=27)b=mmr.getScaledFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,EDGE,EDGE);
      else{b=mmr.getFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(b!=null){Bitmap f=VisionEngine.fit(b,EDGE,0);if(f!=b){b.recycle();b=f;}}}
      return b;
    }catch(RuntimeException|OutOfMemoryError e){return null;}
  }

  private static long parseLong(String s){try{return s==null?0:Long.parseLong(s.trim());}catch(NumberFormatException e){return 0;}}
}
