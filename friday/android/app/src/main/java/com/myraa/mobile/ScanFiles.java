package com.myraa.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets the phone's camera app write one photo for a document scan into FRIDAY's private cache
 * (content://com.myraa.mobile.scans/shot.jpg). Not exported: the camera only gets access through the
 * one-time permission on FRIDAY's capture request. The photo never goes to the gallery.
 */
public final class ScanFiles extends ContentProvider {
  static final String AUTHORITY="com.myraa.mobile.scans",SHOT="shot.jpg";
  static Uri shotUri(){return Uri.parse("content://"+AUTHORITY+"/"+SHOT);}
  static File dir(android.content.Context c){File d=new File(c.getCacheDir(),"scans");if(!d.isDirectory())d.mkdirs();return d;}
  static File shot(android.content.Context c){return new File(dir(c),SHOT);}

  private File file(Uri uri) throws FileNotFoundException {
    if(uri==null||!SHOT.equals(uri.getLastPathSegment())||uri.getPathSegments().size()!=1)throw new FileNotFoundException("Only the scan photo is here.");
    return shot(getContext());
  }
  @Override public boolean onCreate(){return true;}
  @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
    File f=file(uri);
    int m=ParcelFileDescriptor.parseMode(mode==null?"r":mode);
    if(mode!=null&&mode.contains("w"))m|=ParcelFileDescriptor.MODE_CREATE;
    return ParcelFileDescriptor.open(f,m);
  }
  @Override public String getType(Uri uri){return "image/jpeg";}
  @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
    File f;try{f=file(uri);}catch(FileNotFoundException e){return null;}
    MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
    c.addRow(new Object[]{SHOT,f.length()});return c;
  }
  @Override public Uri insert(Uri uri,ContentValues values){return null;}
  @Override public int delete(Uri uri,String selection,String[] args){try{return file(uri).delete()?1:0;}catch(FileNotFoundException e){return 0;}}
  @Override public int update(Uri uri,ContentValues values,String selection,String[] args){return 0;}
}
