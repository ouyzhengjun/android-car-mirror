package com.example.carmirror.scrcpy;
import org.junit.Test;
import java.io.*;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;
public class TouchControllerTest {
 private final ByteArrayOutputStream out=new ByteArrayOutputStream();
 private final TouchController controller=new TouchController(new ControlMessageWriter(out));
 private boolean send(int action,long id,float x,float y,VideoGeometry g)throws Exception{return controller.onTouch(new TouchSample(action,id,x,y,.5f),g);}
 private int value(int event,int offset){return ByteBuffer.wrap(out.toByteArray()).getInt(event*32+offset);}
 @Test public void landscapeMapsOffsetContent()throws Exception {
  assertTrue(send(0,7,510,520,new VideoGeometry(1920,1080,10,20,1000,1000)));
  assertEquals(960,value(0,10));assertEquals(540,value(0,14));
 }
 @Test public void portraitMapsWithoutAdditionalRotation()throws Exception {
  assertTrue(send(0,2,500,500,new VideoGeometry(1080,1920,0,0,1000,1000)));
  assertEquals(540,value(0,10));assertEquals(960,value(0,14));
 }
 @Test public void blackBarsAndExclusiveEdgesDoNotStartGestures()throws Exception {
  VideoGeometry g=new VideoGeometry(1920,1080,0,0,1000,1000);
  assertFalse(send(0,1,500,10,g)); assertFalse(send(2,1,500,500,g));
  assertFalse(send(0,2,g.contentRight,500,g));assertEquals(0,out.size());
 }
 @Test public void leavingContentReleasesAtLastValidPoint()throws Exception {
  VideoGeometry g=new VideoGeometry(100,100,0,0,100,100);
  send(0,8,30,40,g);assertTrue(send(2,8,120,40,g));assertFalse(send(1,8,120,40,g));
  assertEquals(64,out.size()); assertEquals(1,out.toByteArray()[33]);assertEquals(30,value(1,10));assertEquals(0,out.toByteArray()[54]);
 }
 @Test public void multiplePointersMoveUpAndCancelReleaseAll()throws Exception {
  VideoGeometry g=new VideoGeometry(100,100,0,0,100,100);
  send(0,10,10,20,g);send(5,12,30,40,g);send(2,10,20,30,g);send(6,12,40,50,g);send(3,0,0,0,g);
  assertEquals(160,out.size());assertEquals(12,ByteBuffer.wrap(out.toByteArray()).getLong(32+2));
  assertEquals(1,out.toByteArray()[97]);assertEquals(1,out.toByteArray()[129]);
  assertFalse(send(2,10,10,10,g)); assertFalse(send(3,0,0,0,g));
 }
 @Test public void geometryChangeReleasesEveryPointerWithCurrentDimensions()throws Exception {
  VideoGeometry oldGeometry=new VideoGeometry(100,200,0,0,100,200);
  send(0,10,25,100,oldGeometry);send(5,12,99,199,oldGeometry);
  assertTrue(send(2,10,100,25,new VideoGeometry(200,100,0,0,200,100)));
  assertEquals(128,out.size());
  ByteBuffer bytes=ByteBuffer.wrap(out.toByteArray());
  for(int event=2;event<4;event++) {
   assertEquals(1,bytes.get(event*32+1));
   assertEquals(200,bytes.getShort(event*32+18)&65535);
   assertEquals(100,bytes.getShort(event*32+20)&65535);
   assertEquals(0,bytes.getShort(event*32+22));
  }
  assertEquals(10,bytes.getLong(66));assertEquals(50,value(2,10));assertEquals(50,value(2,14));
  assertEquals(12,bytes.getLong(98));assertEquals(198,value(3,10));assertEquals(99,value(3,14));
  assertFalse(send(2,12,50,50,new VideoGeometry(200,100,0,0,200,100)));
  assertFalse(send(3,0,0,0,null));
 }
}
