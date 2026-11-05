package com.myraa.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.MediaStore;
import android.telephony.*;
import org.json.*;
import java.security.SecureRandom;
import java.util.*;

/**
 * Bounded Android intents. No arbitrary URLs or shell. Phone calls and WhatsApp auto-send
 * always need a confirmation round trip: the first request returns a single-use token with
 * the resolved name/number/text, and only a second request carrying that token acts, using
 * the values stored natively (the model cannot swap the recipient or text in between).
 */
public final class MobileActions {
  public static final int CONTACTS_REQUEST=88;
  static final long CONFIRM_WINDOW_MS=120_000;
  public interface Result { void complete(String id,JSONObject result); }
  public interface Launcher { boolean installed(String pkg); Intent app(String label); boolean open(Intent intent); }
  public interface Contacts {
    boolean needsPermission(); List<Recipient> find(String name);
    /** WhatsApp accounts synced into Android Contacts for this mimetype (chat, voice call or video call). */
    default List<WaContact> findWhatsApp(String name,String mimetype){return Collections.emptyList();}
  }
  /** One WhatsApp contact row: the Data row id WhatsApp itself registered, so opening it reaches exactly that chat/call. */
  public static final class WaContact {
    public final String name,phone,mimetype;public final long dataId;
    public WaContact(String name,String phone,String mimetype,long dataId){this.name=name;this.phone=phone;this.mimetype=mimetype;this.dataId=dataId;}
  }
  static final String WA_CHAT="profile",WA_VOICE="voip.call",WA_VIDEO="video.call";
  public static final class Recipient {
    public final String name,phone;
    public Recipient(String name,String phone){this.name=name;this.phone=phone;}
  }
  private interface Then { void with(Recipient person); }
  private static final class Confirmation {
    final String kind,phone,name,text;final long until;
    Confirmation(String kind,Recipient person,String text){this.kind=kind;this.phone=person.phone;this.name=person.name;this.text=text;this.until=System.currentTimeMillis()+CONFIRM_WINDOW_MS;}
  }
  private final Activity activity;
  private final Result result;
  private final Launcher launcher;
  private final Contacts contacts;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final SecureRandom random=new SecureRandom();
  private Runnable pending,pendingDenied;
  private String pendingId,pendingPermission;
  private AlertDialog chooser,confirmDialog;
  private String chooserId,confirmId;
  private final Map<String,Confirmation> confirmations=new HashMap<>();
  private PhoneControls phone;
  private AssistantMemory memory;
  private AssistantMemory memory(){if(memory==null)memory=new AssistantMemory(activity);return memory;}
  private JSONObject announceSetting(boolean driving,JSONObject args){
    if(!args.has("on"))throw new IllegalArgumentException(driving?"Say whether to turn driving mode on or off.":"Say whether to turn call announcements on or off.");
    boolean on=args.optBoolean("on");Announcer.set(activity,driving?"drivingMode":"announceCalls",on);
    boolean access=NotificationReader.enabled(activity);
    String msg=driving?(on?"Driving mode is on. I will read out new messages and say who is calling.":"Driving mode is off.")
      :(on?"I will say the caller's name when the phone rings.":"Call announcements are off.");
    if(on&&!access)msg+=" Notification access is off, so turn it on in Settings > Permissions first.";
    JSONObject v=PhoneControls.result(on&&!access?"permission_required":"done",msg);
    try{v.put("driving",Announcer.driving(activity)).put("announceCalls",Announcer.announceCalls(activity));}catch(JSONException ignored){}
    return v;
  }
  private PhoneControls phone(){if(phone==null)phone=new PhoneControls(activity);return phone;}
  private final Map<String,JSONObject> completed=new LinkedHashMap<>();
  public MobileActions(Activity activity,Result result) {
    this(activity,result,new Launcher(){
      public boolean installed(String pkg){try{activity.getPackageManager().getPackageInfo(pkg,0);return true;}catch(Exception e){return false;}}
      public Intent app(String label){
        PackageManager pm=activity.getPackageManager();Intent main=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for(ResolveInfo info:pm.queryIntentActivities(main,0))if(normalize(info.loadLabel(pm).toString()).equals(normalize(label))&&info.activityInfo.exported)
          return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(info.activityInfo.packageName,info.activityInfo.name);
        return null;
      }
      public boolean open(Intent intent){try{activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true;}catch(ActivityNotFoundException|SecurityException e){return false;}}
    },new Contacts(){
      public boolean needsPermission(){return true;}
      public List<Recipient> find(String name){
        List<Recipient> found=new ArrayList<>();Set<String> seen=new HashSet<>();
        String escaped=name.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
        try(Cursor c=activity.getContentResolver().query(Phone.CONTENT_URI,new String[]{Phone.DISPLAY_NAME,Phone.NUMBER,Phone.NORMALIZED_NUMBER},Phone.DISPLAY_NAME+" LIKE ? ESCAPE '\\'",new String[]{"%"+escaped+"%"},Phone.DISPLAY_NAME+" ASC")){
          if(c!=null)while(c.moveToNext()&&found.size()<20){
            String raw=c.getString(2);if(raw==null||raw.isEmpty())raw=c.getString(1);
            String phone=international(raw,activity);if(phone!=null&&seen.add(phone))found.add(new Recipient(c.getString(0),phone));
          }
        }
        List<Recipient> exact=new ArrayList<>();for(Recipient r:found)if(normalize(r.name).equals(normalize(name)))exact.add(r);
        return exact.isEmpty()?found:exact;
      }
      public List<WaContact> findWhatsApp(String name,String mimetype){
        List<WaContact> found=new ArrayList<>();Set<String> seen=new HashSet<>();
        boolean number=name.trim().matches("\\+?[0-9 ()-]{8,}");String digits=name.replaceAll("[^0-9]","");
        String escaped=name.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
        String where=ContactsContract.Data.MIMETYPE+"=? AND "+(number?ContactsContract.Data.DATA1+" LIKE ?":ContactsContract.Data.DISPLAY_NAME+" LIKE ? ESCAPE '\\'");
        try(Cursor c=activity.getContentResolver().query(ContactsContract.Data.CONTENT_URI,new String[]{ContactsContract.Data._ID,ContactsContract.Data.DISPLAY_NAME,ContactsContract.Data.DATA1},where,new String[]{mimetype,number?digits+"@%":"%"+escaped+"%"},ContactsContract.Data.DISPLAY_NAME+" ASC")){
          if(c!=null)while(c.moveToNext()&&found.size()<20){
            String jid=c.getString(2);if(jid==null)continue;String phone=jid.contains("@")?jid.substring(0,jid.indexOf('@')):jid.replaceAll("[^0-9]","");
            if(!phone.matches("[1-9][0-9]{7,14}")||!seen.add(phone))continue;
            found.add(new WaContact(c.getString(1),phone,mimetype,c.getLong(0)));
          }
        }
        List<WaContact> exact=new ArrayList<>();for(WaContact r:found)if(normalize(r.name).equals(normalize(name)))exact.add(r);
        return exact.isEmpty()?found:exact;
      }
    });
  }
  public MobileActions(Activity activity,Result result,Launcher launcher,Contacts contacts){this.activity=activity;this.result=result;this.launcher=launcher;this.contacts=contacts;}
  public static String normalize(String input){return input==null?"":input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
  private static String international(String raw,Activity activity){
    if(raw==null)return null;String clean=raw.replaceAll("[ ()-]","");if(clean.startsWith("00"))clean="+"+clean.substring(2);
    if(clean.matches("\\+[1-9][0-9]{7,14}"))return clean.substring(1);
    String country="";try{country=activity.getSystemService(TelephonyManager.class).getNetworkCountryIso();}catch(Exception ignored){}
    if(country==null||country.length()!=2){String language=activity.getSharedPreferences("myraa.mobile.v1",0).getString("language","hi-IN");country=language.endsWith("IN")?"IN":"US";}
    String e164=PhoneNumberUtils.formatNumberToE164(raw,country.toUpperCase(Locale.ROOT));return e164==null?null:e164.substring(1);
  }
  public static Intent search(String target,String query){
    if(query==null||query.trim().isEmpty()||query.length()>1000)throw new IllegalArgumentException("Say what you want to search for.");
    Uri uri="youtube".equals(target)?Uri.parse("https://www.youtube.com/results").buildUpon().appendQueryParameter("search_query",query.trim()).build():Uri.parse("https://www.google.com/search").buildUpon().appendQueryParameter("q",query.trim()).build();
    return new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE);
  }
  public static Intent draft(String phone,String message,String pkg){
    if(phone==null||!phone.matches("[1-9][0-9]{7,14}")||message==null||message.trim().isEmpty()||message.length()>4000)throw new IllegalArgumentException("A valid recipient and message are required.");
    return new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/"+phone).buildUpon().appendQueryParameter("text",message).build()).setPackage(pkg);
  }
  private JSONObject status(String status,String message){JSONObject value=new JSONObject();try{value.put("status",status).put("message",message);}catch(JSONException ignored){}return value;}
  private void finish(String id,String status,String message){finish(id,status(status,message));}
  private void finish(String id,JSONObject value){if(completed.containsKey(id))return;completed.put(id,value);while(completed.size()>64)completed.remove(completed.keySet().iterator().next());result.complete(id,value);}
  public void execute(String id,String name,String raw){
    if(id==null||!id.matches("[A-Za-z0-9-]{1,80}"))return;
    if(completed.containsKey(id)){result.complete(id,completed.get(id));return;}
    if(!"mobile_action".equals(name)){finish(id,"unsupported","That mobile action is unavailable.");return;}
    try{
      JSONObject args=new JSONObject(raw);String action=args.optString("action");
      ControlLog.i("command "+action+(args.has("app")?" app="+args.optString("app"):"")+(args.has("recipient")?" contact="+args.optString("recipient"):"")+(args.has("target")?" target="+args.optString("target"):"")+(args.has("control")?" control="+args.optString("control"):"")+(args.has("confirm_token")?" (confirmed)":"")+" control="+(PhoneController.get()!=null?"ready":PhoneController.enabled(activity)?"enabled, not connected":"off"));
      if("open_app".equals(action)){openApp(id,args.optString("app"));return;}
      if("search_youtube".equals(action)||"search_web".equals(action)){
        boolean youtube=action.equals("search_youtube");Intent intent=search(youtube?"youtube":"web",args.optString("query"));String pkg=youtube?"com.google.android.youtube":"com.android.chrome";
        if(launcher.installed(pkg))intent.setPackage(pkg);else if(youtube&&launcher.installed("com.android.chrome"))intent.setPackage("com.android.chrome");
        boolean opened=launcher.open(intent);if(!opened&&intent.getPackage()!=null)opened=launcher.open(new Intent(intent).setPackage(null));
        finish(id,opened?"opened":"unavailable",opened?(youtube?"YouTube":"Browser")+" search opened.":"No app can open this search on this device.");return;
      }
      if("whatsapp_message".equals(action)){
        String token=args.optString("confirm_token");
        if(!token.isEmpty()){sendConfirmed(id,token);return;}
        message(id,args.optString("recipient"),args.optString("message"),args.optBoolean("confirm_on_screen"));return;
      }
      if("call".equals(action)){
        String token=args.optString("confirm_token");
        if(!token.isEmpty()){callConfirmed(id,token);return;}
        call(id,args.optString("recipient"),args.optBoolean("confirm_on_screen"));return;
      }
      if("set_alarm".equals(action)){alarm(id,args,null);return;}
      if("set_reminder".equals(action)){reminder(id,args);return;}
      if("play_music".equals(action)){music(id,args.optString("query"));return;}
      if("torch".equals(action)){finish(id,phone().torch(args.optBoolean("on",true)));return;}
      if("volume".equals(action)){finish(id,phone().volume(args.optString("stream","media"),args.optString("direction"),args.optInt("percent",-1)));return;}
      if("ringer_mode".equals(action)){finish(id,phone().ringer(args.optString("mode")));return;}
      if("brightness".equals(action)){finish(id,phone().brightness(args.optString("direction"),args.optInt("percent",-1)));return;}
      if("battery".equals(action)){finish(id,phone().battery());return;}
      if("navigate".equals(action)){finish(id,phone().navigate(args.optString("place"),args.optString("mode")));return;}
      if("read_notifications".equals(action)){finish(id,phone().notifications(args.optInt("limit",10)));return;}
      if("answer_call".equals(action)||"reject_call".equals(action)){boolean accept="answer_call".equals(action);withPermission(id,Manifest.permission.ANSWER_PHONE_CALLS,()->finish(id,phone().answerCall(accept)),()->finish(id,"permission_required","Phone permission was denied, so FRIDAY cannot answer or reject calls."));return;}
      if("send_sms".equals(action)){
        String token=args.optString("confirm_token");
        if(!token.isEmpty()){smsConfirmed(id,token);return;}
        sms(id,args.optString("recipient"),args.optString("message"),args.optBoolean("confirm_on_screen"));return;
      }
      if("open_settings".equals(action)){finish(id,phone().settings(args.optString("page")));return;}
      if("phone_control".equals(action)){String what=args.optString("control");control(id,c->finish(id,c.global(what)));return;}
      if("read_screen".equals(action)){control(id,c->finish(id,c.readScreen()));return;}
      if("scroll".equals(action)){String dir=args.optString("direction");control(id,c->finish(id,c.scroll(dir)));return;}
      if("type_text".equals(action)){String target=args.optString("target"),text=args.optString("text");control(id,c->{if(!ownScreen(id,c))finish(id,c.type(target,text));});return;}
      if("whatsapp_call".equals(action)){
        String token=args.optString("confirm_token");
        if(!token.isEmpty()){waCallConfirmed(id,token);return;}
        waCall(id,args.optString("recipient"),args.optBoolean("video"),args.optBoolean("confirm_on_screen"));return;
      }
      if("open_contact".equals(action)){openContact(id,args.optString("recipient"));return;}
      if("remember".equals(action)){finish(id,memory().remember(args.optString("fact")));return;}
      if("forget".equals(action)){finish(id,memory().forget(args.optString("query")));return;}
      if("list_memories".equals(action)){finish(id,memory().listMemories());return;}
      if("set_persona".equals(action)){finish(id,memory().setPersona(args.optString("persona")));return;}
      if("set_driving_mode".equals(action)||"set_call_announce".equals(action)){finish(id,announceSetting("set_driving_mode".equals(action),args));return;}
      if("open_dialer".equals(action)){boolean opened=launcher.open(new Intent(Intent.ACTION_DIAL));finish(id,opened?"opened":"unavailable",opened?"Dialer opened.":"No dialer app on this phone.");return;}
      if("tap".equals(action)){tap(id,args);return;}
      finish(id,"unsupported","Supported actions: memory (remember, forget, list), persona, apps, searches, WhatsApp messages and voice/video calls, opening a contact, phone calls and SMS, alarms and reminders, music, torch, volume, silent mode, brightness, battery, navigation, notifications, answering calls, settings pages, and phone control (back, home, read screen, tap, type, scroll).");
    }catch(IllegalArgumentException e){finish(id,"invalid",e.getMessage());}catch(Exception e){finish(id,"failed","Android could not complete this action. Nothing was sent.");}
  }
  private void openApp(String id,String input){
    String app=normalize(input);if(app.isEmpty()||app.length()>100){finish(id,"invalid","Say which app to open.");return;}
    Intent intent=null;
    if(Arrays.asList("wp","wa","whats app","whatsapp","व्हाट्सएप").contains(app)){
      String pkg=whatsapp();if(pkg==null){finish(id,"not_installed","WhatsApp is not installed on this Android device.");return;}intent=activity.getPackageManager().getLaunchIntentForPackage(pkg);if(intent==null)intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/")).setPackage(pkg);
    }else if(Arrays.asList("youtube","you tube","यूट्यूब").contains(app))intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com")).setPackage(launcher.installed("com.google.android.youtube")?"com.google.android.youtube":launcher.installed("com.android.chrome")?"com.android.chrome":null);
    else if(Arrays.asList("chrome","google chrome","browser","क्रोम").contains(app))intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com")).setPackage(launcher.installed("com.android.chrome")?"com.android.chrome":null);
    else intent=launcher.app(input);
    if(intent==null){finish(id,"not_installed","No installed app matches that name.");return;}
    boolean opened=launcher.open(intent);finish(id,opened?"opened":"unavailable",opened?"Opened "+input+".":"Android could not open "+input+".");
  }
  private String whatsapp(){return launcher.installed("com.whatsapp")?"com.whatsapp":launcher.installed("com.whatsapp.w4b")?"com.whatsapp.w4b":null;}

