package com.example.carmirror.scrcpy;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import com.example.carmirror.scrcpy.VideoPacketReader.VideoPacket;

/** Owns a codec worker, not the Surface. offer() applies bounded backpressure on the
 * stream-reading thread; never call it on UI. Encoded reference frames are not dropped.
 * Output is drained continuously even when the remote screen stops producing frames. */
public final class VideoDecoder implements Closeable {
  private static final int CAPACITY=3;
  private final ArrayDeque<VideoPacket> queue=new ArrayDeque<>();
  private final Thread worker;
  private Surface surface;
  private long surfaceVersion;
  private boolean closed;
  private volatile IOException failure;
  private volatile int width,height;
  private MediaCodec codec;
  private VideoPacket config;
  private boolean waitForKey=true;
  public VideoDecoder(){worker=new Thread(this::run,"scrcpy-decoder");worker.start();}
  /** null detaches the surface. A new surface recreates the codec on API 21 too. */
  public synchronized void attach(Surface surface){if(closed)throw new IllegalStateException("Decoder closed");this.surface=surface;surfaceVersion++;notifyAll();}
  public synchronized void offer(VideoPacket packet)throws IOException{
    if(packet==null)throw new NullPointerException("packet");
    while(queue.size()>=CAPACITY&&!closed&&failure==null){try{wait();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted video delivery",e);}}
    if(failure!=null)throw failure;if(closed)throw new IOException("Decoder closed");queue.addLast(packet);notifyAll();
  }
  public IOException failure(){return failure;}
  public int streamWidth(){return width;}
  public int streamHeight(){return height;}
  private void run(){
    long version=-1;
    VideoPacket pending=null;
    try{
      while(true){
        Surface target;long current;
        synchronized(this){if(closed)return;target=surface;current=surfaceVersion;}
        if(current!=version){release();version=current;waitForKey=true;}
        if(pending==null){synchronized(this){pending=queue.pollFirst();notifyAll();}}
        if(pending!=null&&pending.isSession()){
          release();width=pending.width;height=pending.height;config=null;waitForKey=true;pending=null;continue;
        }
        if(pending!=null&&pending.config){config=pending;release();waitForKey=true;pending=null;continue;}
        if(codec==null&&target!=null&&target.isValid()&&width>0&&config!=null){
          MediaFormat format=MediaFormat.createVideoFormat("video/avc",width,height);
          format.setByteBuffer("csd-0",ByteBuffer.wrap(config.data));
          format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,VideoPacketReader.MAX_PACKET_SIZE);
          if(Build.VERSION.SDK_INT>=30)format.setInteger("low-latency",1);
          codec=MediaCodec.createDecoderByType("video/avc");codec.configure(format,target,null,0);codec.start();
        }
        if(pending!=null){
          if(codec==null || (waitForKey&&!pending.keyFrame)){pending=null;}
          else{
            int index=codec.dequeueInputBuffer(0);
            if(index>=0){ByteBuffer input=codec.getInputBuffer(index);if(input==null||input.capacity()<pending.data.length)throw new IOException("Video input exceeds codec capacity");input.clear();input.put(pending.data);codec.queueInputBuffer(index,0,pending.data.length,pending.ptsUs,0);waitForKey=false;pending=null;}
          }
        }
        drain();
        synchronized(this){if(!closed&&queue.isEmpty())wait(5);}
      }
    }catch(InterruptedException e){Thread.currentThread().interrupt();}
    catch(Exception e){failure=e instanceof IOException?(IOException)e:new IOException("Video decoder failed",e);}
    finally{release();synchronized(this){closed=true;queue.clear();notifyAll();}}
  }
  private void drain(){if(codec==null)return;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int index;while((index=codec.dequeueOutputBuffer(info,0))>=0)codec.releaseOutputBuffer(index,true);}
  private void release(){if(codec!=null){try{codec.stop();}catch(RuntimeException ignored){}try{codec.release();}finally{codec=null;}}}
  @Override public void close(){synchronized(this){closed=true;queue.clear();notifyAll();}worker.interrupt();if(Thread.currentThread()!=worker){try{worker.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
}
