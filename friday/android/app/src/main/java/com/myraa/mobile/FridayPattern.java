package com.myraa.mobile;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * FRIDAY Pattern: a 3x3 pattern that locks FRIDAY's own screens (whole app, settings, memory,
 * developer mode). It is not the phone's lock and never unlocks the phone.
 *
 * The pattern itself is never stored: only an HMAC-SHA256 of it with a random salt, made with a
 * key that lives in the Android Keystore and cannot be read out of the phone. It is never logged.
 * Wrong attempts: after 5, a cooldown starts at 30 seconds and doubles with each further miss, up
 * to 30 minutes; it never locks forever, and the phone's own lock (fingerprint, face or PIN) can
 * always reset the pattern.
 */
final class FridayPattern {
  private static final String KEY_ALIAS="friday_pattern_hmac",PREFS="friday.pattern.v1";
  static final List<String> SECTIONS=Arrays.asList("app","settings","memory","developer");
  private static final int FREE_TRIES=5;
  /** Unlocked until FRIDAY goes to the background. Kept in memory only. */
  private static volatile boolean unlocked;
  private FridayPattern(){}

  private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
  static boolean isSet(Context c){return prefs(c).contains("mac");}
  static boolean enabled(Context c){return isSet(c)&&prefs(c).getBoolean("enabled",true);}
  static String mode(Context c){String m=prefs(c).getString("mode","pattern");return Arrays.asList("pattern","biometric_pattern","device").contains(m)?m:"pattern";}
  static Set<String> protectedSections(Context c){
    Set<String> out=new LinkedHashSet<>();
    try{JSONArray a=new JSONArray(prefs(c).getString("protect","[\"settings\",\"memory\",\"developer\"]"));for(int i=0;i<a.length();i++)if(SECTIONS.contains(a.optString(i)))out.add(a.optString(i));}catch(JSONException ignored){}
    return out;
  }
  /** True when this section is protected and FRIDAY is still locked. */
  static boolean needsUnlock(Context c,String section){return enabled(c)&&!unlocked&&(protectedSections(c).contains(section)||protectedSections(c).contains("app"));}
  static void lock(){unlocked=false;}
  static boolean unlocked(){return unlocked;}
  static void markUnlocked(){unlocked=true;}
  static boolean deviceSecure(Context c){KeyguardManager k=c.getSystemService(KeyguardManager.class);return k!=null&&k.isDeviceSecure();}

  static JSONObject state(Context c){
    JSONObject v=new JSONObject();
    try{v.put("set",isSet(c)).put("enabled",enabled(c)).put("mode",mode(c)).put("protect",new JSONArray(protectedSections(c))).put("unlocked",unlocked)
      .put("wait",waitSeconds(c)).put("deviceSecure",deviceSecure(c)).put("biometric",android.os.Build.VERSION.SDK_INT>=28&&deviceSecure(c));}catch(JSONException ignored){}
    return v;
  }

