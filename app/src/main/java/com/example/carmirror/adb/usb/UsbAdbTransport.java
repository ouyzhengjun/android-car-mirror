package com.example.carmirror.adb.usb;

import com.example.carmirror.adb.*;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** One receiver dispatches bounded, independently flow-controlled ADB streams. */
public final class UsbAdbTransport implements AdbSession.Transport {
  private static final String TAG = "CarMirrorUsb";
  private final UsbIo io;
  private final PrivateKey key;
  private final byte[] publicKey;
  final int timeoutMs;
  private final ReentrantLock writer = new ReentrantLock();
  private final Map<Integer, UsbAdbStream> streams = new HashMap<>();
  private boolean started, ready, closed, signed;
  private IOException failure;
  private Thread receiver;
  private int nextId = 1;
  volatile int maxPayload = AdbPacket.MAX_PAYLOAD;
  /**
   * Payload bytes per WRTE. A transfer whose length is an exact multiple of the USB bulk max
   * packet size ends without a short packet, so the peer's gadget keeps waiting for more data
   * and the stream stalls until the write deadline. T26FL hangs on 1024 and 4096, works on 1000.
   */
  static final int WRITE_CHUNK = 1000;
  volatile int writeChunk = WRITE_CHUNK;

  public UsbAdbTransport(UsbIo io, AdbKeyStore.Credentials credentials) throws IOException {
    this(io, credentials.privateKey, credentials.certificate.getPublicKey(), 10000);
  }

  public UsbAdbTransport(UsbIo io, PrivateKey key, PublicKey pub, int timeoutMs)
      throws IOException {
    if (timeoutMs <= 0) throw new IllegalArgumentException("timeout must be positive");
    this.io = Objects.requireNonNull(io);
    this.key = Objects.requireNonNull(key);
    this.timeoutMs = timeoutMs;
    publicKey = UsbAdbProtocol.publicKey(pub);
  }

  @Override
  public boolean connect(long timeout) throws IOException {
    if (timeout <= 0) throw new IllegalArgumentException("timeout must be positive");
    long end = UsbAdbProtocol.deadline(timeout);
    synchronized (this) {
      check();
      if (started) throw new IOException("USB transport already started");
      started = true;
    }
    Log.i(TAG, "USB ADB transport starting, sending CNXN");
    try {
      send(
          new AdbPacket(
              AdbPacket.CNXN,
              AdbPacket.VERSION,
              AdbPacket.MAX_PAYLOAD,
              "host::\0".getBytes(StandardCharsets.US_ASCII)),
          end);
      synchronized (this) {
        check();
        receiver = new Thread(this::receive, "usb-adb-reader");
        receiver.setDaemon(true);
        receiver.start();
        while (!ready) {
          check();
          await(this, end);
        }
      }
      return true;
    } catch (IOException e) {
      fail(e);
      throw e;
    }
  }

