package com.myraa.mobile;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Visual memory: short descriptions of things FRIDAY looked at (a document, objects, a place, a screen, a video,
 * a comparison) that the user explicitly chose to keep, so they can ask later "maine wo bill kab dekha tha".
 * Only text is kept, never the picture, video or screenshot itself. The list lives in FRIDAY's private files,
 * encrypted with AES-GCM under a key held in the Android Keystore (it cannot be read out of the phone), and the
 * user can list, search and delete any entry or all of them. Nothing here is uploaded.
 */
final class VisualMemory {
  private VisualMemory(){}
  private static final String KEY_ALIAS="friday_visual_memory",FILE="visual_memory.enc";
  private static final Object lock=new Object();

  /** All memories, newest last; an empty list when there are none or the file cannot be read. */
  static JSONArray all(Context c){
    synchronized(lock){
      File f=new File(c.getFilesDir(),FILE);
      if(!f.exists())return new JSONArray();
      try(InputStream in=new FileInputStream(f)){
        byte[] data=readAll(in);
        if(data.length<13)return new JSONArray();
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOfRange(data,0,12)));
        return new JSONArray(new String(cipher.doFinal(data,12,data.length-12),StandardCharsets.UTF_8));
      }catch(Exception e){ControlLog.i("visual memory: could not be read ("+e.getClass().getSimpleName()+")");return new JSONArray();}
    }
  }

  /** Keeps one memory: category, title, description and the user's own note. Returns it with its id and time. */
  static JSONObject add(Context c,String category,String title,String description,String note) throws Exception {
    synchronized(lock){
      JSONArray list=all(c);
      long now=System.currentTimeMillis();
      JSONObject m=new JSONObject().put("id",Long.toString(now,36)).put("at",now).put("category",VisionLogic.memoryCategory(category))
        .put("title",VisionLogic.clean(title,80)).put("description",VisionLogic.clean(description,1200));
      String n=VisionLogic.clean(note,300);if(!n.isEmpty())m.put("note",n);
      list.put(m);
      while(list.length()>VisionLogic.MAX_MEMORIES)list.remove(0);
      write(c,list);
      return m;
    }
  }

  /** Deletes the memory with this id; true when one was deleted. */
  static boolean delete(Context c,String id) throws Exception {
    synchronized(lock){
      JSONArray list=all(c),keep=new JSONArray();boolean gone=false;
      for(int i=0;i<list.length();i++){JSONObject m=list.optJSONObject(i);if(m!=null&&m.optString("id").equals(id))gone=true;else if(m!=null)keep.put(m);}
      if(gone)write(c,keep);
      return gone;
    }
  }

  /** Deletes every memory; returns how many there were. */
  static int clear(Context c){
    synchronized(lock){int n=all(c).length();new File(c.getFilesDir(),FILE).delete();return n;}
  }

  private static void write(Context c,JSONArray list) throws Exception {
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
    byte[] iv=cipher.getIV(),body=cipher.doFinal(list.toString().getBytes(StandardCharsets.UTF_8));
    File tmp=new File(c.getFilesDir(),FILE+".tmp"),f=new File(c.getFilesDir(),FILE);
    try(FileOutputStream out=new FileOutputStream(tmp)){out.write(iv);out.write(body);out.getFD().sync();}
    if(!tmp.renameTo(f)){tmp.delete();throw new IOException("The visual memory could not be saved.");}
  }

  private static SecretKey key() throws Exception {
    KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
    if(ks.containsAlias(KEY_ALIAS))return ((KeyStore.SecretKeyEntry)ks.getEntry(KEY_ALIAS,null)).getSecretKey();
    KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
    return g.generateKey();
  }

  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
    while((n=in.read(buf))>0){out.write(buf,0,n);if(out.size()>4_000_000)throw new IOException("too big");}
    return out.toByteArray();
  }

  /** One memory as a line for the model: id, date, category, title, description. */
  static String line(JSONObject m){
    java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("d MMM yyyy, h:mm a",java.util.Locale.ENGLISH);
    return "["+m.optString("id")+"] "+f.format(new java.util.Date(m.optLong("at")))+" ("+m.optString("category")+") "+m.optString("title")+": "
      +VisionLogic.clip(m.optString("description"),400)+(m.has("note")?" Note: "+m.optString("note"):"");
  }

  static JSONArray lines(JSONArray list){JSONArray out=new JSONArray();for(int i=0;i<list.length();i++){JSONObject m=list.optJSONObject(i);if(m!=null)out.put(line(m));}return out;}

}
