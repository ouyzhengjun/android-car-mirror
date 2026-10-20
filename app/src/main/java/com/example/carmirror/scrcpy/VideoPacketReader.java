package com.example.carmirror.scrcpy;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** scrcpy v4.0 defaults, after ScrcpyLauncher has consumed the forward dummy byte.
 * One reader belongs to one stream. Session packets are delivered, never hidden. */
public final class VideoPacketReader {
  public static final int MAX_PACKET_SIZE = 8 * 1024 * 1024;
  private boolean initialized;
  private String deviceName;
  public String deviceName() { return deviceName; }

  public VideoPacket readNext(InputStream in) throws IOException {
    if (!initialized) {
      byte[] name = new byte[64]; readFully(in, name, 0);
      int end=0; while(end<name.length && name[end]!=0) end++;
      deviceName=new String(name,0,end,StandardCharsets.UTF_8);
      byte[] codec=new byte[4];readFully(in,codec,0);
      if(ByteBuffer.wrap(codec).getInt()!=0x68323634) throw new IOException("Expected scrcpy H.264 stream");
      initialized=true;
    }
    int first=in.read(); if(first<0)return null;
    byte[] header=new byte[12];header[0]=(byte)first;readFully(in,header,1);
    ByteBuffer b=ByteBuffer.wrap(header);
    if((first&128)!=0){
      int flags=b.getInt(),width=b.getInt(),height=b.getInt();
      if((flags&~0x80000001)!=0 || width<=0 || height<=0 || width>65535 || height>65535)
        throw new IOException("Invalid video session");
      return new VideoPacket(width,height,(flags&1)!=0,0,false,false,new byte[0]);
    }
    long flags=b.getLong();long size=b.getInt()&0xffffffffL;
    if(size==0 || size>MAX_PACKET_SIZE)throw new IOException("Invalid video packet size: "+size);
    byte[] data=new byte[(int)size];readFully(in,data,0);
    return new VideoPacket(0,0,false,flags&0x1fffffffffffffffL,(flags&(1L<<62))!=0,(flags&(1L<<61))!=0,data);
  }
  private static void readFully(InputStream in,byte[] b,int offset)throws IOException{
    while(offset<b.length){int n=in.read(b,offset,b.length-offset);if(n<0)throw new EOFException("Truncated scrcpy packet");if(n==0){int v=in.read();if(v<0)throw new EOFException();b[offset++]=(byte)v;}else offset+=n;}
  }
  public static final class VideoPacket {
    public final int width,height;
    public final boolean clientResized,config,keyFrame;
    public final long ptsUs;
    public final byte[] data;
    private VideoPacket(int w,int h,boolean resized,long pts,boolean config,boolean key,byte[] data){width=w;height=h;clientResized=resized;ptsUs=pts;this.config=config;keyFrame=key;this.data=data;}
    public boolean isSession(){return width>0;}
  }
}
