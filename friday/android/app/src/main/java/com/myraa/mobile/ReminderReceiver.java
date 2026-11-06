package com.myraa.mobile;

import android.content.*;

/** Wakes up for FRIDAY's reminders, their Done / Snooze buttons, the end of meeting mode, namaz reminders, and phone restarts. */
public final class ReminderReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context,Intent intent){
    String action=intent==null?null:intent.getAction();if(action==null)return;
    long id=intent.getLongExtra("id",-1);
    switch(action){
      case CareReminders.FIRE:CareReminders.fire(context,id);break;
      case CareReminders.DONE:CareReminders.markDone(context,id);break;
      case CareReminders.SNOOZE:CareReminders.snooze(context,id);break;
      case CareReminders.SKIP:CareReminders.skipped(context,id);break;
      case MeetingMode.END:MeetingMode.stop(context,true);break;
      case Proactive.CHECK:Proactive.check(context);break;
      case Prayer.FIRE:Prayer.fire(context,intent.getStringExtra("prayer"),intent.getStringExtra("at"));break;
      case Intent.ACTION_BOOT_COMPLETED:case Intent.ACTION_MY_PACKAGE_REPLACED:
        CareReminders.rescheduleAll(context);MeetingMode.afterRestart(context);Proactive.schedule(context);Prayer.scheduleNext(context);WakeWordService.afterBoot(context);break;
      default:break;
    }
  }
}
