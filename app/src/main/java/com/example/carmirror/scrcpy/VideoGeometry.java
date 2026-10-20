package com.example.carmirror.scrcpy;

/** Immutable fit-center geometry, in View-local pixels (including supplied View offset).
 * Stream coordinates have origin top-left, x right, y down. No rotation is applied:
 * scrcpy rotates the encoded stream itself. Content right/bottom are exclusive.
 * Use the same content rectangle to size/position the rendering Surface. */
public final class VideoGeometry {
  public final int streamWidth,streamHeight;
  public final float viewLeft,viewTop,viewWidth,viewHeight;
  public final float contentLeft,contentTop,contentRight,contentBottom;
  public VideoGeometry(int width,int height,float left,float top,float viewWidth,float viewHeight){
    if(width<=0||height<=0||!finite(left)||!finite(top)||!finite(viewWidth)||!finite(viewHeight)||viewWidth<=0||viewHeight<=0)throw new IllegalArgumentException("Invalid video geometry");
    streamWidth=width;streamHeight=height;viewLeft=left;viewTop=top;this.viewWidth=viewWidth;this.viewHeight=viewHeight;
    float scale=Math.min(viewWidth/width,viewHeight/height);
    contentLeft=left+(viewWidth-width*scale)/2;contentTop=top+(viewHeight-height*scale)/2;
    contentRight=contentLeft+width*scale;contentBottom=contentTop+height*scale;
  }
  private static boolean finite(float f){return !Float.isNaN(f)&&!Float.isInfinite(f);}
  public float contentWidth(){return contentRight-contentLeft;}
  public float contentHeight(){return contentBottom-contentTop;}
  public boolean contains(float x,float y){return x>=contentLeft&&x<contentRight&&y>=contentTop&&y<contentBottom;}
}
