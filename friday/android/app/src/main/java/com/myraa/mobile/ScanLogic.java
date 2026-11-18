package com.myraa.mobile;

import java.util.Arrays;
import java.util.Locale;

/**
 * The image maths behind "ye page scan karo": finds the paper in the photo, evens out shadows and makes the
 * paper white, and lays each page on an A4 PDF page. Plain Java on ARGB pixel arrays, so it runs in JVM checks.
 */
final class ScanLogic {
  static final String COLOR="color",GRAY="gray",BW="bw";
  private ScanLogic(){}

  /** The colour mode the user asked for; an ID card or anything with a photo stays in colour. */
  static String mode(String asked,String words){
    String a=asked==null?"":asked.trim().toLowerCase(Locale.ROOT);
    if(a.equals(COLOR)||a.equals(GRAY)||a.equals(BW))return a;
    String w=words==null?"":words.toLowerCase(Locale.ROOT);
    if(w.matches(".*\\b(black and white|black & white|black white|b ?& ?w|kala safaid|bw)\\b.*"))return BW;
    if(w.matches(".*\\b(gr[ae]y|grayscale|greyscale)\\b.*"))return GRAY;
    return COLOR;
  }

  /** A file title: what the user called it ("Ali ki marksheet scan"), else the kind they named, else "Scanned document". */
  static String title(String asked,String heard){
    String k=kind(asked);if(k!=null)return k;
    String t=asked==null?"":asked.trim().replaceAll("\\s+"," ");
    t=t.replaceAll("(?i)\\b(scan|scanned|scanner|karo|kar do|kardo|ye|yeh|this|the|page|pdf)\\b"," ").replaceAll("\\s+"," ").trim();
    if(!t.isEmpty()){if(t.length()>60)t=t.substring(0,60).trim();return Character.toUpperCase(t.charAt(0))+t.substring(1)+" scan";}
    k=kind(heard);return k!=null?k:"Scanned document";
  }
  /** "CNIC scan", "Bill scan" or "Receipt scan" when the words name one. */
  static String kind(String words){
    String w=words==null?"":words.toLowerCase(Locale.ROOT);
    if(w.matches(".*\\b(cnic|nic|id card|identity card|shanakhti card|shanakhti)\\b.*"))return "CNIC scan";
    if(w.matches(".*\\b(bill|bijli ka bill|gas bill|electricity bill)\\b.*"))return "Bill scan";
    if(w.matches(".*\\b(receipt|raseed|rasid)\\b.*"))return "Receipt scan";
    return null;
  }

  static int lum(int argb){return (((argb>>16)&255)*77+((argb>>8)&255)*150+(argb&255)*29)>>8;}

  /**
   * Where the paper is: {left, top, right, bottom} (right and bottom exclusive). Only crops when the paper is
   * clearly brighter than what is around it (a page on a table or in a hand); otherwise the whole photo.
   */
  static int[] paperBounds(int[] px,int w,int h){
    int[] all={0,0,w,h};
    if(w<16||h<16)return all;
    int step=Math.max(1,Math.max(w,h)/160);
    int gw=w/step,gh=h/step;int[] g=new int[gw*gh];
    for(int y=0;y<gh;y++)for(int x=0;x<gw;x++)g[y*gw+x]=lum(px[(y*step)*w+x*step]);
    // Paper level: the bright end of the middle of the photo. Surroundings: the outer ring.
    int[] mid=new int[(gw/2)*(gh/2)];int n=0;
    for(int y=gh/4;y<gh/4+gh/2;y++)for(int x=gw/4;x<gw/4+gw/2;x++)if(n<mid.length)mid[n++]=g[y*gw+x];
    mid=Arrays.copyOf(mid,n);Arrays.sort(mid);int paper=mid[(int)(n*0.7)];
    int ringW=Math.max(1,Math.min(gw,gh)/25);int[] ring=new int[gw*gh];int r=0;
    for(int y=0;y<gh;y++)for(int x=0;x<gw;x++)if(x<ringW||y<ringW||x>=gw-ringW||y>=gh-ringW)ring[r++]=g[y*gw+x];
    ring=Arrays.copyOf(ring,r);Arrays.sort(ring);int around=ring[r/2];
    if(paper-around<45)return all;
    int t=(paper+around)/2;
    // The bright region that holds the middle of the photo (flood fill on the small grid; text inside it is
    // surrounded by paper, so the fill goes around it).
    int start=-1;
    for(int d=0;d<Math.min(gw,gh)/4&&start<0;d++)
      for(int y=gh/2-d;y<=gh/2+d&&start<0;y++)for(int x=gw/2-d;x<=gw/2+d;x++)if(y>=0&&y<gh&&x>=0&&x<gw&&g[y*gw+x]>t){start=y*gw+x;break;}
    if(start<0)return all;
    boolean[] seen=new boolean[gw*gh];int[] stack=new int[gw*gh];int sp=0,count=0;
    int left=gw,top=gh,right=0,bottom=0;
    stack[sp++]=start;seen[start]=true;
    while(sp>0){
      int i=stack[--sp],x=i%gw,y=i/gw;count++;
      if(x<left)left=x;if(x>right)right=x;if(y<top)top=y;if(y>bottom)bottom=y;
      int[] next={x>0?i-1:-1,x<gw-1?i+1:-1,y>0?i-gw:-1,y<gh-1?i+gw:-1};
      for(int j:next)if(j>=0&&!seen[j]&&g[j]>t){seen[j]=true;stack[sp++]=j;}
    }
    int bwid=right-left+1,bhei=bottom-top+1;
    // Mostly bright inside its box (a page or a card), not a thin streak of light.
    if(bwid<4||bhei<4||count*10<bwid*bhei*5)return all;
    int[] b={left*step,top*step,Math.min(w,(right+1)*step),Math.min(h,(bottom+1)*step)};
    // Keep a hair of the edge inside, never a sliver of table.
    int ix=(b[2]-b[0])/100,iy=(b[3]-b[1])/100;b[0]+=ix;b[2]-=ix;b[1]+=iy;b[3]-=iy;
    long area=(long)(b[2]-b[0])*(b[3]-b[1]);
    if(area<(long)w*h/10||area>(long)w*h*97/100)return all;
    return b;
  }

