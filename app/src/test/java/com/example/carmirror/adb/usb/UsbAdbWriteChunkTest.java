package com.example.carmirror.adb.usb;

import static org.junit.Assert.*;

import org.junit.Test;

/** Guards the USB write chunk size against the gadget stalling on exact packet multiples. */
public class UsbAdbWriteChunkTest {

  /** High-speed bulk endpoints move 512 bytes per USB packet. */
  private static final int USB_MAX_PACKET = 512;

  @Test
  public void chunkNeverEndsOnAnExactPacketMultiple() {
    assertNotEquals(
        "an exact multiple of the packet size finishes without a short packet, so the peer keeps"
            + " waiting for more data and the stream stalls until the write deadline",
        0,
        UsbAdbTransport.WRITE_CHUNK % USB_MAX_PACKET);
  }

  @Test
  public void chunkStaysWithinTheNegotiatedPayloadLimit() {
    assertTrue(UsbAdbTransport.WRITE_CHUNK > 0);
    assertTrue(
        "a WRTE payload may not exceed the peer's advertised maximum",
        UsbAdbTransport.WRITE_CHUNK <= AdbPacket.MAX_PAYLOAD);
  }
}
