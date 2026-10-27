package com.example.carmirror.adb;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Adapts a libadb stream to the {@link InputStream} contract.
 *
 * <p>libadb reports a peer-closed stream with {@code IOException("Stream closed.")} when the CLSE
 * packet arrives after the last payload has been drained, where {@code read()} has to report end
 * of stream instead. Every finished command would otherwise look like a broken session.
 *
 * <p>libadb also returns the raw signed byte, so any value at or above 0x80 arrives negative and
 * callers mistake it for EOF. Multi-byte reads return counts and are unaffected.
 */
public final class AdbPeerInputStream extends FilterInputStream {

  public AdbPeerInputStream(InputStream source) {
    super(source);
  }

  @Override
  public int read() throws IOException {
    try {
      int value = super.read();
      return value < -1 ? value & 0xff : value;
    } catch (IOException error) {
      if (isEndOfStream(error)) return -1;
      throw error;
    }
  }

  @Override
  public int read(byte[] data, int offset, int length) throws IOException {
    try {
      return super.read(data, offset, length);
    } catch (IOException error) {
      if (isEndOfStream(error)) return -1;
      throw error;
    }
  }

  /** True when the error only means that the peer closed the stream. */
  public static boolean isEndOfStream(IOException error) {
    String message = error.getMessage();
    return message != null && message.startsWith("Stream closed");
  }
}
