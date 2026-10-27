package com.example.carmirror.adb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.example.carmirror.Endpoint;
import com.example.carmirror.adb.usb.*;
import java.util.List;

/** Process-scoped ADB owner: activities borrow one client; explicit Disconnect closes it. */
public final class ConnectionOwner {
  private static volatile AdbSession session;
  private static long connectionEpoch;
  private static volatile boolean connected;
  private static volatile String message="未连接";
  private static AdbGateway.Listener observer;
  private static AdbKeyStore keys;
  private static final DeviceCatalog catalog=new DeviceCatalog();
  private static UsbCandidate activeUsb;
  private static UsbIo activeIo;
  public static DeviceCatalog catalog(){return catalog;}
  /** Forward repository snapshots here before refreshing UI. Missing active USB cancels the session. */
  public static synchronized void updateUsb(List<UsbCandidate> candidates){
    if(activeUsb!=null&&!candidates.contains(activeUsb))usbDetached(activeUsb);
    catalog.updateUsb(candidates);
  }
  private static void releaseUsb(){
    UsbIo io=activeIo; activeIo=null; activeUsb=null;
    if(io!=null){Thread t=new Thread(()->{try{io.close();}catch(java.io.IOException ignored){}},"usb-owner-close");t.setDaemon(true);t.start();}
  }
  private ConnectionOwner(){}
  public static synchronized AdbSession get(Context context){
    if(session==null){
      if((context.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)redirectStderr();
      java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(),1);
      keys=new AdbKeyStore(context.getApplicationContext().getFilesDir());
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
      catalog.connectionState(state,text);
      target=observer;
    }
    if(target!=null)target.onState(state,text);
  }
  /** Last selected endpoint, kept so a dropped link can reconnect without user input. */
  private static volatile Endpoint lastEndpoint;
  public static synchronized void connect(Endpoint endpoint){connectTcp(endpoint);}
  public static synchronized void connectTcp(Endpoint endpoint){
    releaseUsb();
    catalog.select(catalog.saveTcp(endpoint));
    lastEndpoint=endpoint;
    connectionEpoch++;
    update(ConnectionState.CONNECTING,"正在连接 " + endpoint.serial());
    session.connect(endpoint,ConnectionOwner::update);
  }
  /** IO must already have system permission; expensive transport/key setup runs on session worker. */
  public static synchronized void connectUsb(UsbCandidate candidate,UsbIo io){
    if(session==null)throw new IllegalStateException("Call get(context) first");
    releaseUsb(); lastEndpoint=null; activeUsb=candidate; activeIo=io;
    boolean found=false;
    for(DeviceEntry e:catalog.entries())if(e.id.equals(DeviceEntry.usbId(candidate)))found=true;
    if(!found){
      java.util.ArrayList<UsbCandidate> all=new java.util.ArrayList<>();
      for(DeviceEntry e:catalog.entries())if(e.usb!=null)all.add(e.usb);
      all.add(candidate);catalog.updateUsb(all);
    }
    catalog.select(DeviceEntry.usbId(candidate));
    catalog.usbPermission(DeviceEntry.usbId(candidate),true);
    connectionEpoch++;
    Log.i("CarMirrorADB","connecting over USB: "+candidate);
    update(ConnectionState.CONNECTING,"正在连接 "+candidate);
    session.connect(candidate.toString(),()->{
      try{return new UsbAdbTransport(io,keys.load());}
      catch(Exception e){try{io.close();}catch(java.io.IOException ignored){}throw e;}
    },ConnectionOwner::update);
  }
  /** USB reconnect requires a new authorized repository.open(candidate), never reuse closed IO. */
  public static synchronized void usbDetached(UsbCandidate candidate){
    if(!candidate.equals(activeUsb))return;
    disconnect();
    java.util.ArrayList<UsbCandidate> remaining=new java.util.ArrayList<>();
    for(DeviceEntry e:catalog.entries())if(e.usb!=null&&!e.usb.equals(candidate))remaining.add(e.usb);
    catalog.updateUsb(remaining);
  }
  /** Starts a fresh connection to the last endpoint. False when nothing was selected yet. */
  public static synchronized boolean reconnect(){
    Endpoint endpoint=lastEndpoint;
    if(endpoint==null||session==null)return false;
    connect(endpoint);
    return true;
  }
  public static synchronized void disconnect(){
    releaseUsb();
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
