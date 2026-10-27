package com.example.carmirror.adb.usb;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.Build;
import android.util.Log;
import java.io.*;
import java.util.*;

/** Foreground-scoped USB discovery/permission owner. Call close when observation ends. */
public final class UsbDeviceRepository implements Closeable {
    /** Diagnostic trail for the OTG path: what was enumerated and why it was rejected. */
    static final String TAG = "CarMirrorUsb";
    public interface PermissionCallback { void onResult(UsbCandidate device, boolean granted); }
    public interface Listener { void onCandidatesChanged(List<UsbCandidate> candidates); }
    interface Backend extends Closeable {
        List<UsbCandidate> scan();
        boolean hasPermission(UsbCandidate c);
        void requestPermission(UsbCandidate c);
        UsbIo open(UsbCandidate c) throws IOException;
        void close();
    }
    private final Backend backend;
    private final Map<UsbCandidate, List<PermissionCallback>> pending = new HashMap<>();
    private final Map<UsbCandidate, UsbIo> opened = new HashMap<>();
    private boolean closed;
    private Listener listener;
    public UsbDeviceRepository(Context context) {
        AndroidBackend android = new AndroidBackend(context.getApplicationContext());
        backend = android;
        android.register(this);
    }
    UsbDeviceRepository(Backend backend) { this.backend = backend; }
    public synchronized void setListener(Listener listener) { this.listener = listener; }
    public synchronized List<UsbCandidate> scan() {
        if (closed) return Collections.emptyList();
        List<UsbCandidate> visible = Collections.unmodifiableList(new ArrayList<>(backend.scan()));
        for (UsbCandidate c : new ArrayList<>(opened.keySet())) {
            if (!visible.contains(c)) closeIo(opened.remove(c));
        }
        for (UsbCandidate c : new ArrayList<>(pending.keySet())) {
            if (!visible.contains(c)) finish(c, false);
        }
        return visible;
    }
    private synchronized void changed() {
        List<UsbCandidate> visible = scan();
        if (listener != null && !closed) listener.onCandidatesChanged(visible);
    }
    public synchronized void requestPermission(UsbCandidate c, PermissionCallback callback) {
        Objects.requireNonNull(callback);
        if (closed || !scan().contains(c)) {
            Log.i(TAG, "permission request skipped, candidate gone: " + c);
            callback.onResult(c, false);
            return;
        }
        if (backend.hasPermission(c)) { Log.i(TAG, "permission already granted: " + c); callback.onResult(c, true); return; }
        boolean first = !pending.containsKey(c);
        List<PermissionCallback> callbacks = pending.get(c);
        if (callbacks == null) { callbacks = new ArrayList<>(); pending.put(c, callbacks); }
        callbacks.add(callback);
        if (first) {
            Log.i(TAG, "requesting system USB permission: " + c);
            try { backend.requestPermission(c); }
            catch (RuntimeException e) { Log.w(TAG, "permission request failed: " + e); finish(c, false); }
        }
    }
    synchronized void permissionResult(String path, int deviceId, boolean granted) {
        for (UsbCandidate c : new ArrayList<>(pending.keySet())) {
            if (c.path.equals(path) && c.deviceId == deviceId) {
                boolean allowed = granted && scan().contains(c) && backend.hasPermission(c);
                Log.i(TAG, "permission result granted=" + granted + " usable=" + allowed + ": " + c);
                finish(c, allowed);
            }
        }
        changed();
    }
    private void finish(UsbCandidate c, boolean granted) {
        List<PermissionCallback> callbacks = pending.remove(c);
        if (callbacks != null) for (PermissionCallback callback : callbacks) callback.onResult(c, granted);
    }
    public synchronized UsbIo open(UsbCandidate c) throws IOException {
        if (closed || !scan().contains(c)) throw new IOException("USB device detached");
        if (!backend.hasPermission(c)) throw new IOException("USB permission not granted");
        Log.i(TAG, "opening USB interface: " + c);
        closeIo(opened.remove(c));
        UsbIo io = backend.open(c);
        opened.put(c, io);
        return io;
    }
    private static void closeIo(UsbIo io) {
        if (io != null) try { io.close(); } catch (IOException ignored) { }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        backend.close();
        for (UsbIo io : opened.values()) closeIo(io);
        opened.clear();
        for (UsbCandidate c : new ArrayList<>(pending.keySet())) finish(c, false);
        listener = null;
    }

