package com.example.carmirror.adb;

import static org.junit.Assert.*;

import com.example.carmirror.Endpoint;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ConnectionOwnerTest {
  private AdbSession session;
  private FakeTransport transport;

  @Before public void setUp() throws Exception {
    transport=new FakeTransport();
    session=new AdbSession(endpoint->transport,Runnable::run);
    field("session").set(null,session);
    field("connected").setBoolean(null,false);
    field("message").set(null,"未连接");
    field("connectionEpoch").setLong(null,0);
    ConnectionOwner.observe(null);
  }

  @After public void tearDown() throws Exception {
    ConnectionOwner.observe(null);
    session.disconnect();
    field("session").set(null,null);
    field("connected").setBoolean(null,false);
    field("message").set(null,"未连接");
  }

  @Test public void explicitDisconnectImmediatelyClearsConnectedState() throws Exception {
    connect();
    ConnectionOwner.disconnect();
    assertFalse(ConnectionOwner.isConnected());
    assertEquals("已断开",ConnectionOwner.message());
  }

  @Test public void brokenAdbProbeClearsConnectedState() throws Exception {
    connect();
    CountDownLatch error=new CountDownLatch(1);
    ConnectionOwner.observe((state,message)->{if(state==ConnectionState.ERROR)error.countDown();});
    transport.broken=true;
    ConnectionOwner.checkAfterStreamFailure(session);
    assertTrue("probe must report the real ADB failure",error.await(2,TimeUnit.SECONDS));
    assertFalse(ConnectionOwner.isConnected());
    assertTrue(ConnectionOwner.message().contains("network gone"));
  }

  @Test public void healthyAdbProbePreservesConnectedState() throws Exception {
    connect();
    CountDownLatch probed=new CountDownLatch(1);
    transport.probed=probed;
    ConnectionOwner.checkAfterStreamFailure(session);
    assertTrue("probe should reach the shared ADB transport",probed.await(2,TimeUnit.SECONDS));
    assertTrue(ConnectionOwner.isConnected());
  }

  @Test public void detachedUsbCancelsPendingConnectionAndDoesNotReconnectTcp() throws Exception {
    connect();
    com.example.carmirror.adb.usb.UsbCandidate candidate=DeviceCatalogTest.usb();
    CountDownLatch ioClosed=new CountDownLatch(1);
    com.example.carmirror.adb.usb.UsbIo io=new com.example.carmirror.adb.usb.UsbIo(){
      public int read(byte[] b,int o,int n,int t){return 0;}
      public int write(byte[] b,int o,int n,int t){return n;}
      public void close(){ioClosed.countDown();}
    };
    ConnectionOwner.connectUsb(candidate,io);
    ConnectionOwner.usbDetached(candidate);
    assertFalse(ConnectionOwner.isConnected());
    assertFalse(ConnectionOwner.reconnect());
    assertTrue(ioClosed.await(2,TimeUnit.SECONDS));
  }

  @Test public void tcpSwitchReleasesUsbIoAndRestoresTcpReconnect() throws Exception {
    CountDownLatch closed=new CountDownLatch(1);
    ConnectionOwner.connectUsb(DeviceCatalogTest.usb(),new com.example.carmirror.adb.usb.UsbIo(){
      public int read(byte[] b,int o,int n,int t){return 0;}
      public int write(byte[] b,int o,int n,int t){return n;}
      public void close(){closed.countDown();}
    });
    connect(); assertTrue(closed.await(2,TimeUnit.SECONDS));
    assertEquals(DeviceEntry.tcpId(Endpoint.parse("192.168.42.147","5555")),ConnectionOwner.catalog().selectedId());
    assertTrue(ConnectionOwner.reconnect());
  }

  private void connect() throws Exception {
    CountDownLatch ready=new CountDownLatch(1);
    ConnectionOwner.observe((state,message)->{if(state==ConnectionState.CONNECTED)ready.countDown();});
    ConnectionOwner.connect(Endpoint.parse("192.168.42.147","5555"));
    assertTrue("fake ADB should connect",ready.await(2,TimeUnit.SECONDS));
    assertTrue(ConnectionOwner.isConnected());
  }

  private static Field field(String name) throws Exception {
    Field field=ConnectionOwner.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static final class FakeTransport implements AdbSession.Transport {
    volatile boolean broken;
    volatile CountDownLatch probed;
    @Override public boolean connect(long timeoutMs){return true;}
    @Override public AdbChannel open(String destination) throws IOException {
      if(broken)throw new IOException("network gone");
      if(!destination.equals("shell:"))throw new AssertionError("unexpected service " + destination);
      CountDownLatch latch=probed;
      if(latch!=null)latch.countDown();
      return new AdbChannel(new ByteArrayInputStream("__CAR_MIRROR_EXIT__0\n".getBytes("UTF-8")),new ByteArrayOutputStream(),()->{});
    }
    @Override public void close(){}
  }
}
