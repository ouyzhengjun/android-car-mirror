package com.example.carmirror.ui;
import android.content.Context;
import android.os.*;
import com.example.carmirror.adb.*;
import com.example.carmirror.adb.usb.*;
import java.util.*;
import java.util.concurrent.*;
/** Process owner spanning home -> mirror transitions, never a background service. */
public final class ForegroundDevices {
  private static ForegroundDevices instance;
  public static synchronized ForegroundDevices get(Context c){if(instance==null)instance=new ForegroundDevices(c.getApplicationContext());return instance;}
  private final Context context;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  public final DemoModeController demo=new DemoModeController();
  private UsbDeviceRepository repository;
  private int started, generation;
  private boolean homeResumed;
  public Runnable changed, launch, detached;
  public String message="未发现 USB ADB 设备；等待 OTG 连接";
  private ForegroundDevices(Context c){context=c;ConnectionOwner.get(c);}
  public void start(){started++;main.removeCallbacks(stop);if(repository==null){repository=new UsbDeviceRepository(context);repository.setListener(this::candidates);candidates(repository.scan());}ConnectionOwner.observe((state,text)->{message=text;if(state==ConnectionState.CONNECTED)demo.connected(ConnectionOwner.catalog().selectedId());if(state==ConnectionState.ERROR)demo.failed();refresh();pump();});}
  public void stop(){started=Math.max(0,started-1);if(started==0)main.postDelayed(stop,150);}
  private final Runnable stop=()->{if(started!=0)return;generation++;demo.foreground(false);ConnectionOwner.observe(null);if(repository!=null){UsbDeviceRepository old=repository;repository=null;old.close();}DeviceEntry e=ConnectionOwner.catalog().selected();if(e!=null&&e.usb!=null){ConnectionOwner.disconnect();demo.candidates(Collections.emptyList());}};
  public void home(boolean resumed){homeResumed=resumed;demo.foreground(resumed);if(resumed){if(ConnectionOwner.isConnected())demo.connected(ConnectionOwner.catalog().selectedId());pump();}}
  private void candidates(List<UsbCandidate> list){DeviceEntry active=ConnectionOwner.catalog().selected();boolean gone=active!=null&&active.usb!=null&&!list.contains(active.usb);ConnectionOwner.updateUsb(list);List<String> ids=new ArrayList<>();for(UsbCandidate c:list)ids.add(DeviceEntry.usbId(c));demo.candidates(ids);if(gone){generation++;message="USB 已拔出，等待重新连接";if(detached!=null)detached.run();}refresh();pump();}
  public void refresh(){if(changed!=null)changed.run();}
  public void pump(){if(!homeResumed||repository==null)return;DemoModeController.Action a=demo.next();if(a==DemoModeController.Action.CONNECT){for(DeviceEntry e:ConnectionOwner.catalog().entries())if(e.id.equals(demo.target())){connect(e);break;}}else if(a==DemoModeController.Action.MIRROR){if(launch!=null)launch.run();}else if(a==DemoModeController.Action.CHOOSE){message="发现多个 USB ADB 设备，请选择后连接";}else if(a==DemoModeController.Action.WAIT){message="未发现 USB ADB 设备；等待 OTG 连接";}refresh();}
  public void connect(DeviceEntry entry){if(repository==null)return;final int ticket=++generation;if(entry.tcp!=null){demo.enabled(false);ConnectionOwner.connectTcp(entry.tcp);refresh();return;}ConnectionOwner.disconnect();ConnectionOwner.catalog().select(entry.id);message="等待系统 USB 授权："+entry.label;refresh();UsbDeviceRepository repo=repository;repo.requestPermission(entry.usb,(c,granted)->{if(ticket!=generation||repository!=repo)return;android.util.Log.i("CarMirrorUsb","permission granted="+granted+" for "+entry.label);ConnectionOwner.catalog().usbPermission(entry.id,granted);if(!granted){message="系统 USB 授权被拒绝或设备已拔出；可重新选择并连接";demo.failed();refresh();return;}message="系统 USB 已授权，正在连接；请在车机确认 RSA 授权";refresh();worker.execute(()->{try{UsbIo io=repo.open(c);main.post(()->{if(ticket!=generation||repository!=repo||started==0){worker.execute(()->{try{io.close();}catch(Exception ignored){}});return;}ConnectionOwner.connectUsb(c,io);});}catch(Exception e){android.util.Log.e("CarMirrorUsb","USB open failed",e);main.post(()->{if(ticket!=generation)return;message="USB 连接失败："+e.getMessage();demo.failed();refresh();});}});});}
  public void cancel(){generation++;demo.enabled(false);ConnectionOwner.disconnect();message="已断开";refresh();}
}
