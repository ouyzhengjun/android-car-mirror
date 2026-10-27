package com.example.carmirror.adb;
import static org.junit.Assert.*;
import com.example.carmirror.Endpoint;
import com.example.carmirror.adb.usb.UsbCandidate;
import java.util.*;
import org.junit.Test;
public class DeviceCatalogTest {
 static UsbCandidate usb(){return UsbCandidate.fromInterface("/usb/1",1,1,2,"Car",null,0,255,66,1,new int[]{129,1},new int[]{2,2});}
 @Test public void savedTcpDoesNotSelectOrConnectAndSelectionIsExclusive(){
  DeviceCatalog c=new DeviceCatalog(); String tcp=c.saveTcp(Endpoint.parse("1.2.3.4","5555"));
  c.updateUsb(Arrays.asList(usb())); assertNull(c.selectedId());
  assertEquals(DeviceEntry.Status.DISCONNECTED,c.entries().get(0).status);
  c.select(tcp); c.select(DeviceEntry.usbId(usb()));
  assertEquals(DeviceEntry.usbId(usb()),c.selectedId());
 }
 @Test public void usbPermissionAndAdbAuthorizationAreSeparateAndDetachClearsSelection(){
  DeviceCatalog c=new DeviceCatalog(); c.updateUsb(Arrays.asList(usb())); String id=DeviceEntry.usbId(usb());
  c.select(id); c.usbPermission(id,false); assertEquals(DeviceEntry.Status.USB_PERMISSION_REQUIRED,c.selected().status);
  c.usbPermission(id,true); c.connectionState(ConnectionState.AUTHORIZING,"RSA");
  assertEquals(DeviceEntry.Status.ADB_AUTHORIZING,c.selected().status);
  c.updateUsb(Collections.emptyList()); assertNull(c.selectedId()); assertTrue(c.entries().isEmpty());
 }
}
