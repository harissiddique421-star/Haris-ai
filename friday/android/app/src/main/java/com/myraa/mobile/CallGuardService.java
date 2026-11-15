package com.myraa.mobile;

import android.net.Uri;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallScreeningService;

/**
 * Android hands this service each incoming call from a number that is not in the contacts, once the user lets
 * FRIDAY screen calls (Settings > Phone protection). Blocked numbers are rejected; others ring as usual with
 * FRIDAY's spam warning next to them. Nothing about the call leaves the phone.
 */
public final class CallGuardService extends CallScreeningService {
  @Override public void onScreenCall(Call.Details details){
    CallResponse allow=new CallResponse.Builder().build();
    try{
      if(Build.VERSION.SDK_INT>=29&&details.getCallDirection()!=Call.Details.DIRECTION_INCOMING){respondToCall(details,allow);return;}
      Uri handle=details.getHandle();
      String number=handle==null?"":handle.getSchemeSpecificPart();
      if(SpamGuard.incoming(this,number==null?"":number)){
        respondToCall(details,new CallResponse.Builder().setDisallowCall(true).setRejectCall(true).setSkipNotification(false).setSkipCallLog(false).build());
        return;
      }
    }catch(RuntimeException e){ControlLog.i("spam: screening failed ("+e.getClass().getSimpleName()+")");}
    respondToCall(details,allow);
  }
}
