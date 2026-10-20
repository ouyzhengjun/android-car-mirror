package com.example.carmirror.scrcpy;

import static org.junit.Assert.*;

import com.example.carmirror.Endpoint;
import com.example.carmirror.adb.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public class ScrcpyLauncherTest {
  static class Device implements AdbGateway {
    List<String> events = new CopyOnWriteArrayList<>();
    List<String> closed = new CopyOnWriteArrayList<>();
    ByteArrayOutputStream command = new ByteArrayOutputStream();
    boolean blockControl, failFirst;
    volatile int sockets;

    public void connect(Endpoint e, Listener l) {}

    public void disconnect() {
      fail("launcher must not disconnect shared ADB");
    }

    public CommandResult shell(String c, long t) {
      throw new AssertionError();
    }

    public void push(InputStream in, long size, String path) throws IOException {
      assertEquals(3, size);
      assertEquals(1, in.read());
      events.add("push:" + path);
    }

    public AdbChannel openService(String destination) throws IOException {
      events.add(destination);
      if (destination.equals("shell:")) return channel("shell", new byte[0], command);
      if (failFirst) {
        failFirst = false;
        throw new IOException("not listening yet");
      }
      int number = ++sockets;
      if (number == 2 && blockControl) {
        try {
          new CountDownLatch(1).await();
        } catch (InterruptedException e) {
          throw new IOException(e);
        }
      }
      return channel(
          "socket" + number,
          number == 1 ? new byte[] {0, 42} : new byte[0],
          new ByteArrayOutputStream());
    }

    AdbChannel channel(String name, byte[] data, OutputStream out) {
      return new AdbChannel(new ByteArrayInputStream(data), out, () -> closed.add(name));
    }
  }

  ScrcpySession launcher(long timeout) {
    return new ScrcpyLauncher(() -> new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, timeout);
  }

  @Test
  public void pushesStartsAndConnectsVideoBeforeControl() throws Exception {
    Device device = new Device();
    ScrcpySession session = launcher(1000);
    ScrcpySession.Streams streams = session.start(device);
    assertTrue(device.events.get(0).startsWith("push:/data/local/tmp/scrcpy-server-"));
    assertEquals("shell:", device.events.get(1));
    assertTrue(device.events.get(2).matches("localabstract:scrcpy_[0-7][0-9a-f]{7}"));
    assertEquals(device.events.get(2), device.events.get(3));
    String command = device.command.toString("UTF-8");
    assertTrue(command.contains("com.genymobile.scrcpy.Server 4.0 "));
    assertTrue(command.contains("tunnel_forward=true"));
    assertTrue(command.contains("audio=false"));
    assertTrue(
        command.contains(
            "scid=" + device.events.get(2).substring("localabstract:scrcpy_".length())));
    assertEquals(42, streams.video().input().read());
    assertNotNull(streams.control());
    session.stop();
    session.stop();
    assertEquals(3, device.closed.size());
  }

  @Test
  public void pollsUntilServerListens() throws Exception {
    Device d = new Device();
    d.failFirst = true;
    ScrcpySession s = launcher(1000);
    s.start(d);
    s.stop();
    assertEquals(5, d.events.size());
  }

  @Test
  public void controlTimeoutClosesVideoAndProcess() throws Exception {
    Device d = new Device();
    d.blockControl = true;
    ScrcpySession s = launcher(80);
    try {
      s.start(d);
      fail("timeout expected");
    } catch (IOException expected) {
    }
    s.stop();
    assertTrue(d.closed.contains("shell"));
    assertTrue(d.closed.contains("socket1"));
  }

  @Test
  public void stopCancelsPendingStartup() throws Exception {
    Device d = new Device();
    d.blockControl = true;
    ScrcpySession s = launcher(5000);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    Future<?> f =
        worker.submit(
            () -> {
              try {
                s.start(d);
                fail();
              } catch (IOException expected) {
              }
            });
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (d.sockets < 2 && System.nanoTime() < deadline) Thread.sleep(2);
      s.stop();
      f.get(1, TimeUnit.SECONDS);
      assertEquals(2, d.closed.size());
    } finally {
      s.stop();
      worker.shutdownNow();
    }
  }

  @Test
  public void lateSocketAfterTimeoutIsClosed() throws Exception {
    CountDownLatch release = new CountDownLatch(1), arrived = new CountDownLatch(1);
    Device d =
        new Device() {
          @Override
          public AdbChannel openService(String destination) throws IOException {
            if (destination.startsWith("localabstract:")) {
              while (release.getCount() > 0) {
                try {
                  release.await();
                } catch (InterruptedException ignored) {
                }
              }
              return channel("late", new byte[] {0}, new ByteArrayOutputStream());
            }
            return super.openService(destination);
          }

          @Override
          AdbChannel channel(String name, byte[] data, OutputStream out) {
            if (!name.equals("late")) return super.channel(name, data, out);
            return new AdbChannel(
                new ByteArrayInputStream(data),
                out,
                () -> {
                  closed.add(name);
                  arrived.countDown();
                });
          }
        };
    ScrcpySession s = launcher(80);
    try {
      s.start(d);
      fail();
    } catch (IOException expected) {
    }
    release.countDown();
    assertTrue(arrived.await(1, TimeUnit.SECONDS));
    assertTrue(d.closed.contains("shell"));
    s.stop();
  }

  @Test
  public void invalidReadinessClosesAllAcquiredChannels() throws Exception {
    Device d =
        new Device() {
          @Override
          AdbChannel channel(String name, byte[] data, OutputStream out) {
            return super.channel(name, new byte[] {1}, out);
          }
        };
    ScrcpySession s = launcher(1000);
    try {
      s.start(d);
      fail();
    } catch (IOException expected) {
    }
    assertTrue(d.closed.contains("shell"));
    assertTrue(d.closed.contains("socket1"));
    assertEquals(1, d.sockets);
    s.stop();
  }

  @Test
  public void fallsBackToSharedStorageWhenTmpIsNotWritable() throws Exception {
    List<String> attempts = new CopyOnWriteArrayList<>();
    Device d =
        new Device() {
          @Override
          public void push(InputStream in, long size, String path) throws IOException {
            attempts.add(path);
            if (path.startsWith("/data/local/tmp/")) throw new IOException("Permission denied");
            byte[] b = new byte[(int) size];
            assertEquals(size, in.read(b));
            events.add("push:" + path);
          }
        };
    ScrcpySession s = launcher(1000);
    try {
      ScrcpySession.Streams streams = s.start(d);
      assertNotNull(streams);
    } finally {
      s.stop();
    }
    assertEquals(2, attempts.size());
    assertTrue(attempts.get(0).startsWith("/data/local/tmp/scrcpy-server-"));
    assertTrue(attempts.get(1).matches("/sdcard/scrcpy-server-[0-7][0-9a-f]{7}\\.jar"));
    assertTrue(d.command.toString("UTF-8").contains("CLASSPATH=" + attempts.get(1)));
  }

  @Test
  public void reportsUploadReasonWhenNoRemoteDirectoryIsWritable() throws Exception {
    Device d =
        new Device() {
          @Override
          public void push(InputStream in, long size, String path) throws IOException {
            throw new IOException("Permission denied");
          }
        };
    ScrcpySession s = launcher(1000);
    try {
      s.start(d);
      fail("upload failure expected");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("Permission denied"));
    }
    assertTrue(d.events.isEmpty());
    s.stop();
  }

  @Test
  public void bundledOfficialServerMatchesPinnedDigest() throws Exception {
    java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
    File asset = new File("src/main/assets/scrcpy-server-v4.0");
    assertEquals(732226, asset.length());
    try (InputStream in = new FileInputStream(asset)) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) sha.update(b, 0, n);
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : sha.digest()) hex.append(String.format("%02x", b & 255));
    assertEquals(
        "84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a", hex.toString());
  }
}
