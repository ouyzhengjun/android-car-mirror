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
import com.example.carmirror.ui.ControlQueue;
import com.example.carmirror.ui.ForegroundDevices;
import com.example.carmirror.ui.MirrorIntent;
import com.example.carmirror.ui.DisplaySize;
import com.example.carmirror.ui.CropValidationGate;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Borrows the process-owned live ADB connection; owns only scrcpy and decoder resources. */
public final class MirrorActivity extends Activity implements SurfaceHolder.Callback {
  private AdbSession adb;
  private ForegroundDevices foreground;
  private MirrorOptions options;
  private CropValidationGate cropGate;
  private final ExecutorService validation=Executors.newSingleThreadExecutor();
  private ScrcpySession session;
  private VideoDecoder decoder;
  private volatile TouchController touch;
  private volatile ControlMessageWriter writer;
  private Thread controlDrain;
  private volatile VideoGeometry geometry;
  private final ExecutorService video=Executors.newSingleThreadExecutor();
  // Control writes are stop-and-wait on the ADB stream, so a slow one holds up the queue behind
  // it. Input older than this budget is dropped instead of replayed somewhere the user never
  // touched.
  private static final long CONTROL_BUDGET_MS=100;
  private final ControlQueue controls=new ControlQueue(32);
  private final AtomicBoolean closed=new AtomicBoolean();
  // Each control write waits for an ADB READY, so a fast drag can outrun the link.
  // Only the newest move matters; older positions are dropped instead of queued.
  private final AtomicBoolean movePending=new AtomicBoolean();
  // Non-zero while a control write runs; a stuck write means the control stream is dead.
  private final java.util.concurrent.atomic.AtomicLong controlSince=new java.util.concurrent.atomic.AtomicLong();
  private boolean resumed;
  private FrameLayout root;
  private SurfaceView surface;
  private TextView status;
  private TextView hint;
  private int streamWidth,streamHeight;
  private final Handler handler=new Handler(Looper.getMainLooper());
  /** Warn once when one control write is slow; fail only once it is clearly stuck. */
  private static final long CONTROL_STALL_WARN_MS=2000,CONTROL_STALL_FAIL_MS=8000;
  private boolean stallWarned;
  private final Runnable health=new Runnable(){public void run(){if(closed.get())return;if(decoder!=null&&decoder.failure()!=null){fail(decoder.failure(),false);return;}
    long since=controlSince.get();
    if(since!=0){
      long waited=android.os.SystemClock.uptimeMillis()-since;
      // A control frame waits for the device to accept it; under load that pause can last
      // seconds while video keeps playing. Failing on the first slow frame costs a full
      // reconnect, so warn early and only give up when the write is clearly stuck.
      if(waited>CONTROL_STALL_WARN_MS&&!stallWarned){stallWarned=true;android.util.Log.w("CarMirrorMirror","control write pending for "+waited+" ms");}
      if(waited>CONTROL_STALL_FAIL_MS){android.util.Log.w("CarMirrorMirror","control write stuck for "+waited+" ms");fail(new LinkLost("控制通道无响应"),true);return;}
    }else stallWarned=false;
    handler.postDelayed(this,250);}};

