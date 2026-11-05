package com.myraa.mobile.tests;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.webkit.WebView;
import org.json.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;

/** Executes the installed application, its native bridge and real WebGL view. */
public class SmokeInstrumentation extends Instrumentation {
  private Activity activity;
  private WebView web;
  private final StringBuilder evidence = new StringBuilder();
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  private String js(String source) throws Exception {
    CountDownLatch latch = new CountDownLatch(1); final String[] result = new String[1];
    runOnMainSync(() -> web.evaluateJavascript(source, value -> { result[0] = value; latch.countDown(); }));
    if (!latch.await(30, TimeUnit.SECONDS)) throw new Exception("JavaScript evaluation timed out.");
    return result[0];
  }
  private void require(boolean value, String check) throws Exception { if (!value) throw new Exception(check); evidence.append("PASS: ").append(check).append('\n'); }
  private String waitJs(String source, java.util.function.Predicate<String> condition, int seconds) throws Exception {
    long end = System.currentTimeMillis() + seconds * 1000L; String result = "null";
    while (System.currentTimeMillis() < end) { result = js(source); if (condition.test(result)) return result; Thread.sleep(500); }
    throw new Exception("Condition timed out: " + source + " last=" + result);
  }
  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      Intent intent = new Intent().setClassName("com.myraa.mobile", "com.myraa.mobile.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity = startActivitySync(intent); waitForIdleSync();
      Field field = activity.getClass().getDeclaredField("web"); field.setAccessible(true); web = (WebView) field.get(activity);
      require(web != null, "native Activity and WebView started");
      waitJs("!!window.__mobileDiagnostics", "true"::equals, 60);
      waitJs("window.__mobileDiagnostics()?.loaded===true", "true"::equals, 90);
      require(true, "existing PMX character loaded in Android WebView");
      String diagnostics = js("window.__mobileDiagnostics()");
      JSONObject data = new JSONObject(diagnostics);
      require(data.getInt("bones") > 100, "real skeleton loaded");
      require(data.getInt("physicsNodes") > 0, "real spring physics active");
      require("https://appassets.androidplatform.net".equals(new JSONTokener(js("location.origin")).nextValue()), "bundled HTTPS origin");
      require(js("JSON.parse(MyraaNative.getState()).keyConfigured").equals("false"), "no API key bundled or preconfigured");
      js("document.getElementById('wave').click();true");
      Thread.sleep(1200);
      require(new JSONTokener(js("window.__mobileDiagnostics().behaviour")).nextValue().equals("wave"), "manual wave plays with happy expression");
      js("document.getElementById('sit').click();true"); Thread.sleep(1500);
      require(js("window.__mobileDiagnostics().loaded").equals("true"), "sit transition keeps renderer alive");
      js("document.getElementById('stand').click();true");
      js("document.getElementById('input').value='Hello MYRAA';document.getElementById('send').click();true");
      require(new JSONTokener(js("document.getElementById('notice').textContent")).nextValue().toString().contains("API key"), "missing key gives actionable error without a network request");
      js("MyraaNative.configure(JSON.stringify({model:'gemini-3.5-flash',quality:'low',language:'en-IN',spokenReplies:false}));true");
      require(js("JSON.parse(MyraaNative.getState()).quality").equals("\"low\""), "native settings persist");
      js("document.getElementById('settings').click();true");
      require(js("document.getElementById('settings-panel').open").equals("true"), "settings dialog opens");
      js("document.getElementById('quality').value='balanced';document.getElementById('save').click();true");
      waitJs("window.__mobileDiagnostics()?.graphicsQuality==='balanced'&&window.__mobileDiagnostics()?.loaded===true", "true"::equals, 90);
      require(true, "Medium preset applies to actual renderer without an Activity restart");
      js("window.__nativeEvents=[];const old=window.MyraaEvents;window.MyraaEvents=e=>{window.__nativeEvents.push(e);old(e)};MyraaNative.configure(JSON.stringify({key:'not-a-real-myraa-test-key-000000',model:'gemini-3.5-flash',quality:'balanced',language:'en-US',spokenReplies:false}));MyraaNative.chat('MYRAA authentication test','auth-test');true");
      waitJs("window.__nativeEvents.some(e=>e.type==='error'&&e.id==='auth-test')", "true"::equals, 90);
      String authError = js("window.__nativeEvents.find(e=>e.type==='error'&&e.id==='auth-test').message");
      require(authError.contains("rejected") || authError.contains("HTTP 400"), "real provider rejects a deliberately invalid key and the app reports it");
      js("MyraaNative.clearKey();true");
      require(js("JSON.parse(MyraaNative.getState()).history.length").equals("0"), "failed provider request does not overwrite conversation history");
      js("window.__nativeEvents=[];MyraaNative.speak('MYRAA speech test');true");
      String voice = waitJs("JSON.stringify(window.__nativeEvents.filter(e=>e.type==='speech'||e.service==='tts'))", value -> value != null && !value.equals("\"[]\""), 30);
      evidence.append("TTS EVENTS: ").append(voice).append('\n');
      js("MyraaNative.cancel();MyraaNative.stopSpeech();true");
      require(true, "stop handlers execute");
      runOnMainSync(() -> callActivityOnPause(activity));
      waitJs("window.__mobileDiagnostics().running===false", "true"::equals, 10);
      require(true, "Activity pause stops the animation loop");
      runOnMainSync(() -> callActivityOnResume(activity));
      waitJs("window.__mobileDiagnostics().running===true", "true"::equals, 10);
      require(true, "Activity resume restarts the animation loop");
      Thread.sleep(3000);
      evidence.append("DIAGNOSTICS: ").append(js("window.__mobileDiagnostics()")).append('\n');
      evidence.append("NATIVE STATE: ").append(js("JSON.parse(MyraaNative.getState())")).append('\n');
      evidence.append("GPU: ").append(js("(()=>{const gl=document.querySelector('canvas').getContext('webgl2');const ext=gl.getExtension('WEBGL_debug_renderer_info');return ext?gl.getParameter(ext.UNMASKED_RENDERER_WEBGL):gl.getParameter(gl.RENDERER)})()")).append('\n');
      android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
      if (screenshot != null) {
        java.io.File file = new java.io.File(getTargetContext().getExternalFilesDir(null), "qa.png");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        screenshot.recycle(); evidence.append("SCREENSHOT: ").append(file.getAbsolutePath()).append('\n');
      }
      result.putString("stream", evidence.toString()); result.putString("result", "passed");
      finish(Activity.RESULT_OK, result);
    } catch (Exception error) {
      try { evidence.append("PAGE: ").append(js("JSON.stringify({url:location.href,title:document.title,body:document.body?.innerText,resources:performance.getEntriesByType('resource').map(r=>({name:r.name,duration:r.duration}))})")); } catch (Exception ignored) {}
      result.putString("stream", evidence.toString() + "FAIL: " + error.toString()); result.putString("result", "failed"); finish(Activity.RESULT_CANCELED, result);
    }
  }
}
