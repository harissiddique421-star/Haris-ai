package com.myraa.mobile;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The mobile actions of FRIDAY's visual intelligence (phase 1: documents and image comparison).
 * Kept apart from MobileActions so the existing actions stay as they are; MobileActions only hands
 * over the actions named in ACTIONS and the file picker's result.
 *
 *  analyze_document  the user picks a PDF or photos of pages; FRIDAY reads, summarises and answers
 *  document_question a follow-up question about the document read last (nothing is sent again)
 *  compare_images    the user picks 2 to 4 pictures; FRIDAY reports what is the same and what changed
 *  save_vision_report the last document reading or comparison saved as a PDF in Downloads/FRIDAY
 *  cancel_vision     stops the reading in progress (and closes the camera screen)
 *  camera_vision     opens FRIDAY's camera screen: objects with boxes, the scene, a question about what is
 *                    in front of the camera (phase 2); asked again while it is open, it looks again
 *
 * The answer to the action comes at once ("the picker is open"); the result arrives later as a
 * "vision-done" event, like research reports, because reading a long PDF can take a minute.
 * Nothing is read until the user picks files, and the picked files are not copied or kept.
 */
final class VisionActions {
  static final String[] ACTIONS={"analyze_document","document_question","compare_images","save_vision_report","cancel_vision","camera_vision"};
  static boolean handles(String action){for(String a:ACTIONS)if(a.equals(action))return true;return false;}

  private final Activity activity;
  private final BiConsumer<String,JSONObject> finish;
  private final Supplier<MobileActions.Notify> notify;
  private final Supplier<MobileActions.Gemini> gemini;
  private final Supplier<VisionEngine.Alt> alt;
  private final int requestCode;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private final VisionEngine engine=new VisionEngine();

  /** What the open picker is for. */
  private String picking,pickQuestion;
  private volatile boolean busy;
  /** The document read last (text kept in memory only, for follow-up questions). */
  private DocumentIntelligence.Result lastDoc;
  private ImageComparison.Result lastCompare;
  private String lastKind="";

  VisionActions(Activity activity,BiConsumer<String,JSONObject> finish,Supplier<MobileActions.Notify> notify,Supplier<MobileActions.Gemini> gemini,Supplier<VisionEngine.Alt> alt,int requestCode){
    this.activity=activity;this.finish=finish;this.notify=notify;this.gemini=gemini;this.alt=alt;this.requestCode=requestCode;
  }

  void execute(String id,String action,JSONObject args){
    String question=VisionLogic.clean(args.optString("query",""),VisionLogic.MAX_QUESTION);
    switch(action){
      case "camera_vision":{camera(id,args,question);return;}
      case "cancel_vision":{
        boolean was=busy||picking!=null||VisionCameraActivity.isOpen();picking=null;engine.cancel();VisionCameraActivity.close();
        finish.accept(id,status(was?"done":"none",was?"Stopped reading. Nothing was saved.":"FRIDAY was not reading anything."));return;
      }
      case "document_question":{documentQuestion(id,question);return;}
      case "save_vision_report":{saveReport(id,args.optString("title","").trim());return;}
      default:break;
    }
    if(busy||picking!=null){finish.accept(id,status("busy","FRIDAY is already reading files. Wait for the result, or say cancel to stop it."));return;}
    boolean compare="compare_images".equals(action);
    Intent pick=compare?imagePicker():documentPicker();
    try{activity.startActivityForResult(pick,requestCode);}
    catch(ActivityNotFoundException|SecurityException e){finish.accept(id,status("unavailable","No file picker on this phone can choose "+(compare?"pictures.":"documents.")));return;}
    picking=compare?"compare":"document";pickQuestion=question;
    ControlLog.i("vision: picker opened ("+picking+")");
    JSONObject v=status("started",compare
      ?"The picture picker is open. Tell the user in one short line to choose 2 to 4 pictures (photos or screenshots). FRIDAY compares them and tells you the result; do not call compare_images again."
      :"The file picker is open. Tell the user in one short line to choose the PDF or the photos of the pages (up to 5). FRIDAY reads it and tells you the result; do not call analyze_document again.");
    put(v,"display",compare?"2 se 4 pictures chunein, phir FRIDAY farq batayegi.":"PDF ya page ki photos chunein, FRIDAY parh kar batayegi.");
    finish.accept(id,v);
  }

