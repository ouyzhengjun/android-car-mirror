package com.example.carmirror.scrcpy;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;
public class ControlMessageWriterTest {
 @Test public void touchMatchesV4WireLayout() throws Exception {
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  new ControlMessageWriter(out).touch(0,0x1234567887654321L,100,200,1080,1920,1f,1,1);
  assertArrayEquals(hex("0200123456788765432100000064000000c804380780ffff0000000100000001"),out.toByteArray());
 }
 @Test public void pressureUsesUnsignedFixedPoint() throws Exception {
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  new ControlMessageWriter(out).touch(2,3,0,0,100,200,.5f);
  assertEquals(0x80,out.toByteArray()[22]&255); assertEquals(0,out.toByteArray()[23]);
 }
 @Test public void navigationKeysHaveDownUpZeroRepeatAndMeta() throws Exception {
  for(int key:new int[]{ControlMessageWriter.KEY_BACK,ControlMessageWriter.KEY_HOME,ControlMessageWriter.KEY_RECENT}) {
   ByteArrayOutputStream out=new ByteArrayOutputStream(); ControlMessageWriter w=new ControlMessageWriter(out);
   w.keycode(0,key); w.keycode(1,key);
   byte[] expected=new byte[28]; expected[5]=(byte)key; expected[15]=1; expected[19]=(byte)key;
   assertArrayEquals(expected,out.toByteArray());
  }
 }
 @Test(expected=IOException.class) public void propagatesConnectionFailure() throws Exception {
  new ControlMessageWriter(new OutputStream(){public void write(int b)throws IOException{throw new IOException("closed");}}).keycode(0,4);
 }
 static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
}
