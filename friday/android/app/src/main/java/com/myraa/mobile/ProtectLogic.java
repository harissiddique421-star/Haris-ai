package com.myraa.mobile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain rules behind "Find my phone" and the spam call warning, kept free of Android so they can be
 * checked on a normal JVM: reading the "FRIDAY dhoondo 1234" SMS, the secret code, and how risky a
 * call from a number that is not in the contacts looks.
 */
final class ProtectLogic {
  private ProtectLogic(){}

  // "FRIDAY dhoondo 1234", also the common spellings and English "find" / "ring"; nothing else may be in the SMS.
  private static final Pattern FIND=Pattern.compile("^\\s*friday[\\s,:.!-]+(?:dhoondo|dhundo|dhoondho|dhundho|dhoondh|dhundh|dhoondhna|find|ring)[\\s,:.!-]+([A-Za-z0-9]{1,40})\\s*[.!]?\\s*$",Pattern.CASE_INSENSITIVE);

  /** The code in a "FRIDAY dhoondo <code>" SMS, or null when the SMS is anything else. */
  static String findCode(String sms){
    if(sms==null||sms.length()>120)return null;
    Matcher m=FIND.matcher(sms.replace(' ',' '));
    return m.matches()?m.group(1):null;
  }
  /** 4 to 12 letters or digits. */
  static boolean validCode(String code){return code!=null&&code.matches("[A-Za-z0-9]{4,12}");}
  /** Codes are compared without case, so "Ab12" and "ab12" are the same. Only this hash is stored. */
  static String hash(String salt,String code){
    try{
      byte[] d=MessageDigest.getInstance("SHA-256").digest((salt+":"+code.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
      StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format(Locale.ROOT,"%02x",x));return b.toString();
    }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  }
  /** Same length and every byte compared, so the time taken does not hint at the code. */
  static boolean sameHash(String a,String b){
    if(a==null||b==null||a.length()!=b.length())return false;
    int diff=0;for(int i=0;i<a.length();i++)diff|=a.charAt(i)^b.charAt(i);return diff==0;
  }

  // ---- Spam call warning
  static final String NONE="none",MEDIUM="medium",HIGH="high";
  static final class Risk {
    final String level,reason;
    Risk(String level,String reason){this.level=level;this.reason=reason;}
  }
  static String digits(String s){return s==null?"":s.replaceAll("[^0-9]","");}

  /**
   * How a call from a number that is NOT a saved contact looks. homeCode is the country calling code of the
   * phone's SIM ("92" for Pakistan), or "" when unknown. Short service numbers (1122, 15) are left alone.
   */
  static Risk callRisk(String number,String homeCode){
    String raw=number==null?"":number.trim(),d=digits(raw);
    if(d.isEmpty())return new Risk(HIGH,"Number chhupa hua hai (private / unknown number). Fraud karne wale aksar number chhupate hain.");
    if(d.length()<=6)return new Risk(NONE,"");
    String intl=raw.startsWith("+")?d:d.startsWith("00")?d.substring(2):null;
    if(intl!=null&&!homeCode.isEmpty()&&!intl.startsWith(homeCode))
      return new Risk(HIGH,"Yeh bahar ke mulk ka number hai. Aisi anjaan international calls aksar fraud hoti hain.");
    return new Risk(MEDIUM,"Yeh number aap ke contacts mein nahi hai.");
  }
  /** The warning shown on the notification. */
  static String advice(Risk r){
    return r.reason+" Ho sakta hai spam ya fraud ho. Apna OTP, PIN, CNIC ya bank / JazzCash / Easypaisa ki details kabhi na batayein.";
  }
  /** Numbers are matched by their last 10 digits, so 0300…, +92300… and 0092300… are the same. */
  static String key(String number){
    String d=digits(number);
    return d.length()>10?d.substring(d.length()-10):d;
  }
}
