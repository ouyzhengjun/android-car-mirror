package com.example.carmirror.adb;

import com.example.carmirror.Endpoint;
import com.example.carmirror.adb.usb.UsbCandidate;
import java.util.*;

/** In-memory device directory; caller persists saved TCP endpoints. No discovery or auto-connect. */
public final class DeviceCatalog {
  private final Map<String,DeviceEntry> entries=new LinkedHashMap<>();
  private String selected;
  public synchronized String saveTcp(Endpoint e) {
    String id=DeviceEntry.tcpId(e);
    if(!entries.containsKey(id)) entries.put(id,new DeviceEntry(id,e.serial(),null,e,DeviceEntry.Status.DISCONNECTED,"未连接"));
    return id;
  }
  public synchronized void updateUsb(List<UsbCandidate> candidates) {
    Set<String> present=new HashSet<>();
    for(UsbCandidate c:candidates) {
      String id=DeviceEntry.usbId(c); present.add(id);
      DeviceEntry old=entries.get(id);
      entries.put(id,new DeviceEntry(id,c.toString(),c,null,old==null?DeviceEntry.Status.USB_PERMISSION_REQUIRED:old.status,old==null?"等待系统 USB 授权":old.message));
    }
    entries.values().removeIf(e->e.usb!=null&&!present.contains(e.id));
    if(selected!=null&&!entries.containsKey(selected)) selected=null;
  }
  public synchronized void select(String id) {
    if(!entries.containsKey(id))throw new IllegalArgumentException("Unknown device: "+id);
    if(!id.equals(selected)&&selected!=null) {
      DeviceEntry old=entries.get(selected);
      if(old!=null)entries.put(selected,old.state(DeviceEntry.Status.DISCONNECTED,"未连接"));
    }
    selected=id;
  }
  public synchronized String selectedId(){return selected;}
  public synchronized DeviceEntry selected(){return entries.get(selected);}
  public synchronized List<DeviceEntry> entries(){return Collections.unmodifiableList(new ArrayList<>(entries.values()));}
  public synchronized void usbPermission(String id,boolean granted){
    DeviceEntry e=entries.get(id);
    if(e!=null&&e.usb!=null)entries.put(id,e.state(granted?DeviceEntry.Status.USB_PERMISSION_GRANTED:DeviceEntry.Status.USB_PERMISSION_REQUIRED,granted?"系统 USB 已授权":"等待系统 USB 授权"));
  }
  public synchronized void connectionState(ConnectionState state,String text){
    DeviceEntry e=selected(); if(e==null)return;
    DeviceEntry.Status status;
    switch(state){
      case CONNECTING:status=DeviceEntry.Status.CONNECTING;break;
      case AUTHORIZING:status=DeviceEntry.Status.ADB_AUTHORIZING;break;
      case CONNECTED:status=DeviceEntry.Status.CONNECTED;break;
      case ERROR:status=DeviceEntry.Status.ERROR;break;
      default:status=DeviceEntry.Status.DISCONNECTED;
    }
    entries.put(e.id,e.state(status,text));
  }
}
