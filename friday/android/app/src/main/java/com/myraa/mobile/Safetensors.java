package com.myraa.mobile;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Minimal reader for the safetensors format (an 8-byte header length, a JSON header, then raw
 * little-endian tensors). Only float32 tensors are needed by the on-device voice models.
 */
final class Safetensors {
  static final class Tensor {
    final int[] shape;final float[] data;
    Tensor(int[] shape,float[] data){this.shape=shape;this.data=data;}
  }
  private final Map<String,Tensor> tensors=new HashMap<>();

  Safetensors(InputStream in) throws IOException {
    byte[] all=readAll(in);
    if(all.length<8)throw new IOException("model file too short");
    ByteBuffer b=ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
    long headerLength=b.getLong();
    if(headerLength<=0||headerLength>all.length-8)throw new IOException("bad model header");
    int base=8+(int)headerLength;
    try{
      JSONObject header=new JSONObject(new String(all,8,(int)headerLength,StandardCharsets.UTF_8));
      Iterator<String> keys=header.keys();
      while(keys.hasNext()){
        String name=keys.next();if("__metadata__".equals(name))continue;
        JSONObject t=header.getJSONObject(name);
        if(!"F32".equals(t.getString("dtype")))throw new IOException("unsupported tensor type in "+name);
        JSONArray s=t.getJSONArray("shape"),o=t.getJSONArray("data_offsets");
        int[] shape=new int[s.length()];long count=1;for(int i=0;i<shape.length;i++){shape[i]=s.getInt(i);count*=shape[i];}
        long start=o.getLong(0),end=o.getLong(1);
        if(end-start!=count*4||base+end>all.length)throw new IOException("bad offsets for "+name);
        float[] data=new float[(int)count];
        ByteBuffer slice=ByteBuffer.wrap(all,base+(int)start,(int)(end-start)).order(ByteOrder.LITTLE_ENDIAN);
        slice.asFloatBuffer().get(data);
        tensors.put(name,new Tensor(shape,data));
      }
    }catch(JSONException e){throw new IOException("bad model header",e);}
  }
  /** The tensor's data, after checking that its shape is what the model code expects. */
  float[] get(String name,int... shape) throws IOException {
    Tensor t=tensors.get(name);
    if(t==null)throw new IOException("missing tensor "+name);
    if(!Arrays.equals(t.shape,shape))throw new IOException("unexpected shape for "+name+": "+Arrays.toString(t.shape));
    return t.data;
  }
  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out=new ByteArrayOutputStream(1<<20);byte[] buf=new byte[1<<16];int n;
    while((n=in.read(buf))>0){out.write(buf,0,n);if(out.size()>64_000_000)throw new IOException("model file too large");}
    return out.toByteArray();
  }
}