  @Override public void onCreate(Bundle state){super.onCreate(state);
    foreground=ForegroundDevices.get(this);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);setContentView(root);
    // Insets controller needs a decor view, so this must run after setContentView.
    immersive();
    surface=new SurfaceView(this);root.addView(surface,new FrameLayout.LayoutParams(1,1));surface.getHolder().addCallback(this);
    // Root receives content and black-bar coordinates using the same fit-center geometry.
    surface.setOnTouchListener((v,e)->forward(e,surface.getX(),surface.getY()));
    root.setOnTouchListener((v,e)->forward(e,0,0));
    status=new TextView(this);status.setTextColor(Color.WHITE);status.setText("正在启动投屏…");root.addView(status);
    hint=exitHint();
    root.addView(hint);
    root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->layoutVideo());
    // No on-screen exit control: the system back gesture (edge swipe) ends the mirror, and a
    // one-shot hint teaches it so the screen stays completely clean afterwards.
    setResult(RESULT_OK);
    handler.postDelayed(()->root.removeView(hint),3000);
    adb=ConnectionOwner.get(this);if(!ConnectionOwner.isConnected()){fail(new IOException("连接已失效，请返回后重新连接"));return;}
    try{options=MirrorIntent.read(getIntent());cropGate=new CropValidationGate(options.crop);}catch(IllegalArgumentException e){fail(e);return;}
    decoder=new VideoDecoder();
    controls.failureHandler(error->fail(error,true));
    handler.post(health);
    video.execute(()->{try{
      if(options.crop!=null){com.example.carmirror.adb.CommandResult display=adb.shell("wm size -d "+options.displayId,5000);int[] originalSize=DisplaySize.parse(display.output());options.crop.validateWithin(originalSize[0],originalSize[1]);}
      ScrcpySession launcher=new ScrcpyLauncher(getApplicationContext(),options);
      synchronized(this){if(closed.get())return;session=launcher;}
      ScrcpySession.Streams streams=launcher.start(adb);
      if(closed.get())return;
      writer=new ControlMessageWriter(streams.control().output());touch=new TouchController(writer);
      // Consume device clipboard/status messages without retaining potentially unbounded text.
      controlDrain=new Thread(()->{try{byte[] buffer=new byte[4096];while(!closed.get()&&streams.control().input().read(buffer)!=-1){}if(!closed.get())fail(new LinkLost("控制连接已断开"),true);}catch(IOException error){if(!closed.get())fail(error,true);}},"mirror-control-read");
      controlDrain.start();
      VideoPacketReader reader=new VideoPacketReader();VideoPacketReader.VideoPacket packet;
      while(!closed.get()&&(packet=reader.readNext(streams.video().input()))!=null){
        if(packet.isSession()){reconnectAttempts=0;validateGeometry(packet.width,packet.height);}
        else decoder.noteNewest(packet.ptsUs);
        decoder.offer(packet);
      }
      if(!closed.get())fail(new LinkLost("投屏连接已断开"),true);
    }catch(Exception error){if(!closed.get())fail(error,decoder==null||decoder.failure()==null);}
    finally{if(closed.get()&&session!=null)session.stop();}});
  }
  /** Suspend input immediately, then query ORIGINAL display bounds off the UI/video threads. */
  private void validateGeometry(final int width,final int height){
    if(closed.get()||cropGate==null)return;
    final long token=cropGate.begin();
    runOnUiThread(()->{if(closed.get()||!cropGate.current(token))return;cancelTouch();status.setText("显示尺寸变化，正在校验投屏区域…");status.setVisibility(View.VISIBLE);});
    try{validation.execute(()->{
      try{
        com.example.carmirror.adb.CommandResult result=adb.shell("wm size -d "+options.displayId,5000);
        int[] bounds=result.timedOut()?new int[]{0,0}:DisplaySize.parse(result.output());
        runOnUiThread(()->{
          if(closed.get()||!cropGate.current(token))return;
          try{
            // The UI update and unlocking occur together; queued old touches stay blocked.
            if(options.crop!=null)options.crop.validateWithin(bounds[0],bounds[1]);
            streamWidth=width;streamHeight=height;layoutVideo();
            cropGate.validate(token,bounds[0],bounds[1]);status.setVisibility(View.GONE);
          }catch(IllegalArgumentException error){invalidCrop(error);}
        });
      }catch(Exception error){runOnUiThread(()->{if(!closed.get()&&cropGate.current(token))invalidCrop(error);});}
    });}catch(RejectedExecutionException ignored){}
  }
  private void invalidCrop(Exception error){
    fail(new IllegalArgumentException("投屏区域设置已失效或无法确认显示尺寸："+error.getMessage()+"；请从屏幕边缘内滑退出，在设置里重新读取尺寸并修改区域"),false);
  }
  /** Shown once per launch for three seconds, then removed; never interactive. */
  private TextView exitHint(){
    TextView t=new TextView(this);
    t.setText("从屏幕边缘内滑可退出投屏");
    t.setTextColor(0xFFF5EFF5);
    t.setTextSize(16);
    t.setPadding(dp(22),dp(11),dp(22),dp(11));
    android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
    bg.setColor(0xD91A1218);bg.setCornerRadius(dp(999));
    bg.setStroke(dp(1),0x3DFFFFFF);t.setBackground(bg);
    FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT);
    p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.topMargin=dp(30);
    t.setLayoutParams(p);
    return t;
  }
  private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
  @Override public void onBackPressed(){
    setResult(RESULT_OK);
    closeSession();
    finish();
  }
  private void immersive(){
    Window window=getWindow();
    if(android.os.Build.VERSION.SDK_INT>=30){
      // Legacy flags leave the gesture bar visible on newer tablets; the controller is exact.
      window.setDecorFitsSystemWindows(false);
      WindowInsetsController bars=window.getInsetsController();
      if(bars!=null){
        bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.hide(WindowInsets.Type.systemBars());
      }
    }else{
      window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
  }
  @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)immersive();}
  private void layoutVideo(){if(streamWidth<=0||root.getWidth()==0||root.getHeight()==0)return;
    VideoGeometry next=new VideoGeometry(streamWidth,streamHeight,0,0,root.getWidth(),
        root.getHeight(),options==null||options.cover);
    geometry=next;FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)surface.getLayoutParams();int w=Math.round(next.contentWidth()),h=Math.round(next.contentHeight());
    if(p.width!=w||p.height!=h){p.width=w;p.height=h;surface.setLayoutParams(p);}surface.setX(next.contentLeft);surface.setY(next.contentTop);
  }
  private interface IoAction{void run()throws IOException;}
  /** Pointer releases must always reach the device; only move samples may be shed. */
  private boolean submit(IoAction action){return submit(action,true,CONTROL_BUDGET_MS);}
  private boolean submit(IoAction action,boolean keepAlive,long budgetMs){if(closed.get())return false;
    return controls.submit(()->{if(closed.get())return;controlSince.set(android.os.SystemClock.uptimeMillis());
      try{action.run();}catch(Exception error){fail(error,true);}finally{controlSince.set(0);}},keepAlive,budgetMs);}
  private boolean forward(MotionEvent e,float offsetX,float offsetY){if(cropGate==null)return true;
    final long touchGeneration=cropGate.generation();
    VideoGeometry g=geometry;if(g==null||closed.get()||!cropGate.allowsTouch(touchGeneration))return true;
    int action=e.getActionMasked();int start=action==MotionEvent.ACTION_MOVE?0:e.getActionIndex();int end=action==MotionEvent.ACTION_MOVE?e.getPointerCount():start+1;
    for(int i=start;i<end;i++){TouchSample sample=new TouchSample(action,e.getPointerId(i),e.getX(i)+offsetX,e.getY(i)+offsetY,e.getPressure(i));
      boolean move=sample.action==TouchSample.MOVE;
      if(move&&!movePending.compareAndSet(false,true))continue;
      // Move samples carry no state the device needs; a release must never be dropped.
      boolean queued=submit(()->{try{if(touch!=null&&cropGate.allowsTouch(touchGeneration))touch.onTouch(sample,g);}finally{if(move)movePending.set(false);}},!move,CONTROL_BUDGET_MS);
      if(!queued&&move)movePending.set(false);}
    return true;
  }
  private void cancelTouch(){VideoGeometry g=geometry;submit(()->{if(touch!=null)touch.onTouch(new TouchSample(3,0,0,0,0),g);});}
  @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);cancelTouch();geometry=null;if(streamWidth>0)validateGeometry(streamWidth,streamHeight);}
  @Override protected void onStart(){super.onStart();foreground.detached=()->{closeSession();finish();};foreground.start();}
  @Override protected void onStop(){foreground.detached=null;foreground.stop();if(!isChangingConfigurations()&&!isFinishing()){closeSession();finish();}super.onStop();}
  @Override protected void onResume(){super.onResume();resumed=true;attachSurfaceIfReady();}
  @Override protected void onPause(){resumed=false;cancelTouch();if(decoder!=null&&!closed.get())decoder.attach(null);super.onPause();}
  /** The projection stream ended or stalled; the ADB link itself may still be healthy. */
  static final class LinkLost extends IOException{LinkLost(String message){super(message);}}
  private static final int MAX_RECONNECTS=5;
  private static int reconnectAttempts;
  private static long reconnectAt;
  private static boolean isLinkFailure(Exception error){
    // Anything raised by our own stream/watchdog paths is a lost projection by construction, so
    // it is classified by type; library errors are only recognised by their message.
    if(error instanceof LinkLost)return true;
    String message=error.getMessage();if(message==null)return false;
    return message.contains("Stream closed")||message.contains("Connection reset")||message.contains("Broken pipe")
      ||message.contains("Socket closed")||message.contains("timed out");
  }
  private static boolean reserveReconnect(){
    long now=android.os.SystemClock.uptimeMillis();
    if(now-reconnectAt>30000)reconnectAttempts=0;
    if(reconnectAttempts>=MAX_RECONNECTS)return false;
    reconnectAttempts++;reconnectAt=now;return true;
  }
  private void fail(Exception error){fail(error,false);}
  private void fail(Exception error,boolean checkAdb){runOnUiThread(()->{
    if(closed.get())return;
    foreground.demo.failed();
    com.example.carmirror.adb.DeviceEntry selected=ConnectionOwner.catalog().selected();
    // Recovery is automatic for both transports: USB has no endpoint to redial, so the
    // selection itself is handed back to the foreground owner, which re-requests access.
    boolean recover=resumed&&checkAdb&&isLinkFailure(error)&&selected!=null&&reserveReconnect();
    if(recover){
      final int attempt=reconnectAttempts;
      android.util.Log.w("MirrorReconnect","link failure: "+error.getMessage()+" attempt="+attempt+" usb="+(selected.usb!=null));
      status.setText("连接中断，正在自动恢复（第 "+attempt+"/"+MAX_RECONNECTS+" 次）…");
      status.setVisibility(View.VISIBLE);
      closeSession();
      startReconnect(attempt,selected);
      return;
    }
    String detail=isLinkFailure(error)?"ADB 连接已断开":error.getMessage();
    if(detail==null||detail.trim().isEmpty())detail=error.getClass().getSimpleName();
    android.util.Log.w("CarMirrorMirror","mirror failed: "+detail+" ("+error.getClass().getSimpleName()+", checkAdb="+checkAdb+")",error);
    status.setText("投屏失败："+detail+"\n请按住 × 3秒退出后重新连接");
    status.setVisibility(View.VISIBLE);
    closeSession();
    if(checkAdb)ConnectionOwner.checkAfterStreamFailure(adb);
  });}
  /** True when a round trip still completes; a dead transport times this out. */
  private boolean adbAlive(){
    try{return !adb.shell("true",3000).timedOut();}catch(Exception error){return false;}
  }
  /**
   * Restarts this screen once the link is usable again, bounded to MAX_RECONNECTS.
   *
   * <p>A projection stream can die while ADB is still fine; probing it first avoids tearing down
   * a healthy connection, which is the difference between a sub-second restart and a full
   * reconnect including the USB permission prompt.
   */
  private void startReconnect(final int attempt,final com.example.carmirror.adb.DeviceEntry entry){
    new Thread(()->{
      if(!adbAlive()){
        android.util.Log.w("MirrorReconnect","ADB is gone, reconnecting");
        if(entry.usb!=null)foreground.connect(entry);
        else ConnectionOwner.reconnect();
        long deadline=android.os.SystemClock.uptimeMillis()+15000;
        while(android.os.SystemClock.uptimeMillis()<deadline&&!ConnectionOwner.isConnected()){
          if(!resumed||isFinishing()||isDestroyed())return;
          try{Thread.sleep(200);}catch(InterruptedException e){return;}
        }
      }
      if(ConnectionOwner.isConnected()){
        android.util.Log.w("MirrorReconnect","link usable, restarting mirror");
        runOnUiThread(()->{if(resumed&&!isFinishing()&&!isDestroyed())recreate();});
        return;
      }
      if(reserveReconnect()){
        final int next=reconnectAttempts;
        android.util.Log.w("MirrorReconnect","retry attempt="+next);
        runOnUiThread(()->{if(!resumed||isFinishing()||isDestroyed())return;status.setText("连接中断，正在自动恢复（第 "+next+"/"+MAX_RECONNECTS+" 次）…");});
        startReconnect(next,entry);
        return;
      }
      runOnUiThread(()->{if(!resumed||isFinishing()||isDestroyed())return;status.setText("投屏失败：ADB 连接已断开\n请按住 × 3 秒退出后重新连接");status.setVisibility(View.VISIBLE);});
    },"mirror-reconnect").start();
  }
  /** All shutdown paths converge here. Closing scrcpy channels releases reads while ADB stays connected. */
  private void closeSession(){if(!closed.compareAndSet(false,true))return;handler.removeCallbacks(health);geometry=null;controls.stop();video.shutdownNow();validation.shutdownNow();
    final ScrcpySession current; synchronized(this){current=session;}
    new Thread(()->{if(current!=null)current.stop();if(decoder!=null)decoder.close();},"mirror-close").start();
  }
  @Override protected void onDestroy(){closeSession();super.onDestroy();}
  private void attachSurfaceIfReady(){if(resumed&&decoder!=null&&!closed.get()&&surface.getHolder().getSurface().isValid())decoder.attach(surface.getHolder().getSurface());}
  @Override public void surfaceCreated(SurfaceHolder holder){attachSurfaceIfReady();}
  @Override public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){}
  @Override public void surfaceDestroyed(SurfaceHolder holder){cancelTouch();if(decoder!=null&&!closed.get())decoder.attach(null);}
}
