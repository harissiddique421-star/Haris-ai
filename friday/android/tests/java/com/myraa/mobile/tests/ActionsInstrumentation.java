package com.myraa.mobile.tests;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.webkit.*;
import android.net.Uri;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;

/** Real native bridge/controller; fixture apps and contacts, no external messages or browsing. */
public class ActionsInstrumentation extends Instrumentation {
  Activity activity;WebView web;Object controller;Class<?> actionClass;int checks;final StringBuilder log=new StringBuilder();
  final Map<String,JSONObject> results=new ConcurrentHashMap<>();final List<Intent> opened=new ArrayList<>();
  final Set<String> installed=new HashSet<>(Arrays.asList("com.whatsapp","com.android.chrome","com.google.android.youtube"));
  String failPackage="";int contactQueries;
  @Override public void onCreate(Bundle b){super.onCreate(b);start();}
  void check(boolean pass,String name)throws Exception{if(!pass)throw new Exception(name);checks++;log.append("PASS: ").append(name).append('\n');}
  String js(String source)throws Exception{CountDownLatch latch=new CountDownLatch(1);String[] value={null};runOnMainSync(()->web.evaluateJavascript(source,r->{value[0]=r;latch.countDown();}));if(!latch.await(20,TimeUnit.SECONDS))throw new Exception("UI evaluation timeout");return value[0];}
  void until(java.util.function.BooleanSupplier read,String label)throws Exception{long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(read.getAsBoolean())return;Thread.sleep(80);}throw new Exception(label+" timeout");}
  String request(String name,String args)throws Exception{String id=UUID.randomUUID().toString();js("MyraaNative.mobileAction("+JSONObject.quote(id)+","+JSONObject.quote(name)+","+JSONObject.quote(args)+");true");return id;}
  JSONObject action(String args)throws Exception{String id=request("mobile_action",args);until(()->{if(results.containsKey(id))return true;try{String raw=js("window.__nativeActionResults["+JSONObject.quote(id)+"]||null");if(raw!=null&&!raw.equals("null")){results.put(id,new JSONObject(raw));return true;}}catch(Exception ignored){}return false;},"action result");return results.get(id);}
  Intent last(){return opened.get(opened.size()-1);}
  @Override public void onStart(){Bundle output=new Bundle();try{
    activity=startActivitySync(new Intent().setClassName("com.myraa.mobile","com.myraa.mobile.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    ClassLoader loader=activity.getClassLoader();Field view=activity.getClass().getDeclaredField("web");view.setAccessible(true);web=(WebView)view.get(activity);
    runOnMainSync(()->{WebViewClient original=web.getWebViewClient();web.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){if(r.getUrl().getPath().endsWith(".pmx"))return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(new byte[0]));return original.shouldInterceptRequest(v,r);}});});
    until(()->{try{return "true".equals(js("!!window.MyraaEvents"));}catch(Exception e){return false;}},"bundled UI");
    js("window.__nativeActionResults={};window.__nativeActionEvents=window.MyraaEvents;window.MyraaEvents=e=>{if(e.type==='mobile-action-result')window.__nativeActionResults[e.id]=e.result;window.__nativeActionEvents(e)};true");
    actionClass=loader.loadClass("com.myraa.mobile.MobileActions");Class<?> resultClass=loader.loadClass("com.myraa.mobile.MobileActions$Result"),launchClass=loader.loadClass("com.myraa.mobile.MobileActions$Launcher"),contactsClass=loader.loadClass("com.myraa.mobile.MobileActions$Contacts"),personClass=loader.loadClass("com.myraa.mobile.MobileActions$Recipient");
    Constructor<?> person=personClass.getConstructor(String.class,String.class);Method emit=activity.getClass().getDeclaredMethod("emit",JSONObject.class);emit.setAccessible(true);
    Object listener=Proxy.newProxyInstance(loader,new Class[]{resultClass},(p,m,a)->{String id=(String)a[0];JSONObject result=(JSONObject)a[1];results.put(id,result);emit.invoke(activity,new JSONObject().put("type","mobile-action-result").put("id",id).put("result",result));return null;});
    Object launcher=Proxy.newProxyInstance(loader,new Class[]{launchClass},(p,m,a)->{
      if(m.getName().equals("installed"))return installed.contains((String)a[0]);
      if(m.getName().equals("app"))return "Calculator".equalsIgnoreCase((String)a[0])?new Intent(Intent.ACTION_MAIN).setClassName("com.fixture.calculator","com.fixture.calculator.Main"):null;
      if(m.getName().equals("open")){Intent intent=new Intent((Intent)a[0]);opened.add(intent);return !failPackage.equals(intent.getPackage());}return null;
    });
    Object contacts=Proxy.newProxyInstance(loader,new Class[]{contactsClass},(p,m,a)->{
      if(m.getName().equals("needsPermission"))return false;
      contactQueries++;String name=(String)a[0];List<Object> list=new ArrayList<>();
      if(name.equals("QA Person"))list.add(person.newInstance("QA Person","919999000001"));
      if(name.equals("Sam")){list.add(person.newInstance("Sam A","919999000002"));list.add(person.newInstance("Sam B","919999000003"));}return list;
    });
    controller=actionClass.getConstructor(Activity.class,resultClass,launchClass,contactsClass).newInstance(activity,listener,launcher,contacts);
    Field actions=activity.getClass().getDeclaredField("mobileActions");actions.setAccessible(true);actions.set(activity,controller);
    JSONObject r=action("{\"action\":\"open_app\",\"app\":\"Chrome\"}");check(r.optString("status").equals("opened")&&last().getPackage().equals("com.android.chrome"),"native Chrome opening targets the installed browser");
    r=action("{\"action\":\"open_app\",\"app\":\"wp\"}");check(r.optString("status").equals("opened")&&last().getPackage().equals("com.whatsapp"),"WP alias opens WhatsApp");
    r=action("{\"action\":\"open_app\",\"app\":\"Calculator\"}");check(last().getComponent().getPackageName().equals("com.fixture.calculator"),"other installed app names resolve to launcher activities");
    r=action("{\"action\":\"search_youtube\",\"query\":\"Hindi songs & rain\"}");check(last().getData().getHost().equals("www.youtube.com")&&last().getData().getQueryParameter("search_query").equals("Hindi songs & rain"),"YouTube search uses the requested encoded query");
    r=action("{\"action\":\"search_web\",\"query\":\"a & b? javascript:alert(1)\"}");check(last().getData().getScheme().equals("https")&&last().getData().getQueryParameterNames().size()==1&&last().getData().getQueryParameter("q").equals("a & b? javascript:alert(1)"),"browser query text cannot become a URL scheme or extra parameter");
    failPackage="com.google.android.youtube";r=action("{\"action\":\"search_youtube\",\"query\":\"test\"}");check(r.optString("status").equals("opened")&&last().getPackage()==null,"unavailable YouTube handler falls back to the browser");failPackage="";
    int before=opened.size();r=action("{\"action\":\"open_app\",\"app\":\"javascript:alert(1)\"}");check(r.optString("status").equals("not_installed")&&opened.size()==before,"app names never become arbitrary intent or URL targets");
    r=action("{\"action\":\"whatsapp_message\",\"recipient\":\"QA Person\",\"message\":\"Hello! Meet at 5?\"}");check(r.optString("status").equals("draft_opened")&&last().getData().getPath().equals("/919999000001")&&last().getData().getQueryParameter("text").equals("Hello! Meet at 5?"),"saved recipient resolves locally and exact WhatsApp draft text is encoded");
    check(last().getAction().equals(Intent.ACTION_VIEW)&&last().getData().getQueryParameterNames().size()==1&&r.optString("message").contains("tap Send"),"WhatsApp action prepares a draft and never claims it was sent");
    int queries=contactQueries;r=action("{\"action\":\"whatsapp_message\",\"recipient\":\"+6512345678\",\"message\":\"Hello\"}");check(last().getData().getPath().equals("/6512345678")&&contactQueries==queries,"explicit international phone needs no contact access or guessing");
    before=opened.size();r=action("{\"action\":\"whatsapp_message\",\"recipient\":\"9999000000\",\"message\":\"Hello\"}");check(r.optString("status").equals("invalid")&&opened.size()==before,"ambiguous local phone number requires a country code");
    String duplicate=request("mobile_action","{\"action\":\"open_app\",\"app\":\"Chrome\"}");until(()->results.containsKey(duplicate),"first result");before=opened.size();js("MyraaNative.mobileAction("+JSONObject.quote(duplicate)+",'mobile_action','{\"action\":\"open_app\",\"app\":\"Chrome\"}');true");Thread.sleep(120);check(opened.size()==before,"duplicate native action IDs do not launch twice");
    Field chooser=actionClass.getDeclaredField("chooser");chooser.setAccessible(true);String choice=request("mobile_action","{\"action\":\"whatsapp_message\",\"recipient\":\"Sam\",\"message\":\"Hello\"}");until(()->{try{return chooser.get(controller)!=null;}catch(Exception e){return false;}},"contact chooser");
    runOnMainSync(()->{try{((AlertDialog)chooser.get(controller)).getListView().performItemClick(null,1,1);}catch(Exception e){throw new RuntimeException(e);}});until(()->results.containsKey(choice),"chosen contact");check(last().getData().getPath().equals("/919999000003"),"ambiguous contact requires selecting the exact recipient");
    installed.remove("com.whatsapp");before=opened.size();r=action("{\"action\":\"whatsapp_message\",\"recipient\":\"QA Person\",\"message\":\"Hello\"}");check(r.optString("status").equals("not_installed")&&opened.size()==before,"missing WhatsApp is reported honestly");
    r=action("{\"action\":\"delete_files\"}");check(r.optString("status").equals("unsupported"),"unsupported device actions cannot execute");
    String bad=request("runShell","{}");until(()->results.containsKey(bad),"unsupported tool");check(results.get(bad).optString("status").equals("unsupported"),"desktop and shell tools are rejected by the native bridge");
    Field foreground=activity.getClass().getDeclaredField("foreground");foreground.setAccessible(true);foreground.setBoolean(activity,false);before=opened.size();r=action("{\"action\":\"open_app\",\"app\":\"Chrome\"}");foreground.setBoolean(activity,true);check(r.optString("status").equals("permission_required")&&opened.size()==before,"background switching requires visible floating controls or returning to MYRAA");
    boolean chromeAvailable;try{activity.getPackageManager().getPackageInfo("com.android.chrome",0);chromeAvailable=true;}catch(Exception missing){chromeAvailable=false;}
    if(chromeAvailable){
      Object real=actionClass.getConstructor(Activity.class,resultClass).newInstance(activity,listener);actions.set(activity,real);
      r=action("{\"action\":\"open_app\",\"app\":\"Chrome\"}");
      until(()->{android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();return root!=null&&"com.android.chrome".contentEquals(root.getPackageName());},"actual Chrome foreground");
      check(r.optString("status").equals("opened"),"production native action actually brings installed Chrome to the foreground");
      android.os.ParcelFileDescriptor command=getUiAutomation().executeShellCommand("am start -n com.myraa.mobile/.MainActivity");new android.os.ParcelFileDescriptor.AutoCloseInputStream(command).close();
    }else log.append("SKIP: real Chrome foreground check; Chrome is not installed.\n");
    output.putString("stream",log+"Checks: "+checks+"; fixture apps and contacts; no messages sent.\n");finish(Activity.RESULT_OK,output);
  }catch(Exception e){output.putString("stream",log+"FAIL: "+e.getMessage());finish(Activity.RESULT_CANCELED,output);}finally{if(activity!=null)runOnMainSync(()->activity.finish());}}
}
