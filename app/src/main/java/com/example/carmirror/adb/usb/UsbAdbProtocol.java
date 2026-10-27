package com.example.carmirror.adb.usb;

import java.io.*;
import java.math.BigInteger;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import javax.crypto.Cipher;
import org.bouncycastle.util.encoders.Base64;

/** Framing and RSA wire encoding, with no Android USB dependency. */
final class UsbAdbProtocol {
  static long deadline(long ms) {
    return System.nanoTime() + Math.min(ms, 86400000L) * 1000000L;
  }

  static void check(long deadline) throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ADB canceled");
    if (System.nanoTime() >= deadline)
      throw new java.net.SocketTimeoutException("USB ADB timed out");
  }

  static void transfer(UsbIo io, byte[] data, boolean writing, long deadline, boolean idle)
      throws IOException {
    int offset = 0;
    while (offset < data.length) {
      if (!idle || offset > 0) check(deadline);
      else if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
      int n =
          writing
              ? io.write(data, offset, data.length - offset, 100)
              : io.read(data, offset, data.length - offset, 100);
      if (n < 0) throw new EOFException("USB disconnected");
      if (n > data.length - offset) throw new IOException("Invalid USB byte count");
      if (n == 0) {
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException();
        }
      } else {
        if (idle && offset == 0) deadline = deadline(10000);
        offset += n;
      }
    }
  }

  static AdbPacket read(UsbIo io) throws IOException {
    byte[] header = new byte[24];
    transfer(io, header, false, deadline(10000), true);
    int[] h = AdbPacket.parseHeader(header);
    byte[] payload = new byte[h[3]];
    transfer(io, payload, false, deadline(10000), false);
    if (AdbPacket.checksum(payload) != h[4]) throw new IOException("ADB checksum mismatch");
    return new AdbPacket(h[0], h[1], h[2], payload);
  }

  static byte[] sign(PrivateKey key, byte[] token) throws GeneralSecurityException {
    // AUTH token is already a SHA-1 digest; do not hash it again.
    byte[] prefix = {
      0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };
    byte[] digest = new byte[35];
    System.arraycopy(prefix, 0, digest, 0, 15);
    System.arraycopy(token, 0, digest, 15, 20);
    Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    rsa.init(Cipher.ENCRYPT_MODE, key);
    return rsa.doFinal(digest);
  }

  static byte[] publicKey(PublicKey key) throws IOException {
    if (!(key instanceof RSAPublicKey)) throw new IOException("ADB requires RSA key");
    RSAPublicKey rsa = (RSAPublicKey) key;
    BigInteger n = rsa.getModulus();
    if (n.bitLength() != 2048
        || !(rsa.getPublicExponent().equals(BigInteger.valueOf(65537))
            || rsa.getPublicExponent().equals(BigInteger.valueOf(3))))
      throw new IOException("ADB requires 2048-bit RSA key");
    ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(64).putInt(n.modInverse(BigInteger.ONE.shiftLeft(32)).negate().intValue());
    little(b, n);
    little(b, BigInteger.ONE.shiftLeft(4096).mod(n));
    b.putInt(rsa.getPublicExponent().intValue());
    return (Base64.toBase64String(b.array()) + " carmirror@android\0")
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static void little(ByteBuffer b, BigInteger n) {
    for (int i = 0; i < 256; i++) {
      b.put(n.byteValue());
      n = n.shiftRight(8);
    }
  }
}
