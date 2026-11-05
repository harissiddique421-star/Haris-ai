package com.myraa.mobile;

import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Turns text FRIDAY wrote (a letter, resume, notes, a list) into a PDF or text file in the
 * phone's Downloads folder, then opens it. Nothing is uploaded; the file stays on the phone.
 */
final class Documents {
  static final int MAX_CHARS=40_000;
  private Documents(){}

  static final class Saved { final Uri uri;final String name,mime; Saved(Uri u,String n,String m){uri=u;name=n;mime=m;} }

  static Saved save(Context c,String rawTitle,String text,String format) throws IOException {
    if(Build.VERSION.SDK_INT<29)throw new IllegalArgumentException("Saving documents needs Android 10 or newer.");
    String title=rawTitle==null?"":rawTitle.trim().replaceAll("\\s+"," ");
    if(title.isEmpty())title="FRIDAY document";
    if(title.length()>80)title=title.substring(0,80);
    if(text==null||text.trim().isEmpty())throw new IllegalArgumentException("Tell me what to write in the document.");
    if(text.length()>MAX_CHARS)throw new IllegalArgumentException("That document is too long for one file.");
    boolean pdf=!"txt".equalsIgnoreCase(format);
    String safe=title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","").trim();if(safe.isEmpty())safe="FRIDAY document";
    String stamp=new SimpleDateFormat("yyyy-MM-dd HHmm",Locale.ROOT).format(new Date());
    String name=safe+" "+stamp+(pdf?".pdf":".txt"),mime=pdf?"application/pdf":"text/plain";
    ContentValues values=new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
    values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/FRIDAY");values.put(MediaStore.MediaColumns.IS_PENDING,1);
    ContentResolver resolver=c.getContentResolver();
    Uri uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
    if(uri==null)throw new IOException("Downloads is not available.");
    try(OutputStream out=resolver.openOutputStream(uri,"w")){
      if(out==null)throw new IOException("no stream");
      if(pdf)writePdf(out,title,text);else out.write((title+"\n\n"+text).getBytes(StandardCharsets.UTF_8));
    }catch(IOException|RuntimeException e){resolver.delete(uri,null,null);throw e;}
    values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);resolver.update(uri,values,null,null);
    return new Saved(uri,name,mime);
  }

  /** A4 pages, a title, then the text wrapped with Android's own shaping so Hindi/Urdu render correctly. */
  private static void writePdf(OutputStream out,String title,String text) throws IOException {
    final int width=595,height=842,margin=50,usable=width-2*margin;
    PdfDocument doc=new PdfDocument();
    TextPaint body=new TextPaint(Paint.ANTI_ALIAS_FLAG);body.setTextSize(11.5f);body.setColor(Color.rgb(25,25,30));
    TextPaint head=new TextPaint(Paint.ANTI_ALIAS_FLAG);head.setTextSize(20f);head.setColor(Color.rgb(15,40,110));head.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));
    StaticLayout titleLayout=StaticLayout.Builder.obtain(title,0,title.length(),head,usable).build();
    String clean=text.replace("\r\n","\n").replaceAll("(?m)^#{1,6}\\s*","").replace("**","");
    StaticLayout layout=StaticLayout.Builder.obtain(clean,0,clean.length(),body,usable).setLineSpacing(3f,1f).build();
    int line=0,pageNo=1;
    try{
      while(line<layout.getLineCount()||pageNo==1){
        PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(width,height,pageNo).create());
        Canvas canvas=page.getCanvas();int y=margin;
        if(pageNo==1){canvas.save();canvas.translate(margin,y);titleLayout.draw(canvas);canvas.restore();y+=titleLayout.getHeight()+18;}
        int first=line,top=layout.getLineTop(first);
        while(line<layout.getLineCount()&&y+(layout.getLineBottom(line)-top)<=height-margin)line++;
        if(line>first){
          canvas.save();canvas.translate(margin,y-top);
          canvas.clipRect(0,top,usable,layout.getLineBottom(line-1));layout.draw(canvas);canvas.restore();
        }
        Paint foot=new Paint(Paint.ANTI_ALIAS_FLAG);foot.setTextSize(9f);foot.setColor(Color.GRAY);
        canvas.drawText(String.valueOf(pageNo),width/2f,height-24,foot);
        doc.finishPage(page);pageNo++;
        if(line==first&&line<layout.getLineCount())line++;
        if(pageNo>200)break;
      }
      doc.writeTo(out);
    }finally{doc.close();}
  }
}