  // ---- create / change
  /** Saves a new pattern. Changing an existing one needs FRIDAY unlocked first. */
  static JSONObject save(Context c,String first,String second){
    if(isSet(c)&&!unlocked)return result(false,"Unlock FRIDAY first to change the pattern.");
    if(!valid(first))return result(false,"Connect at least 4 dots.");
    if(!first.equals(second))return result(false,"The patterns didn't match. Try again.");
    try{
      byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);
      byte[] mac=mac(salt,first);
      prefs(c).edit().putString("salt",Base64.encodeToString(salt,Base64.NO_WRAP)).putString("mac",Base64.encodeToString(mac,Base64.NO_WRAP))
        .putBoolean("enabled",true).putInt("fails",0).putLong("until",0).apply();
      unlocked=true;
      ControlLog.i("pattern: saved");
      return result(true,"FRIDAY pattern is ready.");
    }catch(Exception e){ControlLog.i("pattern: secure storage unavailable");return result(false,"This phone's secure key storage isn't available, so the pattern can't be saved safely.");}
  }
  /** A pattern is 4 to 9 different dots, numbered 0 to 8. */
  static boolean valid(String p){
    if(p==null||!p.matches("[0-8]{4,9}"))return false;
    Set<Character> seen=new HashSet<>();for(char ch:p.toCharArray())if(!seen.add(ch))return false;
    return true;
  }

  // ---- unlock
  static JSONObject verify(Context c,String attempt){
    if(!isSet(c))return result(false,"No FRIDAY pattern is set.");
    long wait=waitSeconds(c);
    if(wait>0){JSONObject r=result(false,"Too many tries. Wait "+wait+" seconds.");try{r.put("wait",wait);}catch(JSONException ignored){}return r;}
    boolean ok=false;
    try{
      byte[] salt=Base64.decode(prefs(c).getString("salt",""),Base64.NO_WRAP),saved=Base64.decode(prefs(c).getString("mac",""),Base64.NO_WRAP);
      ok=attempt!=null&&attempt.matches("[0-8]{1,9}")&&MessageDigest.isEqual(mac(salt,attempt),saved);
    }catch(Exception e){ControlLog.i("pattern: check failed (secure storage)");return result(false,"The secure key couldn't be read. Use your phone lock to reset the pattern.");}
    SharedPreferences p=prefs(c);
    if(ok){p.edit().putInt("fails",0).putLong("until",0).apply();unlocked=true;ControlLog.i("pattern: unlocked");return result(true,"Unlocked.");}
    int fails=p.getInt("fails",0)+1;long until=0;
    if(fails>=FREE_TRIES){long seconds=Math.min(30L<<Math.min(fails-FREE_TRIES,6),30*60);until=System.currentTimeMillis()+seconds*1000;}
    p.edit().putInt("fails",fails).putLong("until",until).apply();
    ControlLog.i("pattern: wrong attempt "+fails);
    JSONObject r=result(false,until>0?"Incorrect pattern. Too many tries: wait "+waitSeconds(c)+" seconds.":"Incorrect pattern.");
    try{r.put("wait",waitSeconds(c)).put("left",Math.max(0,FREE_TRIES-fails));}catch(JSONException ignored){}
    return r;
  }
  static long waitSeconds(Context c){long left=prefs(c).getLong("until",0)-System.currentTimeMillis();return left>0?(left+999)/1000:0;}
  /** The phone's own fingerprint, face or PIN confirmed it is the owner. */
  static void unlockedByDevice(Context c){prefs(c).edit().putInt("fails",0).putLong("until",0).apply();unlocked=true;ControlLog.i("pattern: unlocked with phone lock");}

  // ---- options (FRIDAY must be unlocked)
  static JSONObject options(Context c,JSONObject in){
    if(isSet(c)&&enabled(c)&&!unlocked)return result(false,"Unlock FRIDAY first.");
    SharedPreferences.Editor e=prefs(c).edit();
    if(in.has("mode")){String m=in.optString("mode");if(!Arrays.asList("pattern","biometric_pattern","device").contains(m))return result(false,"Unknown unlock method.");if(!"pattern".equals(m)&&!deviceSecure(c))return result(false,"Set a screen lock on the phone first to use fingerprint, face or phone lock.");e.putString("mode",m);}
    if(in.has("protect")){JSONArray a=in.optJSONArray("protect"),clean=new JSONArray();if(a!=null)for(int i=0;i<a.length();i++)if(SECTIONS.contains(a.optString(i)))clean.put(a.optString(i));e.putString("protect",clean.toString());}
    if(in.has("enabled")){if(in.optBoolean("enabled")&&!isSet(c))return result(false,"Create a pattern first.");e.putBoolean("enabled",in.optBoolean("enabled"));}
    e.apply();return result(true,"Saved.");
  }
  /** Removes the pattern. Needs FRIDAY unlocked (by pattern or by the phone's own lock). */
  static JSONObject remove(Context c){
    if(isSet(c)&&!unlocked)return result(false,"Unlock FRIDAY first.");
    prefs(c).edit().remove("salt").remove("mac").remove("fails").remove("until").putBoolean("enabled",false).apply();
    ControlLog.i("pattern: removed");return result(true,"FRIDAY pattern is turned off and deleted.");
  }

  // ---- Keystore
  private static byte[] mac(byte[] salt,String pattern) throws Exception {
    Mac m=Mac.getInstance("HmacSHA256");m.init(key());m.update(salt);m.update(pattern.getBytes(StandardCharsets.US_ASCII));return m.doFinal();
  }
  private static SecretKey key() throws Exception {
    KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
    if(ks.containsAlias(KEY_ALIAS))return (SecretKey)ks.getKey(KEY_ALIAS,null);
    KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,"AndroidKeyStore");
    g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_SIGN).build());
    return g.generateKey();
  }
  private static JSONObject result(boolean ok,String message){JSONObject v=new JSONObject();try{v.put("ok",ok).put("message",message);}catch(JSONException ignored){}return v;}
}
