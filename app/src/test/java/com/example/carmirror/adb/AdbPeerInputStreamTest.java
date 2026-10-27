package com.example.carmirror.adb;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

public class AdbPeerInputStreamTest {

  @Test
  public void peerCloseReadsAsEndOfStream() throws Exception {
    AdbPeerInputStream stream = new AdbPeerInputStream(new Failing("Stream closed."));
    assertEquals(-1, stream.read());
    assertEquals(-1, stream.read(new byte[8], 0, 8));
  }

  @Test
  public void otherFailuresKeepPropagating() throws Exception {
    AdbPeerInputStream stream = new AdbPeerInputStream(new Failing("broken pipe"));
    try {
      stream.read();
      fail("expected the original failure");
    } catch (IOException expected) {
      assertEquals("broken pipe", expected.getMessage());
    }
    try {
      stream.read(new byte[8], 0, 8);
      fail("expected the original failure");
    } catch (IOException expected) {
      assertEquals("broken pipe", expected.getMessage());
    }
  }

  @Test
  public void signedBytesAreReadAsUnsigned() throws Exception {
    AdbPeerInputStream stream = new AdbPeerInputStream(new Signed());
    assertEquals(0xc8, stream.read());
    assertEquals(-1, stream.read());
  }

  @Test
  public void endOfStreamIsRecognised() {
    assertTrue(AdbPeerInputStream.isEndOfStream(new IOException("Stream closed.")));
    assertTrue(AdbPeerInputStream.isEndOfStream(new IOException("Stream closed")));
    assertFalse(AdbPeerInputStream.isEndOfStream(new IOException("connection reset")));
    assertFalse(AdbPeerInputStream.isEndOfStream(new IOException()));
  }

  static class Failing extends InputStream {
    private final String message;

    Failing(String message) {
      this.message = message;
    }

    @Override
    public int read() throws IOException {
      throw new IOException(message);
    }

    @Override
    public int read(byte[] data, int offset, int length) throws IOException {
      throw new IOException(message);
    }
  }

  static class Signed extends InputStream {
    private boolean first = true;

    @Override
    public int read() {
      if (first) {
        first = false;
        return (byte) 0xc8;
      }
      return -1;
    }
  }
}
