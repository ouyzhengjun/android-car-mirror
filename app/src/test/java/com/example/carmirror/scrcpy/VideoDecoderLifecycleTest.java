package com.example.carmirror.scrcpy;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import org.junit.Test;

public class VideoDecoderLifecycleTest {
  @Test public void attachAfterExplicitCloseIsSafeForLateSurfaceCallbacks() {
    VideoDecoder decoder=new VideoDecoder();
    decoder.close();
    decoder.attach(null);
    decoder.attach(null);
  }

  @Test public void attachAfterWorkerFailureIsSafeForPauseRace() throws Exception {
    VideoDecoder decoder=new VideoDecoder();
    try {
      VideoPacketReader reader=new VideoPacketReader();
      ByteArrayInputStream stream=new ByteArrayInputStream(invalidConfigStream());
      decoder.offer(reader.readNext(stream));
      decoder.offer(reader.readNext(stream));
      Field closed=VideoDecoder.class.getDeclaredField("closed");
      closed.setAccessible(true);
      long deadline=System.currentTimeMillis()+2000;
      while(!isClosed(decoder,closed)&&System.currentTimeMillis()<deadline)Thread.sleep(5);
      assertTrue("decoder worker must close after invalid AVC config",isClosed(decoder,closed));
      assertNotNull(decoder.failure());
      decoder.attach(null);
    }finally {
      decoder.close();
    }
  }

  @Test public void separateSpsAndPpsPacketsAreAcceptedBeforeCodecStartup() throws Exception {
    VideoDecoder decoder=new VideoDecoder();
    try {
      VideoPacketReader reader=new VideoPacketReader();
      ByteArrayInputStream stream=new ByteArrayInputStream(splitConfigStream());
      decoder.offer(reader.readNext(stream));
      decoder.offer(reader.readNext(stream));
      decoder.offer(reader.readNext(stream));
      Field configField=VideoDecoder.class.getDeclaredField("config");
      configField.setAccessible(true);
      long deadline=System.currentTimeMillis()+2000;
      AvcConfig result=null;
      while(result==null&&decoder.failure()==null&&System.currentTimeMillis()<deadline) {
        synchronized(decoder){result=((AvcConfig.Accumulator)configField.get(decoder)).complete();}
        if(result==null)Thread.sleep(5);
      }
      assertNull(decoder.failure());
      assertNotNull("decoder must retain split SPS and PPS",result);
    }finally {decoder.close();}
  }

  private static boolean isClosed(VideoDecoder decoder,Field field) throws Exception {
    synchronized(decoder){return field.getBoolean(decoder);}
  }

  private static byte[] invalidConfigStream() throws Exception {
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    DataOutputStream out=new DataOutputStream(bytes);
    out.write(new byte[64]);
    out.writeInt(0x68323634);
    out.writeInt(0x80000000);
    out.writeInt(1280);
    out.writeInt(720);
    out.writeLong(1L<<62);
    out.writeInt(1);
    out.writeByte(0x67);
    return bytes.toByteArray();
  }

  private static byte[] splitConfigStream() throws Exception {
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    DataOutputStream out=new DataOutputStream(bytes);
    out.write(new byte[64]);
    out.writeInt(0x68323634);
    out.writeInt(0x80000000);
    out.writeInt(1280);
    out.writeInt(720);
    out.writeLong(1L<<62);
    out.writeInt(6);
    out.write(new byte[]{0,0,0,1,0x67,0x42});
    out.writeLong(1L<<62);
    out.writeInt(6);
    out.write(new byte[]{0,0,0,1,0x68,7});
    return bytes.toByteArray();
  }
}