  static void await(Object lock, long end) throws IOException {
    UsbAdbProtocol.check(end);
    try {
      TimeUnit.NANOSECONDS.timedWait(lock, Math.max(1, end - System.nanoTime()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("ADB canceled");
    }
  }

  private synchronized void check() throws IOException {
    if (closed) throw failure != null ? failure : new IOException("USB ADB closed");
  }

  void send(AdbPacket packet, long end) throws IOException {
    if (verbose(packet.command))
      Log.i(TAG, "-> " + name(packet.command) + " a0=" + packet.arg0 + " a1=" + packet.arg1 + " len=" + packet.payload.length);
    try {
      UsbAdbProtocol.check(end);
      if (!writer.tryLock(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS))
        throw new java.net.SocketTimeoutException("USB writer busy");
      try {
        check();
        UsbAdbProtocol.transfer(io, packet.header(), true, end, false);
        UsbAdbProtocol.transfer(io, packet.payload, true, end, false);
      } finally {
        writer.unlock();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      InterruptedIOException x = new InterruptedIOException("ADB canceled");
      fail(x);
      throw x;
    } catch (IOException e) {
      fail(e);
      throw e;
    }
  }

  void send(int cmd, int local, int remote, byte[] payload) throws IOException {
    send(new AdbPacket(cmd, local, remote, payload), UsbAdbProtocol.deadline(timeoutMs));
  }

  private void receive() {
    try {
      while (true) {
        check();
        AdbPacket packet = UsbAdbProtocol.read(io);
        if (verbose(packet.command))
          Log.i(TAG, "<- " + name(packet.command) + " a0=" + packet.arg0 + " a1=" + packet.arg1 + " len=" + packet.payload.length);
        dispatch(packet);
      }
    } catch (Exception e) {
      fail(e instanceof IOException ? (IOException) e : new IOException("ADB protocol failure", e));
    }
  }

  private void dispatch(AdbPacket p) throws Exception {
    if (!ready) {
      if (p.command == AdbPacket.AUTH && p.arg0 == 1 && p.arg1 == 0 && p.payload.length == 20) {
        Log.i(TAG, "device requested RSA authentication, replying with " + (signed ? "public key" : "signature"));
        byte[] response = signed ? publicKey : UsbAdbProtocol.sign(key, p.payload);
        int type = signed ? 3 : 2;
        signed = true;
        send(AdbPacket.AUTH, type, 0, response);
        return;
      }
      // Modern adbd advertises its maximum, not the negotiated minimum.
      // We advertise v1, so checksums remain mandatory on both directions.
      if (p.command == AdbPacket.CNXN
          && (p.arg0 == AdbPacket.VERSION || p.arg0 == 0x01000001)
          && p.arg1 > 0) {
        synchronized (this) {
          maxPayload = Math.min(p.arg1, AdbPacket.MAX_PAYLOAD);
          ready = true;
          notifyAll();
        }
        Log.i(TAG, "device handshake accepted, maxPayload=" + maxPayload);
        return;
      }
      Log.i(TAG, "invalid ADB handshake: command=" + p.command + " arg0=" + p.arg0 + " arg1=" + p.arg1);
      throw new IOException("Invalid ADB handshake");
    }
    if (p.command != AdbPacket.OKAY && p.command != AdbPacket.WRTE && p.command != AdbPacket.CLSE)
      throw new IOException("Unexpected ADB command");
    if (p.command != AdbPacket.WRTE && p.payload.length != 0)
      throw new IOException("Unexpected control payload");
    UsbAdbStream stream;
    synchronized (this) {
      stream = streams.get(p.arg1);
    }
    // A late packet after local CLSE is allowed; never reuse IDs on this transport.
    if (stream == null) {
      synchronized (this) {
        if (p.arg1 <= 0 || p.arg1 >= nextId || p.arg0 < 0)
          throw new IOException("Invalid ADB stream ID");
      }
      if (p.command != AdbPacket.CLSE) {
        if (p.arg0 == 0) throw new IOException("Invalid ADB remote stream ID");
        send(AdbPacket.CLSE, p.arg1, p.arg0, new byte[0]);
      }
      return;
    }
    stream.receive(p);
  }

  @Override
  public AdbChannel open(String service) throws IOException {
    if (service == null || service.indexOf('\0') >= 0) throw new IOException("Invalid service");
    Log.i(TAG, "opening USB ADB service: " + service);
    byte[] name = (service + '\0').getBytes(StandardCharsets.UTF_8);
    UsbAdbStream stream;
    synchronized (this) {
      check();
      if (!ready) throw new IOException("Not connected");
      if (name.length > maxPayload || streams.size() >= 32 || nextId == Integer.MAX_VALUE)
        throw new IOException("ADB service limit");
      stream = new UsbAdbStream(this, nextId++);
      streams.put(stream.local, stream);
    }
    try {
      send(AdbPacket.OPEN, stream.local, 0, name);
      stream.opened();
      return stream.channel();
    } catch (IOException e) {
      stream.close();
      throw e;
    }
  }

  synchronized void remove(int id) {
    streams.remove(id);
  }

  private void fail(IOException cause) {
    List<UsbAdbStream> active;
    Thread reader;
    synchronized (this) {
      if (closed) return;
      closed = true;
      failure = cause;
      active = new ArrayList<>(streams.values());
      streams.clear();
      reader = receiver;
      notifyAll();
    }
    try {
      io.close();
    } catch (IOException ignored) {
    }
    for (UsbAdbStream stream : active) stream.fail(cause);
    if (reader != null && reader != Thread.currentThread()) reader.interrupt();
  }

  /** Stream lifecycle only; per-write OKAY/WRTE would log hundreds of lines per upload. */
  private static boolean verbose(int command) {
    return command == AdbPacket.CNXN
        || command == AdbPacket.AUTH
        || command == AdbPacket.OPEN
        || command == AdbPacket.CLSE;
  }

  private static String name(int command) {
    switch (command) {
      case AdbPacket.CNXN: return "CNXN";
      case AdbPacket.AUTH: return "AUTH";
      case AdbPacket.OPEN: return "OPEN";
      case AdbPacket.OKAY: return "OKAY";
      case AdbPacket.WRTE: return "WRTE";
      case AdbPacket.CLSE: return "CLSE";
      default: return String.format("0x%08x", command);
    }
  }

  @Override
  public void close() {
    fail(new IOException("USB ADB closed"));
  }
}
