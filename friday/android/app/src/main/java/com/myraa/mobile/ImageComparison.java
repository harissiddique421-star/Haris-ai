package com.myraa.mobile;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Image comparison: two to four pictures the user picked (photos, screenshots, documents). The phone
 * first measures how alike they look (picture fingerprints, no upload), then the vision engine
 * explains what is the same, what changed and which text or numbers differ, as a structured report.
 */
final class ImageComparison {
  private ImageComparison(){}
  static final int EDGE=1280;

  static final class Result {
    final JSONObject data;final int[] likeness;final int[] changed;final String provider,fileNames;
    Result(JSONObject d,int[] l,int[] c,String p,String f){data=d;likeness=l;changed=c;provider=p;fileNames=f;}
  }

  static Result compare(Context context,List<Uri> uris,String question,VisionEngine engine,String key,String model,VisionEngine.Alt alt) throws Exception {
    if(uris.size()<2)throw new IllegalArgumentException("Pick at least two pictures to compare.");
    if(uris.size()>VisionLogic.MAX_COMPARE)throw new IllegalArgumentException("FRIDAY compares up to four pictures at a time.");
    ContentResolver resolver=context.getContentResolver();
    VisionEngine.Request r=new VisionEngine.Request();
    r.system=VisionLogic.compareInstruction();
    StringBuilder names=new StringBuilder();
    for(Uri uri:uris){
      String mime=resolver.getType(uri);
      if(!VisionLogic.imageMime(mime))throw new IllegalArgumentException("Only pictures can be compared (photos or screenshots). For a PDF, take a screenshot of the page first.");
      r.media.add(VisionEngine.prepare(resolver,uri,EDGE,85));
      if(names.length()>0)names.append(", ");names.append(VisionEngine.name(resolver,uri));
    }
    // Local fingerprints of the prepared pictures (already upright and small): picture 1 against each other one.
    int n=r.media.size();long[] hash=new long[n];int[][] small=new int[n][];
    for(int i=0;i<n;i++){Object[] f=fingerprint(r.media.get(i).data);hash[i]=(Long)f[0];small[i]=(int[])f[1];}
    int[] likeness=new int[n-1],changed=new int[n-1];
    for(int i=1;i<n;i++){likeness[i-1]=VisionLogic.similarity(hash[0],hash[i],small[0],small[i]);changed[i-1]=VisionLogic.changedShare(small[0],small[i],40);}
    r.prompt=VisionLogic.comparePrompt(question,n,likeness);
    VisionEngine.Answer a=engine.ask(key,model,alt,r);
    JSONObject d=VisionLogic.parseJson(a.text);
    if(d==null)d=new JSONObject().put("summary",VisionLogic.clip(a.text,800)).put("differences",new JSONArray());
    ControlLog.i("compare: "+n+" pictures with "+a.provider+(a.cached?" (cached)":""));
    return new Result(d,likeness,changed,a.provider,names.toString());
  }

  /** {dHash, 32x32 grey} of a JPEG, decoded small so it costs little memory. */
  static Object[] fingerprint(byte[] jpeg) throws IOException {
    BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=4;
    Bitmap b=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,o);
    if(b==null)throw new IOException("A picture could not be decoded.");
    try{
      Bitmap h=Bitmap.createScaledBitmap(b,9,8,true),s=Bitmap.createScaledBitmap(b,32,32,true);
      try{
        int[] hp=new int[72];h.getPixels(hp,0,9,0,0,9,8);
        int[] sp=new int[1024];s.getPixels(sp,0,32,0,0,32,32);
        return new Object[]{VisionLogic.dHash(VisionLogic.grey(hp)),VisionLogic.grey(sp)};
      }finally{if(h!=b)h.recycle();if(s!=b)s.recycle();}
    }finally{b.recycle();}
  }

  /** The report as Markdown, for a PDF with make_document. */
  static String markdown(Result res){
    JSONObject d=res.data;StringBuilder b=new StringBuilder();
    b.append("## Summary\n").append(d.optString("summary","")).append("\n\n");
    if(res.likeness.length>0){
      b.append("## Picture check on the phone\n");
      for(int i=0;i<res.likeness.length;i++)b.append("- Picture 1 vs ").append(i+2).append(": ").append(res.likeness[i]).append("/100 alike (").append(VisionLogic.likeness(res.likeness[i])).append("), about ").append(res.changed[i]).append("% of the area changed\n");
      b.append('\n');
    }
    List<String> same=VisionLogic.strings(d.optJSONArray("same"),20);
    if(!same.isEmpty()){b.append("## The same\n");for(String s:same)b.append("- ").append(s).append('\n');b.append('\n');}
    JSONArray diffs=d.optJSONArray("differences");
    if(diffs!=null&&diffs.length()>0){
      b.append("## Differences\n| What | Where | Details |\n| --- | --- | --- |\n");
      for(int i=0;i<diffs.length();i++){JSONObject x=diffs.optJSONObject(i);if(x==null)continue;b.append("| ").append(cell(x.optString("what"))).append(" | ").append(cell(x.optString("where"))).append(" | ").append(cell(x.optString("details"))).append(" |\n");}
      b.append('\n');
    }
    JSONArray text=d.optJSONArray("text_changes");
    if(text!=null&&text.length()>0){
      b.append("## Text changes\n| Before | After | Where |\n| --- | --- | --- |\n");
      for(int i=0;i<text.length();i++){JSONObject x=text.optJSONObject(i);if(x==null)continue;b.append("| ").append(cell(x.optString("before"))).append(" | ").append(cell(x.optString("after"))).append(" | ").append(cell(x.optString("where"))).append(" |\n");}
    }
    return b.toString().trim();
  }
  private static String cell(String s){return s==null?"":s.replace("|","/").replace("\n"," ").trim();}
}