    private static final class AndroidBackend implements Backend {
        final Context context;
        final UsbManager manager;
        final String action;
        BroadcastReceiver receiver;
        AndroidBackend(Context context) {
            this.context = context;
            manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            action = context.getPackageName() + ".USB_PERMISSION." + UUID.randomUUID();
        }
        void register(UsbDeviceRepository owner) {
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (action.equals(intent.getAction())) {
                        UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (d != null) owner.permissionResult(d.getDeviceName(), d.getDeviceId(),
                                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
                    } else owner.changed();
                }
            };
            IntentFilter filter = new IntentFilter(action);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            // USB broadcasts originate from the system; NOT_EXPORTED also accepts system UID.
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(receiver, filter);
        }
        @Override public List<UsbCandidate> scan() {
            List<UsbCandidate> result = new ArrayList<>();
            if (manager == null) return result;
            for (UsbDevice d : manager.getDeviceList().values()) {
                String serial = null;
                if (manager.hasPermission(d)) try { serial = d.getSerialNumber(); } catch (SecurityException ignored) { }
                for (int n = 0; n < d.getInterfaceCount(); n++) {
                    UsbInterface i = d.getInterface(n);
                    int[] addresses = new int[i.getEndpointCount()], types = new int[addresses.length];
                    for (int e = 0; e < addresses.length; e++) {
                        addresses[e] = i.getEndpoint(e).getAddress(); types[e] = i.getEndpoint(e).getType();
                    }
                    UsbCandidate c = UsbCandidate.fromInterface(d.getDeviceName(), d.getDeviceId(),
                            d.getVendorId(), d.getProductId(), d.getProductName(), serial, n,
                            i.getInterfaceClass(), i.getInterfaceSubclass(), i.getInterfaceProtocol(), addresses, types);
                    if (c != null) { result.add(c); break; }
                    Log.i(TAG, "interface " + n + " of " + d.getDeviceName()
                            + " is not an ADB interface: class=" + i.getInterfaceClass()
                            + " subclass=" + i.getInterfaceSubclass() + " protocol=" + i.getInterfaceProtocol()
                            + " endpoints=" + describe(addresses, types));
                }
                Log.i(TAG, "usb device " + d.getDeviceName() + " vid=" + hex(d.getVendorId())
                        + " pid=" + hex(d.getProductId()) + " product=" + d.getProductName()
                        + " interfaces=" + d.getInterfaceCount() + " permission=" + manager.hasPermission(d));
            }
            Collections.sort(result, (a, b) -> a.path.compareTo(b.path));
            return result;
        }
        UsbDevice device(UsbCandidate c) {
            if (manager == null) return null;
            UsbDevice d = manager.getDeviceList().get(c.path);
            return d != null && d.getDeviceId() == c.deviceId ? d : null;
        }
        @Override public boolean hasPermission(UsbCandidate c) {
            UsbDevice d = device(c); return d != null && manager.hasPermission(d);
        }
        @Override public void requestPermission(UsbCandidate c) {
            UsbDevice d = device(c);
            if (d == null) throw new IllegalStateException("USB device detached");
            // Mutable is required for UsbManager to fill EXTRA_DEVICE/EXTRA_PERMISSION_GRANTED.
            // Package targeting makes this safe and valid with Android 14's mutable PI restrictions.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent intent = PendingIntent.getBroadcast(context, c.deviceId,
                    new Intent(action).setPackage(context.getPackageName()), flags);
            manager.requestPermission(d, intent);
        }
        @Override public UsbIo open(UsbCandidate c) throws IOException {
            UsbDevice d = device(c);
            if (d == null || !manager.hasPermission(d)) throw new IOException("USB unavailable or unauthorized");
            UsbDeviceConnection connection = manager.openDevice(d);
            if (connection == null) throw new IOException("Cannot open USB device");
            try {
                UsbInterface iface = d.getInterface(c.interfaceIndex);
                UsbEndpoint in = null, out = null;
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint endpoint = iface.getEndpoint(e);
                    if (endpoint.getAddress() == c.inputAddress) in = endpoint;
                    if (endpoint.getAddress() == c.outputAddress) out = endpoint;
                }
                if (in == null || out == null || !connection.claimInterface(iface, true))
                    throw new IOException("Cannot claim USB ADB interface");
                return new AndroidUsbIo(connection, iface, in, out);
            } catch (IOException | RuntimeException e) { connection.close(); throw e; }
        }
        @Override public void close() {
            if (receiver != null) { context.unregisterReceiver(receiver); receiver = null; }
        }
        private static String hex(int value) { return String.format("%04x", value); }
        private static String describe(int[] addresses, int[] types) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < addresses.length; i++) {
                if (i > 0) out.append(',');
                out.append(String.format("addr=%02x type=%d", addresses[i], types[i]));
            }
            return out.toString();
        }
    }
}
