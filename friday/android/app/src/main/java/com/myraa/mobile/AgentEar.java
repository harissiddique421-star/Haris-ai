package com.myraa.mobile;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/**
 * Agentic mode's ear: Android's speech recognizer, restarted after every sentence while the mode is on,
 * so the user can keep talking. It is paused while FRIDAY speaks (so she does not hear herself) and
 * reports partial words, the final sentence, and problems. Runs on the main thread, as Android requires.
 * en-IN writes Roman Urdu / Hinglish in Latin letters; hi-IN, ur-PK and en-US are the other choices.
 */
final class AgentEar {
  interface Listener { void heard(String kind, String text, int code); }

  private final Context context;private final Listener listener;private final Handler main=new Handler(Looper.getMainLooper());
  private SpeechRecognizer recognizer;private boolean on,paused,listening;private String language="en-IN";private int quickErrors;

  AgentEar(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}

  static String language(String l){return "hi-IN".equals(l)||"ur-PK".equals(l)||"en-US".equals(l)?l:"en-IN";}

  void start(String lang){main.post(()->{language=language(lang);on=true;paused=false;quickErrors=0;listen();});}
  void stop(){main.post(()->{on=false;listening=false;destroy();});}
  /** Pause while FRIDAY speaks; resume when she is done. */
  void pause(boolean p){main.post(()->{paused=p;if(p){listening=false;if(recognizer!=null)try{recognizer.cancel();}catch(RuntimeException ignored){}}else if(on)listen();});}
  boolean active(){return on;}

  private void destroy(){if(recognizer!=null){try{recognizer.destroy();}catch(RuntimeException ignored){}recognizer=null;}}

  private void listen(){
    if(!on||paused||listening)return;
    if(!SpeechRecognizer.isRecognitionAvailable(context)){on=false;listener.heard("error","No speech recognizer is installed. Install or update the Google app.",-1);return;}
    if(recognizer==null){
      recognizer=SpeechRecognizer.createSpeechRecognizer(context);
      recognizer.setRecognitionListener(new RecognitionListener(){
        public void onReadyForSpeech(Bundle b){listener.heard("ready","",0);}
        public void onBeginningOfSpeech(){listener.heard("speech","",0);}
        public void onRmsChanged(float v){}
        public void onBufferReceived(byte[] b){}
        public void onEndOfSpeech(){}
        public void onEvent(int t,Bundle b){}
        public void onPartialResults(Bundle b){String t=top(b);if(!t.isEmpty())listener.heard("partial",t,0);}
        public void onResults(Bundle b){listening=false;quickErrors=0;String t=top(b);if(!t.isEmpty())listener.heard("final",t,0);again(150);}
        public void onError(int error){
          listening=false;
          if(error==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS){on=false;listener.heard("error","Allow the microphone for FRIDAY, then start again.",error);return;}
          // No speech or no match is normal between sentences: just listen again. Repeated real errors back off.
          boolean quiet=error==SpeechRecognizer.ERROR_NO_MATCH||error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
          if(!quiet){quickErrors++;if(error==SpeechRecognizer.ERROR_RECOGNIZER_BUSY||error==SpeechRecognizer.ERROR_CLIENT)destroy();}
          if(quickErrors>=6){on=false;listener.heard("error","The speech recognizer keeps failing (error "+error+"). Check the internet and the Google app, then start again.",error);return;}
          again(quiet?100:Math.min(3000,400*quickErrors));
        }
      });
    }
    Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE,language).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
      .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1).putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,context.getPackageName())
      // Let the user pause briefly inside a sentence; FRIDAY's own turn check waits for unfinished sentences.
      .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,1200L)
      .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,1000L);
    try{recognizer.startListening(intent);listening=true;}
    catch(RuntimeException e){listening=false;destroy();quickErrors++;if(quickErrors<6)again(800);else{on=false;listener.heard("error","The speech recognizer couldn't start.",-1);}}
  }
  private void again(long ms){main.postDelayed(this::listen,ms);}
  private static String top(Bundle b){
    ArrayList<String> r=b==null?null:b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    return r==null||r.isEmpty()||r.get(0)==null?"":r.get(0).trim();
  }
}
