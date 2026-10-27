package com.example.carmirror.adb.usb;

import java.util.Objects;

/** Immutable USB attachment identity; serial/product labels are optional and never identify a device. */
public final class UsbCandidate {
    public final String path, productName, serialNumber;
    public final int deviceId, vendorId, productId, interfaceIndex, inputAddress, outputAddress;

    private UsbCandidate(String path, int deviceId, int vendorId, int productId, String product,
            String serial, int index, int input, int output) {
        this.path = Objects.requireNonNull(path); this.deviceId = deviceId;
        this.vendorId = vendorId; this.productId = productId; productName = product;
        serialNumber = serial; interfaceIndex = index; inputAddress = input; outputAddress = output;
    }
    /** Endpoint types use USB transfer type values (bulk=2), addresses include direction bit. */
    public static UsbCandidate fromInterface(String path, int deviceId, int vendorId, int productId,
            String product, String serial, int index, int cls, int sub, int protocol,
            int[] addresses, int[] types) {
        if (cls != 0xff || sub != 0x42 || protocol != 1) return null;
        if (addresses.length != types.length) throw new IllegalArgumentException("Endpoint arrays differ");
        int in = -1, out = -1;
        for (int i = 0; i < addresses.length; i++) {
            if (types[i] == 2) {
                if ((addresses[i] & 0x80) != 0 && in < 0) in = addresses[i];
                else if ((addresses[i] & 0x80) == 0 && out < 0) out = addresses[i];
            }
        }
        return in < 0 || out < 0 ? null : new UsbCandidate(path, deviceId, vendorId, productId,
                product, serial, index, in, out);
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof UsbCandidate)) return false;
        UsbCandidate c = (UsbCandidate) other;
        return deviceId == c.deviceId && path.equals(c.path) && vendorId == c.vendorId
                && productId == c.productId && interfaceIndex == c.interfaceIndex
                && inputAddress == c.inputAddress && outputAddress == c.outputAddress;
    }
    @Override public int hashCode() { return Objects.hash(path, deviceId, vendorId, productId,
            interfaceIndex, inputAddress, outputAddress); }
    @Override public String toString() {
        return (productName == null ? "USB ADB" : productName) + String.format(" [%04x:%04x] ", vendorId, productId)
                + (serialNumber == null ? "" : serialNumber + " ") + path;
    }
}
