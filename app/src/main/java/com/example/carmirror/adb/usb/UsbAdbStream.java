package com.example.carmirror.adb.usb;

import com.example.carmirror.adb.AdbChannel;
import java.io.*;
import java.net.ConnectException;
import java.util.Arrays;

/** A single unacknowledged inbound packet bounds buffering to 4 KiB per stream. */
final class UsbAdbStream implements Closeable {
  private static final byte[] EMPTY = new byte[0];
  final int local;
  private final UsbAdbTransport transport;
  private int remote, offset;
  private boolean closed, writePending;
  private IOException failure;
  private byte[] pending;

  UsbAdbStream(UsbAdbTransport transport, int local) {
    this.transport = transport;
    this.local = local;
  }

  synchronized void opened() throws IOException {
    long end = UsbAdbProtocol.deadline(transport.timeoutMs);
    while (remote == 0 && !closed) {
      check();
      UsbAdbTransport.await(this, end);
    }
    check();
    if (closed) throw new ConnectException("ADB service rejected");
  }

  private void check() throws IOException {
    if (failure != null) throw failure;
  }

  void receive(AdbPacket p) throws IOException {
    boolean remove = false, emptyWrite = false, late = false;
    synchronized (this) {
      if (closed) {
        late = p.command != AdbPacket.CLSE;
        if (late && p.arg0 <= 0) throw new IOException("Invalid late ADB stream ID");
      } else if (p.command == AdbPacket.CLSE && remote == 0 && p.arg0 == 0) {
        closed = true;
        remove = true;
        notifyAll();
      } else {
        if (p.arg0 <= 0 || (remote != 0 && p.arg0 != remote))
          throw new IOException("ADB remote stream ID mismatch");
        if (p.command == AdbPacket.OKAY) {
          if (remote == 0) remote = p.arg0;
          else if (!writePending) throw new IOException("Unexpected ADB OKAY");
          writePending = false;
        } else if (remote == 0) throw new IOException("Data before OPEN acknowledgement");
        else if (p.command == AdbPacket.WRTE) {
          if (pending != null) throw new IOException("ADB inbound flow control violation");
          emptyWrite = p.payload.length == 0;
          if (!emptyWrite) {
            pending = p.payload;
            offset = 0;
          }
        } else {
          closed = true;
          remove = true;
        }
        notifyAll();
      }
    }
    if (late) transport.send(AdbPacket.CLSE, local, p.arg0, EMPTY);
    else if (remove) transport.remove(local);
    else if (emptyWrite) transport.send(AdbPacket.OKAY, local, remote, EMPTY);
  }

  synchronized void fail(IOException e) {
    failure = e;
    closed = true;
    pending = null;
    notifyAll();
  }

  AdbChannel channel() {
    InputStream in =
        new InputStream() {
          public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : b[0] & 255;
          }

          public int read(byte[] b, int off, int len) throws IOException {
            if (off < 0 || len < 0 || off > b.length - len) throw new IndexOutOfBoundsException();
            if (len == 0) return 0;
            int n;
            boolean ack;
            synchronized (UsbAdbStream.this) {
              while (pending == null && !closed) {
                check();
                try {
                  UsbAdbStream.this.wait();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new InterruptedIOException("ADB read canceled");
                }
              }
              check();
              if (pending == null) return -1;
              n = Math.min(len, pending.length - offset);
              System.arraycopy(pending, offset, b, off, n);
              offset += n;
              ack = offset == pending.length;
              if (ack) pending = null;
              ack = ack && !closed;
            }
            if (ack) transport.send(AdbPacket.OKAY, local, remote, EMPTY);
            return n;
          }

          public void close() throws IOException {
            UsbAdbStream.this.close();
          }
        };
    OutputStream out =
        new OutputStream() {
          public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
          }

          public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (off < 0 || len < 0 || off > b.length - len) throw new IndexOutOfBoundsException();
            while (len > 0) {
              int n = Math.min(len, transport.maxPayload);
              long end = UsbAdbProtocol.deadline(transport.timeoutMs);
              synchronized (UsbAdbStream.this) {
                check();
                if (closed) throw new IOException("ADB stream closed");
                writePending = true;
              }
              try {
                transport.send(
                    new AdbPacket(
                        AdbPacket.WRTE, local, remote, Arrays.copyOfRange(b, off, off + n)),
                    end);
                synchronized (UsbAdbStream.this) {
                  while (writePending && !closed) {
                    check();
                    UsbAdbTransport.await(UsbAdbStream.this, end);
                  }
                  check();
                  if (closed) throw new IOException("ADB stream closed");
                }
              } catch (IOException e) {
                UsbAdbStream.this.close();
                throw e;
              }
              off += n;
              len -= n;
            }
          }
        };
    return new AdbChannel(in, out, this);
  }

  @Override
  public void close() throws IOException {
    int peer;
    synchronized (this) {
      if (closed) return;
      closed = true;
      pending = null;
      peer = remote;
      notifyAll();
    }
    transport.remove(local);
    // OPEN cancellation waits for the remote ID; transport closes a late OKAY.
    if (peer != 0) transport.send(AdbPacket.CLSE, local, peer, EMPTY);
  }
}
