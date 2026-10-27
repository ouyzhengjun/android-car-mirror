package com.example.carmirror.adb.usb;

import static org.junit.Assert.*;

import com.example.carmirror.adb.*;
import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import org.junit.*;

public class UsbAdbTransportTest {
  static KeyPair keys;
  Fake io;
  UsbAdbTransport transport;
  ExecutorService jobs;

  @BeforeClass
  public static void keys() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    keys = g.generateKeyPair();
  }

  @Before
  public void setup() throws Exception {
    io = new Fake();
    transport = new UsbAdbTransport(io, keys.getPrivate(), keys.getPublic(), 400);
    jobs = Executors.newCachedThreadPool();
  }

  @After
  public void cleanup() throws Exception {
    transport.close();
    jobs.shutdownNow();
  }

  static int cmd(String s) {
    return ByteBuffer.wrap(s.getBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  static byte[] packet(String s, int a, int b, byte[] p) {
    int sum = 0;
    for (byte v : p) sum += v & 255;
    return ByteBuffer.allocate(24 + p.length)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(cmd(s))
        .putInt(a)
        .putInt(b)
        .putInt(p.length)
        .putInt(sum)
        .putInt(~cmd(s))
        .put(p)
        .array();
  }

  void feed(String s, int a, int b, byte[] p) {
    io.feed(packet(s, a, b, p));
  }

  byte[] next(String s) throws Exception {
    byte[] p = io.packets.poll(2, TimeUnit.SECONDS);
    assertNotNull("missing " + s, p);
    assertEquals(cmd(s), ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).getInt());
    return p;
  }

  int arg(byte[] p, int n) {
    return ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).getInt(4 * n);
  }

  void connected() throws Exception {
    Future<Boolean> f = jobs.submit(() -> transport.connect(1000));
    next("CNXN");
    feed("CNXN", 0x1000000, 4096, new byte[0]);
    assertTrue(f.get(2, TimeUnit.SECONDS));
  }

  AdbChannel open(int remote) throws Exception {
    Future<AdbChannel> f = jobs.submit(() -> transport.open("shell:"));
    byte[] p = next("OPEN");
    feed("OKAY", remote, arg(p, 1), new byte[0]);
    return f.get(2, TimeUnit.SECONDS);
  }

  @Test
  public void connectsAcrossPartialAndZeroProgressIo() throws Exception {
    io.zeroReads = 4;
    io.zeroWrites = 4;
    connected();
  }

  @Test
  public void signsTokenThenOffersAndroidPublicKey() throws Exception {
    Future<Boolean> f = jobs.submit(() -> transport.connect(1500));
    next("CNXN");
    byte[] token = new byte[20];
    Arrays.fill(token, (byte) 7);
    feed("AUTH", 1, 0, token);
    byte[] sign = next("AUTH");
    assertEquals(2, arg(sign, 1));
    Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    c.init(Cipher.DECRYPT_MODE, keys.getPublic());
    byte[] digest = c.doFinal(Arrays.copyOfRange(sign, 24, sign.length));
    assertArrayEquals(token, Arrays.copyOfRange(digest, 15, digest.length));
    assertEquals(35, digest.length);
    feed("AUTH", 1, 0, token);
    byte[] pub = next("AUTH");
    assertEquals(3, arg(pub, 1));
    assertEquals(0, pub[pub.length - 1]);
    String text = new String(pub, 24, pub.length - 25, "US-ASCII");
    byte[] encoded = Base64.getDecoder().decode(text.split(" ")[0]);
    assertEquals(524, encoded.length);
    assertEquals(64, arg(encoded, 0));
    feed("CNXN", 0x1000000, 4096, new byte[0]);
    assertTrue(f.get(2, TimeUnit.SECONDS));
  }

  @Test
  public void multiplexesAndAcknowledgesOnlyConsumedData() throws Exception {
    connected();
    AdbChannel a = open(11), b = open(12);
    feed("WRTE", 11, 1, new byte[] {1, 2});
    feed("WRTE", 12, 2, new byte[] {3});
    assertEquals(3, b.input().read());
    byte[] ack = next("OKAY");
    assertEquals(2, arg(ack, 1));
    assertEquals(1, a.input().read());
    assertNull(io.packets.poll(40, TimeUnit.MILLISECONDS));
    assertEquals(2, a.input().read());
    next("OKAY");
    feed("CLSE", 11, 1, new byte[0]);
    assertEquals(-1, a.input().read());
    assertNull(io.packets.poll(40, TimeUnit.MILLISECONDS));
    b.close();
    next("CLSE");
  }

  @Test
  public void writeWaitsForAckAndCloseCancelsIt() throws Exception {
    connected();
    AdbChannel a = open(11);
    Future<?> f =
        jobs.submit(
            () -> {
              a.output().write(new byte[5000]);
              return null;
            });
    byte[] p = next("WRTE");
    assertEquals(4096, p.length - 24);
    assertFalse(f.isDone());
    feed("OKAY", 11, 1, new byte[0]);
    assertEquals(904, next("WRTE").length - 24);
    a.close();
    next("CLSE");
    try {
      f.get(1, TimeUnit.SECONDS);
      fail();
    } catch (ExecutionException expected) {
      assertTrue(expected.getCause() instanceof IOException);
    }
  }

  @Test
  public void handshakeTimeoutClosesIo() throws Exception {
    try {
      transport.connect(60);
      fail();
    } catch (IOException expected) {
    }
    assertTrue(io.closed);
  }

  @Test
  public void unplugUnblocksRead() throws Exception {
    connected();
    AdbChannel a = open(11);
    Future<Integer> f = jobs.submit(() -> a.input().read());
    io.close();
    try {
      f.get(1, TimeUnit.SECONDS);
      fail();
    } catch (ExecutionException expected) {
      assertTrue(expected.getCause() instanceof IOException);
    }
  }

  @Test
  public void malformedMagicClosesConnection() throws Exception {
    connected();
    AdbChannel a = open(11);
    byte[] bad = packet("WRTE", 11, 1, new byte[] {4});
    bad[20] ^= 1;
    io.feed(bad);
    try {
      a.input().read();
      fail();
    } catch (IOException expected) {
    }
    assertTrue(io.closed);
  }

  @Test
  public void openRejectionIsConnectException() throws Exception {
    connected();
    Future<AdbChannel> f = jobs.submit(() -> transport.open("localabstract:scrcpy_12345678"));
    byte[] p = next("OPEN");
    feed("CLSE", 0, arg(p, 1), new byte[0]);
    try {
      f.get(1, TimeUnit.SECONDS);
      fail();
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof java.net.ConnectException);
    }
  }

  @Test
  public void lateOpenAckIsClosedRatherThanLeakingPeerStream() throws Exception {
    connected();
    Future<AdbChannel> f = jobs.submit(() -> transport.open("shell:"));
    next("OPEN");
    try {
      f.get(2, TimeUnit.SECONDS);
      fail();
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof java.net.SocketTimeoutException);
    }
    assertNull(io.packets.poll(40, TimeUnit.MILLISECONDS));
    feed("OKAY", 19, 1, new byte[0]);
    byte[] close = next("CLSE");
    assertEquals(1, arg(close, 1));
    assertEquals(19, arg(close, 2));
  }

  @Test
  public void publicKeyCarriesModulusAndMontgomeryParameters() throws Exception {
    byte[] wire = UsbAdbProtocol.publicKey(keys.getPublic());
    String value = new String(wire, "US-ASCII").split(" ")[0];
    byte[] bytes = Base64.getDecoder().decode(value);
    java.math.BigInteger modulus =
        ((java.security.interfaces.RSAPublicKey) keys.getPublic()).getModulus();
    byte[] le = Arrays.copyOfRange(bytes, 8, 264);
    for (int i = 0; i < 128; i++) {
      byte x = le[i];
      le[i] = le[255 - i];
      le[255 - i] = x;
    }
    assertEquals(modulus, new java.math.BigInteger(1, le));
    long inverse = Integer.toUnsignedLong(arg(bytes, 1));
    assertEquals(0xffffffffL, modulus.longValue() * inverse & 0xffffffffL);
    byte[] rr = Arrays.copyOfRange(bytes, 264, 520);
    for (int i = 0; i < 128; i++) {
      byte x = rr[i];
      rr[i] = rr[255 - i];
      rr[255 - i] = x;
    }
    assertEquals(
        java.math.BigInteger.ONE.shiftLeft(4096).mod(modulus), new java.math.BigInteger(1, rr));
    assertEquals(65537, arg(bytes, 130));
  }

  @Test
  public void noWriteProgressTimesOutAndClosesIo() throws Exception {
    io.zeroWrites = Integer.MAX_VALUE;
    try {
      transport.connect(50);
      fail();
    } catch (java.net.SocketTimeoutException expected) {
    }
    assertTrue(io.closed);
  }

  @Test
  public void unacknowledgedWriteTimesOut() throws Exception {
    connected();
    AdbChannel a = open(11);
    Future<?> f =
        jobs.submit(
            () -> {
              a.output().write(3);
              return null;
            });
    next("WRTE");
    try {
      f.get(2, TimeUnit.SECONDS);
      fail();
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof java.net.SocketTimeoutException);
    }
    next("CLSE");
  }

  @Test
  public void closeWakesConcurrentOpenAndReader() throws Exception {
    connected();
    AdbChannel a = open(11);
    Future<?> read = jobs.submit(() -> a.input().read());
    Future<?> opening = jobs.submit(() -> transport.open("sync:"));
    next("OPEN");
    transport.close();
    for (Future<?> f : Arrays.asList(read, opening)) {
      try {
        f.get(1, TimeUnit.SECONDS);
        fail();
      } catch (ExecutionException e) {
        assertTrue(e.getCause() instanceof IOException);
      }
    }
  }

  @Test
  public void interruptedConnectClosesIo() throws Exception {
    Future<?> f = jobs.submit(() -> transport.connect(5000));
    next("CNXN");
    f.cancel(true);
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (!io.closed && System.nanoTime() < end) Thread.sleep(1);
    assertTrue(io.closed);
  }

  @Test
  public void invalidFramesAbortBlockedReaders() throws Exception {
    byte[][] frames = {
      packet("WRTE", 11, 1, new byte[] {4}),
      packet("WRTE", 11, 1, new byte[0]),
      packet("NOPE", 11, 1, new byte[0]),
      packet("WRTE", 99, 1, new byte[] {4}),
      packet("WRTE", 11, 99, new byte[] {4}),
      packet("OKAY", 11, 1, new byte[] {4})
    };
    frames[0][16]++;
    ByteBuffer.wrap(frames[1]).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 4097);
    for (byte[] frame : frames) {
      connected();
      AdbChannel a = open(11);
      io.feed(frame);
      try {
        a.input().read();
        fail();
      } catch (IOException expected) {
      }
      assertTrue(io.closed);
      transport.close();
      io = new Fake();
      transport = new UsbAdbTransport(io, keys.getPrivate(), keys.getPublic(), 400);
    }
  }

  @Test
  public void inboundFlowControlViolationAbortsConnection() throws Exception {
    connected();
    AdbChannel a = open(11);
    feed("WRTE", 11, 1, new byte[] {1});
    feed("WRTE", 11, 1, new byte[] {2});
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (!io.closed && System.nanoTime() < end) Thread.sleep(1);
    assertTrue(io.closed);
    try {
      a.input().read();
      fail();
    } catch (IOException expected) {
    }
  }

  @Test
  public void unknownProtocolVersionFailsHandshake() throws Exception {
    Future<?> f = jobs.submit(() -> transport.connect(1000));
    next("CNXN");
    feed("CNXN", 0x12345678, 4096, new byte[0]);
    try {
      f.get(1, TimeUnit.SECONDS);
      fail();
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof IOException);
    }
    assertTrue(io.closed);
  }

  @Test
  public void concurrentOpensAreMatchedByLocalId() throws Exception {
    connected();
    Future<AdbChannel> a = jobs.submit(() -> transport.open("shell:"));
    Future<AdbChannel> b = jobs.submit(() -> transport.open("sync:"));
    byte[] first = next("OPEN"), second = next("OPEN");
    assertNotEquals(arg(first, 1), arg(second, 1));
    feed("OKAY", 22, arg(second, 1), new byte[0]);
    feed("OKAY", 21, arg(first, 1), new byte[0]);
    a.get(1, TimeUnit.SECONDS).close();
    b.get(1, TimeUnit.SECONDS).close();
    next("CLSE");
    next("CLSE");
  }

  @Test
  public void partialFrameAndZeroProgressHaveDeadline() throws Exception {
    io.feed(new byte[] {1});
    try {
      UsbAdbProtocol.transfer(io, new byte[24], false, UsbAdbProtocol.deadline(30), false);
      fail();
    } catch (java.net.SocketTimeoutException expected) {
    }
  }

  @Test
  public void emptyInboundWriteIsAckedWithoutReturningZeroRead() throws Exception {
    connected();
    AdbChannel a = open(11);
    Future<Integer> f = jobs.submit(() -> a.input().read());
    feed("WRTE", 11, 1, new byte[0]);
    next("OKAY");
    assertFalse(f.isDone());
    feed("WRTE", 11, 1, new byte[] {9});
    assertEquals(9, (int) f.get(1, TimeUnit.SECONDS));
    next("OKAY");
  }

  @Test
  public void acknowledgedOpenPreservesBufferedDataWhenPeerClosesBeforeOpenerResumes()
      throws Exception {
    UsbAdbStream stream = new UsbAdbStream(transport, 1);
    CountDownLatch openerStarted = new CountDownLatch(1);
    Future<AdbChannel> opened;
    synchronized (stream) {
      opened = jobs.submit(() -> {
        openerStarted.countDown();
        stream.opened();
        return stream.channel();
      });
      assertTrue(openerStarted.await(1, TimeUnit.SECONDS));
      // Hold the monitor so the opener cannot resume until all three packets arrive.
      stream.receive(new AdbPacket(AdbPacket.OKAY, 42, 1, new byte[0]));
      stream.receive(new AdbPacket(AdbPacket.WRTE, 42, 1, new byte[] {7, 8}));
      stream.receive(new AdbPacket(AdbPacket.CLSE, 42, 1, new byte[0]));
    }
    AdbChannel channel = opened.get(1, TimeUnit.SECONDS);
    assertEquals(7, channel.input().read());
    assertEquals(8, channel.input().read());
    assertEquals(-1, channel.input().read());
    assertNull(io.packets.poll(40, TimeUnit.MILLISECONDS));
  }

  @Test
  public void closeRacingDispatchStillClosesLateRemoteOpen() throws Exception {
    connected();
    UsbAdbStream stream = new UsbAdbStream(transport, 1);
    stream.close();
    stream.receive(new AdbPacket(AdbPacket.OKAY, 42, 1, new byte[0]));
    byte[] close = next("CLSE");
    assertEquals(42, arg(close, 2));
  }

  @Test
  public void modernPeerAdvertisesItsMaximumButUsesOurLegacyChecksums() throws Exception {
    Future<Boolean> f = jobs.submit(() -> transport.connect(1000));
    next("CNXN");
    feed("CNXN", 0x01000001, 1024 * 1024, "device::".getBytes("US-ASCII"));
    assertTrue(f.get(1, TimeUnit.SECONDS));
    AdbChannel a = open(11);
    feed("WRTE", 11, 1, new byte[] {5});
    assertEquals(5, a.input().read());
    next("OKAY");
  }

  static final class Fake implements UsbIo {
    final BlockingQueue<Byte> incoming = new LinkedBlockingQueue<>();
    final BlockingQueue<byte[]> packets = new LinkedBlockingQueue<>();
    final ByteArrayOutputStream outgoing = new ByteArrayOutputStream();
    volatile boolean closed;
    int zeroReads, zeroWrites;

    void feed(byte[] data) {
      for (byte b : data) incoming.add(b);
    }

    public int read(byte[] b, int off, int len, int timeout) throws IOException {
      if (closed) throw new IOException("unplugged");
      if (zeroReads-- > 0) return 0;
      try {
        Byte v = incoming.poll(Math.min(timeout, 10), TimeUnit.MILLISECONDS);
        if (v == null) return 0;
        b[off] = v;
        return 1;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException();
      }
    }

    public synchronized int write(byte[] b, int off, int len, int timeout) throws IOException {
      if (closed) throw new IOException("unplugged");
      if (zeroWrites-- > 0) return 0;
      int n = Math.min(len, 7);
      outgoing.write(b, off, n);
      byte[] all = outgoing.toByteArray();
      if (all.length >= 24) {
        int size = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN).getInt(12) + 24;
        if (all.length == size) {
          packets.add(all);
          outgoing.reset();
        }
      }
      return n;
    }

    public void close() {
      closed = true;
    }
  }
}
