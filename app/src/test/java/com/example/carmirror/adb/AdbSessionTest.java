package com.example.carmirror.adb;

import static org.junit.Assert.*;

import com.example.carmirror.Endpoint;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public class AdbSessionTest {
  static class Transport implements AdbSession.Transport {
    boolean closed;
    String destination;
    AdbChannel channel;

    public boolean connect(long timeout) {
      return true;
    }

    public AdbChannel open(String d) throws IOException {
      destination = d;
      return channel;
    }

    public void close() {
      closed = true;
    }
  }

  @Test public void supplierRunsOffCallerAndSwitchClosesOwnedStreams() throws Exception {
    Transport tcp = new Transport(), usb = new Transport();
    CountDownLatch closed = new CountDownLatch(1), first = new CountDownLatch(1), second = new CountDownLatch(1);
    tcp.channel = new AdbChannel(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), closed::countDown);
    AdbSession session = new AdbSession(e -> tcp, Runnable::run);
    session.connect(Endpoint.parse("127.0.0.1", "5555"), (s,m) -> {if(s==ConnectionState.CONNECTED)first.countDown();});
    assertTrue(first.await(2, TimeUnit.SECONDS)); session.openService("shell:");
    Thread caller = Thread.currentThread();
    session.connect("USB car", () -> {assertNotSame(caller,Thread.currentThread()); return usb;},
        (s,m) -> {if(s==ConnectionState.CONNECTED)second.countDown();});
    assertTrue(second.await(2, TimeUnit.SECONDS)); assertTrue(closed.await(2,TimeUnit.SECONDS));
    assertTrue(tcp.closed); session.disconnect();
  }

  @Test
  public void connectsThroughAuthorizationAndReplacesOldTransport() throws Exception {
    List<Transport> transports = new ArrayList<>();
    List<ConnectionState> states = new CopyOnWriteArrayList<>();
    AdbSession session =
        new AdbSession(
            e -> {
              Transport t = new Transport();
              transports.add(t);
              return t;
            },
            Runnable::run);
    CountDownLatch connected = new CountDownLatch(2);
    AdbGateway.Listener listener =
        (s, m) -> {
          states.add(s);
          if (s == ConnectionState.CONNECTED) connected.countDown();
        };
    session.connect(Endpoint.parse("127.0.0.1", "5555"), listener);
    while (transports.size() < 1 || !states.contains(ConnectionState.CONNECTED)) Thread.sleep(5);
    session.connect(Endpoint.parse("127.0.0.1", "5556"), listener);
    assertTrue(connected.await(2, TimeUnit.SECONDS));
    assertTrue(transports.get(0).closed);
    assertTrue(states.contains(ConnectionState.AUTHORIZING));
    session.disconnect();
  }

  @Test
  public void shellUsesShortServiceAndReturnsExitCode() throws Exception {
    Transport t = new Transport();
    ByteArrayOutputStream sent = new ByteArrayOutputStream();
    t.channel =
        new AdbChannel(
            new ByteArrayInputStream("hello\n__CAR_MIRROR_EXIT__7\n".getBytes("UTF-8")),
            sent,
            () -> {});
    AdbSession s = connected(t);
    CommandResult r = s.shell("echo hello", 1000);
    assertEquals("shell:", t.destination);
    assertEquals(7, r.exitCode());
    assertFalse(r.timedOut());
    assertTrue(r.output().contains("hello"));
    assertTrue(sent.toString("UTF-8").contains("echo hello"));
    s.disconnect();
  }

  @Test
  public void timeoutClosesBlockedChannel() throws Exception {
    Transport t = new Transport();
    CountDownLatch closed = new CountDownLatch(1);
    InputStream blocked =
        new InputStream() {
          public int read() throws IOException {
            try {
              closed.await();
            } catch (InterruptedException e) {
              throw new IOException(e);
            }
            return -1;
          }
        };
    t.channel = new AdbChannel(blocked, new ByteArrayOutputStream(), closed::countDown);
    AdbSession s = connected(t);
    assertTrue(s.shell("sleep 99", 30).timedOut());
    assertEquals(0, closed.getCount());
    s.disconnect();
  }

  @Test
  public void disconnectClosesOpenChannels() throws Exception {
    Transport t = new Transport();
    CountDownLatch closed = new CountDownLatch(1);
    t.channel =
        new AdbChannel(
            new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), closed::countDown);
    AdbSession s = connected(t);
    s.openService("localabstract:scrcpy");
    s.disconnect();
    assertTrue(closed.await(1, TimeUnit.SECONDS));
  }

  @Test
  public void failedAuthorizationClosesTransportAndReportsError() throws Exception {
    Transport t =
        new Transport() {
          public boolean connect(long timeout) {
            return false;
          }
        };
    CountDownLatch failed = new CountDownLatch(1);
    AdbSession s = new AdbSession(e -> t, Runnable::run);
    s.connect(
        Endpoint.parse("127.0.0.1", "5555"),
        (state, m) -> {
          if (state == ConnectionState.ERROR) failed.countDown();
        });
    assertTrue(failed.await(2, TimeUnit.SECONDS));
    assertTrue(t.closed);
    s.disconnect();
  }

  @Test
  public void commandStreamFailureReportsError() throws Exception {
    Transport t = new Transport();
    t.channel =
        new AdbChannel(
            new InputStream() {
              public int read() throws IOException {
                throw new IOException("lost transport");
              }
            },
            new ByteArrayOutputStream(),
            () -> {});
    CountDownLatch ready = new CountDownLatch(1), failed = new CountDownLatch(1);
    AdbSession s = new AdbSession(e -> t, Runnable::run);
    s.connect(
        Endpoint.parse("127.0.0.1", "5555"),
        (state, m) -> {
          if (state == ConnectionState.CONNECTED) ready.countDown();
          if (state == ConnectionState.ERROR) failed.countDown();
        });
    assertTrue(ready.await(2, TimeUnit.SECONDS));
    try {
      s.shell("id", 500);
      fail("expected failure");
    } catch (IOException expected) {
    }
    assertTrue(failed.await(1, TimeUnit.SECONDS));
    assertTrue(t.closed);
    s.disconnect();
  }

  @Test
  public void rejectsMultibyteServiceNamesBeforeCallingLibrary() throws Exception {
    Transport t = new Transport();
    AdbSession s = connected(t);
    try {
      s.openService("localabstract:车机");
      fail("expected invalid destination");
    } catch (IOException expected) {
    }
    assertNull(t.destination);
    s.disconnect();
  }

  @Test
  public void pushWritesSyncFraming() throws Exception {
    Transport t = new Transport();
    ByteArrayOutputStream sent = new ByteArrayOutputStream();
    t.channel =
        new AdbChannel(
            new ByteArrayInputStream(new byte[] {'O', 'K', 'A', 'Y', 0, 0, 0, 0}), sent, () -> {});
    AdbSession s = connected(t);
    s.push(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, "/data/local/tmp/test");
    assertEquals("sync:", t.destination);
    String bytes = sent.toString("ISO-8859-1");
    assertTrue(bytes.startsWith("SEND"));
    assertTrue(bytes.contains("/data/local/tmp/test,33206"));
    assertTrue(bytes.contains("DATA"));
    assertTrue(bytes.contains("DONE"));
    s.disconnect();
  }

  @Test
  public void credentialsSurviveStoreRecreation() throws Exception {
    File root = java.nio.file.Files.createTempDirectory("adb-keys").toFile();
    try {
      AdbKeyStore.Credentials first = new AdbKeyStore(root).load();
      AdbKeyStore.Credentials second = new AdbKeyStore(root).load();
      assertArrayEquals(first.privateKey.getEncoded(), second.privateKey.getEncoded());
      assertArrayEquals(first.certificate.getEncoded(), second.certificate.getEncoded());
      assertEquals("RSA", first.privateKey.getAlgorithm());
    } finally {
      for (File f : new File(root, "adb-identity").listFiles()) f.delete();
      new File(root, "adb-identity").delete();
      root.delete();
    }
  }

  @Test
  public void commandTimeoutIncludesServiceOpen() throws Exception {
    Transport t =
        new Transport() {
          @Override
          public AdbChannel open(String destination) {
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
              return null;
            }
            return null;
          }
        };
    AdbSession s = connected(t);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      assertTrue(worker.submit(() -> s.shell("id", 30)).get(500, TimeUnit.MILLISECONDS).timedOut());
    } finally {
      s.disconnect();
      worker.shutdownNow();
    }
  }

  private static class PacketLimitedOutput extends ByteArrayOutputStream {
    int largest;

    @Override
    public synchronized void write(byte[] data, int offset, int length) {
      if (length > 4096)
        throw new IllegalArgumentException("write exceeds READY-safe packet size: " + length);
      largest = Math.max(largest, length);
      super.write(data, offset, length);
    }
  }

  @Test
  public void largePushLimitsEveryUnderlyingWriteAndPreservesPayload() throws Exception {
    byte[] payload = new byte[150000];
    new Random(1234).nextBytes(payload);
    PacketLimitedOutput sent = new PacketLimitedOutput();
    Transport t = new Transport();
    t.channel =
        new AdbChannel(
            new ByteArrayInputStream(new byte[] {'O', 'K', 'A', 'Y', 0, 0, 0, 0}), sent, () -> {});
    AdbSession session = connected(t);
    try {
      session.push(new ByteArrayInputStream(payload), payload.length, "/data/local/tmp/server.jar");
      DataInputStream wire = new DataInputStream(new ByteArrayInputStream(sent.toByteArray()));
      ByteArrayOutputStream received = new ByteArrayOutputStream();
      while (wire.available() > 0) {
        byte[] id = new byte[4];
        wire.readFully(id);
        int length = Integer.reverseBytes(wire.readInt());
        String kind = new String(id, "US-ASCII");
        if (kind.equals("DONE")) break;
        byte[] data = new byte[length];
        wire.readFully(data);
        if (kind.equals("DATA")) received.write(data);
      }
      assertArrayEquals(payload, received.toByteArray());
      assertTrue(sent.largest <= 4096);
    } finally {
      session.disconnect();
    }
  }

  @Test
  public void longShellCommandLimitsEveryUnderlyingWrite() throws Exception {
    char[] chars = new char[20000];
    Arrays.fill(chars, 'x');
    PacketLimitedOutput sent = new PacketLimitedOutput();
    Transport t = new Transport();
    t.channel =
        new AdbChannel(
            new ByteArrayInputStream("__CAR_MIRROR_EXIT__0\n".getBytes("UTF-8")), sent, () -> {});
    AdbSession session = connected(t);
    try {
      assertEquals(0, session.shell(new String(chars), 1000).exitCode());
      assertTrue(sent.toString("UTF-8").contains(new String(chars)));
      assertTrue(sent.largest <= 4096);
    } finally {
      session.disconnect();
    }
  }

  @Test
  public void pushRemoteFailurePropagatesMessageAndClosesChannel() throws Exception {
    Transport t = new Transport();
    CountDownLatch closed = new CountDownLatch(1);
    ByteArrayOutputStream reply = new ByteArrayOutputStream();
    DataOutputStream protocol = new DataOutputStream(reply);
    byte[] reason = "permission denied".getBytes("UTF-8");
    protocol.writeBytes("FAIL");
    protocol.writeInt(Integer.reverseBytes(reason.length));
    protocol.write(reason);
    t.channel =
        new AdbChannel(
            new ByteArrayInputStream(reply.toByteArray()),
            new ByteArrayOutputStream(),
            closed::countDown);
    AdbSession session = connected(t);
    try {
      session.push(new ByteArrayInputStream(new byte[] {1}), 1, "/forbidden");
      fail("expected remote failure");
    } catch (IOException expected) {
      assertEquals("permission denied", expected.getMessage());
      assertEquals(0, closed.getCount());
    } finally {
      session.disconnect();
    }
  }

  @Test
  public void scrcpySocketRefusalKeepsSharedTransportForRetry() throws Exception {
    Transport t =
        new Transport() {
          boolean refused;

          public AdbChannel open(String destination) throws IOException {
            if (!refused) {
              refused = true;
              throw new java.net.ConnectException("not listening");
            }
            return new AdbChannel(
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), () -> {});
          }
        };
    AdbSession s = connected(t);
    try {
      try {
        s.openService("localabstract:scrcpy_12345678");
        fail();
      } catch (IOException expected) {
      }
      assertFalse(t.closed);
      s.openService("localabstract:scrcpy_12345678").close();
    } finally {
      s.disconnect();
    }
  }

  @Test
  public void scrcpyTransportFailureStillDisconnects() throws Exception {
    Transport t =
        new Transport() {
          public AdbChannel open(String destination) throws IOException {
            throw new IOException("transport lost");
          }
        };
    AdbSession s = connected(t);
    try {
      try {
        s.openService("localabstract:scrcpy_12345678");
        fail();
      } catch (IOException expected) {
      }
      assertTrue(t.closed);
    } finally {
      s.disconnect();
    }
  }

  @Test
  public void shellRefusalStillDisconnects() throws Exception {
    Transport t =
        new Transport() {
          public AdbChannel open(String destination) throws IOException {
            throw new java.net.ConnectException("shell refused");
          }
        };
    AdbSession s = connected(t);
    try {
      try {
        s.openService("shell:");
        fail();
      } catch (IOException expected) {
      }
      assertTrue(t.closed);
    } finally {
      s.disconnect();
    }
  }

  @Test
  public void blockedFactoryTimesOutAndLateTransportCannotReplaceNewConnection() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    CountDownLatch error = new CountDownLatch(1), ready = new CountDownLatch(1);
    Transport late = new Transport(), replacement = new Transport();
    java.util.concurrent.atomic.AtomicInteger created = new java.util.concurrent.atomic.AtomicInteger();
    AdbSession session =
        new AdbSession(
            endpoint -> {
              if (created.getAndIncrement() == 0) {
                entered.countDown();
                while (release.getCount() != 0) {
                  try {
                    release.await();
                  } catch (InterruptedException ignored) {
                    // Model a library socket constructor that does not respond to interruption.
                  }
                }
                return late;
              }
              return replacement;
            },
            Runnable::run,
            60);
    session.connect(
        Endpoint.parse("127.0.0.1", "5555"),
        (state, message) -> {
          if (state == ConnectionState.ERROR) error.countDown();
        });
    assertTrue(entered.await(1, TimeUnit.SECONDS));
    assertTrue("factory creation must have a deadline", error.await(1, TimeUnit.SECONDS));
    session.connect(
        Endpoint.parse("127.0.0.1", "5556"),
        (state, message) -> {
          if (state == ConnectionState.CONNECTED) ready.countDown();
        });
    assertTrue(ready.await(1, TimeUnit.SECONDS));
    release.countDown();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (!late.closed && System.nanoTime() < deadline) Thread.sleep(5);
    assertTrue("late factory result must be closed", late.closed);
    assertFalse("new connection must remain open", replacement.closed);
    session.disconnect();
  }

  @Test
  public void authorizationTimeoutReportsBeforeSlowTransportClose() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    CountDownLatch error = new CountDownLatch(1);
    Transport transport =
        new Transport() {
          @Override
          public boolean connect(long timeout) {
            entered.countDown();
            while (release.getCount() != 0) {
              try {
                release.await();
              } catch (InterruptedException ignored) {
              }
            }
            return true;
          }

          @Override
          public void close() {
            while (release.getCount() != 0) {
              try {
                release.await();
              } catch (InterruptedException ignored) {
              }
            }
            super.close();
          }
        };
    AdbSession session = new AdbSession(endpoint -> transport, Runnable::run, 60);
    session.connect(
        Endpoint.parse("127.0.0.1", "5555"),
        (state, message) -> {
          if (state == ConnectionState.ERROR) error.countDown();
        });
    try {
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertTrue("timeout UI must not wait for a stuck close", error.await(1, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      session.disconnect();
    }
  }

  @Test
  public void commandDuringAuthorizationDoesNotClosePendingTransport() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    CountDownLatch ready = new CountDownLatch(1), error = new CountDownLatch(1);
    Transport transport =
        new Transport() {
          @Override
          public boolean connect(long timeout) {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              return false;
            }
            return true;
          }
        };
    AdbSession session = new AdbSession(endpoint -> transport, Runnable::run);
    session.connect(
        Endpoint.parse("127.0.0.1", "5555"),
        (state, message) -> {
          if (state == ConnectionState.CONNECTED) ready.countDown();
          if (state == ConnectionState.ERROR) error.countDown();
        });
    assertTrue(entered.await(1, TimeUnit.SECONDS));
    try {
      session.shell("id", 500);
      fail("command should be rejected until handshake completes");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("正在连接"));
    }
    assertNull("transport.open must not run during handshake", transport.destination);
    assertFalse(transport.closed);
    assertEquals(1, error.getCount());
    release.countDown();
    assertTrue(ready.await(1, TimeUnit.SECONDS));
    assertFalse(transport.closed);
    session.disconnect();
  }

  @Test
  public void excessiveCommandOutputClosesOnlyItsChannel() throws Exception {
    Transport transport = new Transport();
    CountDownLatch channelClosed = new CountDownLatch(1);
    transport.channel =
        new AdbChannel(
            new ByteArrayInputStream(new byte[4 * 1024 * 1024 + 1]),
            new ByteArrayOutputStream(),
            channelClosed::countDown);
    AdbSession session = connected(transport);
    try {
      session.shell("yes", 3000);
      fail("expected bounded output error");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("超过 4 MiB"));
    }
    assertTrue(channelClosed.await(1, TimeUnit.SECONDS));
    assertFalse("local output limit must preserve healthy ADB", transport.closed);
    session.disconnect();
  }

  private AdbSession connected(Transport t) throws Exception {
    AdbSession s = new AdbSession(e -> t, Runnable::run);
    CountDownLatch ready = new CountDownLatch(1);
    s.connect(
        Endpoint.parse("127.0.0.1", "5555"),
        (state, m) -> {
          if (state == ConnectionState.CONNECTED) ready.countDown();
        });
    assertTrue(ready.await(2, TimeUnit.SECONDS));
    return s;
  }
}
