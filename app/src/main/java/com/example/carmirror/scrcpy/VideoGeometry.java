package com.example.carmirror.scrcpy;

/** Immutable stream-to-view geometry, in View-local pixels (including supplied View offset).
 * Stream coordinates have origin top-left, x right, y down. No rotation is applied:
 * scrcpy rotates the encoded stream itself. Content right/bottom are exclusive.
 * Use the same content rectangle to size/position the rendering Surface.
 *
 * <p>FIT scales the stream down until it fits, leaving bars. COVER scales it up until both
 * dimensions are filled and lets the content overflow the view, so the visible area is a
 * centre crop; that overflow is what removes the letterbox but also what the touch mapping
 * has to subtract. */
public final class VideoGeometry {
  public final int streamWidth,streamHeight;
  public final float viewLeft,viewTop,viewWidth,viewHeight;
  public final float contentLeft,contentTop,contentRight,contentBottom;
  public final boolean cover;

  public VideoGeometry(int width,int height,float left,float top,float viewWidth,float viewHeight){
    this(width,height,left,top,viewWidth,viewHeight,false);
  }

  public VideoGeometry(int width,int height,float left,float top,float viewWidth,float viewHeight,boolean cover){
    if(width<=0||height<=0||!finite(left)||!finite(top)||!finite(viewWidth)||!finite(viewHeight)||viewWidth<=0||viewHeight<=0)throw new IllegalArgumentException("Invalid video geometry");
    streamWidth=width;streamHeight=height;viewLeft=left;viewTop=top;this.viewWidth=viewWidth;this.viewHeight=viewHeight;this.cover=cover;
    float scale=cover
        ?Math.max(viewWidth/width,viewHeight/height)
        :Math.min(viewWidth/width,viewHeight/height);
    contentLeft=left+(viewWidth-width*scale)/2;contentTop=top+(viewHeight-height*scale)/2;
    contentRight=contentLeft+width*scale;contentBottom=contentTop+height*scale;
  }
  private static boolean finite(float f){return !Float.isNaN(f)&&!Float.isInfinite(f);}
  public float contentWidth(){return contentRight-contentLeft;}
  public float contentHeight(){return contentBottom-contentTop;}
  /** Visible width of the stream after cropping, i.e. the view width for COVER. */
  public float visibleWidth(){return Math.min(viewWidth,contentWidth());}
  public float visibleHeight(){return Math.min(viewHeight,contentHeight());}
  public boolean contains(float x,float y){return x>=contentLeft&&x<contentRight&&y>=contentTop&&y<contentBottom;}
}
