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
  // Frames arrive in bursts over ADB; render them on their presentation timeline instead
  // of on arrival so motion stays even. The delay absorbs normal jitter.
  private static final long PLAYOUT_DELAY_US=50000;
  private long basePtsUs=-1,baseWallUs;
  private final ArrayDeque<VideoPacket> queue=new ArrayDeque<>();
  private final Thread worker;
  private Surface surface;
  private long surfaceVersion;
  private boolean closed;
  private volatile IOException failure;
  private volatile int width,height;
  private MediaCodec codec;
  private final AvcConfig.Accumulator config=new AvcConfig.Accumulator();
  private boolean waitForKey=true;
  public VideoDecoder(){worker=new Thread(this::run,"scrcpy-decoder");worker.start();}
  /** null detaches the surface. A new surface recreates the codec on API 21 too.
   * Late Activity lifecycle callbacks are harmless after the decoder worker has exited. */
  public synchronized void attach(Surface surface){if(closed)return;this.surface=surface;surfaceVersion++;notifyAll();}
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
          release();width=pending.width;height=pending.height;config.reset();waitForKey=true;pending=null;continue;
        }
        if(pending!=null&&pending.config){if(config.accept(pending.data)){release();waitForKey=true;}pending=null;continue;}
        AvcConfig complete=config.complete();
        if(codec==null&&target!=null&&target.isValid()&&width>0&&complete!=null){
          MediaFormat format=MediaFormat.createVideoFormat("video/avc",width,height);
          format.setByteBuffer("csd-0",ByteBuffer.wrap(complete.sps));
          format.setByteBuffer("csd-1",ByteBuffer.wrap(complete.pps));
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
  private void drain(){
    if(codec==null)return;
    MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int index;
    while((index=codec.dequeueOutputBuffer(info,0))>=0){
      pace(info.presentationTimeUs);
      codec.releaseOutputBuffer(index,true);
    }
  }
  /** Sleeps until this frame's position on the playback timeline. */
  private void pace(long ptsUs){
    long now=System.nanoTime()/1000;
    if(basePtsUs<0||ptsUs<basePtsUs){basePtsUs=ptsUs;baseWallUs=now+PLAYOUT_DELAY_US;}
    long target=baseWallUs+(ptsUs-basePtsUs);
    // Reset after a long idle gap or when the timeline jumps ahead of real time.
    if(target>now+250000||target<now-150000){basePtsUs=ptsUs;baseWallUs=now+PLAYOUT_DELAY_US;target=baseWallUs;}
    long waitUs=target-now;
    if(waitUs>2000)try{Thread.sleep(waitUs/1000,(int)(waitUs%1000)*1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
  }
  private void release(){basePtsUs=-1;if(codec!=null){try{codec.stop();}catch(RuntimeException ignored){}try{codec.release();}finally{codec=null;}}}
  @Override public void close(){synchronized(this){closed=true;queue.clear();notifyAll();}worker.interrupt();if(Thread.currentThread()!=worker){try{worker.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
}
