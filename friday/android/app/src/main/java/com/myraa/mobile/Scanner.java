package com.myraa.mobile;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.media.ExifInterface;
import java.io.*;
import java.util.List;

/**
 * Document scans ("ye page scan karo"): turns each camera photo into a clean page (paper found and cropped,
 * shadows evened out, paper white, see ScanLogic) and joins the pages into one A4 PDF. Pages wait as JPEG files
 * in FRIDAY's private cache until the PDF is saved, then they are deleted. Nothing is uploaded.
 */
final class Scanner {
  static final int MAX_PAGES=20,LONG_SIDE=1800;
  private Scanner(){}

  /** The cleaned page n, from the photo the camera app wrote. */
  static File page(Context c,File shot,String mode,int n) throws IOException {
    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
    BitmapFactory.decodeFile(shot.getPath(),bounds);
    if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("The camera did not save a photo.");
    int sample=1;while(Math.max(bounds.outWidth,bounds.outHeight)/(sample*2)>=LONG_SIDE)sample*=2;
    BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=sample;o.inPreferredConfig=Bitmap.Config.ARGB_8888;
    Bitmap bm=BitmapFactory.decodeFile(shot.getPath(),o);
    if(bm==null)throw new IOException("The camera photo could not be read.");
    try{
      int rotate=0;
      try{
        switch(new ExifInterface(shot.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)){
          case ExifInterface.ORIENTATION_ROTATE_90:rotate=90;break;
          case ExifInterface.ORIENTATION_ROTATE_180:rotate=180;break;
          case ExifInterface.ORIENTATION_ROTATE_270:rotate=270;break;
          default:break;
        }
      }catch(IOException|RuntimeException ignored){}
      int[] size=ScanLogic.fit(bm.getWidth(),bm.getHeight(),LONG_SIDE);
      if(rotate!=0||size[0]!=bm.getWidth()){
        Matrix m=new Matrix();m.postScale(size[0]/(float)bm.getWidth(),size[1]/(float)bm.getHeight());if(rotate!=0)m.postRotate(rotate);
        Bitmap t=Bitmap.createBitmap(bm,0,0,bm.getWidth(),bm.getHeight(),m,true);if(t!=bm){bm.recycle();bm=t;}
      }
      int w=bm.getWidth(),h=bm.getHeight();int[] px=new int[w*h];bm.getPixels(px,0,w,0,0,w,h);
      bm.recycle();bm=null;
      int[] b=ScanLogic.paperBounds(px,w,h);
      int cw=b[2]-b[0],ch=b[3]-b[1];
      int[] crop=px;
      if(cw!=w||ch!=h){crop=new int[cw*ch];for(int y=0;y<ch;y++)System.arraycopy(px,(b[1]+y)*w+b[0],crop,y*cw,cw);}
      px=null;
      int[] clean=ScanLogic.clean(crop,cw,ch,mode);
      if(cw!=w||ch!=h)ScanLogic.whiteCorners(clean,crop,cw,ch);
      crop=null;
      Bitmap out=Bitmap.createBitmap(clean,cw,ch,Bitmap.Config.ARGB_8888);
      File f=new File(ScanFiles.dir(c),"page-"+n+".jpg");
      try(OutputStream s=new FileOutputStream(f)){if(!out.compress(Bitmap.CompressFormat.JPEG,88,s))throw new IOException("The page could not be saved.");}
      finally{out.recycle();}
      return f;
    }finally{if(bm!=null)bm.recycle();}
  }

  /** A small copy of a page to show on screen. */
  static Bitmap preview(File page,int maxSide){
    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(page.getPath(),bounds);
    int sample=1;while(Math.max(bounds.outWidth,bounds.outHeight)/(sample*2)>=maxSide)sample*=2;
    BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=sample;
    return BitmapFactory.decodeFile(page.getPath(),o);
  }

  /** One A4 page per scanned page, the image centred with a small white margin. */
  static void writePdf(OutputStream out,List<File> pages) throws IOException {
    PdfDocument doc=new PdfDocument();
    try{
      Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
      for(int i=0;i<pages.size();i++){
        Bitmap bm=BitmapFactory.decodeFile(pages.get(i).getPath());
        if(bm==null)throw new IOException("A scanned page could not be read.");
        try{
          int[] p=ScanLogic.page(bm.getWidth(),bm.getHeight());
          PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(p[0],p[1],i+1).create());
          Canvas canvas=page.getCanvas();canvas.drawColor(Color.WHITE);
          canvas.drawBitmap(bm,null,new Rect(p[2],p[3],p[2]+p[4],p[3]+p[5]),paint);
          doc.finishPage(page);
        }finally{bm.recycle();}
      }
      doc.writeTo(out);
    }finally{doc.close();}
  }

  /** Deletes the camera photo and the waiting pages. */
  static void clear(Context c){File[] all=ScanFiles.dir(c).listFiles();if(all!=null)for(File f:all)f.delete();}
}
