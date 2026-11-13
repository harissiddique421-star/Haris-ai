package com.myraa.mobile;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Agentic mode's ear with Voice ID: FRIDAY's own microphone goes through the same VoiceGate as a Live
 * call, so only the owner's verified voice is kept. When the owner's turn ends, that audio alone (other
 * voices are already silence) is turned into text by a Transcriber (Gemini), and the text is reported
 * like AgentEar's "final". Other voices are reported as "ignored" / "unclear" and never transcribed.
 *
 * Paused while FRIDAY speaks: audio is dropped and the gate starts fresh on resume, so her own voice and
 * a half-checked burst never become a turn. Three threads: microphone reader, gate, transcription.
 */
final class GatedEar implements Ear {
  interface Transcriber { String transcribe(byte[] wav,String language) throws Exception; }

  /** Shortest owner speech worth transcribing (0.35 s), and the longest kept (30 s, as the gate's turn limit). */
  static final int MIN_BYTES=VoiceGate.RATE*2*35/100,MAX_BYTES=VoiceGate.RATE*2*30;

  private final Supplier<VoiceGate> gates;
  private final Transcriber transcriber;
  private final AgentEar.Listener listener;
  private final LinkedBlockingQueue<short[]> input=new LinkedBlockingQueue<>();
  private final ExecutorService text=Executors.newSingleThreadExecutor();
  private volatile boolean on,paused;
  private volatile String language="en-IN";
  private volatile int generation;
  private Thread reader,worker;
  private volatile MicInput mic;

  GatedEar(Supplier<VoiceGate> gates,Transcriber transcriber,AgentEar.Listener listener){this.gates=gates;this.transcriber=transcriber;this.listener=listener;}

  @Override public synchronized void start(String lang){
    stop();
    language=AgentEar.language(lang);on=true;paused=false;int g=++generation;input.clear();
    MicInput m;
    try{m=MicInput.open();m.start();}
    catch(RuntimeException e){on=false;listener.heard("error","The microphone is busy. Close other apps that use it, then start again.",-1);return;}
    mic=m;
    ControlLog.i("agentic: Voice ID ear on ("+m.effects()+")");
    reader=new Thread(()->read(m,g),"friday-agent-mic");reader.start();
    worker=new Thread(()->gate(g),"friday-agent-gate");worker.setPriority(Thread.NORM_PRIORITY+1);worker.start();
    listener.heard("ready","",0);
  }

