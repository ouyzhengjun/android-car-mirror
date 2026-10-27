package com.example.carmirror.adb;

import com.example.carmirror.Endpoint;
import com.example.carmirror.adb.usb.UsbCandidate;

/** Immutable dashboard snapshot. Selection belongs to DeviceCatalog. */
public final class DeviceEntry {
  public enum Status { DISCONNECTED, USB_PERMISSION_REQUIRED, USB_PERMISSION_GRANTED, CONNECTING, ADB_AUTHORIZING, CONNECTED, ERROR }
  public final String id, label, message;
  public final UsbCandidate usb;
  public final Endpoint tcp;
  public final Status status;
  DeviceEntry(String id, String label, UsbCandidate usb, Endpoint tcp, Status status, String message) {
    this.id=id; this.label=label; this.usb=usb; this.tcp=tcp; this.status=status; this.message=message;
  }
  public static String usbId(UsbCandidate c) { return "usb:"+c.path+":"+c.deviceId+":"+c.interfaceIndex; }
  public static String tcpId(Endpoint e) { return "tcp:"+e.serial(); }
  DeviceEntry state(Status status,String message) {return new DeviceEntry(id,label,usb,tcp,status,message);}
}
