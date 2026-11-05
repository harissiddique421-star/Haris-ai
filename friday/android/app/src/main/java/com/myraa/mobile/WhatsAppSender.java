package com.myraa.mobile;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.accessibility.*;
import java.util.List;

/**
 * Presses WhatsApp's Send button once, only for a message the user has just confirmed.
 * Idle unless armed: it ignores every event while no confirmed job is waiting, only listens
 * to WhatsApp packages (see res/xml/whatsapp_sender.xml), checks the typed text matches the
 * confirmed text exactly, then clicks Send and disarms. Jobs expire after 20 seconds.
 */
public final class WhatsAppSender extends AccessibilityService {
  public interface Done { void done(boolean sent,String why); }
  private static final long TIMEOUT_MS=20_000;
  private static final Handler main=new Handler(Looper.getMainLooper());
  private static Job job;
  private static final class Job { final String pkg,phone,text;final Done done;final Runnable expire;Job(String pkg,String phone,String text,Done done,Runnable expire){this.pkg=pkg;this.phone=phone;this.text=text;this.done=done;this.expire=expire;} }

  public static boolean enabled(Context context){
    String list=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    if(list==null)return false;
    ComponentName me=new ComponentName(context,WhatsAppSender.class);
    for(String item:list.split(":")){ComponentName c=ComponentName.unflattenFromString(item);if(me.equals(c))return true;}
    return false;
  }
  public static synchronized void arm(String pkg,String phone,String text,Done done){
    disarm();
    Runnable expire=new Runnable(){public void run(){Job j=finishJob(this);if(j!=null)j.done.done(false,"WhatsApp did not show the Send button in time");}};
    job=new Job(pkg,phone,text,done,expire);main.postDelayed(expire,TIMEOUT_MS);
  }
  public static synchronized void disarm(){if(job!=null){main.removeCallbacks(job.expire);job=null;}}
  private static synchronized Job finishJob(Runnable expectedExpire){
    if(job==null||(expectedExpire!=null&&job.expire!=expectedExpire))return null;
    Job j=job;job=null;main.removeCallbacks(j.expire);return j;
  }
  private static synchronized Job current(){return job;}

  @Override public void onAccessibilityEvent(AccessibilityEvent event){
    Job j=current();if(j==null||event==null||event.getPackageName()==null||!j.pkg.contentEquals(event.getPackageName()))return;
    AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null||root.getPackageName()==null||!j.pkg.contentEquals(root.getPackageName()))return;
    AccessibilityNodeInfo entry=first(root.findAccessibilityNodeInfosByViewId(j.pkg+":id/entry"));
    if(entry==null||entry.getText()==null)return;
    if(!entry.getText().toString().trim().equals(j.text.trim()))return;
    AccessibilityNodeInfo send=first(root.findAccessibilityNodeInfosByViewId(j.pkg+":id/send"));
    if(send==null){List<AccessibilityNodeInfo> byLabel=root.findAccessibilityNodeInfosByText("Send");for(AccessibilityNodeInfo n:byLabel)if(n.isClickable()&&n.getContentDescription()!=null&&"send".equalsIgnoreCase(n.getContentDescription().toString())){send=n;break;}}
    if(send==null||!send.isEnabled())return;
    Job mine=finishJob(j.expire);if(mine==null)return;
    boolean clicked=send.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    mine.done.done(clicked,clicked?"sent":"WhatsApp did not accept the Send tap");
  }
  private static AccessibilityNodeInfo first(List<AccessibilityNodeInfo> nodes){return nodes==null||nodes.isEmpty()?null:nodes.get(0);}
  @Override public void onInterrupt(){}
  @Override public boolean onUnbind(Intent intent){Job j=finishJob(null);if(j!=null)j.done.done(false,"automatic sending was turned off");return super.onUnbind(intent);}
}
