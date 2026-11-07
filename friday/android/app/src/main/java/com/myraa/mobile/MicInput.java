package com.myraa.mobile;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import java.util.ArrayList;
import java.util.List;

/**
 * FRIDAY's own microphone for the wake word, Voice Guardian and the wake test: 16 kHz mono
 * 16-bit PCM from the VOICE_RECOGNITION source, with the phone's audio pre-processing switched
 * on where the phone has it: noise suppression, acoustic echo cancellation (so FRIDAY's own
 * voice from the speaker is cancelled) and automatic gain control. On AOSP-based phones these
 * effects are the platform's WebRTC audio processing module; manufacturers may ship their own.
 * The Live call does not use this class: it captures through the WebView, whose getUserMedia
 * runs Chromium's WebRTC audio processing (echo cancellation, noise suppression, gain control).
 *
 * In software it adds an 80 Hz high-pass filter that removes DC offset and low rumble (fans,
 * traffic, handling noise) before speech detection. Everything is released in close().
 */
final class MicInput implements AutoCloseable {
  static final int RATE=16000;
  final AudioRecord record;
  private final List<AudioEffect> effects=new ArrayList<>();
  private final String active;
  // One-pole high-pass, cut-off 80 Hz: y[n] = a * (y[n-1] + x[n] - x[n-1]).
  private static final float HP_A=(float)(1/(1+2*Math.PI*80/RATE));
  private float hpX,hpY;

  private MicInput(AudioRecord r){
    record=r;StringBuilder s=new StringBuilder();int session=r.getAudioSessionId();
    if(add(NoiseSuppressor.isAvailable()?create("ns",session):null))s.append("noise suppression, ");
    if(add(AcousticEchoCanceler.isAvailable()?create("aec",session):null))s.append("echo cancellation, ");
    if(add(AutomaticGainControl.isAvailable()?create("agc",session):null))s.append("gain control, ");
    active=s.length()==0?"none":s.substring(0,s.length()-2);
  }
  /** Opens the microphone (not yet recording). Throws IllegalStateException when it is busy. */
  static MicInput open(){
    int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
    AudioRecord r=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,RATE*2));
    if(r.getState()!=AudioRecord.STATE_INITIALIZED){r.release();throw new IllegalStateException("microphone busy");}
    return new MicInput(r);
  }
  private static AudioEffect create(String kind,int session){
    try{
      AudioEffect e="ns".equals(kind)?NoiseSuppressor.create(session):"aec".equals(kind)?AcousticEchoCanceler.create(session):AutomaticGainControl.create(session);
      if(e!=null&&e.setEnabled(true)!=AudioEffect.SUCCESS){e.release();return null;}
      return e;
    }catch(RuntimeException e){return null;}
  }
  private boolean add(AudioEffect e){if(e==null)return false;effects.add(e);return true;}
  /** Which phone effects are on, for the log: e.g. "noise suppression, echo cancellation". */
  String effects(){return active;}
  void start(){
    record.startRecording();
    if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("not recording");
    hpX=0;hpY=0;
  }
  /** Fills buf completely with filtered audio. Returns false when the microphone stopped or failed. */
  boolean readFully(short[] buf){
    int got=0;
    while(got<buf.length){int n=record.read(buf,got,buf.length-got);if(n<=0)return false;got+=n;}
    for(int i=0;i<buf.length;i++){
      float x=buf[i],y=HP_A*(hpY+x-hpX);hpX=x;hpY=y;
      buf[i]=(short)Math.max(-32768,Math.min(32767,Math.round(y)));
    }
    return true;
  }
  /** Stops recording from any thread; a blocked readFully then returns false. */
  void stop(){try{record.stop();}catch(RuntimeException ignored){}}
  @Override public void close(){
    stop();
    for(AudioEffect e:effects){try{e.setEnabled(false);}catch(RuntimeException ignored){}try{e.release();}catch(RuntimeException ignored){}}
    effects.clear();record.release();
  }
}
