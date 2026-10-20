package com.example.carmirror.ui;

/** One stationary hold fires once. Crossing slop permanently turns this gesture into a drag. */
public final class ExitGestureState {
  private final float slop;
  private long downTime;
  private float downX, downY;
  private boolean down, dragging, fired;
  public ExitGestureState(float slop) { this.slop = slop; }
  public void onDown(long t, float x, float y) { downTime=t; downX=x; downY=y; down=true; dragging=false; fired=false; }
  public void onMove(long t, float x, float y) { if(down && Math.hypot(x-downX,y-downY)>slop) dragging=true; }
  public void onUp(long t) { down=false; }
  public boolean isDragging() { return dragging; }
  public float progress(long t) { return down&&!dragging&&!fired ? Math.min(1f,Math.max(0f,(t-downTime)/3000f)) : 0f; }
  public boolean shouldExit(long t) { if(!down||dragging||fired||t-downTime<3000)return false; fired=true; return true; }
}
