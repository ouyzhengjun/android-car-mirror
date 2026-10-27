package com.example.carmirror.adb.usb;

import android.hardware.usb.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the claimed interface and connection. Closing never waits for a bulk call lock. */
public final class AndroidUsbIo implements UsbIo {
    interface BulkPort {
        int transfer(boolean input, byte[] data, int offset, int length, int timeoutMs);
        void close();
    }
    private final BulkPort port;
    private final AtomicBoolean closed = new AtomicBoolean();
    AndroidUsbIo(BulkPort port) { this.port = port; }
    AndroidUsbIo(UsbDeviceConnection connection, UsbInterface iface, UsbEndpoint input, UsbEndpoint output) {
        this(new BulkPort() {
            public int transfer(boolean reading, byte[] data, int offset, int length, int timeoutMs) {
                return connection.bulkTransfer(reading ? input : output, data, offset, length, timeoutMs);
            }
            public void close() { connection.close(); }
        });
    }
    @Override public int read(byte[] data, int offset, int length, int timeoutMs) throws IOException {
        return transfer(true, data, offset, length, timeoutMs);
    }
    @Override public int write(byte[] data, int offset, int length, int timeoutMs) throws IOException {
        return transfer(false, data, offset, length, timeoutMs);
    }
    private int transfer(boolean input, byte[] data, int offset, int length, int timeoutMs) throws IOException {
        if (data == null || offset < 0 || length < 0 || offset > data.length - length)
            throw new IndexOutOfBoundsException();
        if (timeoutMs <= 0) throw new IllegalArgumentException("Finite positive timeout required");
        if (closed.get()) throw new IOException("USB connection closed");
        if (length == 0) return 0;
        // Pre-P Android truncates bulk transfers above 16384 bytes. Bound on every API for consistency.
        int count = port.transfer(input, data, offset, Math.min(length, 16384), timeoutMs);
        if (closed.get()) throw new IOException("USB connection closed");
        // Android reports both timeout and disconnect as -1. Detach receiver closes this IO;
        // otherwise let the protocol's deadline govern retries rather than treating timeout as EOF.
        return count < 0 ? 0 : count;
    }
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            // Native close cancels in-flight requests; do not release first (may wait for IO).
            port.close();
        }
    }
}