  /** Resolves a spoken name or international number to exactly one recipient, asking for Contacts or a choice when needed. */
  private void resolve(String id,String recipient,Then then){
    if(recipient.trim().isEmpty()||recipient.length()>100){finish(id,"invalid","Tell me who it is for.");return;}
    if(recipient.trim().matches("\\+?[0-9 ()-]+")){
      String phone=recipient.replaceAll("[ ()+-]","");if(phone.startsWith("00"))phone=phone.substring(2);boolean international=recipient.trim().startsWith("+")||recipient.trim().startsWith("00")||phone.length()>=11;if(!international||!phone.matches("[1-9][0-9]{7,14}")){finish(id,"invalid","Include the country code in the recipient phone number, for example +91.");return;}
      then.with(new Recipient(recipient.trim(),phone));return;
    }
    if(contacts.needsPermission()&&activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED){
      if(pending!=null){finish(id,"busy","Finish the current permission request first.");return;}
      pendingId=id;pendingPermission=Manifest.permission.READ_CONTACTS;pendingDenied=null;pending=()->resolve(id,recipient,then);activity.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},CONTACTS_REQUEST);return;
    }
    List<Recipient> matches=contacts.find(recipient.trim());
    if(matches.isEmpty()){finish(id,"not_found","No matching contact with a usable phone number was found. Give the full saved name or an international phone number.");return;}
    boolean exact=matches.size()==1&&normalize(matches.get(0).name).equals(normalize(recipient));
    if(exact){then.with(matches.get(0));return;}
    if(chooser!=null){finish(id,"busy","Choose the recipient from the current list first.");return;}
    String[] labels=new String[matches.size()];for(int i=0;i<labels.length;i++)labels[i]=matches.get(i).name+" · +"+matches.get(i).phone;
    chooserId=id;chooser=new AlertDialog.Builder(activity).setTitle("Which "+recipient+"?").setItems(labels,(d,index)->{chooser=null;chooserId=null;then.with(matches.get(index));})
      .setNegativeButton("Cancel",(d,w)->{chooser=null;chooserId=null;finish(id,"cancelled","Cancelled. Nothing was sent.");}).setOnCancelListener(d->{chooser=null;chooserId=null;finish(id,"cancelled","Cancelled. Nothing was sent.");}).show();
  }
  private String token(String kind,Recipient person,String text){
    long now=System.currentTimeMillis();confirmations.values().removeIf(c->c.until<now);
    String token=Long.toHexString(random.nextLong()&0xffffffffffL);confirmations.put(token,new Confirmation(kind,person,text));return token;
  }
  private Confirmation take(String kind,String token){Confirmation c=confirmations.remove(token);if(c==null||!c.kind.equals(kind)||c.until<System.currentTimeMillis())return null;return c;}
  private void askConfirmation(String id,String kind,Recipient person,String text,String question){
    JSONObject value=status("needs_confirmation",question+" Ask the user this exact question and wait for a clear yes. Only after they say yes, call mobile_action again with the same action and confirm_token. If they say no or change anything, do not reuse the token.");
    try{value.put("confirm_token",token(kind,person,text)).put("recipient_name",person.name).put("recipient_phone","+"+person.phone);if(text!=null)value.put("message_text",text);}catch(JSONException ignored){}
    finish(id,value);
  }
  /** Typed commands have no voice round trip, so the user confirms on screen instead. */
  private void confirmOnScreen(String id,String title,String body,Runnable yes){
    if(confirmDialog!=null){finish(id,"busy","Answer the current confirmation first.");return;}
    confirmId=id;confirmDialog=new AlertDialog.Builder(activity).setTitle(title).setMessage(body)
      .setPositiveButton("Yes",(d,w)->{confirmDialog=null;confirmId=null;yes.run();})
      .setNegativeButton("No",(d,w)->{confirmDialog=null;confirmId=null;finish(id,"cancelled","Cancelled. Nothing was sent.");})
      .setOnCancelListener(d->{confirmDialog=null;confirmId=null;finish(id,"cancelled","Cancelled. Nothing was sent.");}).show();
  }

  private void message(String id,String recipient,String text,boolean onScreen){
    if(whatsapp()==null){finish(id,"not_installed","WhatsApp is not installed on this Android device.");return;}
    if(recipient.trim().isEmpty()||recipient.length()>100||text.trim().isEmpty()||text.length()>4000){finish(id,"invalid","Tell me the recipient and the complete message.");return;}
    resolveWhatsApp(id,recipient,WA_CHAT,true,wa->{
      Recipient person=new Recipient(wa.name,wa.phone);
      if(!PhoneController.enabled(activity)&&PhoneController.get()==null){ControlLog.i("whatsapp message: accessibility off, opening draft");openDraft(id,person,text,"Automatic sending is off, so review it and tap Send in WhatsApp. To let FRIDAY send after you confirm, turn on FRIDAY in Android Accessibility settings.");return;}
      if(onScreen){confirmOnScreen(id,"Send WhatsApp message?","To: "+person.name+" (+"+person.phone+")\n\n"+text,()->sendNow(id,person,text));return;}
      askConfirmation(id,"whatsapp",person,text,"Send this WhatsApp message to "+person.name+" (+"+person.phone+"): \""+text+"\"?");
    });
  }
  private void sendConfirmed(String id,String token){
    Confirmation c=take("whatsapp",token);
    if(c==null){finish(id,"invalid","That confirmation expired or was already used. Ask again with the recipient and message.");return;}
    if(whatsapp()==null){finish(id,"not_installed","WhatsApp is not installed on this Android device.");return;}
    sendNow(id,new Recipient(c.name,c.phone),c.text);
  }
  /** Sends a confirmed message: auto-send when a control service is connected (waiting briefly if it is reconnecting), otherwise an honest draft. */
  private void sendNow(String id,Recipient person,String text){
    withControl(ctl->{
      if(ctl!=null){autoSend(id,person,text);return;}
      ControlLog.i("whatsapp message: control not connected, opening draft");
      openDraft(id,person,text,PhoneController.enabled(activity)?"FRIDAY's accessibility switch is on but Android has not connected it right now, so tap Send yourself this time. If this keeps happening, turn the switch off and on once.":"Automatic sending is off, so review it and tap Send in WhatsApp.");
    });
  }
  private void autoSend(String id,Recipient person,String text){
    String pkg=whatsapp();
    WhatsAppSender.arm(pkg,person.phone,text,(sent,why)->main.post(()->{
      ControlLog.i("whatsapp message to "+person.name+": "+(sent?"sent and verified":"not sent, "+why));
      if(sent)finish(id,"sent","WhatsApp message sent to "+person.name+". Checked: WhatsApp cleared the message box after Send.");
      else finish(id,"draft_opened","FRIDAY could not press Send ("+why+"). The draft for "+person.name+" is open; tap Send in WhatsApp. It has not been sent.");
    }));
    if(!launcher.open(draft(person.phone,text,pkg))){WhatsAppSender.disarm();finish(id,"unavailable","WhatsApp could not open this recipient. Nothing was sent.");}
  }
  private void openDraft(String id,Recipient person,String text,String note){boolean opened=launcher.open(draft(person.phone,text,whatsapp()));finish(id,opened?"draft_opened":"unavailable",opened?"WhatsApp draft opened for "+person.name+". "+note+" FRIDAY has not sent it.":"WhatsApp could not open this recipient. Nothing was sent.");}

  private void call(String id,String recipient,boolean onScreen){
    resolve(id,recipient,person->{
      if(onScreen){confirmOnScreen(id,"Call "+person.name+"?","+"+person.phone,()->placeCall(id,person));return;}
      askConfirmation(id,"call",person,null,"Call "+person.name+" (+"+person.phone+")?");
    });
  }
  private void callConfirmed(String id,String token){
    Confirmation c=take("call",token);
    if(c==null){finish(id,"invalid","That confirmation expired or was already used. Ask again who to call.");return;}
    placeCall(id,new Recipient(c.name,c.phone));
  }
  private void placeCall(String id,Recipient person){
    Uri tel=Uri.parse("tel:+"+person.phone);
    if(activity.checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED){
      if(pending!=null){finish(id,"busy","Finish the current permission request first.");return;}
      pendingId=id;pendingPermission=Manifest.permission.CALL_PHONE;pending=()->placeCall(id,person);pendingDenied=()->dialFallback(id,person);
      activity.requestPermissions(new String[]{Manifest.permission.CALL_PHONE},CONTACTS_REQUEST);return;
    }
    boolean opened=launcher.open(new Intent(Intent.ACTION_CALL,tel));
    finish(id,opened?"calling":"unavailable",opened?"Calling "+person.name+".":"Android could not start the call.");
  }
  private void dialFallback(String id,Recipient person){
    boolean opened=launcher.open(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:+"+person.phone)));
    finish(id,opened?"dialer_opened":"unavailable",opened?"Phone permission was denied, so the dialer is open with "+person.name+"'s number. Tap Call to start it.":"Android could not open the dialer.");
  }

  private void withPermission(String id,String permission,Runnable granted,Runnable denied){
    if(activity.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED){granted.run();return;}
    if(pending!=null){finish(id,"busy","Finish the current permission request first.");return;}
    pendingId=id;pendingPermission=permission;pending=granted;pendingDenied=denied;
    activity.requestPermissions(new String[]{permission},CONTACTS_REQUEST);
  }
  private void sms(String id,String recipient,String text,boolean onScreen){
    if(text.trim().isEmpty()||text.length()>1000){finish(id,"invalid","Tell me the recipient and the complete message.");return;}
    resolve(id,recipient,person->{
      if(onScreen){confirmOnScreen(id,"Send SMS?","To: "+person.name+" (+"+person.phone+")\n\n"+text,()->sendSms(id,person,text));return;}
      askConfirmation(id,"sms",person,text,"Send this SMS to "+person.name+" (+"+person.phone+"): \""+text+"\"?");
    });
  }
  private void smsConfirmed(String id,String token){
    Confirmation c=take("sms",token);
    if(c==null){finish(id,"invalid","That confirmation expired or was already used. Ask again with the recipient and message.");return;}
    sendSms(id,new Recipient(c.name,c.phone),c.text);
  }
  private void sendSms(String id,Recipient person,String text){
    withPermission(id,Manifest.permission.SEND_SMS,()->{try{finish(id,phone().sms(person.phone,text,person.name));}catch(RuntimeException e){finish(id,"failed","The SMS could not be sent.");}},
      ()->finish(id,"permission_required","SMS permission was denied, so nothing was sent."));
  }
  private void alarm(String id,JSONObject args,String label){
    int hour=args.optInt("hour",-1),minute=args.optInt("minute",0);
    if(hour<0||hour>23||minute<0||minute>59)throw new IllegalArgumentException("Say the alarm time, for example 6:30 AM.");
    String text=label!=null?label:args.optString("label","").trim();if(text.length()>200)text=text.substring(0,200);
    Intent intent=new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,hour).putExtra(AlarmClock.EXTRA_MINUTES,minute).putExtra(AlarmClock.EXTRA_SKIP_UI,true);
    if(!text.isEmpty())intent.putExtra(AlarmClock.EXTRA_MESSAGE,text);
    boolean opened=launcher.open(intent);String time=String.format(Locale.ROOT,"%02d:%02d",hour,minute);
    finish(id,opened?"set":"unavailable",opened?(label!=null?"Reminder set for "+time+": "+text:"Alarm set for "+time+(text.isEmpty()?"":" ("+text+")"))+".":"No clock app on this phone accepts alarms.");
  }
  private void reminder(String id,JSONObject args){
    String text=args.optString("text","").trim();if(text.isEmpty()||text.length()>200)throw new IllegalArgumentException("Say what to remind you about.");
    if(args.has("in_minutes")){
      int minutes=args.optInt("in_minutes",-1);if(minutes<1||minutes>1440)throw new IllegalArgumentException("Reminders can be from 1 minute to 24 hours away.");
      Intent intent=new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH,minutes*60).putExtra(AlarmClock.EXTRA_MESSAGE,text).putExtra(AlarmClock.EXTRA_SKIP_UI,true);
      boolean opened=launcher.open(intent);finish(id,opened?"set":"unavailable",opened?"Reminder set in "+minutes+" minute"+(minutes==1?"":"s")+": "+text+".":"No clock app on this phone accepts timers.");return;
    }
    alarm(id,args,"Reminder: "+text);
  }
  private void music(String id,String query){
    String q=query==null?"":query.trim();if(q.isEmpty()||q.length()>200)throw new IllegalArgumentException("Say which song, artist or playlist to play.");
    Intent intent=new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(MediaStore.EXTRA_MEDIA_FOCUS,"vnd.android.cursor.item/*").putExtra(SearchManager.QUERY,q);
    if(launcher.open(intent)){finish(id,"playing","Playing "+q+" in your music app.");return;}
    Intent youtube=search("youtube",q);if(launcher.installed("com.google.android.youtube"))youtube.setPackage("com.google.android.youtube");
    boolean opened=launcher.open(youtube);finish(id,opened?"opened":"unavailable",opened?"No music app took the request, so YouTube search for "+q+" is open.":"No app on this phone can play music.");
  }

  interface WithControl { void run(PhoneController c); }
  private long accessibilityPromptAt;
  /**
   * Gives a live, connected control service. Readiness is checked now, not from a saved flag:
   * if the switch is on but Android is still (re)connecting the service, waits up to 3 seconds.
   * Only reports permission_required when the switch is really off, and opens Accessibility
   * settings at most once a minute so the user isn't sent there again and again.
   */
  private void control(String id,WithControl then){
    withControl(c->{
      if(c!=null){then.run(c);return;}
      if(PhoneController.enabled(activity)){
        ControlLog.i("control: switch is on but the service did not connect within 3s");
        finish(id,"unavailable","FRIDAY's accessibility switch is on, but Android has not connected the service right now. Try again in a moment; if it keeps failing, turn the FRIDAY switch off and on once in Accessibility settings.");return;
      }
      long now=System.currentTimeMillis();boolean opened=false;
      if(now-accessibilityPromptAt>60_000){accessibilityPromptAt=now;opened=launcher.open(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));}
      ControlLog.i("control: accessibility switch is off");
      finish(id,"permission_required","Phone control is off: no FRIDAY accessibility switch is on."+(opened?" I opened Accessibility settings: turn on \"FRIDAY phone control\", then ask again.":" Turn on \"FRIDAY phone control\" in Accessibility settings."));
    });
  }
  private void withControl(WithControl then){
    PhoneController now=PhoneController.get();
    if(now!=null||!PhoneController.enabled(activity)){then.run(now);return;}
    ControlLog.i("control: switch on, waiting for the service to connect");
    long until=System.currentTimeMillis()+3000;
    main.postDelayed(new Runnable(){public void run(){
      PhoneController c=PhoneController.get();
      if(c!=null||System.currentTimeMillis()>until){then.run(c);return;}
      main.postDelayed(this,250);
    }},250);
  }
  private boolean ownScreen(String id,PhoneController c){
    if(!activity.getPackageName().equals(c.foreground()))return false;
    finish(id,"refused","FRIDAY's own screen is in front. Open the app you want me to use first, then ask again.");return true;
  }
  /** Taps a labelled button. Risky labels and permission/install screens need a yes first, bound to the same app via the token. */
  private void tap(String id,JSONObject args){control(id,c->{if(!ownScreen(id,c))tap(id,args,c);});}
  private void tap(String id,JSONObject args,PhoneController c){
    String token=args.optString("confirm_token"),target,expectPkg=null;boolean confirmed=false;
    if(!token.isEmpty()){
      Confirmation k=take("tap",token);
      if(k==null){finish(id,"invalid","That confirmation expired or was already used. Ask again what to tap.");return;}
      target=k.name;expectPkg=k.phone;confirmed=true;
    }else target=args.optString("target").trim();
    if(target.isEmpty()||target.length()>120){finish(id,"invalid","Say which button or item to tap.");return;}
    String pkg=c.foreground();
    if(expectPkg!=null&&!expectPkg.equals(pkg)){finish(id,"cancelled","The screen changed since you confirmed, so I did not tap anything.");return;}
    android.view.accessibility.AccessibilityNodeInfo node=c.find(target,false);
    if(node==null){ControlLog.i("tap: \""+target+"\" not found in "+pkg);finish(id,"not_found","I can't see \""+target+"\" on this screen. Use read_screen to see what is there.");return;}
    String label=PhoneController.labelOf(node);
    if(!confirmed&&PhoneController.risky(label,pkg)){
      JSONObject value=status("needs_confirmation","Tapping \""+label+"\" in "+c.appLabel(pkg)+" may send, pay, delete, install or change a permission. Ask the user this exact question and wait for a clear yes. Only after they say yes, call mobile_action again with action tap and this confirm_token.");
      try{value.put("confirm_token",token("tap",new Recipient(target,pkg),null)).put("label",label).put("app",c.appLabel(pkg));}catch(JSONException ignored){}
      finish(id,value);return;
    }
    boolean ok=c.tap(node);
    ControlLog.i("tap \""+label+"\" in "+pkg+": "+(ok?"tapped":"no response"));
    finish(id,ok?"done":"failed",ok?"Tapped \""+label+"\". Check the screen again before the next step.":"That item did not respond to a tap.");
  }

  interface ThenWa { void with(WaContact contact); }
  /**
   * Finds the WhatsApp account for a spoken name using the rows WhatsApp syncs into Contacts, so
   * it never confuses a phone-only contact with a WhatsApp one. Same name twice: asks which one.
   * For messages (allowPhoneFallback) a saved phone contact without a synced WhatsApp row still
   * works through its number; calls need the WhatsApp row.
   */
  private void resolveWhatsApp(String id,String recipient,String kind,boolean allowPhoneFallback,ThenWa then){
    String pkg=whatsapp();if(pkg==null){finish(id,"not_installed","WhatsApp is not installed on this Android device.");return;}
    if(recipient==null||recipient.trim().isEmpty()||recipient.length()>100){finish(id,"invalid","Tell me who it is for.");return;}
    if(contacts.needsPermission()&&activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED){
      if(pending!=null){finish(id,"busy","Finish the current permission request first.");return;}
      pendingId=id;pendingPermission=Manifest.permission.READ_CONTACTS;pendingDenied=null;pending=()->resolveWhatsApp(id,recipient,kind,allowPhoneFallback,then);activity.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},CONTACTS_REQUEST);return;
    }
    String mimetype="vnd.android.cursor.item/vnd."+pkg+"."+kind;
    List<WaContact> matches=contacts.findWhatsApp(recipient.trim(),mimetype);
    ControlLog.i("whatsapp contact \""+recipient.trim()+"\" ("+kind+"): "+matches.size()+" match"+(matches.size()==1?"":"es"));
    if(matches.isEmpty()){
      if(allowPhoneFallback){resolve(id,recipient,person->then.with(new WaContact(person.name,person.phone,mimetype,-1)));return;}
      boolean phoneOnly=!recipient.trim().matches("\\+?[0-9 ()-]+")&&!contacts.find(recipient.trim()).isEmpty();
      finish(id,"not_found",phoneOnly?recipient.trim()+" is in your phone contacts, but WhatsApp has no account synced for them, so I can't place a WhatsApp call. Open WhatsApp once so it syncs contacts, or check they use WhatsApp.":"I couldn't find a WhatsApp contact called "+recipient.trim()+". Say the full saved name.");return;
    }
    boolean exact=matches.size()==1;
    if(exact){then.with(matches.get(0));return;}
    if(chooser!=null){finish(id,"busy","Choose the contact from the current list first.");return;}
    String[] labels=new String[matches.size()];for(int i=0;i<labels.length;i++)labels[i]=matches.get(i).name+" · +"+matches.get(i).phone;
    ControlLog.i("whatsapp contact: several matches, asking the user");
    chooserId=id;chooser=new AlertDialog.Builder(activity).setTitle("Which "+recipient.trim()+"?").setItems(labels,(d,index)->{chooser=null;chooserId=null;then.with(matches.get(index));})
      .setNegativeButton("Cancel",(d,w)->{chooser=null;chooserId=null;finish(id,"cancelled","Cancelled. Nothing was done.");}).setOnCancelListener(d->{chooser=null;chooserId=null;finish(id,"cancelled","Cancelled. Nothing was done.");}).show();
  }
  private void waCall(String id,String recipient,boolean video,boolean onScreen){
    resolveWhatsApp(id,recipient,video?WA_VIDEO:WA_VOICE,false,wa->{
      String what=video?"WhatsApp video call":"WhatsApp voice call";
      if(onScreen){confirmOnScreen(id,what+"?",wa.name+" (+"+wa.phone+")",()->placeWaCall(id,wa,video));return;}
      JSONObject value=status("needs_confirmation","Start a "+what+" to "+wa.name+" (+"+wa.phone+")? Ask the user this exact question and wait for a clear yes. Only after they say yes, call mobile_action again with action whatsapp_call and this confirm_token.");
      try{value.put("confirm_token",token(video?"wavideo":"wavoice",new Recipient(wa.name,wa.phone),wa.mimetype+"#"+wa.dataId)).put("recipient_name",wa.name).put("recipient_phone","+"+wa.phone).put("video",video);}catch(JSONException ignored){}
      finish(id,value);
    });
  }
  private void waCallConfirmed(String id,String token){
    Confirmation c=take("wavoice",token);boolean video=false;
    if(c==null){c=take("wavideo",token);video=true;}
    if(c==null){finish(id,"invalid","That confirmation expired or was already used. Ask again who to call on WhatsApp.");return;}
    int hash=c.text.lastIndexOf('#');
    placeWaCall(id,new WaContact(c.name,c.phone,c.text.substring(0,hash),Long.parseLong(c.text.substring(hash+1))),video);
  }
  /** Opens WhatsApp's own call entry for that contact, then checks the call screen actually appeared. */
  private void placeWaCall(String id,WaContact wa,boolean video){
    String pkg=whatsapp();if(pkg==null){finish(id,"not_installed","WhatsApp is not installed on this Android device.");return;}
    String what=video?"video call":"voice call";
    Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI,wa.dataId),wa.mimetype).setPackage(pkg);
    if(!launcher.open(intent)){ControlLog.i("whatsapp "+what+": WhatsApp refused the call intent");finish(id,"unavailable","WhatsApp did not accept the "+what+" request for "+wa.name+". Nothing was started.");return;}
    ControlLog.i("whatsapp "+what+" to "+wa.name+": intent sent, verifying");
    main.postDelayed(()->{
      PhoneController ctl=PhoneController.get();
      if(ctl==null){finish(id,"calling","Started a WhatsApp "+what+" to "+wa.name+". I can't check the call screen because FRIDAY's accessibility switch is off.");return;}
      boolean onCall=ctl.screenShows(pkg,"calling","ringing","connecting","end-to-end","end call","कॉल","reconnecting");
      ControlLog.i("whatsapp "+what+": verify "+(onCall?"call screen visible":"no call screen, front app "+ctl.foreground()));
      if(onCall)finish(id,"calling","WhatsApp "+what+" to "+wa.name+" is ringing. Checked: the WhatsApp call screen is open.");
      else finish(id,"unverified","I asked WhatsApp to start a "+what+" to "+wa.name+", but I can't see the call screen. WhatsApp may be asking for a permission or showing a prompt; check the phone.");
    },3500);
  }
  private void openContact(String id,String recipient){
    resolve(id,recipient,person->{
      boolean opened=launcher.open(new Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT,Uri.parse("tel:+"+person.phone)));
      ControlLog.i("open_contact "+person.name+": "+(opened?"opened":"no contacts app"));
      finish(id,opened?"opened":"unavailable",opened?"Opened "+person.name+"'s contact.":"No contacts app could open it.");
    });
  }

  public void permissionResult(){
    Runnable retry=pending,denied=pendingDenied;String id=pendingId,permission=pendingPermission;pending=null;pendingDenied=null;pendingId=null;pendingPermission=null;if(retry==null)return;
    if(activity.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED){retry.run();return;}
    if(denied!=null){denied.run();return;}
    finish(id,"permission_required","Contacts access was denied. Use a full international phone number or allow Contacts in Android app settings.");
  }
  public void cancel(String id){
    if(id==null||!id.matches("[A-Za-z0-9-]{1,80}"))return;if(completed.containsKey(id))return;
    if(id.equals(pendingId)){pending=null;pendingDenied=null;pendingId=null;pendingPermission=null;}
    if(id.equals(chooserId)&&chooser!=null){chooser.dismiss();chooser=null;chooserId=null;}
    if(id.equals(confirmId)&&confirmDialog!=null){confirmDialog.dismiss();confirmDialog=null;confirmId=null;}
    finish(id,"cancelled","Action cancelled. Nothing was sent.");
  }
  public void close(){pending=null;pendingDenied=null;pendingId=null;pendingPermission=null;confirmations.clear();WhatsAppSender.disarm();if(chooser!=null){chooser.dismiss();chooser=null;}if(confirmDialog!=null){confirmDialog.dismiss();confirmDialog=null;}}
}
