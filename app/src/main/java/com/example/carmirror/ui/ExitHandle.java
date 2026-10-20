package com.example.carmirror.ui;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;

/** Activity-local overlay; no overlay permission or window manager service. */
public final class ExitHandle extends View {
  private final Paint paint=new Paint(3);
  private final ExitGestureState gesture;
  private final Runnable exit;
  private float startX,startY,originX,originY;
  private final Runnable tick=new Runnable(){public void run(){
    invalidate();
    if(gesture.shouldExit(SystemClock.uptimeMillis())){exit.run();return;}
    postDelayed(this,16);
  }};
  public ExitHandle(Context context,Runnable exit){super(context);this.exit=exit;gesture=new ExitGestureState(ViewConfiguration.get(context).getScaledTouchSlop());setContentDescription("拖动调整位置，原地按住3秒退出投屏");}
  @Override protected void onDraw(Canvas c){float r=Math.min(getWidth(),getHeight())/2f-5;paint.setColor(0xcc303030);c.drawCircle(getWidth()/2f,getHeight()/2f,r,paint);paint.setColor(Color.WHITE);paint.setTextSize(r);paint.setTextAlign(Paint.Align.CENTER);c.drawText("×",getWidth()/2f,getHeight()/2f+r/3,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(5);paint.setColor(0xff55ddaa);c.drawArc(5,5,getWidth()-5,getHeight()-5,-90,360*gesture.progress(SystemClock.uptimeMillis()),false,paint);paint.setStyle(Paint.Style.FILL);}
  @Override public boolean onTouchEvent(android.view.MotionEvent e){long t=e.getEventTime();switch(e.getActionMasked()){
    case MotionEvent.ACTION_DOWN: startX=e.getRawX();startY=e.getRawY();originX=getX();originY=getY();gesture.onDown(t,startX,startY);removeCallbacks(tick);post(tick);return true;
    case MotionEvent.ACTION_MOVE:gesture.onMove(t,e.getRawX(),e.getRawY());if(gesture.isDragging()){removeCallbacks(tick);View p=(View)getParent();setX(Math.max(0,Math.min(p.getWidth()-getWidth(),originX+e.getRawX()-startX)));setY(Math.max(0,Math.min(p.getHeight()-getHeight(),originY+e.getRawY()-startY)));invalidate();}return true;
    case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL: cancel();return true;
    case MotionEvent.ACTION_POINTER_DOWN:cancel();return true;
  }return true;}
  public void cancel(){gesture.onUp(SystemClock.uptimeMillis());removeCallbacks(tick);invalidate();}
  @Override protected void onDetachedFromWindow(){cancel();super.onDetachedFromWindow();}
}