  @Override public synchronized void stop(){
    on=false;generation++;
    MicInput m=mic;mic=null;
    if(m!=null)m.stop();
    if(reader!=null)reader.interrupt();if(worker!=null)worker.interrupt();
    if(reader!=null)try{reader.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    if(m!=null)try{m.close();}catch(RuntimeException ignored){}
    reader=null;worker=null;input.clear();
  }

  @Override public void pause(boolean p){paused=p;if(p)input.clear();}
  @Override public boolean active(){return on;}
  void shutdown(){stop();text.shutdownNow();}

  private void read(MicInput m,int g){
    short[] buf=new short[VoiceGate.CHUNK];
    while(on&&g==generation){
      if(!m.readFully(buf)){if(on&&g==generation){on=false;listener.heard("error","The microphone stopped. Start again.",-1);}return;}
      if(!paused)input.offer(buf.clone());
    }
  }

  private void gate(int g){
    VoiceGate gate=null;boolean wasPaused=false;
    ByteArrayOutputStream turn=new ByteArrayOutputStream();
    Turn out=new Turn(turn,g);
    try{
      while(on&&g==generation){
        short[] pcm=input.poll(300,TimeUnit.MILLISECONDS);
        if(paused){wasPaused=true;continue;}
        if(pcm==null)continue;
        if(gate==null||wasPaused){
          // Fresh gate after FRIDAY spoke: nothing she said and no half-checked burst carries over.
          if(gate==null){gate=gates.get();gate.warmUp();}else{long ms=gate.checkMs;gate=gates.get();gate.checkMs=ms;}
          wasPaused=false;turn.reset();
        }
        gate.push(pcm,pcm.length,out);
        if(!gate.report.isEmpty()){for(String line:gate.report)ControlLog.i("agentic voice id: "+line);gate.report.clear();}
      }
    }catch(InterruptedException e){Thread.currentThread().interrupt();}
    catch(RuntimeException e){if(g==generation){on=false;ControlLog.i("agentic: Voice ID ear stopped ("+e.getClass().getSimpleName()+")");listener.heard("error","Voice ID stopped working. Start again, or turn Voice ID off in Settings.",-1);}}
  }

  /** Collects the owner's verified audio for one turn; other voices only end up as silence. */
  private final class Turn implements VoiceGate.Output {
    private final ByteArrayOutputStream turn;private final int g;
    Turn(ByteArrayOutputStream turn,int g){this.turn=turn;this.g=g;}
    @Override public void owner(short[] pcm){
      if(turn.size()>=MAX_BYTES)return;
      ByteBuffer b=ByteBuffer.allocate(pcm.length*2).order(ByteOrder.LITTLE_ENDIAN);for(short s:pcm)b.putShort(s);
      turn.write(b.array(),0,pcm.length*2);
    }
    @Override public void silence(int samples){}
    @Override public void event(String name,double score){
      if(g!=generation||paused)return;
      String s=Double.isNaN(score)?"":String.format(Locale.ROOT," (voice %.2f)",score);
      if("owner".equals(name)){listener.heard("speech","",0);return;}
      if("unknown".equals(name)){ControlLog.i("agentic voice id: ignored another voice"+s);listener.heard("ignored","",0);return;}
      if("unclear".equals(name)||"low".equals(name)){ControlLog.i("agentic voice id: not sure it was the owner"+s);listener.heard("unclear","",0);return;}
      if(!"end".equals(name))return;
      byte[] pcm=turn.toByteArray();turn.reset();
      if(pcm.length<MIN_BYTES)return;
      listener.heard("partial","…",0);
      String lang=language;
      text.execute(()->{
        if(g!=generation)return;
        long t=System.currentTimeMillis();
        try{
          String said=clean(transcriber.transcribe(wav(pcm),lang));
          ControlLog.i("agentic: owner speech "+(pcm.length/32)+" ms turned into text in "+(System.currentTimeMillis()-t)+" ms"+(said.isEmpty()?" (no words)":""));
          if(g==generation&&!said.isEmpty())listener.heard("final",said,0);
        }catch(Exception e){
          ControlLog.i("agentic: speech to text failed ("+e.getClass().getSimpleName()+")");
          if(g==generation)listener.heard("unclear","",1);
        }
      });
    }
  }

  /** The transcriber's answer as one line of the user's words; empty when it heard none. */
  static String clean(String s){
    if(s==null)return "";
    String t=s.replaceAll("[\\r\\n]+"," ").replaceAll("\\s+"," ").trim();
    if(t.startsWith("\"")&&t.endsWith("\"")&&t.length()>1)t=t.substring(1,t.length()-1).trim();
    String low=t.toLowerCase(Locale.ROOT).replaceAll("[^a-z]","");
    if(low.isEmpty()&&t.replaceAll("[\\p{P}\\s]","").isEmpty())return "";
    if(low.equals("none")||low.equals("nospeech")||low.equals("silence")||low.equals("inaudible"))return "";
    return t.length()>1000?t.substring(0,1000):t;
  }

  /** 16 kHz mono 16-bit PCM as a WAV file. */
  static byte[] wav(byte[] pcm){
    ByteBuffer b=ByteBuffer.allocate(44+pcm.length).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36+pcm.length).put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    b.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(VoiceGate.RATE).putInt(VoiceGate.RATE*2).putShort((short)2).putShort((short)16);
    b.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
    return b.array();
  }
}