  /**
   * The scan look: estimates the paper's own colour across the page (shadows, yellow light), divides it out so
   * the paper turns white, then deepens the ink. color keeps colours (CNIC photo, stamps); gray and bw make a
   * clean grey or high-contrast black-on-white page.
   */
  static int[] clean(int[] px,int w,int h,String mode){
    int block=Math.max(8,Math.min(w,h)/20);
    int bw=(w+block-1)/block,bh=(h+block-1)/block;
    float[][] bg=new float[3][bw*bh];
    for(int by=0;by<bh;by++)for(int bx=0;bx<bw;bx++){
      int x0=bx*block,y0=by*block,x1=Math.min(w,x0+block),y1=Math.min(h,y0+block);
      // The paper in this block: the brighter half of its pixels (ink and shadows are the darker part).
      long sum=0;int cnt=0;
      for(int y=y0;y<y1;y+=2)for(int x=x0;x<x1;x+=2){sum+=lum(px[y*w+x]);cnt++;}
      int avg=cnt==0?255:(int)(sum/cnt);
      long r=0,gg=0,b=0;int k=0;
      for(int y=y0;y<y1;y+=2)for(int x=x0;x<x1;x+=2){int p=px[y*w+x];if(lum(p)>=avg){r+=(p>>16)&255;gg+=(p>>8)&255;b+=p&255;k++;}}
      if(k==0)k=1;
      int i=by*bw+bx;bg[0][i]=r/(float)k;bg[1][i]=gg/(float)k;bg[2][i]=b/(float)k;
    }
    // A block that is all photo or ink would look like dark paper: take the brightest neighbour, then smooth.
    for(int c=0;c<3;c++){bg[c]=smooth(dilate(bg[c],bw,bh),bw,bh);}
    int[] out=new int[w*h];
    boolean keepColor=COLOR.equals(mode);
    for(int y=0;y<h;y++){
      float fy=Math.max(0,Math.min(bh-1f,(y+0.5f)/block-0.5f));int y0=(int)fy,y1=Math.min(bh-1,y0+1);float ty=fy-y0;
      for(int x=0;x<w;x++){
        float fx=Math.max(0,Math.min(bw-1f,(x+0.5f)/block-0.5f));int x0=(int)fx,x1=Math.min(bw-1,x0+1);float tx=fx-x0;
        int p=px[y*w+x];int[] ch={(p>>16)&255,(p>>8)&255,p&255};int[] o=new int[3];
        for(int c=0;c<3;c++){
          float[] b=bg[c];
          float back=(b[y0*bw+x0]*(1-tx)+b[y0*bw+x1]*tx)*(1-ty)+(b[y1*bw+x0]*(1-tx)+b[y1*bw+x1]*tx)*ty;
          float v=ch[c]*255f/Math.max(60f,back);
          o[c]=(int)Math.min(255f,v);
        }
        if(keepColor){
          // Paper to pure white, ink a little deeper; colours kept.
          for(int c=0;c<3;c++)o[c]=levels(o[c],18,232,1f);
          out[y*w+x]=0xFF000000|(o[0]<<16)|(o[1]<<8)|o[2];
        }else{
          int v=(o[0]*77+o[1]*150+o[2]*29)>>8;
          v=BW.equals(mode)?levels(v,90,200,1.2f):levels(v,30,225,1f);
          out[y*w+x]=0xFF000000|(v<<16)|(v<<8)|v;
        }
      }
    }
    return out;
  }
  /**
   * A page photographed a little crooked leaves table in the corners of the crop: paints the dark area that
   * touches the edge white, only within a thin band along the edges (so a dark header on the page itself stays).
   */
  static void whiteCorners(int[] clean,int[] orig,int w,int h){
    if(w<16||h<16)return;
    int[] c={lum(orig[0]),lum(orig[w-1]),lum(orig[(h-1)*w]),lum(orig[h*w-1])};Arrays.sort(c);int around=c[0];
    int[] mid=new int[1024];int n=0;
    for(int y=h/4;y<h*3/4&&n<mid.length;y+=Math.max(1,h/64))for(int x=w/4;x<w*3/4&&n<mid.length;x+=Math.max(1,w/32))mid[n++]=lum(orig[y*w+x]);
    if(n==0)return;mid=Arrays.copyOf(mid,n);Arrays.sort(mid);int paper=mid[(int)(n*0.7)];
    if(paper-around<45)return;
    int t=(paper+around)/2,bandX=Math.max(2,w/20),bandY=Math.max(2,h/20);
    boolean[] seen=new boolean[w*h];int[] stack=new int[w*h];int sp=0;
    for(int x=0;x<w;x++){int[] e={x,(h-1)*w+x};for(int i:e)if(!seen[i]&&lum(orig[i])<t){seen[i]=true;stack[sp++]=i;}}
    for(int y=0;y<h;y++){int[] e={y*w,y*w+w-1};for(int i:e)if(!seen[i]&&lum(orig[i])<t){seen[i]=true;stack[sp++]=i;}}
    while(sp>0){
      int i=stack[--sp],x=i%w,y=i/w;clean[i]=0xFFFFFFFF;
      int[] next={x>0?i-1:-1,x<w-1?i+1:-1,y>0?i-w:-1,y<h-1?i+w:-1};
      for(int j:next){
        if(j<0||seen[j])continue;int jx=j%w,jy=j/w;
        if(jx>=bandX&&jx<w-bandX&&jy>=bandY&&jy<h-bandY)continue;
        if(lum(orig[j])<t){seen[j]=true;stack[sp++]=j;}
      }
    }
  }
  static int levels(int v,int black,int white,float gamma){
    if(v<=black)return 0;if(v>=white)return 255;
    double t=(v-black)/(double)(white-black);
    return (int)Math.round(255*Math.pow(t,gamma));
  }
  private static float[] dilate(float[] a,int w,int h){
    float[] o=new float[a.length];
    for(int y=0;y<h;y++)for(int x=0;x<w;x++){float m=0;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int yy=y+dy,xx=x+dx;if(yy>=0&&yy<h&&xx>=0&&xx<w)m=Math.max(m,a[yy*w+xx]);}o[y*w+x]=m;}
    return o;
  }
  private static float[] smooth(float[] a,int w,int h){
    float[] o=new float[a.length];
    for(int y=0;y<h;y++)for(int x=0;x<w;x++){float s=0;int n=0;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int yy=y+dy,xx=x+dx;if(yy>=0&&yy<h&&xx>=0&&xx<w){s+=a[yy*w+xx];n++;}}o[y*w+x]=s/n;}
    return o;
  }

  /** Width and height after scaling so the long side is at most max (never upscaled). */
  static int[] fit(int w,int h,int max){
    if(Math.max(w,h)<=max)return new int[]{w,h};
    double s=max/(double)Math.max(w,h);return new int[]{Math.max(1,(int)Math.round(w*s)),Math.max(1,(int)Math.round(h*s))};
  }

  /** A4 page (portrait, or landscape for a wide image) and where the image goes: {pageW,pageH,x,y,drawW,drawH}. */
  static int[] page(int imgW,int imgH){
    boolean wide=imgW>imgH*1.05;int pw=wide?842:595,ph=wide?595:842,m=24;
    double s=Math.min((pw-2.0*m)/imgW,(ph-2.0*m)/imgH);
    int dw=(int)Math.round(imgW*s),dh=(int)Math.round(imgH*s);
    return new int[]{pw,ph,(pw-dw)/2,(ph-dh)/2,dw,dh};
  }
}
