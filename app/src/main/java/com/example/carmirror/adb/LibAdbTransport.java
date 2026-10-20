package com.example.carmirror.adb;

import com.example.carmirror.Endpoint;
import io.github.muntashirakon.adb.*;
import java.io.*;
import java.util.concurrent.TimeUnit;

public final class LibAdbTransport implements AdbSession.Transport {
  private final AdbConnection connection;

  public LibAdbTransport(Endpoint endpoint, AdbKeyStore store) throws Exception {
    AdbKeyStore.Credentials keys = store.load();
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
    return new AdbChannel(stream.openInputStream(), stream.openOutputStream(), stream);
  }

  public void close() throws IOException {
    connection.close();
  }
}
