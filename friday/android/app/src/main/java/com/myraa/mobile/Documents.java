package com.myraa.mobile;

import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.*;
import android.database.Cursor;
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
      if(pdf){
        // The report layout should never fail, but if it does on some phone, still save a plain PDF.
        java.io.ByteArrayOutputStream buf=new java.io.ByteArrayOutputStream();
        try{writePdf(buf,title,text);}catch(RuntimeException e){ControlLog.i("documents: report layout failed ("+e.getClass().getSimpleName()+"), saving plain PDF");buf.reset();writePlainPdf(buf,title,text);}
        buf.writeTo(out);
      }else out.write((title+"\n\n"+text).getBytes(StandardCharsets.UTF_8));
    }catch(IOException|RuntimeException e){resolver.delete(uri,null,null);throw e;}
    values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);resolver.update(uri,values,null,null);
    return new Saved(uri,name,mime);
  }

  // ---- Professional PDF layout

  private static final int W=595,H=842,M=56,USABLE=W-2*M;
  private static final int INK=Color.rgb(28,30,36),ACCENT=Color.rgb(20,60,140),MUTED=Color.rgb(110,116,128),RULE=Color.rgb(210,216,226),BAND=Color.rgb(16,40,96);

  /** One piece of the page: a paragraph, heading, list item, table row or rule. */
  private static final class Block {
    StaticLayout layout;int indent,before,after;String marker;boolean rule,keepWithNext;
    StaticLayout[] cells;int[] cellX;boolean header;int height(){
      if(rule)return 1;
      if(cells!=null){int h=0;for(StaticLayout c:cells)h=Math.max(h,c.getHeight());return h+10;}
      return layout.getHeight();}
  }

  /**
   * A4 pages with a title band (title, kind, date), then the Markdown the text was written in laid out
   * like a report: ## headings with a rule, ### subheadings, bullet and numbered lists, **bold**, tables
   * (| a | b |) and paragraphs. Android's own text shaping keeps Urdu and Hindi correct. Every page
   * after the first has the title in its header, and every page a page number.
   */
  private static void writePdf(OutputStream out,String title,String text) throws IOException {
    TextPaint body=paint(11f,INK,false),bold=paint(11f,INK,true),h2=paint(15f,ACCENT,true),h3=paint(12.5f,INK,true),small=paint(9f,MUTED,false);
    TextPaint cell=paint(9.5f,INK,false),cellHead=paint(9.5f,Color.WHITE,true);
    List<Block> blocks=parse(text,title,body,h2,h3,cell,cellHead);
    String kind=text.contains("## Executive summary")||text.contains("## Sources")?"Research report":"Document";
    String date=new SimpleDateFormat("d MMMM yyyy",Locale.ENGLISH).format(new Date());
    PdfDocument doc=new PdfDocument();
    try{
      int pageNo=0;PdfDocument.Page page=null;Canvas canvas=null;int y=0;
      int i=0;
      while(i<blocks.size()||page==null){
        if(page==null){
          pageNo++;page=doc.startPage(new PdfDocument.PageInfo.Builder(W,H,pageNo).create());canvas=page.getCanvas();
          y=pageNo==1?cover(canvas,title,kind,date):header(canvas,title,small);
          if(i>=blocks.size())break;
        }
        Block b=blocks.get(i);
        int need=b.before+b.height()+(b.keepWithNext&&i+1<blocks.size()?Math.min(60,blocks.get(i+1).height()+blocks.get(i+1).before):0);
        int room=H-M-y;
        if(need>room&&y>topOf(pageNo)){footer(canvas,pageNo,small);doc.finishPage(page);page=null;if(pageNo>300)break;continue;}
        y+=b.before;
        if(b.rule){Paint p=new Paint();p.setColor(RULE);p.setStrokeWidth(0.8f);canvas.drawLine(M,y,W-M,y,p);y+=b.after;i++;continue;}
        if(b.cells!=null){
          int h=b.height();
          Paint bg=new Paint();bg.setColor(b.header?ACCENT:(i%2==0?Color.rgb(244,246,250):Color.WHITE));canvas.drawRect(M,y,W-M,y+h,bg);
          Paint line=new Paint();line.setColor(RULE);line.setStrokeWidth(0.6f);canvas.drawLine(M,y+h,W-M,y+h,line);
          for(int c=0;c<b.cells.length;c++){canvas.save();canvas.translate(M+b.cellX[c]+5,y+5);b.cells[c].draw(canvas);canvas.restore();}
          y+=h+b.after;i++;continue;
        }
        StaticLayout l=b.layout;
        if(b.height()<=room){draw(canvas,b,l,0,l.getLineCount(),y);y+=b.height()+b.after;i++;continue;}
        // A paragraph longer than the room left: fill this page line by line, the rest continues on the next.
        int line=0,top=0;
        while(line<l.getLineCount()&&l.getLineBottom(line)-top<=room)line++;
        if(line==0){footer(canvas,pageNo,small);doc.finishPage(page);page=null;continue;}
        draw(canvas,b,l,0,line,y);
        Block rest=new Block();rest.indent=b.indent;rest.after=b.after;rest.layout=rebuild(l,line);blocks.set(i,rest);
        footer(canvas,pageNo,small);doc.finishPage(page);page=null;
      }
      if(page!=null){footer(canvas,pageNo,small);doc.finishPage(page);}
      doc.writeTo(out);
    }finally{doc.close();}
  }
  /** Plain fallback: the title, then the text as paragraphs, no Markdown layout. */
  private static void writePlainPdf(OutputStream out,String title,String text) throws IOException {
    TextPaint body=paint(11f,INK,false);
    String plain=text.replace("\r\n","\n").replaceAll("(?m)^#+\\s*","").replace("**","");
    PdfDocument doc=new PdfDocument();
    try{
      StaticLayout all=StaticLayout.Builder.obtain(title+"\n\n"+plain,0,title.length()+2+plain.length(),body,USABLE).setLineSpacing(3f,1f).build();
      int line=0,pageNo=0;
      while(line<all.getLineCount()&&pageNo<300){
        pageNo++;PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(W,H,pageNo).create());Canvas c=page.getCanvas();
        int first=line,top=all.getLineTop(first);
        while(line<all.getLineCount()&&all.getLineBottom(line)-top<=H-2*M)line++;
        if(line==first)line++;
        c.save();c.translate(M,M-top);c.clipRect(0,top,USABLE,all.getLineBottom(line-1));all.draw(c);c.restore();
        doc.finishPage(page);
      }
      if(pageNo==0){doc.finishPage(doc.startPage(new PdfDocument.PageInfo.Builder(W,H,1).create()));}
      doc.writeTo(out);
    }finally{doc.close();}
  }
  private static int topOf(int pageNo){return pageNo==1?196:M+10;}
  private static TextPaint paint(float size,int color,boolean bold){
    TextPaint p=new TextPaint(Paint.ANTI_ALIAS_FLAG);p.setTextSize(size);p.setColor(color);
    if(bold)p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));return p;
  }
  private static int cover(Canvas c,String title,String kind,String date){
    Paint band=new Paint();band.setColor(BAND);c.drawRect(0,0,W,150,band);
    Paint accent=new Paint();accent.setColor(Color.rgb(64,140,255));c.drawRect(0,150,W,154,accent);
    TextPaint k=paint(10f,Color.rgb(170,195,240),true);k.setLetterSpacing(0.12f);
    c.drawText(kind.toUpperCase(Locale.ROOT),M,48,k);
    TextPaint t=paint(21f,Color.WHITE,true);
    StaticLayout tl=StaticLayout.Builder.obtain(title,0,title.length(),t,USABLE).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
    c.save();c.translate(M,60);tl.draw(c);c.restore();
    TextPaint d=paint(9.5f,MUTED,false);c.drawText(date+"  ·  Prepared by FRIDAY",M,176,d);
    return 196;
  }
  private static int header(Canvas c,String title,TextPaint small){
    String t=title.length()>70?title.substring(0,70)+"…":title;c.drawText(t,M,M-12,small);
    Paint p=new Paint();p.setColor(RULE);p.setStrokeWidth(0.6f);c.drawLine(M,M-6,W-M,M-6,p);return M+10;
  }
  private static void footer(Canvas c,int pageNo,TextPaint small){
    String n="Page "+pageNo;c.drawText(n,W-M-small.measureText(n),H-28,small);c.drawText("FRIDAY",M,H-28,small);
  }
  private static void draw(Canvas c,Block b,StaticLayout l,int from,int to,int y){
    if(b.marker!=null){TextPaint p=new TextPaint(l.getPaint());p.setColor(ACCENT);c.drawText(b.marker,M+b.indent-16,y-l.getLineTop(from)+l.getLineBaseline(from),p);}
    int top=l.getLineTop(from),bottom=l.getLineBottom(to-1);
    c.save();c.translate(M+b.indent,y-top);c.clipRect(0,top,USABLE,bottom);l.draw(c);c.restore();
  }
  private static StaticLayout rebuild(StaticLayout l,int fromLine){
    CharSequence t=l.getText();int start=l.getLineStart(fromLine);
    return StaticLayout.Builder.obtain(t,start,t.length(),l.getPaint(),l.getWidth()).setLineSpacing(3f,1f).build();
  }
  private static StaticLayout layout(CharSequence t,TextPaint p,int width){return StaticLayout.Builder.obtain(t,0,t.length(),p,Math.max(40,width)).setLineSpacing(3f,1f).build();}

  /** **bold** spans as real bold; other Markdown marks removed. */
  static CharSequence inline(String s){
    String t=s.replaceAll("(?<!\\*)\\*(?!\\*)([^*]+)(?<!\\*)\\*(?!\\*)","$1").replace("__","").replace("`","");
    SpannableStringBuilder b=new SpannableStringBuilder();int at=0;
    while(true){int a=t.indexOf("**",at);if(a<0)break;int z=t.indexOf("**",a+2);if(z<0)break;
      b.append(t,at,a);int st=b.length();b.append(t,a+2,z);b.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),st,b.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);at=z+2;}
    b.append(t.substring(at).replace("**",""));
    return b;
  }

  private static List<Block> parse(String text,String title,TextPaint body,TextPaint h2,TextPaint h3,TextPaint cell,TextPaint cellHead){
    List<Block> out=new ArrayList<>();
    String[] lines=text.replace("\r\n","\n").split("\n");
    StringBuilder para=new StringBuilder();
    List<String[]> table=new ArrayList<>();
    for(int n=0;n<=lines.length;n++){
      String raw=n<lines.length?lines[n]:"";String line=raw.trim();
      boolean isTable=line.startsWith("|")&&line.endsWith("|")&&line.length()>2;
      if(!isTable&&!table.isEmpty()){addTable(out,table,cell,cellHead);table.clear();}
      if(isTable){flush(out,para,body);if(!line.matches("\\|[\\s:|-]+\\|")){String[] cells=line.substring(1,line.length()-1).split("\\|",-1);for(int k=0;k<cells.length;k++)cells[k]=cells[k].trim();table.add(cells);}continue;}
      if(line.isEmpty()){flush(out,para,body);continue;}
      if(line.startsWith("# ")){flush(out,para,body);String h=line.substring(2).replace("**","").trim();if(h.equalsIgnoreCase(title.trim()))continue;Block b=text(h,h2,0);b.before=16;b.after=6;b.keepWithNext=true;out.add(b);continue;}
      if(line.startsWith("## ")){flush(out,para,body);Block b=text(line.substring(3).replace("**","").trim(),h2,0);b.before=out.isEmpty()?0:18;b.after=3;b.keepWithNext=true;out.add(b);Block r=new Block();r.rule=true;r.after=8;r.keepWithNext=true;out.add(r);continue;}
      if(line.startsWith("###")){flush(out,para,body);Block b=text(line.replaceFirst("^#+\\s*","").replace("**","").trim(),h3,0);b.before=10;b.after=4;b.keepWithNext=true;out.add(b);continue;}
      if(line.matches("^(-{3,}|\\*{3,}|_{3,})$")){flush(out,para,body);Block r=new Block();r.rule=true;r.before=6;r.after=8;out.add(r);continue;}
      java.util.regex.Matcher bullet=java.util.regex.Pattern.compile("^([-*•])\\s+(.*)$").matcher(line);
      java.util.regex.Matcher num=java.util.regex.Pattern.compile("^(\\d{1,3})[.)]\\s+(.*)$").matcher(line);
      int depth=raw.length()-raw.replaceFirst("^\\s+","").length()>=2?1:0;
      if(bullet.matches()||num.matches()){
        flush(out,para,body);
        boolean isNum=num.matches();String item=isNum?num.group(2):bullet.group(2);
        Block b=text(item,body,18+depth*16);b.marker=isNum?num.group(1)+".":"•";b.before=3;b.after=2;out.add(b);continue;
      }
      if(para.length()>0)para.append(' ');para.append(line);
    }
    flush(out,para,body);
    return out;
  }
  private static Block text(String s,TextPaint p,int indent){Block b=new Block();b.indent=indent;b.layout=layout(inline(s),p,USABLE-indent);return b;}
  private static void flush(List<Block> out,StringBuilder para,TextPaint body){
    if(para.length()==0)return;Block b=text(para.toString(),body,0);b.before=4;b.after=6;out.add(b);para.setLength(0);
  }
  private static void addTable(List<Block> out,List<String[]> rows,TextPaint cell,TextPaint head){
    int cols=0;for(String[] r:rows)cols=Math.max(cols,r.length);cols=Math.min(cols,6);if(cols==0)return;
    int[] x=new int[cols];int w=USABLE/cols;for(int c=0;c<cols;c++)x[c]=c*w;
    for(int r=0;r<rows.size();r++){
      Block b=new Block();b.header=r==0;b.cellX=x;b.cells=new StaticLayout[cols];b.before=r==0?8:0;b.after=r==rows.size()-1?8:0;
      for(int c=0;c<cols;c++){String v=c<rows.get(r).length?rows.get(r)[c]:"";if(v.length()>300)v=v.substring(0,300);b.cells[c]=layout(inline(v),r==0?head:cell,w-10);}
      if(r==0)b.keepWithNext=true;
      out.add(b);
    }
  }

  // ---- Finding saved documents (FRIDAY's own files in Downloads/FRIDAY)

  static final class Found { final Uri uri;final String name,mime;final long modified; Found(Uri u,String n,String m,long t){uri=u;name=n;mime=m;modified=t;} }

  /** FRIDAY's saved documents, newest first (Android lets an app see the Downloads files it made). */
  static List<Found> list(Context c,int limit){
    List<Found> out=new ArrayList<>();
    if(Build.VERSION.SDK_INT<29)return out;
    String[] cols={MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.MIME_TYPE,MediaStore.MediaColumns.DATE_MODIFIED};
    try(Cursor cur=c.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cols,MediaStore.MediaColumns.IS_PENDING+"=0",null,MediaStore.MediaColumns.DATE_MODIFIED+" DESC")){
      if(cur==null)return out;
      while(cur.moveToNext()&&out.size()<limit){
        String name=cur.getString(1);if(name==null)continue;
        out.add(new Found(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cur.getLong(0)),name,cur.getString(2)==null?"application/octet-stream":cur.getString(2),cur.getLong(3)));
      }
    }catch(RuntimeException e){ControlLog.i("documents: list failed ("+e.getClass().getSimpleName()+")");}
    return out;
  }
  /**
   * The document whose name best matches the words the user said ("biology notes", "Ali ka resume");
   * empty words or "last"/"latest"/"abhi wala" mean the newest. Null when nothing matches well enough.
   */
  static Found find(Context c,String words){
    List<Found> all=list(c,200);if(all.isEmpty())return null;
    String q=MobileActions.normalize(words==null?"":words).replaceAll("\\b(the|a|an|wala|wali|vala|document|doc|file|pdf|report|ka|ki|ke|ko|my|mera|meri)\\b"," ").trim();
    if(q.isEmpty()||q.matches("(last|latest|new|newest|recent|abhi|akhri|aakhri|pichla|pichli)( .*)?"))return all.get(0);
    String[] want=q.split("\\s+");Found best=null;double bestScore=0;
    for(Found f:all){
      String name=MobileActions.normalize(f.name.replaceAll("\\.[A-Za-z0-9]{1,5}$","").replaceAll("\\d{4}-\\d{2}-\\d{2} \\d{4}$",""));
      int hit=0;for(String w:want)if(name.contains(w)||(w.length()>=4&&ContactMatcher.score(w,name)>=ContactMatcher.HIGH))hit++;
      double score=(double)hit/want.length;
      if(score>bestScore){bestScore=score;best=f;}
    }
    return bestScore>=0.5?best:null;
  }
}
