package com.myraa.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;
import java.util.List;

/**
 * MediaPipe's gesture recogniser (Apache-2.0) running on the phone in video mode: it finds one hand in each
 * picture and names its shape (Open_Palm, Thumb_Up, Victory, ...). The picture never leaves the phone.
 * Built only when the MediaPipe library is present (see HandShapes).
 */
final class MediaPipeHands implements HandShapes {
  static final String MODEL="mediapipe/gesture_recognizer.task";
  private final GestureRecognizer recognizer;
  private long lastMs=-1;

  MediaPipeHands(Context c){
    GestureRecognizer.GestureRecognizerOptions options=GestureRecognizer.GestureRecognizerOptions.builder()
      .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
      .setRunningMode(RunningMode.VIDEO)
      .setNumHands(1)
      .setMinHandDetectionConfidence(0.6f)
      .setMinHandPresenceConfidence(0.6f)
      .setMinTrackingConfidence(0.5f)
      .build();
    recognizer=GestureRecognizer.createFromOptions(c,options);
    ControlLog.i("gestures: hand shape model ready");
  }

  @Override public synchronized Object[] detect(Bitmap upright,long ms){
    if(ms<=lastMs)ms=lastMs+1; // video mode needs rising times
    lastMs=ms;
    MPImage image=new BitmapImageBuilder(upright).build();
    GestureRecognizerResult r=recognizer.recognizeForVideo(image,ms);
    List<List<Category>> all=r.gestures();
    if(all==null||all.isEmpty()||all.get(0).isEmpty())return null;
    Category best=all.get(0).get(0);
    return new Object[]{best.categoryName(),best.score()};
  }

  @Override public synchronized void close(){try{recognizer.close();}catch(RuntimeException ignored){}}
}
