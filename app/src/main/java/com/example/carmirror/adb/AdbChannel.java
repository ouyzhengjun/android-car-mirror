package com.example.carmirror.adb;

import java.io.*;

public final class AdbChannel implements Closeable {
  private final InputStream input;
  private final OutputStream output;
  private final Closeable closer;
  private boolean closed;

  public AdbChannel(InputStream input, OutputStream output, Closeable closer) {
    this.input = input;
    // libadb waits for READY once per write call, but its internal splitting does not.
    // Keep each call within the smallest legacy ADB payload so every packet waits for READY.
    this.output =
        new OutputStream() {
          @Override
          public synchronized void write(int value) throws IOException {
            output.write(value);
          }

          @Override
          public synchronized void write(byte[] data, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > data.length - length)
              throw new IndexOutOfBoundsException();
            while (length > 0) {
              int count = Math.min(4096, length);
              output.write(data, offset, count);
              offset += count;
              length -= count;
            }
          }

          @Override
          public synchronized void flush() throws IOException {
            output.flush();
          }

          @Override
          public void close() throws IOException {
            AdbChannel.this.close();
          }
        };
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
