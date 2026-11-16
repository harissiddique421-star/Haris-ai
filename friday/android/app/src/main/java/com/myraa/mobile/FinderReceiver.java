package com.myraa.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every incoming SMS, only to look for the "FRIDAY dhoondo <code>" message (see PhoneFinder). Nothing else in
 * any SMS is read, kept or sent anywhere, and when Find my phone is off nothing is looked at at all.
 */
public final class FinderReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c,Intent intent){
    if(intent==null||!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction()))return;
    if(!PhoneFinder.enabled(c))return;
    SmsMessage[] parts;
    try{parts=Telephony.Sms.Intents.getMessagesFromIntent(intent);}catch(RuntimeException e){return;}
    if(parts==null)return;
    // One SMS can arrive in several parts: join them per sender.
    Map<String,StringBuilder> bySender=new LinkedHashMap<>();
    for(SmsMessage m:parts){
      if(m==null)continue;String from=m.getOriginatingAddress();if(from==null)continue;
      StringBuilder b=bySender.get(from);if(b==null){b=new StringBuilder();bySender.put(from,b);}
      String body=m.getMessageBody();if(body!=null)b.append(body);
    }
    for(Map.Entry<String,StringBuilder> e:bySender.entrySet()){
      if(e.getValue().length()>160)continue;
      try{if(PhoneFinder.onSms(c,e.getKey(),e.getValue().toString()))break;}catch(RuntimeException ex){ControlLog.i("finder: failed ("+ex.getClass().getSimpleName()+")");}
    }
  }
}
