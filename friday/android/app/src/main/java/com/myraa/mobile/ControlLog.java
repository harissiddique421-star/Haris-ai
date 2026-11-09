package com.myraa.mobile;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Debug log for the phone control layer: written to logcat (tag FRIDAY.Control) and kept as the
 * last 80 lines, so the user can see it in Settings > diagnostics without a PC.
 * Never logs message text, typed text or phone numbers, only what kind of step ran and why it failed.
 *
 * The lines are also kept in a small private file, so the steps before FRIDAY was closed (a crash,
 * or Android ending the app) are still there after it starts again. They are marked as from the
 * last run.
 */
final class ControlLog {
  private static final String TAG="FRIDAY.Control";
  private static final int MAX=80;
  private static final ArrayDeque<String> lines=new ArrayDeque<>();
  private static File file;
  private static final ExecutorService writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"friday-log");t.setDaemon(true);return t;});
  private ControlLog(){}

  /** Loads the last run's lines (once per process) and keeps writing new ones to the file. */
  static synchronized void init(File dir){
    if(file!=null||dir==null)return;
    file=new File(dir,"control-log.txt");
    try{
      if(file.exists()&&file.length()<64_000){
        List<String> old=new ArrayList<>();
        for(String l:new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8).split("\n"))
          if(!l.isEmpty())old.add(l.startsWith("[last run] ")?l:"[last run] "+l);
        List<String> mine=new ArrayList<>(lines);lines.clear();
        lines.addAll(old.subList(Math.max(0,old.size()-40),old.size()));lines.addAll(mine);
        while(lines.size()>MAX)lines.removeFirst();
      }
    }catch(Exception ignored){}
    save();
  }

  static synchronized void i(String message){
    Log.i(TAG,message);
    lines.addLast(new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+" "+message);
    while(lines.size()>MAX)lines.removeFirst();
    save();
  }

  /** From the crash handler: written at once, because the app is about to close. */
  static synchronized void crash(String where){
    Log.i(TAG,"crash: "+where);
    lines.addLast(new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+" CRASH: "+where);
    while(lines.size()>MAX)lines.removeFirst();
    write(String.join("\n",lines));
  }

  static synchronized List<String> recent(){return new ArrayList<>(lines);}

  private static void save(){
    if(file==null)return;
    final String text=String.join("\n",lines);
    try{writer.execute(()->write(text));}catch(RuntimeException ignored){}
  }
  private static void write(String text){
    File f=file;if(f==null)return;
    try(FileOutputStream out=new FileOutputStream(f)){out.write(text.getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}
  }
}
