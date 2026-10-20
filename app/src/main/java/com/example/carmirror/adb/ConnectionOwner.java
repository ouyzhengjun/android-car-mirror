package com.example.carmirror.adb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.example.carmirror.Endpoint;

/** Process-scoped ADB owner: activities borrow one client; explicit Disconnect closes it. */
public final class ConnectionOwner {
  private static volatile AdbSession session;
  private static long connectionEpoch;
  private static volatile boolean connected;
  private static volatile String message="未连接";
  private static AdbGateway.Listener observer;
  private ConnectionOwner(){}
  public static synchronized AdbSession get(Context context){
    if(session==null){
      if((context.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)redirectStderr();
      java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(),1);
      AdbKeyStore keys=new AdbKeyStore(context.getApplicationContext().getFilesDir());
      Handler main=new Handler(Looper.getMainLooper());
      session=new AdbSession(endpoint->new LibAdbTransport(endpoint,keys),main::post);
    }
    return session;
  }
  /** App processes send stderr to /dev/null, hiding library thread failures from logcat. */
  private static void redirectStderr(){
    System.setErr(new java.io.PrintStream(new java.io.OutputStream(){
      private final StringBuilder pending=new StringBuilder();
      @Override public synchronized void write(int b){
        if(b=='\n'){android.util.Log.e("SystemErr",pending.toString());pending.setLength(0);}
        else if(b!='\r')pending.append((char)b);
      }
    },true));
  }
  public static boolean isConnected(){return connected;}
  public static String message(){return message;}
  public static synchronized void observe(AdbGateway.Listener listener){observer=listener;}
  private static void update(ConnectionState state,String text){
    final AdbGateway.Listener target;
    synchronized(ConnectionOwner.class){
      connected=state==ConnectionState.CONNECTED;
      message=text;
      target=observer;
    }
    if(target!=null)target.onState(state,text);
  }
  public static synchronized void connect(Endpoint endpoint){
    connectionEpoch++;
    update(ConnectionState.CONNECTING,"正在连接 " + endpoint.serial());
    session.connect(endpoint,ConnectionOwner::update);
  }
  public static synchronized void disconnect(){
    connectionEpoch++;
    update(ConnectionState.DISCONNECTED,"已断开");
    session.disconnect();
  }
  /** A projection stream can fail while ADB remains usable. Probe it before changing device state. */
  public static void checkAfterStreamFailure(AdbSession expected){
    final long epoch;
    synchronized(ConnectionOwner.class){
      if(!isConnected() || session!=expected)return;
      epoch=connectionEpoch;
    }
    Thread probe=new Thread(()->{
      try{
        CommandResult result=expected.shell("true",4000);
        if(result.timedOut()){
          synchronized(ConnectionOwner.class){
            if(session==expected && connectionEpoch==epoch && isConnected()){
              connected=false;
              message="ADB 连接中断：设备探测超时，请重新连接";
              connectionEpoch++;
              expected.disconnect();
            }
          }
        }
      }catch(java.io.IOException ignored){
        // AdbSession reports transport failures through its installed listener.
      }
    },"adb-stream-probe");
    probe.setDaemon(true);
    probe.start();
  }
}
