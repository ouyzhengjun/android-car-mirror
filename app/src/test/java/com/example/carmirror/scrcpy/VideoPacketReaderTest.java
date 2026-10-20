package com.example.carmirror.scrcpy;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
public class VideoPacketReaderTest {
  static byte[] stream(long flags, int size, byte[] payload) throws Exception {
    ByteArrayOutputStream b=new ByteArrayOutputStream(); DataOutputStream d=new DataOutputStream(b);
    d.write(new byte[64]); d.writeInt(0x68323634); d.writeInt(0x80000000); d.writeInt(1920); d.writeInt(1080);
    d.writeLong(flags); d.writeInt(size); d.write(payload); return b.toByteArray();
  }
  @Test public void fragmentedMetadataConfigAndKeyFrame() throws Exception {
    InputStream in=new FilterInputStream(new ByteArrayInputStream(stream((1L<<62)|(1L<<61)|123,3,new byte[]{1,2,3}))) {
      public int read(byte[] b,int o,int n)throws IOException{return super.read(b,o,Math.min(n,1));}
    };
    VideoPacketReader r=new VideoPacketReader();
    VideoPacketReader.VideoPacket s=r.readNext(in); assertTrue(s.isSession()); assertEquals(1920,s.width);
    VideoPacketReader.VideoPacket p=r.readNext(in); assertTrue(p.config); assertTrue(p.keyFrame); assertEquals(123,p.ptsUs); assertArrayEquals(new byte[]{1,2,3},p.data);
    assertNull(r.readNext(in));
  }
  @Test public void rotationIsAnotherSession() throws Exception {
    byte[] first=stream(0,1,new byte[]{7}); ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(first);
    DataOutputStream d=new DataOutputStream(b);d.writeInt(0x80000001);d.writeInt(1080);d.writeInt(1920);
    VideoPacketReader r=new VideoPacketReader();InputStream in=new ByteArrayInputStream(b.toByteArray());r.readNext(in);r.readNext(in);
    VideoPacketReader.VideoPacket p=r.readNext(in);assertEquals(1080,p.width);assertEquals(1920,p.height);assertTrue(p.clientResized);
  }
  @Test public void rejectsZeroAndUnsignedOversize() throws Exception {
    for(int n:new int[]{0,-1,VideoPacketReader.MAX_PACKET_SIZE+1}) {
      VideoPacketReader r=new VideoPacketReader();InputStream in=new ByteArrayInputStream(stream(0,n,new byte[0]));r.readNext(in);
      try{r.readNext(in);fail();}catch(IOException expected){}
    }
  }
  @Test public void truncatedFrameThrows() throws Exception {
    VideoPacketReader r=new VideoPacketReader();InputStream in=new ByteArrayInputStream(stream(0,3,new byte[]{1}));r.readNext(in);
    try{r.readNext(in);fail();}catch(EOFException expected){}
  }
}
