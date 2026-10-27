package com.example.carmirror.adb.usb;

import java.io.*;

/**
 * Bulk byte transport. Zero means no progress; negative means disconnected. Calls must honor
 * timeoutMs; close must unblock outstanding IO.
 */
public interface UsbIo extends Closeable {
  int read(byte[] data, int offset, int length, int timeoutMs) throws IOException;

  int write(byte[] data, int offset, int length, int timeoutMs) throws IOException;
}
