package com.myraa.mobile;

import android.content.*;
import android.media.*;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import android.view.KeyEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.net.ssl.HttpsURLConnection;

/**
 * YouTube and media playback, in reusable pieces: URL detection, search, opening a video,
 * reading the real playback state, transport controls and the fallbacks between them.
 *
 * Playback state comes from Android's media sessions (needs FRIDAY's Notification access).
 * Commands go to the playing app's media session first, then to a system media key; the
 * accessibility tap is only the last resort and happens at most once per request.
 */
final class MediaPlayback {
  static final String YOUTUBE="com.google.android.youtube",YT_MUSIC="com.google.android.apps.youtube.music";
  private static final ExecutorService network=Executors.newSingleThreadExecutor();
  private static final Pattern URL_ID=Pattern.compile(
    "(?i)(?:https?://)?(?:www\\.|m\\.|music\\.)?(?:youtube\\.com/(?:watch\\?(?:[^\\s#]*&)?v=|shorts/|live/|embed/|v/)|youtu\\.be/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])");
  private static final Pattern RESULT=Pattern.compile("\"videoRenderer\"\\s*:\\s*\\{\\s*\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"");
  private static final Pattern TITLE=Pattern.compile("\"title\"\\s*:\\s*\\{\\s*\"runs\"\\s*:\\s*\\[\\s*\\{\\s*\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){1,200})\"");
  private MediaPlayback(){}

  // ---- URL detection
  /** The 11-character video id from any YouTube link form (watch, youtu.be, shorts, live, embed, music), or null. */
  static String videoId(String text){
    if(text==null)return null;
    Matcher m=URL_ID.matcher(text.trim());
    return m.find()?m.group(1):null;
  }
  static boolean hasYouTubeLink(String text){return videoId(text)!=null;}

  // ---- search
  static final class Video { final String id,title; Video(String id,String title){this.id=id;this.title=title;} }
  /** Top results of a YouTube search, most relevant first (YouTube's own ranking). Retries once on a network error. */
  static List<Video> search(String query) throws IOException {
    IOException last=null;
    for(int attempt=0;attempt<2;attempt++){
      try{return searchOnce(query);}catch(IOException e){last=e;ControlLog.i("youtube: search attempt "+(attempt+1)+" failed ("+e.getClass().getSimpleName()+")");}
    }
    throw last;
  }
  private static List<Video> searchOnce(String query) throws IOException {
    URL url=new URL("https://www.youtube.com/results?hl=en&search_query="+URLEncoder.encode(query.trim(),"UTF-8"));
    HttpsURLConnection c=(HttpsURLConnection)url.openConnection();
    try{
      c.setInstanceFollowRedirects(true);c.setConnectTimeout(8000);c.setReadTimeout(10000);
      c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
      c.setRequestProperty("Accept-Language","en-US,en;q=0.8");
      c.setRequestProperty("Cookie","CONSENT=YES+1; SOCS=CAI");
      int status=c.getResponseCode();if(status<200||status>=300)throw new IOException("HTTP "+status);
      String html=read(c.getInputStream(),3*1024*1024);
      List<Video> out=new ArrayList<>();Set<String> seen=new HashSet<>();
      Matcher m=RESULT.matcher(html);
      while(m.find()&&out.size()<5){
        String id=m.group(1);if(!seen.add(id))continue;
        Matcher t=TITLE.matcher(html.substring(m.end(),Math.min(html.length(),m.end()+4000)));
        out.add(new Video(id,t.find()?unescape(t.group(1)):""));
      }
      return out;
    }finally{c.disconnect();}
  }
  static void background(Runnable r){network.execute(r);}

  // ---- opening
  /** Watch link for the YouTube app. Songs get YouTube's own mix after them, so next/previous work. */
  static Uri watchUri(String id,boolean music){return watchUri(id,music,0);}
  /** startSeconds > 0 opens the video at that point (resume where the user stopped). */
  static Uri watchUri(String id,boolean music,int startSeconds){
    Uri.Builder b=Uri.parse("https://www.youtube.com/watch").buildUpon().appendQueryParameter("v",id);
    if(music)b.appendQueryParameter("list","RD"+id);
    if(startSeconds>0)b.appendQueryParameter("t",startSeconds+"s");
    return b.build();
  }
  static Intent openIntent(Context c,String id,boolean music,String pkg){return openIntent(c,id,music,pkg,0);}
  static Intent openIntent(Context c,String id,boolean music,String pkg,int startSeconds){
    Intent i=new Intent(Intent.ACTION_VIEW,watchUri(id,music,startSeconds)).addCategory(Intent.CATEGORY_BROWSABLE);
    if(pkg!=null)i.setPackage(pkg);
    return i;
  }

  // ---- playback state
  /** The media session to talk to: the preferred app's if it has one, otherwise the most recent active one. Null without Notification access. */
  static MediaController controller(Context c,String preferPkg){
    try{
      MediaSessionManager m=c.getSystemService(MediaSessionManager.class);if(m==null)return null;
      List<MediaController> list=m.getActiveSessions(new ComponentName(c,NotificationReader.class));
      if(list==null||list.isEmpty())return null;
      if(preferPkg!=null)for(MediaController mc:list)if(preferPkg.equals(mc.getPackageName()))return mc;
      for(MediaController mc:list){PlaybackState s=mc.getPlaybackState();if(s!=null&&s.getState()==PlaybackState.STATE_PLAYING)return mc;}
      return list.get(0);
    }catch(SecurityException e){return null;}
  }
  static boolean canReadState(Context c){return NotificationReader.enabled(c);}
  /** playing, paused, buffering, stopped, error, none (no session yet) or unknown (no Notification access). */
  static String state(Context c,String pkg){
    if(!canReadState(c))return "unknown";
    MediaController mc=controller(c,pkg);
    if(mc==null||(pkg!=null&&!pkg.equals(mc.getPackageName())))return "none";
    PlaybackState s=mc.getPlaybackState();if(s==null)return "none";
    switch(s.getState()){
      case PlaybackState.STATE_PLAYING:return "playing";
      case PlaybackState.STATE_PAUSED:return "paused";
      case PlaybackState.STATE_BUFFERING:case PlaybackState.STATE_CONNECTING:return "buffering";
      case PlaybackState.STATE_ERROR:return "error";
      case PlaybackState.STATE_STOPPED:case PlaybackState.STATE_NONE:return "stopped";
      default:return "buffering";
    }
  }
  static String nowPlaying(Context c,String pkg){
    MediaController mc=controller(c,pkg);if(mc==null)return "";
    MediaMetadata md=mc.getMetadata();if(md==null)return "";
    String t=md.getString(MediaMetadata.METADATA_KEY_TITLE);return t==null?"":t;
  }

  // ---- remembering where the user stopped (Quran, stories, lectures)
  private static MediaController tracked;private static MediaController.Callback trackCallback;
  private static SharedPreferences prefs(Context c){return c.getSharedPreferences(Announcer.PREFS,Context.MODE_PRIVATE);}
  /** Called when FRIDAY opens a video: remember which one, from the start. */
  static void remember(Context c,String id,String title,boolean music){
    prefs(c).edit().putString("lastVideo",id).putString("lastVideoTitle",title==null?"":title).putBoolean("lastVideoMusic",music).putLong("lastVideoPos",0).apply();
  }
  /** Called once it really plays: follow the YouTube session and save the position whenever it pauses or stops. */
  static void track(Context c,String pkg){
    Context app=c.getApplicationContext();MediaController mc=controller(app,pkg);
    if(mc==null||!pkg.equals(mc.getPackageName()))return;
    if(tracked!=null&&trackCallback!=null){try{tracked.unregisterCallback(trackCallback);}catch(RuntimeException ignored){}}
    String title=nowPlaying(app,pkg);if(!title.isEmpty())prefs(app).edit().putString("lastVideoTitle",title).apply();
    tracked=mc;trackCallback=new MediaController.Callback(){
      @Override public void onPlaybackStateChanged(PlaybackState s){save(app,s);}
      @Override public void onMetadataChanged(MediaMetadata md){
        String t=md==null?null:md.getString(MediaMetadata.METADATA_KEY_TITLE);
        // The next video in a playlist or mix started: FRIDAY knows it only by its title now.
        if(t!=null&&!t.equals(prefs(app).getString("lastVideoTitle","")))prefs(app).edit().putString("lastVideoTitle",t).remove("lastVideo").putLong("lastVideoPos",0).apply();
      }
      @Override public void onSessionDestroyed(){if(tracked!=null)save(app,tracked.getPlaybackState());tracked=null;}
    };
    mc.registerCallback(trackCallback,new Handler(Looper.getMainLooper()));
  }
  private static void save(Context c,PlaybackState s){
    if(s==null)return;long pos=s.getPosition();
    if(s.getState()==PlaybackState.STATE_PLAYING)pos+=(long)((SystemClock.elapsedRealtime()-s.getLastPositionUpdateTime())*s.getPlaybackSpeed());
    if(pos>0)prefs(c).edit().putLong("lastVideoPos",pos).apply();
  }
  /** The live position if YouTube still has the video, else the saved one. Seconds. */
  static int resumePoint(Context c){
    MediaController mc=controller(c,YOUTUBE);
    if(mc!=null&&YOUTUBE.equals(mc.getPackageName())&&mc.getPlaybackState()!=null){save(c,mc.getPlaybackState());}
    return (int)Math.max(0,prefs(c).getLong("lastVideoPos",0)/1000-3);
  }
  static String lastId(Context c){return prefs(c).getString("lastVideo",null);}
  static String lastTitle(Context c){return prefs(c).getString("lastVideoTitle","");}
  static boolean lastMusic(Context c){return prefs(c).getBoolean("lastVideoMusic",false);}

  // ---- transport controls
  /** pause, resume, stop, next or previous: media session first, then a system media key. Returns which route worked. */
  static String command(Context c,String cmd,String preferPkg){return command(c,cmd,preferPkg,false);}
  /** With strict, only the preferred app's own session is used, so another app's music is never started by mistake. */
  static String command(Context c,String cmd,String preferPkg,boolean strict){
    MediaController mc=controller(c,preferPkg);
    if(strict&&(mc==null||!preferPkg.equals(mc.getPackageName())))return "none";
    if(mc!=null){
      MediaController.TransportControls t=mc.getTransportControls();
      switch(cmd){
        case "pause":t.pause();return "session";
        case "resume":t.play();return "session";
        case "stop":t.stop();return "session";
        case "next":t.skipToNext();return "session";
        case "previous":t.skipToPrevious();return "session";
      }
    }
    int key;
    switch(cmd){
      case "pause":key=KeyEvent.KEYCODE_MEDIA_PAUSE;break;
      case "resume":key=KeyEvent.KEYCODE_MEDIA_PLAY;break;
      case "stop":key=KeyEvent.KEYCODE_MEDIA_STOP;break;
      case "next":key=KeyEvent.KEYCODE_MEDIA_NEXT;break;
      case "previous":key=KeyEvent.KEYCODE_MEDIA_PREVIOUS;break;
      default:throw new IllegalArgumentException("Say pause, resume, stop, next or previous.");
    }
    AudioManager audio=c.getSystemService(AudioManager.class);
    audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key));
    audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key));
    return "media_key";
  }
  /** Last resort: tap YouTube's own on-screen button once, only when YouTube is in front. */
  static boolean accessibilityTap(String... labels){
    PhoneController pc=PhoneController.get();
    if(pc==null||!YOUTUBE.equals(pc.foreground())&&!YT_MUSIC.equals(pc.foreground()))return false;
    for(String l:labels){android.view.accessibility.AccessibilityNodeInfo n=pc.find(l,false);if(n!=null&&pc.tap(n)){ControlLog.i("youtube: tapped \""+l+"\" with accessibility");return true;}}
    return false;
  }

  private static String read(InputStream in,int limit) throws IOException {
    try(InputStream s=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] buf=new byte[16384];int n;
      while((n=s.read(buf))!=-1){out.write(buf,0,n);if(out.size()>limit)break;}
      return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
  }
  private static String unescape(String s){
    String v=s.replace("\\u0026","&").replace("\\\"","\"").replace("\\/","/").replace("\\\\","\\");
    Matcher m=Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(v);StringBuffer b=new StringBuffer();
    while(m.find())m.appendReplacement(b,Matcher.quoteReplacement(String.valueOf((char)Integer.parseInt(m.group(1),16))));
    m.appendTail(b);return b.toString();
  }
}
