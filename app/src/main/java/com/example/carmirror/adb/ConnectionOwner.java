package com.example.carmirror.adb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.example.carmirror.Endpoint;

/** Process-scoped ADB owner: activities borrow one client; explicit Disconnect closes it. */
public final class ConnectionOwner {
  private static AdbSession session;
  private static boolean connected;
  private static String message="未连接";
  private static AdbGateway.Listener observer;
  private ConnectionOwner(){}
  public static synchronized AdbSession get(Context context){
    if(session==null){
      java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(),1);
      AdbKeyStore keys=new AdbKeyStore(context.getApplicationContext().getFilesDir());
      Handler main=new Handler(Looper.getMainLooper());
      session=new AdbSession(endpoint->new LibAdbTransport(endpoint,keys),main::post);
    }
    return session;
  }
  public static boolean isConnected(){return connected;}
  public static String message(){return message;}
  public static void observe(AdbGateway.Listener listener){observer=listener;}
  public static void connect(Endpoint endpoint){session.connect(endpoint,(state,text)->{
    connected=state==ConnectionState.CONNECTED;message=text;
    if(observer!=null)observer.onState(state,text);
  });}
}
