package org.watchlauncher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keyboards and mice that speak HID over GATT, without Android's HID host.
 *
 * This build has a Bluetooth Classic HID host and no HOGP, so an LE keyboard
 * or mouse cannot be bonded or used the ordinary way -- {@code getType()}
 * reports LE and the platform has nothing to connect it with. But API 19 does
 * have a full GATT client, and HID over GATT is only a GATT service: 0x1812,
 * with the key presses arriving as notifications.
 *
 * So the app can be its own HID host. It reads the reports itself and turns
 * them into the same events the two hardware buttons produce, which is all
 * this launcher ever consumed anyway.
 *
 * <h3>Boot protocol</h3>
 *
 * A HID device normally describes its reports in a Report Map that has to be
 * parsed to know what any byte means. Boot protocol sidesteps that entirely:
 * it is the fixed format a PC BIOS understands, so a keyboard report is always
 * eight bytes -- modifiers, a reserved byte, then up to six usage codes -- and
 * a mouse report is buttons followed by signed X and Y. Setting Protocol Mode
 * to 0 asks for it, and then nothing needs decoding tables.
 *
 * <h3>The part that may not work</h3>
 *
 * Most HOGP devices refuse to send reports over an unencrypted link, and
 * encryption means LE bonding, which is exactly what Android 4.4 is poor at.
 * Reading a protected characteristic is supposed to trigger pairing from
 * inside the stack even when createBond() will not do it. Whether this
 * vendor's stack manages that is not something the documentation settles, so
 * this class reports what actually happens at each step rather than assuming.
 */
public class LeHid {

    public interface Listener {
        /** Progress, for the screen. */
        void onLeHidStatus(String line);

        /** A raw boot report. {@code keyboard} false means it is a mouse. */
        void onLeHidReport(boolean keyboard, byte[] report);
    }

    private static final UUID HID_SERVICE   = uuid(0x1812);
    private static final UUID PROTOCOL_MODE = uuid(0x2A4E);
    private static final UUID BOOT_KB_IN    = uuid(0x2A22);
    private static final UUID BOOT_MOUSE_IN = uuid(0x2A33);
    private static final UUID REPORT        = uuid(0x2A4D);
    private static final UUID REPORT_MAP    = uuid(0x2A4B);
    private static final UUID CCCD          = uuid(0x2902);

