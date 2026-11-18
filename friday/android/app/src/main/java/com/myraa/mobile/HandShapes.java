package com.myraa.mobile;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * The on-device hand shape model (MediaPipe gesture recogniser), seen from the app through this small interface.
 * The MediaPipe code lives in android/app/src/mediapipe and is built in only when scripts/fetch-mediapipe.sh
 * fetched the library and the model (the Linux/CI build). Where it is missing or cannot run on a phone, open()
 * returns null and gesture mode keeps working with motion gestures only.
 */
interface HandShapes {
  /** The best hand shape in this upright picture at time ms: {name, score 0..1}, or null when there is no hand. */
  Object[] detect(Bitmap upright,long ms);
  void close();

  /** The model, or null when this APK or phone cannot run it. Call off the main thread (loading takes a moment). */
  static HandShapes open(Context c){
    try{
      Class<?> k=Class.forName("com.myraa.mobile.MediaPipeHands");
      return (HandShapes)k.getDeclaredConstructor(Context.class).newInstance(c.getApplicationContext());
    }catch(Throwable t){
      ControlLog.i("gestures: hand shape model unavailable ("+t.getClass().getSimpleName()+"), motion gestures only");
      return null;
    }
  }
}
