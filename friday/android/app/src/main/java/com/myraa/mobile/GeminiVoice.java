package com.myraa.mobile;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Agentic mode's natural voice: the reply is spoken with a Gemini TTS model in the same voice as the
 * Live call (Aoede etc.), instead of the phone's speech engine. Long replies are cut into short pieces
 * and the next piece is made while the current one plays, so speech starts after the first piece only.
 * If Gemini cannot speak (no key, no TTS model, no internet, too slow) the caller falls back to the
 * phone's voice for what is left. 24 kHz 16-bit mono PCM is played with an AudioTrack.
 */
final class GeminiVoice {
  /** finished(current): false when a newer reply or stop() cut this one off. failed(): the phone voice should say rest (started: some was already spoken). */
  interface Events { void started(); void finished(boolean current); void failed(String rest,String why,boolean started); }
  interface Keys { String key() throws Exception; }

  static final int RATE=24000;
  /** TTS models tried in order; a model this key cannot use is skipped for the rest of the session. */
  static final String[] MODELS={"gemini-2.5-flash-preview-tts","gemini-2.5-flash-tts","gemini-2.5-pro-preview-tts"};
  /** A piece that takes longer than this to make is given up and the phone's voice speaks the rest. */
  static final int PIECE_TIMEOUT_MS=15000;

  private final Keys keys;
  private final ExecutorService player=Executors.newSingleThreadExecutor(),maker=Executors.newFixedThreadPool(2);
  private final AtomicInteger generation=new AtomicInteger();
  private volatile AudioTrack track;
  private volatile int modelIndex;

  GeminiVoice(Keys keys){this.keys=keys;}

  /** Speaks text in voice (a Gemini voice name). Stops anything still speaking first. */
  void speak(String text,String voice,String style,Events events){
    int g=generation.incrementAndGet();
    stopTrack();
    List<String> pieces=pieces(text);
    if(pieces.isEmpty()){events.finished(true);return;}
    player.execute(()->play(g,pieces,voice,style,events));
  }

  void stop(){generation.incrementAndGet();stopTrack();}
  boolean speaking(){return track!=null;}
  void shutdown(){stop();player.shutdownNow();maker.shutdownNow();}

  private void play(int g,List<String> pieces,String voice,String style,Events events){
    boolean started=false,handedOff=false;long frames=0;
    AudioTrack t=null;
    try{
      List<Future<byte[]>> made=new ArrayList<>();
      // Make the first two pieces at once; each later piece starts when one finishes playing.
      for(int i=0;i<Math.min(2,pieces.size());i++){String p=pieces.get(i);made.add(maker.submit(()->make(p,voice,style)));}
      for(int i=0;i<pieces.size();i++){
        if(g!=generation.get())return;
        byte[] pcm;
        try{pcm=made.get(i).get(PIECE_TIMEOUT_MS,TimeUnit.MILLISECONDS);}
        catch(Exception e){
          for(Future<byte[]> f:made)f.cancel(true);
          Throwable c=e.getCause()==null?e:e.getCause();
          ControlLog.i("agentic voice: Gemini could not speak ("+c.getClass().getSimpleName()+(c.getMessage()==null?"":": "+c.getMessage())+"), phone voice speaks the rest");
          if(g==generation.get()){if(t!=null)drain(t,g,frames);handedOff=true;events.failed(String.join(" ",pieces.subList(i,pieces.size())),c.getClass().getSimpleName(),started);}
          return;
        }
        if(i+2<pieces.size()){String p=pieces.get(i+2);made.add(maker.submit(()->make(p,voice,style)));}
        if(g!=generation.get())return;
        if(t==null){t=open();track=t;t.play();}
        if(!started){started=true;events.started();}
        int off=0;
        while(off<pcm.length&&g==generation.get()){int n=t.write(pcm,off,Math.min(8192,pcm.length-off));if(n<=0)break;off+=n;frames+=n/2;}
      }
      if(t!=null&&g==generation.get())drain(t,g,frames);
    }finally{
      if(t!=null){try{t.stop();}catch(RuntimeException ignored){}t.release();if(track==t)track=null;}
      if(started&&!handedOff)events.finished(g==generation.get());
    }
  }

