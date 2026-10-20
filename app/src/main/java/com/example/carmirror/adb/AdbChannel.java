package com.example.carmirror.adb;

import java.io.*;

public final class AdbChannel implements Closeable {
  private final InputStream input;
  private final OutputStream output;
  private final Closeable closer;
  private boolean closed;

  public AdbChannel(InputStream input, OutputStream output, Closeable closer) {
    this.input = input;
    this.output = output;
    this.closer = closer;
  }

  public InputStream input() {
    return input;
  }

  public OutputStream output() {
    return output;
  }

  public synchronized void close() throws IOException {
    if (!closed) {
      closed = true;
      closer.close();
    }
  }
}