  private Intent documentPicker(){
    return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
      .putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","image/*"}).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
  }
  private Intent imagePicker(){
    // Android's photo picker shares only the chosen pictures and needs no storage permission.
    if(Build.VERSION.SDK_INT>=33)return new Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,VisionLogic.MAX_COMPARE);
    return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
  }

  /** The picker closed (MainActivity forwards the result through MobileActions). */
  void pickResult(int resultCode,Intent data){
    String kind=picking,question=pickQuestion;picking=null;pickQuestion=null;
    if(kind==null)return;
    List<Uri> uris=new ArrayList<>();
    if(resultCode==Activity.RESULT_OK&&data!=null){
      ClipData clip=data.getClipData();
      if(clip!=null)for(int i=0;i<clip.getItemCount();i++){Uri u=clip.getItemAt(i).getUri();if(u!=null&&!uris.contains(u))uris.add(u);}
      if(uris.isEmpty()&&data.getData()!=null)uris.add(data.getData());
    }
    boolean compare="compare".equals(kind);
    if(uris.isEmpty()){event(compare?"compare":"document",false,"Nothing was picked, so nothing was read.",null);return;}
    if(compare&&uris.size()<2){event("compare",false,"Only one picture was picked. Comparing needs 2 to 4 pictures.",null);return;}
    if(compare&&uris.size()>VisionLogic.MAX_COMPARE)uris=uris.subList(0,VisionLogic.MAX_COMPARE);
    if(!compare&&uris.size()>VisionLogic.MAX_DOC_FILES){event("document",false,"Pick up to "+VisionLogic.MAX_DOC_FILES+" files at a time (or one PDF).",null);return;}
    List<Uri> files=new ArrayList<>(uris);
    android.widget.Toast.makeText(activity,compare?"Pictures compare ho rahi hain...":"Document parha ja raha hai...",android.widget.Toast.LENGTH_SHORT).show();
    busy=true;
    worker.execute(()->{
      try{
        String[] k=keys();
        if(compare){
          ImageComparison.Result r=ImageComparison.compare(activity,files,question,engine,k[0],k[1],alt.get());
          lastCompare=r;lastKind="compare";
          JSONObject e=new JSONObject();
          e.put("summary",VisionLogic.compareDigest(r.data,900)).put("answer",r.data.optString("answer","")).put("pictures",files.size())
            .put("similarity",r.data.optString("similarity","")).put("likeness",r.likeness.length>0?r.likeness[0]:-1).put("provider",r.provider);
          event("compare",true,null,e);
        }else{
          DocumentIntelligence.Result r=DocumentIntelligence.analyze(activity,files,question,engine,k[0],k[1],alt.get());
          lastDoc=r;lastKind="document";
          JSONObject e=new JSONObject();
          e.put("title",r.data.optString("title","Document")).put("doc_type",r.data.optString("doc_type","")).put("summary",VisionLogic.documentDigest(r.data,1200))
            .put("answer",r.data.optString("answer","")).put("tables",r.data.optJSONArray("tables")==null?0:r.data.optJSONArray("tables").length())
            .put("handwritten",r.data.optBoolean("handwritten",false)).put("files",r.fileNames).put("provider",r.provider)
            .put("unclear",VisionLogic.strings(r.data.optJSONArray("unclear"),5).size()>0);
          event("document",true,null,e);
        }
      }catch(VisionEngine.Cancelled c){event(compare?"compare":"document",false,"Stopped. Nothing was saved.",null);}
      catch(IllegalArgumentException e){event(compare?"compare":"document",false,e.getMessage(),null);}
      catch(OutOfMemoryError e){event(compare?"compare":"document",false,"The phone ran out of memory reading these files. Try fewer or smaller files.",null);}
      catch(Exception e){ControlLog.i("vision: failed ("+VisionEngine.code(e)+")");event(compare?"compare":"document",false,VisionEngine.explain(e),null);}
      finally{busy=false;}
    });
  }

  /** camera_vision: opens the camera screen, or looks again with the new question when it is already open. */
  private void camera(String id,JSONObject args,String question){
    String mode="scene".equals(args.optString("mode",""))?"scene":"objects";
    if(VisionCameraActivity.ask(mode,question)){
      JSONObject v=status("started","The camera screen is open: FRIDAY is looking again"+(question.isEmpty()?"":" for \""+question+"\"")+". The answer shows on the screen; say one short line meanwhile. Do not call camera_vision again for this.");
      put(v,"display","Dobara dekh rahi hoon...");finish.accept(id,v);return;
    }
    String setting=activity.getSharedPreferences(Announcer.PREFS,0).getString("language","hi-IN");
    VisionCameraActivity.session=new VisionCameraActivity.Session(this::keys,alt.get(),e->main.post(()->{MobileActions.Notify n=notify.get();if(n!=null)n.event(e);}),
      VisionLogic.spokenLanguage(setting),mode,question,args.optBoolean("on",false),args.optBoolean("speak",true));
    try{activity.startActivity(new Intent(activity,VisionCameraActivity.class));}
    catch(ActivityNotFoundException|SecurityException e){finish.accept(id,status("unavailable","The camera screen could not open."));return;}
    ControlLog.i("vision: camera screen opened ("+mode+")");
    JSONObject v=status("started","FRIDAY's camera screen is open"+(question.isEmpty()?"":" and will answer \""+question+"\" when the user taps Objects or Scene")+". Tell the user in one short line to point the back camera at the thing and tap Objects (names things with boxes) or Scene (describes the place); Live keeps looking while the view changes; Awaz speaks the answer. When they close it you get a [Phone] message with what was seen. Do not call camera_vision again unless they ask about something new.");
    put(v,"display","Camera khul gaya. Cheez ki taraf karein aur Objects ya Scene dabayein.");
    finish.accept(id,v);
  }

  private void documentQuestion(String id,String question){
    DocumentIntelligence.Result doc=lastDoc;
    if(doc==null){finish.accept(id,status("no_document","FRIDAY has not read a document in this session. Offer to read one with analyze_document (the user picks the PDF or photos)."));return;}
    if(question.isEmpty()){finish.accept(id,status("invalid","Ask what the user wants to know about the document."));return;}
    if(busy){finish.accept(id,status("busy","FRIDAY is still reading. Wait for the result first."));return;}
    busy=true;
    JSONObject v=status("started","Looking in \""+doc.data.optString("title","the document")+"\" for the answer. Say one short line that you are checking; the answer comes as a [Phone] message.");
    put(v,"display","Document mein dekh rahi hoon...");
    finish.accept(id,v);
    worker.execute(()->{
      try{
        String[] k=keys();
        String answer=DocumentIntelligence.ask(doc.data.optString("full_text",""),doc.data.optString("summary",""),question,engine,k[0],k[1],alt.get());
        JSONObject e=new JSONObject().put("title",doc.data.optString("title","Document")).put("question",question).put("answer",VisionLogic.clip(answer,1500));
        event("question",true,null,e);
      }catch(VisionEngine.Cancelled c){event("question",false,"Stopped.",null);}
      catch(Exception e){event("question",false,VisionEngine.explain(e),null);}
      finally{busy=false;}
    });
  }

  private void saveReport(String id,String title){
    String kind=lastKind;
    if(kind.isEmpty()){finish.accept(id,status("none","There is no document reading or picture comparison to save yet."));return;}
    String text,name;
    if("document".equals(kind)){
      JSONObject d=lastDoc.data;
      name=title.isEmpty()?d.optString("title","Document")+" - notes":title;
      StringBuilder b=new StringBuilder("## Summary\n").append(d.optString("summary","")).append("\n\n");
      String tables=VisionLogic.tablesMarkdown(d);if(!tables.isEmpty())b.append(tables).append("\n\n");
      String full=d.optString("full_text","").trim();
      if(!full.isEmpty())b.append("## Full text\n").append(VisionLogic.clip(full,Documents.MAX_CHARS-b.length()-200));
      text=b.toString();
    }else{
      name=title.isEmpty()?"Picture comparison":title;
      text=ImageComparison.markdown(lastCompare);
    }
    try{
      Documents.Saved doc=Documents.save(activity,name,VisionLogic.clip(text,Documents.MAX_CHARS),"pdf");
      boolean opened=false;
      try{activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(doc.uri,doc.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));opened=true;}catch(ActivityNotFoundException ignored){}
      finish.accept(id,status("saved","Saved "+doc.name+" in Downloads/FRIDAY"+(opened?" and opened it.":".")+" It can be sent with send_document."));
    }catch(IllegalArgumentException e){finish.accept(id,status("invalid",e.getMessage()));}
    catch(Exception e){finish.accept(id,status("failed","The PDF could not be saved ("+e.getClass().getSimpleName()+")."));}
  }

  private String[] keys(){
    MobileActions.Gemini g=gemini.get();String key="",model="";
    if(g!=null){try{key=g.key();}catch(Exception ignored){}model=g.model();}
    return new String[]{key==null?"":key,model};
  }

  /** Tells the page (and so the user) the result: type vision-done with kind document, compare or question. */
  private void event(String kind,boolean ok,String message,JSONObject extra){
    JSONObject e=extra==null?new JSONObject():extra;
    try{e.put("type","vision-done").put("kind",kind).put("ok",ok);if(message!=null)e.put("message",message);}catch(JSONException ignored){}
    main.post(()->{MobileActions.Notify n=notify.get();if(n!=null)n.event(e);});
  }

  private static JSONObject status(String status,String message){JSONObject v=new JSONObject();put(v,"status",status);put(v,"message",message);return v;}
  private static void put(JSONObject o,String k,Object v){try{o.put(k,v);}catch(JSONException ignored){}}
}
