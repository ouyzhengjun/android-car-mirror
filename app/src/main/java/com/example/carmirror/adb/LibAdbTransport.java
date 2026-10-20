package com.example.carmirror.adb;

import com.example.carmirror.Endpoint;
import io.github.muntashirakon.adb.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

public final class LibAdbTransport implements AdbSession.Transport {
  private final AdbConnection connection;

  public LibAdbTransport(Endpoint endpoint, AdbKeyStore store) throws Exception {
    AdbKeyStore.Credentials keys = store.load();
    // Builder.build() opens its own socket with no caller-supplied connect timeout.
    // Fail fast for an unreachable endpoint before entering that library call.
    try (Socket probe = new Socket()) {
      probe.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 5000);
    }
    connection =
        new AdbConnection.Builder(endpoint.host(), endpoint.port())
            .setPrivateKey(keys.privateKey)
            .setCertificate(keys.certificate)
            .setDeviceName("CarMirror")
            .setApi(28)
            .build();
  }

  public boolean connect(long timeout) throws Exception {
    return connection.connect(timeout, TimeUnit.MILLISECONDS, false);
  }

  public AdbChannel open(String destination) throws Exception {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
      throw new IllegalStateException("ADB I/O must run on a worker thread");
    AdbStream stream = connection.open(destination);
    return new AdbChannel(openInput(stream), stream.openOutputStream(), stream);
  }

  /**
   * libadb's AdbInputStream.read() returns the raw signed byte, so any value at or above
   * 0x80 arrives negative and callers mistake it for EOF. Multi-byte reads return counts
   * and are unaffected.
   */
  private static InputStream openInput(AdbStream stream) {
    return new FilterInputStream(stream.openInputStream()) {
      @Override
      public int read() throws IOException {
        int value = super.read();
        return value < -1 ? value & 0xff : value;
      }
    };
  }

  public void close() throws IOException {
    connection.close();
  }
}
