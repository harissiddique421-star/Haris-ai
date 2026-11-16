package com.myraa.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Notification buttons: Stop for Find my phone, Block for the spam call warning. */
public final class ProtectReceiver extends BroadcastReceiver {
  static final String STOP_RING="com.myraa.mobile.FINDER_STOP",BLOCK="com.myraa.mobile.SPAM_BLOCK";
  @Override public void onReceive(Context c,Intent intent){
    String action=intent==null?null:intent.getAction();
    if(STOP_RING.equals(action))PhoneFinder.stop(c);
    else if(BLOCK.equals(action))SpamGuard.blockFromWarning(c,intent.getStringExtra("number"));
  }
}
