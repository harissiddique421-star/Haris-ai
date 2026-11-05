package com.myraa.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.*;
import org.json.*;
import java.util.*;

/** Bounded Android intents. No accessibility, arbitrary URLs, shell or automatic sending. */
public final class MobileActions {
  public static final int CONTACTS_REQUEST=88;
  public interface Result { void complete(String id,JSONObject result); }
  public interface Launcher { boolean installed(String pkg); Intent app(String label); boolean open(Intent intent); }
  public interface Contacts { boolean needsPermission(); List<Recipient> find(String name); }
  public static final class Recipient {
    public final String name,phone;
    public Recipient(String name,String phone){this.name=name;this.phone=phone;}
  }
  private final Activity activity;
  private final Result result;
  private final Launcher launcher;
  private final Contacts contacts;
  private Runnable pending;
  private String pendingId;
  private AlertDialog chooser;
  private String chooserId;
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
  private void finish(String id,String status,String message){JSONObject value=status(status,message);completed.put(id,value);while(completed.size()>64)completed.remove(completed.keySet().iterator().next());result.complete(id,value);}
  public void execute(String id,String name,String raw){
    if(id==null||!id.matches("[A-Za-z0-9-]{1,80}"))return;
    if(completed.containsKey(id)){result.complete(id,completed.get(id));return;}
    if(!"mobile_action".equals(name)){finish(id,"unsupported","That mobile action is unavailable.");return;}
    try{
      JSONObject args=new JSONObject(raw);String action=args.optString("action");
      if("open_app".equals(action)){openApp(id,args.optString("app"));return;}
      if("search_youtube".equals(action)||"search_web".equals(action)){
        boolean youtube=action.equals("search_youtube");Intent intent=search(youtube?"youtube":"web",args.optString("query"));String pkg=youtube?"com.google.android.youtube":"com.android.chrome";
        if(launcher.installed(pkg))intent.setPackage(pkg);else if(youtube&&launcher.installed("com.android.chrome"))intent.setPackage("com.android.chrome");
        boolean opened=launcher.open(intent);if(!opened&&intent.getPackage()!=null)opened=launcher.open(new Intent(intent).setPackage(null));
        finish(id,opened?"opened":"unavailable",opened?(youtube?"YouTube":"Browser")+" search opened.":"No app can open this search on this device.");return;
      }
      if("whatsapp_message".equals(action)){message(id,args.optString("recipient"),args.optString("message"));return;}
      finish(id,"unsupported","Supported actions: open an app, search YouTube or the web, and prepare a WhatsApp message.");
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
  private void message(String id,String recipient,String text){
    if(whatsapp()==null){finish(id,"not_installed","WhatsApp is not installed on this Android device.");return;}
    if(recipient.trim().isEmpty()||recipient.length()>100||text.trim().isEmpty()||text.length()>4000){finish(id,"invalid","Tell me the recipient and the complete message.");return;}
    if(recipient.trim().matches("\\+?[0-9 ()-]+")){
      String phone=recipient.replaceAll("[ ()+-]","");if(phone.startsWith("00"))phone=phone.substring(2);boolean international=recipient.trim().startsWith("+")||recipient.trim().startsWith("00")||phone.length()>=11;if(!international||!phone.matches("[1-9][0-9]{7,14}")){finish(id,"invalid","Include the country code in the recipient phone number, for example +91.");return;}
      openDraft(id,new Recipient(recipient,phone),text);return;
    }
    if(contacts.needsPermission()&&activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED){
      if(pending!=null){finish(id,"busy","Finish the current contact selection first.");return;}
      pendingId=id;pending=()->message(id,recipient,text);activity.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},CONTACTS_REQUEST);return;
    }
    List<Recipient> matches=contacts.find(recipient.trim());
    if(matches.isEmpty()){finish(id,"not_found","No matching contact with a usable phone number was found. Give the full saved name or an international phone number.");return;}
    if(matches.size()==1){openDraft(id,matches.get(0),text);return;}
    if(chooser!=null){finish(id,"busy","Choose the recipient from the current list first.");return;}
    String[] labels=new String[matches.size()];for(int i=0;i<labels.length;i++)labels[i]=matches.get(i).name+" · +"+matches.get(i).phone;
    chooserId=id;chooser=new AlertDialog.Builder(activity).setTitle("Which "+recipient+"?").setItems(labels,(d,index)->{chooser=null;openDraft(id,matches.get(index),text);})
      .setNegativeButton("Cancel",(d,w)->{chooser=null;finish(id,"cancelled","Message cancelled. Nothing was sent.");}).setOnCancelListener(d->{chooser=null;finish(id,"cancelled","Message cancelled. Nothing was sent.");}).show();
  }
  private void openDraft(String id,Recipient person,String text){boolean opened=launcher.open(draft(person.phone,text,whatsapp()));finish(id,opened?"draft_opened":"unavailable",opened?"WhatsApp draft opened for "+person.name+". Review it and tap Send in WhatsApp; MYRAA has not sent it.":"WhatsApp could not open this recipient. Nothing was sent.");}
  public void permissionResult(){Runnable retry=pending;String id=pendingId;pending=null;pendingId=null;if(retry==null)return;if(activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED)retry.run();else finish(id,"permission_required","Contacts access was denied. Use a full international phone number or allow Contacts in Android app settings.");}
  public void cancel(String id){if(id==null||!id.matches("[A-Za-z0-9-]{1,80}"))return;if(completed.containsKey(id))return;if(id.equals(pendingId)){pending=null;pendingId=null;}if(id.equals(chooserId)&&chooser!=null){chooser.dismiss();chooser=null;chooserId=null;}finish(id,"cancelled","Action cancelled. Nothing was sent.");}
  public void close(){pending=null;pendingId=null;if(chooser!=null){chooser.dismiss();chooser=null;}}
}
