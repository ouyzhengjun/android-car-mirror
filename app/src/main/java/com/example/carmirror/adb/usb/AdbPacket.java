package com.example.carmirror.adb.usb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Original implementation of AOSP's six-word little-endian ADB frame. */
final class AdbPacket {
  static final int CNXN = 0x4e584e43,
      AUTH = 0x48545541,
      OPEN = 0x4e45504f,
      OKAY = 0x59414b4f,
      WRTE = 0x45545257,
      CLSE = 0x45534c43;
  static final int MAX_PAYLOAD = 4096, VERSION = 0x01000000;
  final int command, arg0, arg1;
  final byte[] payload;

  AdbPacket(int command, int arg0, int arg1, byte[] payload) {
    this.command = command;
    this.arg0 = arg0;
    this.arg1 = arg1;
    this.payload = payload;
  }

  byte[] header() {
    return ByteBuffer.allocate(24)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(command)
        .putInt(arg0)
        .putInt(arg1)
        .putInt(payload.length)
        .putInt(checksum(payload))
        .putInt(~command)
        .array();
  }

  static int[] parseHeader(byte[] header) throws IOException {
    ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
    int[] h = new int[6];
    for (int i = 0; i < 6; i++) h[i] = b.getInt();
    if (h[0] != CNXN
        && h[0] != AUTH
        && h[0] != OPEN
        && h[0] != OKAY
        && h[0] != WRTE
        && h[0] != CLSE) throw new IOException("Unknown ADB command");
    if (h[5] != ~h[0] || h[3] < 0 || h[3] > MAX_PAYLOAD)
      throw new IOException("Invalid ADB header");
    return h;
  }

  static int checksum(byte[] data) {
    int sum = 0;
    for (byte b : data) sum += b & 255;
    return sum;
  }
}
