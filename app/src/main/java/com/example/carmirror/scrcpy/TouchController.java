package com.example.carmirror.scrcpy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Maps fit-center content to stream pixels and retains each accepted pointer's last position.
 * A pointer leaving content is released there; it needs a fresh DOWN to enter again. */
public final class TouchController {
 private final ControlMessageWriter writer;
 private final Map<Long,Point> active=new LinkedHashMap<>();
 public TouchController(ControlMessageWriter writer){this.writer=Objects.requireNonNull(writer);}
 public synchronized boolean onTouch(TouchSample sample,VideoGeometry geometry)throws IOException {
  Objects.requireNonNull(sample);
  if(sample.action==TouchSample.CANCEL){
   boolean handled=!active.isEmpty();
   for(Map.Entry<Long,Point> entry:active.entrySet())release(entry.getKey(),entry.getValue());
   active.clear();return handled;
  }
  Objects.requireNonNull(geometry);
  Point previous=active.get(sample.pointerId);
  boolean down=sample.action==TouchSample.DOWN||sample.action==TouchSample.POINTER_DOWN;
  boolean up=sample.action==TouchSample.UP||sample.action==TouchSample.POINTER_UP;
  if(!down&&!up&&sample.action!=TouchSample.MOVE)return false;
  if(down&&previous!=null)return false;
  if(!down&&previous==null)return false;
  boolean valid=geometry.contains(sample.x,sample.y)&&(!Float.isNaN(sample.pressure)&&!Float.isInfinite(sample.pressure));
  // Release before changing geometry or leaving content, avoiding a stuck remote finger.
  if(previous!=null&&(!valid||previous.width!=geometry.streamWidth||previous.height!=geometry.streamHeight)){
   release(sample.pointerId,previous);active.remove(sample.pointerId);return true;
  }
  if(!valid)return false;
  Point point=new Point(Math.min(geometry.streamWidth-1,Math.round((sample.x-geometry.contentLeft)*geometry.streamWidth/geometry.contentWidth())),
    Math.min(geometry.streamHeight-1,Math.round((sample.y-geometry.contentTop)*geometry.streamHeight/geometry.contentHeight())),geometry.streamWidth,geometry.streamHeight);
  writer.touch(down?0:up?1:2,sample.pointerId,point.x,point.y,point.width,point.height,up?0f:sample.pressure);
  if(up)active.remove(sample.pointerId);else active.put(sample.pointerId,point);
  return true;
 }
 private void release(long id,Point p)throws IOException{writer.touch(1,id,p.x,p.y,p.width,p.height,0f);}
 private static final class Point {
  final int x,y,width,height;
  Point(int x,int y,int width,int height){this.x=x;this.y=y;this.width=width;this.height=height;}
 }
}