    private static UUID uuid(int short16) {
        return UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb",
                short16));
    }

    private final Context ctx;
    private final BluetoothAdapter adapter;
    private final Listener listener;

    private BluetoothGatt gatt;
    private final List<BluetoothGattCharacteristic> toSubscribe =
            new ArrayList<BluetoothGattCharacteristic>();
    private boolean scanning = false;
    private final List<String> seen = new ArrayList<String>();

    public LeHid(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
        adapter = BluetoothAdapter.getDefaultAdapter();
    }

    private void say(String s) {
        if (listener != null) listener.onLeHidStatus(s);
    }

    // ---------------------------------------------------------------- scan

    /** LE discovery is a different call from classic discovery, which is why
     *  these devices never appeared properly in the ordinary scan. */
    public void scan() {
        if (adapter == null) { say("no bluetooth"); return; }
        if (scanning) return;
        seen.clear();
        scanning = adapter.startLeScan(scanCallback);
        say(scanning ? "scanning for LE input..." : "LE scan refused");
    }

    public void stopScan() {
        if (adapter == null || !scanning) return;
        try { adapter.stopLeScan(scanCallback); } catch (Exception e) { /* ignore */ }
        scanning = false;
    }

    private final BluetoothAdapter.LeScanCallback scanCallback =
            new BluetoothAdapter.LeScanCallback() {
        public void onLeScan(BluetoothDevice device, int rssi, byte[] record) {
            String addr = device.getAddress();
            if (seen.contains(addr)) return;
            seen.add(addr);

            String name = null;
            try { name = device.getName(); } catch (Exception e) { /* ignore */ }
            boolean hid = advertisesHid(record);
            say((name == null ? addr : name) + (hid ? "  HID" : "") + "  " + rssi);
            if (hid) {
                stopScan();
                connect(device);
            }
        }
    };

    /**
     * Does the advertisement list the HID service?
     *
     * The record is a sequence of length-prefixed AD structures; types 0x02
     * and 0x03 carry lists of 16-bit service UUIDs.
     */
    static boolean advertisesHid(byte[] record) {
        if (record == null) return false;
        int i = 0;
        while (i < record.length - 1) {
            int len = record[i] & 0xFF;
            if (len == 0) break;
            int type = record[i + 1] & 0xFF;
            if (type == 0x02 || type == 0x03) {
                for (int j = i + 2; j + 1 < i + 1 + len && j + 1 < record.length; j += 2) {
                    int u = (record[j] & 0xFF) | ((record[j + 1] & 0xFF) << 8);
                    if (u == 0x1812) return true;
                }
            }
            i += len + 1;
        }
        return false;
    }

    // ---------------------------------------------------------------- connect

    public void connect(BluetoothDevice d) {
        say("connecting to " + d.getAddress());
        close();
        // autoConnect false: connect now and report failure, rather than
        // waiting indefinitely for the device to come into range.
        gatt = d.connectGatt(ctx, false, callback);
        if (gatt == null) say("connectGatt refused");
    }

    public void close() {
        if (gatt == null) return;
        try { gatt.close(); } catch (Exception e) { /* ignore */ }
        gatt = null;
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {

        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothGatt.STATE_CONNECTED) {
                say("connected (status " + status + "), discovering");
                g.discoverServices();
            } else if (state == BluetoothGatt.STATE_DISCONNECTED) {
                // Status 8 is a supervision timeout, 19 a remote disconnect,
                // 22 a local one. 133 is the catch-all this stack returns when
                // it has nothing better, and usually means the link dropped
                // before encryption completed.
                say("disconnected (status " + status + ")");
            }
        }

        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                say("discovery failed (" + status + ")");
                return;
            }
            BluetoothGattService hid = g.getService(HID_SERVICE);
            if (hid == null) {
                StringBuilder b = new StringBuilder("no HID service. found: ");
                for (BluetoothGattService s : g.getServices()) {
                    b.append(shortUuid(s.getUuid())).append(' ');
                }
                say(b.toString());
                return;
            }
            say("HID service found");

            toSubscribe.clear();
            for (BluetoothGattCharacteristic c : hid.getCharacteristics()) {
                UUID u = c.getUuid();
                if (BOOT_KB_IN.equals(u) || BOOT_MOUSE_IN.equals(u) || REPORT.equals(u)) {
                    toSubscribe.add(c);
                }
                say("  char " + shortUuid(u) + " props 0x"
                        + Integer.toHexString(c.getProperties()));
            }

            // Ask for boot protocol before subscribing, so the reports that
            // arrive are the fixed-format ones rather than whatever the report
            // map describes.
            BluetoothGattCharacteristic mode = hid.getCharacteristic(PROTOCOL_MODE);
            if (mode != null) {
                mode.setValue(new byte[]{0});
                mode.setWriteType(
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                boolean ok = g.writeCharacteristic(mode);
                say("boot protocol requested: " + ok);
            } else {
                say("no protocol mode characteristic");
            }
            subscribeNext(g);
        }

        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d,
                                      int status) {
            // Status 5 is insufficient authentication and 15 insufficient
            // encryption: both mean the device wants a bonded link, and both
            // are the answer to whether this can work at all.
            say("subscribe " + shortUuid(d.getCharacteristic().getUuid())
                    + " -> " + (status == 0 ? "ok" : ("status " + status)));
            subscribeNext(g);
        }

        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null || listener == null) return;
            boolean keyboard = !BOOT_MOUSE_IN.equals(c.getUuid());
            listener.onLeHidReport(keyboard, v);
        }
    };

    /** One descriptor write at a time: this stack drops overlapping GATT
     *  operations silently rather than queueing them. */
    private void subscribeNext(BluetoothGatt g) {
        if (toSubscribe.isEmpty()) { say("subscriptions done"); return; }
        BluetoothGattCharacteristic c = toSubscribe.remove(0);
        try {
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor cccd = c.getDescriptor(CCCD);
            if (cccd == null) { say("  " + shortUuid(c.getUuid()) + " has no CCCD"); subscribeNext(g); return; }
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!g.writeDescriptor(cccd)) { say("  write refused"); subscribeNext(g); }
        } catch (Exception e) {
            subscribeNext(g);
        }
    }

    static String shortUuid(UUID u) {
        String s = u.toString();
        return "0x" + s.substring(4, 8);
    }
}