  /** Waits until the frames written have played. Pads with 120 ms of silence so the last syllable is not cut. */
  private void drain(AudioTrack t,int g,long frames){
    byte[] pad=new byte[RATE*2*120/1000];t.write(pad,0,pad.length);frames+=pad.length/2;
    long until=System.currentTimeMillis()+frames*1000/RATE+3000;
    while(System.currentTimeMillis()<until&&g==generation.get()){
      long pos;try{pos=t.getPlaybackHeadPosition()&0xffffffffL;}catch(RuntimeException e){return;}
      if(pos>=frames||t.getPlayState()!=AudioTrack.PLAYSTATE_PLAYING)return;
      try{Thread.sleep(30);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
    }
  }

  private AudioTrack open(){
    int min=AudioTrack.getMinBufferSize(RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
    AudioTrack t=new AudioTrack.Builder()
      .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
      .setBufferSizeInBytes(Math.max(min,RATE)).setTransferMode(AudioTrack.MODE_STREAM).build();
    return t;
  }

  private void stopTrack(){AudioTrack t=track;if(t!=null){try{t.pause();t.flush();}catch(RuntimeException ignored){}}}

  /** One piece of speech as 24 kHz PCM. Tries the TTS models in order. */
  private byte[] make(String text,String voice,String style) throws Exception{
    String key=keys.key();
    if(key==null||key.isEmpty())throw new IllegalStateException("no Gemini key");
    Exception last=null;
    for(int m=modelIndex;m<MODELS.length;m++){
      try{byte[] pcm=request(MODELS[m],key,text,voice,style);if(m!=modelIndex)modelIndex=m;return pcm;}
      catch(GeminiAccess.Failure f){
        last=f;
        if(f.status==404||f.status==400&&String.valueOf(f.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("model")){ControlLog.i("agentic voice: "+MODELS[m]+" not available, trying the next");modelIndex=m+1;continue;}
        throw f;
      }
    }
    throw last==null?new IllegalStateException("no TTS model"):last;
  }

  private static byte[] request(String model,String key,String text,String voice,String style) throws Exception{
    String said=style==null||style.isEmpty()?text:style+": "+text;
    JSONObject body=new JSONObject()
      .put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",said)))))
      .put("generationConfig",new JSONObject().put("responseModalities",new JSONArray().put("AUDIO"))
        .put("speechConfig",new JSONObject().put("voiceConfig",new JSONObject().put("prebuiltVoiceConfig",new JSONObject().put("voiceName",voice)))));
    HttpsURLConnection c=(HttpsURLConnection)new URL("https://generativelanguage.googleapis.com/v1beta/models/"+model+":generateContent").openConnection();
    try{
      c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setConnectTimeout(8000);c.setReadTimeout(PIECE_TIMEOUT_MS);c.setDoOutput(true);
      c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("x-goog-api-key",key);
      try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
      int status=c.getResponseCode();
      if(status/100!=2){String err=c.getErrorStream()==null?"":new String(read(c.getErrorStream(),65536),StandardCharsets.UTF_8);throw GeminiAccess.failure(status,err);}
      return pcm(new String(read(c.getInputStream(),16*1024*1024),StandardCharsets.UTF_8));
    }finally{c.disconnect();}
  }

  /** The audio in a TTS answer (base64 PCM in inlineData), joined. */
  static byte[] pcm(String answer) throws Exception{
    JSONArray parts=new JSONObject(answer).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    for(int i=0;i<parts.length();i++){
      JSONObject p=parts.getJSONObject(i),d=p.optJSONObject("inlineData");if(d==null)d=p.optJSONObject("inline_data");
      if(d!=null&&!d.optString("data").isEmpty()){byte[] b=Base64.getDecoder().decode(d.getString("data"));out.write(b,0,b.length);}
    }
    if(out.size()==0)throw new IllegalStateException("no audio in the answer");
    return out.toByteArray();
  }

  /**
   * The text cut into pieces to speak one after another: the first piece short (one sentence, so speech
   * starts soon), later pieces up to about 300 characters, never cutting inside a sentence unless it is very long.
   */
  static List<String> pieces(String text){
    List<String> out=new ArrayList<>();
    if(text==null)return out;
    String t=text.replaceAll("[*_#`]+","").replaceAll("\\s+"," ").trim();
    if(t.isEmpty())return out;
    List<String> sentences=new ArrayList<>();
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("[^.!?।؟\\n]+[.!?।؟]*").matcher(t);
    while(m.find()){String s=m.group().trim();if(!s.isEmpty())sentences.add(s);}
    StringBuilder cur=new StringBuilder();
    for(String s:sentences){
      while(s.length()>400){int cut=s.lastIndexOf(' ',380);if(cut<100)cut=380;flush(out,cur);out.add(s.substring(0,cut).trim());s=s.substring(cut).trim();}
      int limit=out.isEmpty()?120:300;
      if(cur.length()>0&&cur.length()+1+s.length()>limit)flush(out,cur);
      if(cur.length()>0)cur.append(' ');
      cur.append(s);
      if(out.isEmpty()&&cur.length()>=40)flush(out,cur);
    }
    flush(out,cur);
    return out;
  }
  private static void flush(List<String> out,StringBuilder cur){if(cur.length()>0){out.add(cur.toString().trim());cur.setLength(0);}}

  private static byte[] read(InputStream in,int max) throws Exception{
    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;
    while((n=in.read(b))>0){out.write(b,0,n);if(out.size()>max)throw new IllegalStateException("answer too large");}
    return out.toByteArray();
  }

}
