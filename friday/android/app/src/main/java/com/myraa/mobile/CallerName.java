package com.myraa.mobile;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import java.util.*;

/**
 * Turns the caller shown in the dialer's call notification into the saved contact name, using
 * the contacts already on the phone. Nothing is stored or sent anywhere. Without the Contacts
 * permission, or when the number is not saved, the number is kept as it was.
 */
final class CallerName {
  private CallerName(){}

  /** What the dialer shows: usually the caller in the title; some dialers put "Incoming call" there and the number in the text. */
  static String pick(CharSequence title,CharSequence text){
    String t=strip(title),x=strip(text);
    if(!looksLikeNumber(t)&&looksLikeNumber(x)&&(t.isEmpty()||t.matches("(?i).*\\b(incoming|calling|call)\\b.*")))return x;
    return t;
  }
  /** The saved contact name for a number, or the caller unchanged. */
  static String resolve(Context c,String caller){
    String shown=strip(caller);
    if(!looksLikeNumber(shown))return shown;
    if(c.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)return shown;
    try{
      for(String n:variants(c,shown)){String name=lookup(c,n);if(name!=null)return name;}
      String name=byLastDigits(c,digits(shown));if(name!=null)return name;
    }catch(RuntimeException e){ControlLog.i("caller: contact lookup skipped ("+e.getClass().getSimpleName()+")");}
    return shown;
  }
  static boolean isNumber(String s){return looksLikeNumber(strip(s));}

  /** Reads digits one by one so the speech engine does not say "three billion". */
  static String speakable(String caller){
    String s=strip(caller);if(!looksLikeNumber(s))return s;
    StringBuilder b=new StringBuilder();
    for(char ch:s.toCharArray()){if(ch=='+')b.append("plus ");else if(Character.isDigit(ch))b.append(ch).append(' ');}
    return b.toString().trim();
  }

  static boolean looksLikeNumber(String s){
    if(s==null||s.isEmpty()||!s.matches("[+0-9()\\-.\\s]+"))return false;
    return digits(s).length()>=5;
  }
  static String digits(String s){return s==null?"":s.replaceAll("[^0-9]","");}
  private static String strip(CharSequence v){return v==null?"":v.toString().replaceAll("[\\p{Cf}\\p{Cntrl}]","").replaceAll("\\s+"," ").trim();}

  /** 03001234567, +923001234567 and 00923001234567 are the same Pakistani number; other countries follow the SIM. */
  static List<String> variants(Context c,String number){
    LinkedHashSet<String> out=new LinkedHashSet<>();String d=digits(number);
    out.add(number.trim());
    if(number.trim().startsWith("+"))out.add("+"+d);
    if(d.startsWith("00")&&d.length()>4)out.add("+"+d.substring(2));
    String iso="";
    try{TelephonyManager t=c.getSystemService(TelephonyManager.class);iso=t.getSimCountryIso();if(iso==null||iso.isEmpty())iso=t.getNetworkCountryIso();}catch(RuntimeException ignored){}
    if(iso==null||iso.isEmpty())iso="pk";
    try{String e164=PhoneNumberUtils.formatNumberToE164(number,iso.toUpperCase(Locale.ROOT));if(e164!=null)out.add(e164);}catch(RuntimeException ignored){}
    // Pakistan, also when the SIM says otherwise: 03xx… <-> +923xx…
    if(d.startsWith("0")&&!d.startsWith("00")&&d.length()==11)out.add("+92"+d.substring(1));
    String intl=d.startsWith("0092")?d.substring(4):d.startsWith("92")&&d.length()==12?d.substring(2):null;
    if(intl!=null){out.add("+92"+intl);out.add("0"+intl);}
    out.add(d);
    return new ArrayList<>(out);
  }
  private static String lookup(Context c,String number){
    if(number==null||number.isEmpty())return null;
    Uri uri=Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,Uri.encode(number));
    try(Cursor cur=c.getContentResolver().query(uri,new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},null,null,null)){
      while(cur!=null&&cur.moveToNext()){String name=cur.getString(0);if(name!=null&&!name.trim().isEmpty())return name.trim();}
    }
    return null;
  }
  /** Last resort for phones whose lookup is strict about formats: match the last 10 digits. */
  private static String byLastDigits(Context c,String d){
    if(d.length()<7)return null;
    String tail=d.length()>10?d.substring(d.length()-10):d;
    String[] cols={ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER};
    try(Cursor cur=c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,cols,
        ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER+" LIKE ? OR "+ContactsContract.CommonDataKinds.Phone.NUMBER+" LIKE ?",
        new String[]{"%"+tail,"%"+tail.substring(tail.length()-7)},null)){
      while(cur!=null&&cur.moveToNext()){
        String name=cur.getString(0),num=digits(cur.getString(1));
        if(name!=null&&!name.trim().isEmpty()&&num.endsWith(tail))return name.trim();
      }
    }
    return null;
  }
}
