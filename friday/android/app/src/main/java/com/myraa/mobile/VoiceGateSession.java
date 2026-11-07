package com.myraa.mobile;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Runs a VoiceGate for one Live call on a background thread. The WebView hands it each microphone
 * frame (16 kHz 16-bit PCM, base64) with feed() and gets back, in order, what may be sent to
 * Gemini: {"a":[["o","<owner pcm base64>"],["z",<silent samples>]],"e":[["owner",0.81]],"s":"STATE"}.
 * Nothing waits on the speaker check: feed() returns at once with whatever is ready.
 *
 * If the gate falls more than 8 s behind (a very slow phone) it reports "fail" and the call goes
 * on without Voice ID, which the call's diagnostics show.
 */
final class VoiceGateSession {
  /** States the Live call can be in, as shown in diagnostics. */
  static final String IDLE="IDLE",LISTENING="LISTENING",PROCESSING="PROCESSING_COMMAND",AI_SPEAKING="AI_SPEAKING",BARGE_IN="OWNER_BARGE_IN",ERROR="ERROR";
  private static final int MAX_BACKLOG_SAMPLES=8*VoiceGate.RATE;

  private final VoiceGate gate;
  private final LinkedBlockingQueue<short[]> input=new LinkedBlockingQueue<>();
  private final Object lock=new Object();
  private final List<Object[]> audio=new ArrayList<>();
  private final List<Object[]> events=new ArrayList<>();
  private final Thread worker;
  private volatile boolean running=true;
  private volatile String failure;
  private volatile int ai;
  private final AtomicInteger backlog=new AtomicInteger();
  private ByteArrayOutputStream ownerRun;

  VoiceGateSession(VoiceGate gate){
    this.gate=gate;
    worker=new Thread(this::loop,"friday-voice-gate");
    worker.setPriority(Thread.NORM_PRIORITY+1);
    worker.start();
  }

  private final VoiceGate.Output out=new VoiceGate.Output(){
    @Override public void owner(short[] pcm){
      synchronized(lock){
        if(ownerRun==null){ownerRun=new ByteArrayOutputStream();audio.add(new Object[]{"o",ownerRun});}
        ByteBuffer b=ByteBuffer.allocate(pcm.length*2).order(ByteOrder.LITTLE_ENDIAN);for(short s:pcm)b.putShort(s);
        ownerRun.write(b.array(),0,pcm.length*2);
      }
    }
    @Override public void silence(int samples){
      synchronized(lock){
        ownerRun=null;
        Object[] last=audio.isEmpty()?null:audio.get(audio.size()-1);
        if(last!=null&&"z".equals(last[0]))last[1]=(Integer)last[1]+samples;else audio.add(new Object[]{"z",samples});
      }
    }
    @Override public void event(String name,double score){
      synchronized(lock){events.add(new Object[]{name,score});}
      ControlLog.i("voice id: "+name+(Double.isNaN(score)?"":String.format(Locale.ROOT," (voice %.2f, needs %.2f, check %d ms)",score,gate.threshold(),gate.checkMs)));
    }
  };

  private void loop(){
    try{
      while(running){
        short[] pcm=input.poll(500,TimeUnit.MILLISECONDS);
        if(pcm==null)continue;
        gate.push(pcm,pcm.length,out);
        backlog.addAndGet(-pcm.length);
      }
    }catch(InterruptedException e){Thread.currentThread().interrupt();}
    catch(RuntimeException e){failure="Voice ID stopped: "+e.getClass().getSimpleName();ControlLog.i("voice id: "+failure);}
  }

  /** ai: 0 FRIDAY is listening, 1 thinking, 2 speaking. Returns the JSON described above. */
  String feed(String base64,int ai){
    this.ai=ai;
    JSONObject result=new JSONObject();
    try{
      if(failure!=null)return result.put("fail",failure).toString();
      if(base64!=null&&!base64.isEmpty()){
        byte[] raw=Base64.getDecoder().decode(base64);
        short[] pcm=new short[raw.length/2];ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        if(backlog.addAndGet(pcm.length)>MAX_BACKLOG_SAMPLES){failure="Voice ID fell behind on this phone";ControlLog.i("voice id: "+failure);return result.put("fail",failure).toString();}
        input.add(pcm);
      }
      JSONArray a=new JSONArray(),e=new JSONArray();
      synchronized(lock){
        for(Object[] item:audio){
          if("o".equals(item[0]))a.put(new JSONArray().put("o").put(Base64.getEncoder().encodeToString(((ByteArrayOutputStream)item[1]).toByteArray())));
          else a.put(new JSONArray().put("z").put(item[1]));
        }
        for(Object[] ev:events)e.put(new JSONArray().put(ev[0]).put(Double.isNaN((Double)ev[1])?-1:Math.round((Double)ev[1]*1000)/1000.0));
        audio.clear();events.clear();ownerRun=null;
      }
      if(a.length()>0)result.put("a",a);
      if(e.length()>0)result.put("e",e);
      return result.put("s",state()).toString();
    }catch(JSONException|IllegalArgumentException ex){
      return "{\"s\":\"ERROR\"}";
    }
  }

  /** The call's state: the gate's own state, or what FRIDAY is doing when nobody is talking. */
  String state(){
    if(failure!=null)return ERROR;
    VoiceGate.State s=gate.state();
    if(s==VoiceGate.State.AUTHORIZED_USER&&ai==2)return BARGE_IN;
    if(s!=VoiceGate.State.LISTENING)return s.name();
    return ai==2?AI_SPEAKING:ai==1?PROCESSING:LISTENING;
  }
  String diagnostics(){
    return String.format(Locale.ROOT,"Voice ID on · owner %d · ignored %d · checks %d · last voice %s · needs %.2f · last check %d ms%s",
      gate.owners,gate.unknowns,gate.checks,Double.isNaN(gate.lastScore)?"-":String.format(Locale.ROOT,"%.2f",gate.lastScore),gate.threshold(),gate.checkMs,failure==null?"":" · "+failure);
  }
  void stop(){running=false;worker.interrupt();input.clear();}
}
