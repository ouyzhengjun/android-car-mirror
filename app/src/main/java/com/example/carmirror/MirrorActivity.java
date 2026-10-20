package com.example.carmirror;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.example.carmirror.adb.AdbSession;
import com.example.carmirror.adb.ConnectionOwner;
import com.example.carmirror.scrcpy.*;
import com.example.carmirror.ui.ExitHandle;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Borrows the process-owned live ADB connection; owns only scrcpy and decoder resources. */
public final class MirrorActivity extends Activity implements SurfaceHolder.Callback {
  private AdbSession adb;
  private ScrcpySession session;
  private VideoDecoder decoder;
  private volatile TouchController touch;
  private volatile ControlMessageWriter writer;
  private Thread controlDrain;
  private volatile VideoGeometry geometry;
  private final ExecutorService video=Executors.newSingleThreadExecutor();
  private final ExecutorService controls=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(256));
  private final AtomicBoolean closed=new AtomicBoolean();
  private FrameLayout root;
  private SurfaceView surface;
  private TextView status;
  private ExitHandle exit;
  private int streamWidth,streamHeight;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final Runnable health=new Runnable(){public void run(){if(closed.get())return;if(decoder!=null&&decoder.failure()!=null){fail(decoder.failure());return;}handler.postDelayed(this,250);}};

  @Override public void onCreate(Bundle state){super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    immersive();
    root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);setContentView(root);
    surface=new SurfaceView(this);root.addView(surface,new FrameLayout.LayoutParams(1,1));surface.getHolder().addCallback(this);
    // Root receives content and black-bar coordinates using the same fit-center geometry.
    surface.setOnTouchListener((v,e)->forward(e,surface.getX(),surface.getY()));
    root.setOnTouchListener((v,e)->forward(e,0,0));
    status=new TextView(this);status.setTextColor(Color.WHITE);status.setText("正在启动投屏…");root.addView(status);
    LinearLayout navigation=new LinearLayout(this);
    String[] labels={"返回","主页","最近任务"};int[] codes={4,3,187};
    for(int i=0;i<codes.length;i++){final int code=codes[i];Button b=new Button(this);b.setText(labels[i]);b.setOnClickListener(v->submit(()->{if(writer!=null){writer.keycode(0,code);writer.keycode(1,code);}}));navigation.addView(b);}
    FrameLayout.LayoutParams nav=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);root.addView(navigation,nav);
    exit=new ExitHandle(this,()->{closeSession();finish();});int size=(int)(64*getResources().getDisplayMetrics().density);FrameLayout.LayoutParams handle=new FrameLayout.LayoutParams(size,size,Gravity.TOP|Gravity.RIGHT);root.addView(exit,handle);
    // Gravity only establishes the initial position; explicit x/y then support clamped dragging.
    root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{layoutVideo();exit.setX(Math.max(0,Math.min(root.getWidth()-exit.getWidth(),exit.getX())));exit.setY(Math.max(0,Math.min(root.getHeight()-exit.getHeight(),exit.getY())));});
    adb=ConnectionOwner.get(this);if(!ConnectionOwner.isConnected()){fail(new IOException("连接已失效，请返回后重新连接"));return;}
    decoder=new VideoDecoder();if(surface.getHolder().getSurface().isValid())decoder.attach(surface.getHolder().getSurface());
    handler.post(health);
    video.execute(()->{try{
      ScrcpySession launcher=new ScrcpyLauncher(getApplicationContext());
      synchronized(this){if(closed.get())return;session=launcher;}
      ScrcpySession.Streams streams=launcher.start(adb);
      if(closed.get())return;
      writer=new ControlMessageWriter(streams.control().output());touch=new TouchController(writer);
      // Consume device clipboard/status messages without retaining potentially unbounded text.
      controlDrain=new Thread(()->{try{byte[] buffer=new byte[4096];while(!closed.get()&&streams.control().input().read(buffer)!=-1){}if(!closed.get())fail(new IOException("控制连接已断开"));}catch(IOException error){if(!closed.get())fail(error);}},"mirror-control-read");
      controlDrain.start();
      VideoPacketReader reader=new VideoPacketReader();VideoPacketReader.VideoPacket packet;
      while(!closed.get()&&(packet=reader.readNext(streams.video().input()))!=null){
        if(packet.isSession()){final int w=packet.width,h=packet.height;runOnUiThread(()->{if(closed.get())return;streamWidth=w;streamHeight=h;layoutVideo();cancelTouch();status.setVisibility(View.GONE);});}
        decoder.offer(packet);
      }
      if(!closed.get())fail(new IOException("投屏连接已断开"));
    }catch(Exception error){if(!closed.get())fail(error);}
    finally{if(closed.get()&&session!=null)session.stop();}});
  }
  private void immersive(){getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
  @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)immersive();}
  private void layoutVideo(){if(streamWidth<=0||root.getWidth()==0||root.getHeight()==0)return;
    VideoGeometry next=new VideoGeometry(streamWidth,streamHeight,0,0,root.getWidth(),root.getHeight());
    geometry=next;FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)surface.getLayoutParams();int w=Math.round(next.contentWidth()),h=Math.round(next.contentHeight());
    if(p.width!=w||p.height!=h){p.width=w;p.height=h;surface.setLayoutParams(p);}surface.setX(next.contentLeft);surface.setY(next.contentTop);
  }
  private interface IoAction{void run()throws IOException;}
  private void submit(IoAction action){if(closed.get())return;try{controls.execute(()->{if(closed.get())return;try{action.run();}catch(Exception error){fail(error);}});}catch(RejectedExecutionException error){if(!closed.get())fail(new IOException("控制通道响应超时",error));}}
  private boolean forward(MotionEvent e,float offsetX,float offsetY){VideoGeometry g=geometry;if(g==null||closed.get())return true;
    int action=e.getActionMasked();int start=action==MotionEvent.ACTION_MOVE?0:e.getActionIndex();int end=action==MotionEvent.ACTION_MOVE?e.getPointerCount():start+1;
    for(int i=start;i<end;i++){TouchSample sample=new TouchSample(action,e.getPointerId(i),e.getX(i)+offsetX,e.getY(i)+offsetY,e.getPressure(i));submit(()->{if(touch!=null)touch.onTouch(sample,g);});}return true;
  }
  private void cancelTouch(){VideoGeometry g=geometry;submit(()->{if(touch!=null)touch.onTouch(new TouchSample(3,0,0,0,0),g);});}
  @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);cancelTouch();exit.cancel();geometry=null;root.post(this::layoutVideo);}
  @Override protected void onPause(){cancelTouch();exit.cancel();super.onPause();}
  @Override public void onBackPressed(){submit(()->{if(writer!=null){writer.keycode(0,4);writer.keycode(1,4);}});}
  private void fail(Exception error){runOnUiThread(()->{if(closed.get())return;status.setText("投屏失败："+error.getMessage()+"\n请按住 × 3秒退出后重新连接");status.setVisibility(View.VISIBLE);closeSession();});}
  /** All shutdown paths converge here. Closing scrcpy channels releases reads while ADB stays connected. */
  private void closeSession(){if(!closed.compareAndSet(false,true))return;handler.removeCallbacks(health);geometry=null;controls.shutdownNow();video.shutdownNow();
    final ScrcpySession current; synchronized(this){current=session;}
    new Thread(()->{if(current!=null)current.stop();if(decoder!=null)decoder.close();},"mirror-close").start();
  }
  @Override protected void onDestroy(){closeSession();super.onDestroy();}
  @Override public void surfaceCreated(SurfaceHolder holder){if(decoder!=null&&!closed.get())decoder.attach(holder.getSurface());}
  @Override public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){}
  @Override public void surfaceDestroyed(SurfaceHolder holder){cancelTouch();if(decoder!=null&&!closed.get())decoder.attach(null);}
}
