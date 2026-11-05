package com.myraa.mobile.tests;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.webkit.*;
import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.lang.reflect.*;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;

/** No Google traffic. Real activity/storage/UI with an injected metadata transport.
 * Only a unique QA preferences file is modified; the owner's file is hashed, never exported. */
public class AuthInstrumentation extends Instrumentation {
  private Activity activity;
  private WebView web;
  private Class<?> apiClass, factoryClass;
  private SharedPreferences isolated;
  private final StringBuilder evidence = new StringBuilder();
  private int checks;
  private static final String FIXTURE_KEY = "fixture-only-gemini-key-00000000000000";
  private static final String METADATA = "{\"name\":\"models/gemini-3.5-flash\",\"supportedGenerationMethods\":[\"generateContent\"]}";
  private MockConnection latest;

  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  private void require(boolean ok, String name) throws Exception {
    if (!ok) throw new Exception(name); checks++; evidence.append("PASS: ").append(name).append('\n');
  }
  private String js(String source) throws Exception {
    CountDownLatch latch = new CountDownLatch(1); String[] result = new String[1];
    runOnMainSync(() -> web.evaluateJavascript(source, value -> { result[0] = value; latch.countDown(); }));
    if (!latch.await(30, TimeUnit.SECONDS)) throw new Exception("JavaScript evaluation timed out.");
    return result[0];
  }
  private void waitTrue(String source) throws Exception {
    long deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) { if ("true".equals(js(source))) return; Thread.sleep(100); }
    throw new Exception("Expected UI state did not arrive.");
  }
  private String payload(String reason) throws Exception {
    return new JSONObject().put("error", new JSONObject().put("message", "Fixture response")
      .put("details", new JSONArray().put(new JSONObject().put("reason", reason)))).toString();
  }
  private Exception described(int status, String body) throws Exception { return (Exception) apiClass.getMethod("failure", int.class, String.class).invoke(null, status, body); }
  private String code(Exception error) throws Exception { return (String) error.getClass().getField("code").get(error); }
  private Object client(int status, String body) throws Exception {
    Object factory = Proxy.newProxyInstance(factoryClass.getClassLoader(), new Class<?>[]{factoryClass}, (proxy, method, args) -> {
      latest = new MockConnection((URL)args[0], status, body); return latest;
    });
    return apiClass.getConstructor(factoryClass).newInstance(factory);
  }
  private void provider(int status, String body) throws Exception {
    Field field = activity.getClass().getDeclaredField("provider"); field.setAccessible(true); field.set(activity, client(status, body));
  }
  private void events() throws Exception { js("window.__authEvents=[];true"); }
  private void saved() throws Exception { waitTrue("window.__authEvents.some(e=>e.type==='settings'&&e.status==='saved')"); }
  private byte[] ownerDigest() throws Exception {
    File file = new File(getTargetContext().getApplicationInfo().dataDir, "shared_prefs/myraa.mobile.v1.xml");
    if (!file.exists()) return new byte[0];
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new FileInputStream(file)) { byte[] buffer = new byte[4096]; int count; while ((count=input.read(buffer))!=-1) digest.update(buffer,0,count); }
    return digest.digest();
  }
  @Override public void onStart() {
    Bundle result = new Bundle(); byte[] original = null;
    try {
      apiClass = getTargetContext().getClassLoader().loadClass("com.myraa.mobile.GeminiAccess");
      factoryClass = getTargetContext().getClassLoader().loadClass("com.myraa.mobile.GeminiAccess$Connections");
      for (String reason : new String[]{"ACCOUNT_STATE_INVALID","CONSUMER_SUSPENDED","API_KEY_INVALID","API_KEY_EXPIRED","API_KEY_SERVICE_BLOCKED","SERVICE_DISABLED","API_KEY_HTTP_REFERRER_BLOCKED","API_KEY_IP_ADDRESS_BLOCKED","API_KEY_ANDROID_APP_BLOCKED"}) {
        require(code(described(403,payload(reason))).equals(reason), "safe structured provider reason: " + reason);
      }
      require(code(described(429,payload("API_KEY_INVALID"))).equals("QUOTA_EXCEEDED"),"quota is not mislabeled as an invalid key");
      require(code(described(503,payload("ACCOUNT_STATE_INVALID"))).equals("PROVIDER_UNAVAILABLE"),"server outage is not mislabeled as account suspension");
      require(code(described(404,"{}")).equals("MODEL_UNAVAILABLE"),"missing model gets its own diagnosis");
      String exposed = new JSONObject().put("error",new JSONObject().put("message","Key " + FIXTURE_KEY + " was reported as leaked")).toString();
      Exception failure = described(403,exposed);
      require(code(failure).equals("API_KEY_LEAKED")&&!failure.getMessage().contains(FIXTURE_KEY),"leaked-key error excludes provider payload and credential");
      require(!described(400,"not JSON " + FIXTURE_KEY).getMessage().contains(FIXTURE_KEY),"malformed provider payload stays private");

      original = ownerDigest();
      activity = startActivitySync(new Intent().setClassName("com.myraa.mobile","com.myraa.mobile.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      isolated = getTargetContext().getSharedPreferences("qa.auth." + UUID.randomUUID(),Context.MODE_PRIVATE);
      isolated.edit().putString("history","[{\"role\":\"user\",\"text\":\"QA history sentinel\",\"at\":1}]").commit();
      Field prefs = activity.getClass().getDeclaredField("prefs"); prefs.setAccessible(true); prefs.set(activity,isolated);
      Field view = activity.getClass().getDeclaredField("web"); view.setAccessible(true); web=(WebView)view.get(activity);
      require(web!=null,"real installed activity started with isolated QA preferences");
      // These are authentication/UI tests. Avoid loading the 3D model on a
      // memory-constrained host; rendering has separate runtime evidence.
      runOnMainSync(()->{
        WebViewClient originalClient=web.getWebViewClient();
        web.setWebViewClient(new WebViewClient(){
          @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){
            if(request.getUrl().getPath().endsWith(".pmx"))return new WebResourceResponse("text/plain","UTF-8",404,"QA model omitted",null,new ByteArrayInputStream(new byte[0]));
            return originalClient.shouldInterceptRequest(v,request);
          }
          @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){return originalClient.shouldOverrideUrlLoading(v,request);}
          @Override public void onPageFinished(WebView v,String url){originalClient.onPageFinished(v,url);}
        });
      });
      waitTrue("!!window.MyraaEvents&&!!document.getElementById('check-connection')");
      js("window.__authEvents=[];window.__originalEvents=window.MyraaEvents;window.MyraaEvents=e=>{window.__authEvents.push(e);window.__originalEvents(e)};window.MyraaEvents({type:'state',state:JSON.parse(MyraaNative.getState())});true");
      provider(200,METADATA);
      js("document.getElementById('settings').click();document.getElementById('key').value='"+FIXTURE_KEY+"';document.getElementById('save').click();true"); saved();
      String ciphertext=isolated.getString("keyCipher","");
      require(!ciphertext.isEmpty()&&!ciphertext.contains(FIXTURE_KEY),"verified candidate is encrypted before storage");
      require(latest.getURL().getHost().equals("generativelanguage.googleapis.com")&&!latest.getURL().toString().contains(FIXTURE_KEY)&&FIXTURE_KEY.equals(latest.getRequestProperty("x-goog-api-key")),"credential travels in a header to the pinned Google host, not the URL");
      require("GET".equals(latest.getRequestMethod())&&!latest.getInstanceFollowRedirects()&&latest.closed,"validation is metadata-only, refuses redirects and closes its connection");
      require(js("JSON.parse(MyraaNative.getState()).keyVerified").equals("true"),"saved verification state is honest");
      require(isolated.getString("history","").contains("QA history sentinel"),"successful settings change preserves conversation history");

      provider(403,payload("CONSUMER_SUSPENDED")); events();
      js("document.getElementById('settings').click();document.getElementById('key').value='fixture-rejected-candidate-000000';document.getElementById('model').value='gemini-test-model';document.getElementById('save').click();true");
      waitTrue("window.__authEvents.some(e=>e.type==='settings'&&e.status==='error'&&e.code==='CONSUMER_SUSPENDED')");
      require(ciphertext.equals(isolated.getString("keyCipher",""))&&"gemini-3.5-flash".equals(isolated.getString("model","")),"rejected replacement preserves the previous key and model atomically");
      require("true".equals(js("document.getElementById('settings-panel').open&&!document.getElementById('save').disabled&&document.getElementById('key').value==='fixture-rejected-candidate-000000'")),"failed save keeps the dialog and masked draft available for correction");
      require("true".equals(js("document.getElementById('settings-status').textContent.includes('suspended')&&!document.getElementById('settings-status').textContent.includes('fixture-rejected')")),"UI shows exact Google suspension without echoing the key");

      provider(200,METADATA); events();
      js("document.getElementById('check-connection').click();true");
      waitTrue("window.__authEvents.some(e=>e.type==='settings'&&e.status==='checked')");
      require(ciphertext.equals(isolated.getString("keyCipher","")),"checking an unsaved candidate does not replace the saved credential");
      require("true".equals(js("document.getElementById('model').value==='gemini-test-model'&&document.getElementById('key').value==='fixture-rejected-candidate-000000'")),"connection check preserves unsaved model and key drafts");

      provider(403,payload("API_KEY_INVALID")); events();
      js("document.getElementById('key').value='';document.getElementById('model').value='gemini-3.5-flash';document.getElementById('quality').value='balanced';document.getElementById('save').click();true"); saved();
      require("balanced".equals(isolated.getString("quality",""))&&ciphertext.equals(isolated.getString("keyCipher","")),"offline graphics changes do not validate or replace the saved key");
      require(!js("MyraaNative.getState()").contains(FIXTURE_KEY),"native state never exports plaintext credentials");
      require(isolated.getString("history","").contains("QA history sentinel"),"authentication checks preserve existing history");
      // Real WebView getUserMedia and PC audio engine; simulated token/socket, no Google traffic.
      provider(200,"{\"name\":\"auth_tokens/fixture-live-only\"}");
      js("window.__livePcm=0;window.__liveSocket=null;window.WebSocket=class {static OPEN=1;readyState=0;constructor(url){window.__liveSocket=this;window.__liveEndpoint=url.split('?')[0];setTimeout(()=>{if(this.readyState!==3){this.readyState=1;this.onopen?.({})}},25)}send(raw){const d=JSON.parse(raw);if(d.setup){window.__liveSetup=d.setup;setTimeout(()=>this.onmessage?.({data:JSON.stringify({setupComplete:{}})}),5)}else if(d.realtimeInput?.audio){window.__livePcm++;window.__liveMime=d.realtimeInput.audio.mimeType}}close(){this.readyState=3}};const originalGetMedia=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);navigator.mediaDevices.getUserMedia=async(...args)=>{const stream=await originalGetMedia(...args);window.__liveMedia=stream;return stream};true");
      js("document.getElementById('voice').click();true");
      waitTrue("window.__livePcm>0");
      require("POST".equals(latest.getRequestMethod())&&latest.getURL().getPath().equals("/v1beta/auth_tokens"),"live credential is minted natively at the pinned endpoint");
      JSONObject tokenRequest=new JSONObject(latest.written.toString("UTF-8"));
      require(tokenRequest.optInt("uses")==1&&tokenRequest.has("expireTime")&&tokenRequest.has("newSessionExpireTime")&&!tokenRequest.toString().contains(FIXTURE_KEY),"ephemeral token is single-use and expires without exposing the durable key");
      require(FIXTURE_KEY.equals(latest.getRequestProperty("x-goog-api-key"))&&!latest.getURL().toString().contains(FIXTURE_KEY)&&latest.closed,"live provisioning retains header-only credentials and closes native connection");
      require("true".equals(js("window.__liveEndpoint.endsWith('BidiGenerateContentConstrained')&&window.__liveSetup.generationConfig.responseModalities[0]==='AUDIO'")),"in-app websocket requests native Gemini audio");
      require("true".equals(js("window.__liveMime==='audio/pcm;rate=16000'&&window.__liveMedia.getAudioTracks().some(t=>t.readyState==='live')")),"real Android WebView microphone streams 16kHz PCM without recognizer activity");
      js("window.__liveSocket.onmessage({data:JSON.stringify({serverContent:{inputTranscription:{text:'Live user'},outputTranscription:{text:'Live MYRAA'}}})});true");
      waitTrue("document.getElementById('messages').textContent.includes('Live MYRAA')");
      require(true,"Gemini live captions update the bundled conversation UI");
      js("window.__liveSocket.onmessage({data:JSON.stringify({serverContent:{modelTurn:{parts:[{inlineData:{mimeType:'audio/pcm;rate=24000',data:btoa(String.fromCharCode(...new Uint8Array(48000)))}}]}}})});true");
      waitTrue("document.getElementById('presence').textContent==='Speaking with you'");
      require(true,"Android WebAudio plays Gemini native PCM without platform TTS");
      Class<?> serviceClass=activity.getClassLoader().loadClass("com.myraa.mobile.LiveVoiceService");
      require(serviceClass.getField("running").getBoolean(null),"explicit Live call has an ongoing microphone foreground service");
      int pcmBefore=Integer.parseInt(js("window.__livePcm"));
      runOnMainSync(()->activity.startActivity(new Intent().setClassName("com.myraa.mobile.tests","com.myraa.mobile.tests.ExternalTestActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
      waitTrue("window.__livePcm>"+(pcmBefore+3)+"&&window.__liveMedia.getAudioTracks().some(t=>t.readyState==='live')");
      require(true,"real microphone PCM continues while a different app is foreground");
      android.os.ParcelFileDescriptor command=getUiAutomation().executeShellCommand("am start -n com.myraa.mobile/.MainActivity");new android.os.ParcelFileDescriptor.AutoCloseInputStream(command).close();Thread.sleep(400);
      runOnMainSync(()->activity.startService(new Intent().setClassName("com.myraa.mobile","com.myraa.mobile.LiveVoiceService").setAction("com.myraa.mobile.STOP_LIVE")));

      waitTrue("window.__liveMedia.getTracks().every(t=>t.readyState==='ended')");
      require("true".equals(js("window.__liveSocket.readyState===3&&document.getElementById('voice').getAttribute('aria-pressed')==='false'")),"notification Stop closes the live socket and releases actual microphone tracks");
      runOnMainSync(()->activity.finish()); waitForIdleSync();
      require(Arrays.equals(original,ownerDigest()),"owner preferences file remains byte-for-byte unchanged");
      result.putString("result","passed"); result.putString("stream",evidence.toString()+"Checks: "+checks+"; no live Google traffic.\n");
      finish(Activity.RESULT_OK,result);
    } catch(Exception error) {
      try { evidence.append("DIAGNOSTIC: ").append(js("JSON.stringify({notice:document.getElementById('notice')?.textContent,presence:document.getElementById('presence')?.textContent,pcm:window.__livePcm,setup:!!window.__liveSetup,media:!!window.__liveMedia,events:window.__authEvents?.map(e=>e.type),socket:window.__liveSocket?.readyState})")).append('\n'); } catch(Exception ignored) {}
      result.putString("result","failed");result.putString("stream",evidence.toString()+"FAIL: "+error.getClass().getSimpleName()+": "+error.getMessage());
      finish(Activity.RESULT_CANCELED,result);
    } finally {
      if(activity!=null)runOnMainSync(()->activity.finish());
      if(isolated!=null)isolated.edit().clear().commit();
    }
  }
  private static final class MockConnection extends HttpsURLConnection {
    final int status; final byte[] body; boolean closed; final ByteArrayOutputStream written=new ByteArrayOutputStream();
    MockConnection(URL url,int status,String body){super(url);this.status=status;this.body=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    @Override public OutputStream getOutputStream(){return written;}
    @Override public int getResponseCode(){return status;}
    @Override public InputStream getInputStream(){return new ByteArrayInputStream(body);}
    @Override public InputStream getErrorStream(){return new ByteArrayInputStream(body);}
    @Override public void disconnect(){closed=true;}
    @Override public boolean usingProxy(){return false;}
    @Override public void connect(){}
    @Override public String getCipherSuite(){return "QA fixture";}
    @Override public Certificate[] getLocalCertificates(){return new Certificate[0];}
    @Override public Certificate[] getServerCertificates(){return new Certificate[0];}
  }
}
