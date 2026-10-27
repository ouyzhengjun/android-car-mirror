package com.example.carmirror.adb.usb;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class AndroidUsbIoTest {
    @Test public void preservesPartialCountOffsetDirectionAndBoundsLegacyTransfers() throws Exception {
        Port port = new Port(); AndroidUsbIo io = new AndroidUsbIo(port);
        port.result = 7;
        assertEquals(7, io.read(new byte[20000], 3, 19997, 50));
        assertTrue(port.input); assertEquals(3, port.offset); assertEquals(16384, port.length);
        port.result = 4;
        assertEquals(4, io.write(new byte[10], 2, 8, 60));
        assertFalse(port.input); assertEquals(8, port.length); assertEquals(60, port.timeout);
    }
    @Test public void timeoutIsNoProgressAndCloseRejectsFurtherCalls() throws Exception {
        Port port = new Port(); AndroidUsbIo io = new AndroidUsbIo(port);
        port.result = -1;
        assertEquals(0, io.read(new byte[1], 0, 1, 10));
        io.close(); io.close(); assertEquals(1, port.closes);
        try { io.read(new byte[1], 0, 1, 10); fail(); } catch(IOException expected) { }
    }
    @Test public void forbidsInfiniteTimeoutAndInvalidSlice() throws Exception {
        AndroidUsbIo io = new AndroidUsbIo(new Port());
        try { io.read(new byte[1], 0, 1, 0); fail(); } catch(IllegalArgumentException expected) { }
        try { io.write(new byte[1], 1, 1, 10); fail(); } catch(IndexOutOfBoundsException expected) { }
    }
    static class Port implements AndroidUsbIo.BulkPort {
        int result, offset, length, timeout, closes; boolean input;
        public int transfer(boolean input, byte[] data, int offset, int length, int timeout) {
            this.input=input; this.offset=offset; this.length=length; this.timeout=timeout; return result;
        }
        public void close() { closes++; }
    }
}
