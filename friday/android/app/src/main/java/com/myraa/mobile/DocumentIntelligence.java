package com.myraa.mobile;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * Document intelligence: reads a PDF, scanned pages or photos of paper the user picked (printed or
 * handwritten, Urdu and English), and returns the summary, headings, dates, key details, tables and
 * full text, plus the answer to the user's question. Follow-up questions use the text already read,
 * so the file is not sent again. Runs on a background thread; the files are read once and not kept.
 */
final class DocumentIntelligence {
  private DocumentIntelligence(){}
  /** Gemini takes a PDF directly up to about 20 MB in one request. */
  static final int MAX_PDF_BYTES=18*1024*1024;
  static final int MAX_PDF_PAGES_AS_PICTURES=8,OCR_EDGE=2000;

  static final class Result {
    final JSONObject data;final String provider,fileNames;
    Result(JSONObject d,String p,String f){data=d;provider=p;fileNames=f;}
  }

  static Result analyze(Context context,List<Uri> uris,String question,VisionEngine engine,String key,String model,VisionEngine.Alt alt) throws Exception {
    if(uris.isEmpty())throw new IllegalArgumentException("No file was picked.");
    ContentResolver resolver=context.getContentResolver();
    VisionEngine.Request r=new VisionEngine.Request();
    r.system=VisionLogic.documentInstruction();
    List<Uri> pdfs=new ArrayList<>();StringBuilder names=new StringBuilder();
    for(Uri uri:uris){
      String mime=resolver.getType(uri);
      if(!VisionLogic.documentMime(mime))throw new IllegalArgumentException("FRIDAY can read PDF files and photos (JPEG, PNG, WebP, HEIC). This file is "+(mime==null?"of an unknown type":mime)+".");
      if(names.length()>0)names.append(", ");names.append(VisionEngine.name(resolver,uri));
      if("application/pdf".equals(mime)){r.media.add(new VisionEngine.Media(readAll(resolver,uri,MAX_PDF_BYTES),mime));pdfs.add(uri);}
      else r.media.add(VisionEngine.prepare(resolver,uri,OCR_EDGE,88));
    }
    r.prompt=VisionLogic.documentPrompt(question,uris.size());
    r.maxTokens=16384;
    // The second provider cannot read PDFs: it gets the pages as pictures instead (first pages only).
    if(!pdfs.isEmpty())r.altMedia=()->{
      List<VisionEngine.Media> out=new ArrayList<>();
      for(VisionEngine.Media m:r.media)if(VisionLogic.imageMime(m.mime))out.add(m);
      for(Uri pdf:pdfs){try{out.addAll(pdfPages(resolver,pdf,MAX_PDF_PAGES_AS_PICTURES-out.size()));}catch(IOException|RuntimeException e){ControlLog.i("documents: could not draw PDF pages ("+e.getClass().getSimpleName()+")");}}
      return out;
    };
    VisionEngine.Answer a=engine.ask(key,model,alt,r);
    JSONObject d=VisionLogic.parseJson(a.text);
    if(d==null){
      // A model that ignored the JSON format still read the document: keep its words as the text.
      d=new JSONObject().put("title","Document").put("summary",VisionLogic.clip(a.text,600)).put("full_text",a.text).put("answer","");
    }
    ControlLog.i("documents: read "+uris.size()+" file(s) with "+a.provider+(a.cached?" (cached)":""));
    return new Result(d,a.provider,names.toString());
  }

  /** A follow-up question about the document read last, answered from its text only. */
  static String ask(String fullText,String summary,String question,VisionEngine engine,String key,String model,VisionEngine.Alt alt) throws Exception {
    VisionEngine.Request r=new VisionEngine.Request();
    r.system=VisionLogic.questionInstruction();r.json=false;r.maxTokens=1024;
    r.prompt="DOCUMENT SUMMARY:\n"+summary+"\n\nDOCUMENT TEXT:\n"+VisionLogic.clip(fullText,VisionLogic.MAX_TEXT_FOR_QA)+"\n\nQUESTION: "+VisionLogic.clean(question,VisionLogic.MAX_QUESTION);
    return engine.ask(key,model,alt,r).text;
  }

  private static byte[] readAll(ContentResolver resolver,Uri uri,int max) throws IOException {
    try(InputStream in=resolver.openInputStream(uri)){
      if(in==null)throw new IOException("This file cannot be opened.");
      ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[65536];int n;
      while((n=in.read(b))>0){out.write(b,0,n);if(out.size()>max)throw new IllegalArgumentException("This PDF is bigger than 18 MB. Pick a smaller one, or only the pages you need.");}
      return out.toByteArray();
    }
  }

  /** The first pages of a PDF drawn as JPEG pictures, one page in memory at a time. */
  static List<VisionEngine.Media> pdfPages(ContentResolver resolver,Uri uri,int max) throws IOException {
    List<VisionEngine.Media> out=new ArrayList<>();
    if(max<=0)return out;
    try(ParcelFileDescriptor fd=resolver.openFileDescriptor(uri,"r")){
      if(fd==null)throw new IOException("no file");
      try(PdfRenderer pdf=new PdfRenderer(fd)){
        for(int i=0;i<pdf.getPageCount()&&out.size()<max;i++){
          try(PdfRenderer.Page page=pdf.openPage(i)){
            float scale=Math.min(3f,1600f/Math.max(page.getWidth(),page.getHeight()));
            int w=Math.max(1,Math.round(page.getWidth()*scale)),h=Math.max(1,Math.round(page.getHeight()*scale));
            Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            try{b.eraseColor(Color.WHITE);page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);out.add(new VisionEngine.Media(VisionEngine.jpeg(b,85),"image/jpeg"));}
            finally{b.recycle();}
          }
        }
      }
    }
    return out;
  }
}
