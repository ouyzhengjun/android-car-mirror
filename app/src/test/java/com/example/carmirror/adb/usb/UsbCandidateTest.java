package com.example.carmirror.adb.usb;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class UsbCandidateTest {
    private UsbCandidate candidate(String path, int id) {
        return UsbCandidate.fromInterface(path, id, 123, 456, "Car", null,
                0, 0xff, 0x42, 1, new int[]{0x81, 2}, new int[]{2, 2});
    }
    @Test public void rejectsNonAdbInterface() {
        assertNull(UsbCandidate.fromInterface("a", 1, 1, 2, "Car", null,
                0, 0xff, 0x42, 2, new int[]{0x81, 2}, new int[]{2, 2}));
    }
    @Test public void requiresBulkEndpointsInBothDirections() {
        assertNull(UsbCandidate.fromInterface("a", 1, 1, 2, "Car", null,
                0, 0xff, 0x42, 1, new int[]{0x81, 2}, new int[]{3, 2}));
        assertNull(UsbCandidate.fromInterface("a", 1, 1, 2, "Car", null,
                0, 0xff, 0x42, 1, new int[]{1, 2}, new int[]{2, 2}));
    }
    @Test public void sameModelHasDistinctIdentityAndVisiblePath() {
        UsbCandidate a = candidate("/dev/bus/usb/001/002", 2);
        UsbCandidate b = candidate("/dev/bus/usb/001/003", 3);
        assertNotEquals(a, b);
        assertTrue(a.toString().contains("/dev/bus/usb/001/002"));
        assertNotEquals(a, candidate("/dev/bus/usb/001/002", 4));
    }
    @Test public void permissionDenialIsDeliveredOnceAndDoesNotOpen() throws Exception {
        FakeBackend backend = new FakeBackend(candidate("a", 1));
        UsbDeviceRepository repo = new UsbDeviceRepository(backend);
        List<Boolean> results = new ArrayList<>();
        repo.requestPermission(backend.devices.get(0), (device, granted) -> results.add(granted));
        repo.permissionResult("a", 1, false);
        repo.permissionResult("a", 1, true);
        assertEquals(Arrays.asList(false), results);
        try { repo.open(backend.devices.get(0)); fail(); } catch (IOException expected) { }
        assertEquals(0, backend.opens);
    }
    @Test public void pendingPermissionPromptCanBeReissued() throws Exception {
        FakeBackend backend = new FakeBackend(candidate("a", 1));
        UsbDeviceRepository repo = new UsbDeviceRepository(backend);
        List<Boolean> results = new ArrayList<>();
        // A prompt the system dropped leaves the callbacks pending with no retry path.
        repo.requestPermission(backend.devices.get(0), (device, granted) -> results.add(granted));
        repo.requestPermission(backend.devices.get(0), (device, granted) -> results.add(granted));
        assertEquals(1, backend.prompts);
        repo.reissuePermission(backend.devices.get(0));
        assertEquals(2, backend.prompts);
        backend.permitted = true;
        repo.permissionResult("a", 1, true);
        assertEquals(Arrays.asList(true, true), results);
    }
    @Test public void reissueDoesNothingOnceGranted() throws Exception {
        FakeBackend backend = new FakeBackend(candidate("a", 1));
        backend.permitted = true;
        UsbDeviceRepository repo = new UsbDeviceRepository(backend);
        repo.reissuePermission(backend.devices.get(0));
        assertEquals(0, backend.prompts);
    }
    @Test public void detachRemovesCandidateClosesResourceAndFailsPendingPermission() throws Exception {
        FakeBackend backend = new FakeBackend(candidate("a", 1));
        backend.permitted = true;
        UsbDeviceRepository repo = new UsbDeviceRepository(backend);
        UsbIo io = repo.open(backend.devices.get(0));
        backend.permitted = false;
        List<Boolean> results = new ArrayList<>();
        repo.requestPermission(backend.devices.get(0), (device, granted) -> results.add(granted));
        backend.devices.clear();
        assertTrue(repo.scan().isEmpty());
        assertTrue(backend.io.closed);
        assertEquals(Arrays.asList(false), results);
        try { io.read(new byte[1], 0, 1, 10); fail(); } catch (IOException expected) { }
    }
    @Test public void stalePermissionCannotAuthorizeReattachedDevice() {
        FakeBackend backend = new FakeBackend(candidate("a", 1));
        UsbDeviceRepository repo = new UsbDeviceRepository(backend);
        List<Boolean> results = new ArrayList<>();
        repo.requestPermission(backend.devices.get(0), (device, granted) -> results.add(granted));
        backend.devices.set(0, candidate("a", 2));
        repo.scan();
        repo.permissionResult("a", 1, true);
        assertEquals(Arrays.asList(false), results);
    }
    static class FakeBackend implements UsbDeviceRepository.Backend {
        List<UsbCandidate> devices = new ArrayList<>();
        boolean permitted; int opens, prompts; FakeIo io = new FakeIo();
        FakeBackend(UsbCandidate candidate) { devices.add(candidate); }
        public List<UsbCandidate> scan() { return new ArrayList<>(devices); }
        public boolean hasPermission(UsbCandidate c) { return permitted; }
        public void requestPermission(UsbCandidate c) { prompts++; }
        public UsbIo open(UsbCandidate c) { opens++; return io; }
        public void close() { }
    }
    static class FakeIo implements UsbIo {
        boolean closed;
        public int read(byte[] b, int o, int n, int t) throws IOException { if(closed) throw new IOException(); return 0; }
        public int write(byte[] b, int o, int n, int t) throws IOException { return read(b,o,n,t); }
        public void close() { closed = true; }
    }
}
