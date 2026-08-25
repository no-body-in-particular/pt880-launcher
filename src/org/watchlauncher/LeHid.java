package org.watchlauncher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

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

        /** The list of devices seen has changed. */
        void onLeHidFound();

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
    private Listener listener;

    /** One advertiser seen during a scan. */
    public static class Found {
        public BluetoothDevice device;
        public String name;
        public boolean hid;
        public int rssi;

        public String label() {
            String n = (name == null || name.length() == 0)
                    ? device.getAddress() : name;
            return hid ? (n + "  HID") : n;
        }
    }

    /** LE peripherals stop advertising once they are connected, and many sleep
     *  when idle, so a scan that finds nothing usually means the device needs
     *  waking or putting back into pairing mode rather than that anything is
     *  broken. Twenty seconds is long enough to tell those apart. */
    private static final long SCAN_MS = 20000;

    private BluetoothGatt gatt;
    private final List<BluetoothGattCharacteristic> toSubscribe =
            new ArrayList<BluetoothGattCharacteristic>();
    private boolean scanning = false;
    private final List<Found> found = new ArrayList<Found>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    public LeHid(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
        adapter = BluetoothAdapter.getDefaultAdapter();
    }

    /*
     * Every callback below arrives on a binder thread, not the UI thread: the
     * LE scan callback and all of the GATT ones. The listener is a screen, and
     * a screen redraws itself, so calling it directly touches views off the
     * main thread -- which throws CalledFromWrongThreadException, and an
     * uncaught exception on a binder thread takes the whole process with it.
     *
     * On a watch whose launcher IS this app, that looks like the screen simply
     * going back to the home screen and nothing happening, with no crash to
     * see. So everything the listener is told goes through the main thread,
     * and each hop is wrapped: a screen that has been popped in the meantime
     * must not be able to kill the app either.
     */
    private void post(final Runnable r) {
        ui.post(new Runnable() {
            public void run() {
                try { r.run(); } catch (Exception e) { /* the screen has gone */ }
            }
        });
    }

    private void say(final String s) {
        final Listener l = listener;
        if (l == null) return;
        post(new Runnable() { public void run() { l.onLeHidStatus(s); } });
    }

    private void changed() {
        final Listener l = listener;
        if (l == null) return;
        post(new Runnable() { public void run() { l.onLeHidFound(); } });
    }

    private void report(final boolean keyboard, final byte[] v) {
        final Listener l = listener;
        if (l == null) return;
        post(new Runnable() { public void run() { l.onLeHidReport(keyboard, v); } });
    }

    /** The report screen takes over the callbacks when it opens, and the list
     *  screen takes them back when it returns. */
    public void setListener(Listener l) { listener = l; }

    public List<Found> devices() { return found; }

    public boolean scanning() { return scanning; }

    // ---------------------------------------------------------------- scan

    /** LE discovery is a different call from classic discovery, which is why
     *  these devices never appeared properly in the ordinary scan. */
    public void scan() {
        if (adapter == null) { say("no bluetooth"); return; }
        if (scanning) return;

        // A classic inquiry and an LE scan share one radio, and on this
        // controller the inquiry wins: the LE callback simply never fires.
        // Entering the Bluetooth screen starts a twelve second inquiry, so
        // without this the scan looked hung for exactly that long and then
        // found nothing anyway.
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
                say("stopped the classic scan first");
            }
        } catch (Exception e) { /* carry on */ }

        found.clear();
        changed();

        scanning = adapter.startLeScan(scanCallback);
        say(scanning ? "scanning 20s..." : "LE scan refused by the stack");
        if (scanning) {
            ui.removeCallbacks(endScan);
            ui.postDelayed(endScan, SCAN_MS);
        }
    }

    private final Runnable endScan = new Runnable() {
        public void run() {
            stopScan();
            say(found.isEmpty()
                    ? "nothing advertising. wake the device or put it in pairing mode"
                    : (found.size() + " found"));
        }
    };

    public void stopScan() {
        ui.removeCallbacks(endScan);
        if (adapter == null || !scanning) return;
        try { adapter.stopLeScan(scanCallback); } catch (Exception e) { /* ignore */ }
        scanning = false;
    }

    private final BluetoothAdapter.LeScanCallback scanCallback =
            new BluetoothAdapter.LeScanCallback() {
        public void onLeScan(BluetoothDevice device, int rssi, byte[] record) {
            String addr = device.getAddress();
            for (int i = 0; i < found.size(); i++) {
                if (found.get(i).device.getAddress().equals(addr)) {
                    found.get(i).rssi = rssi;      // keep the freshest signal
                    return;
                }
            }
            Found f = new Found();
            f.device = device;
            f.rssi = rssi;
            try { f.name = device.getName(); } catch (Exception e) { f.name = null; }
            // Only a hint. Plenty of HID devices advertise the service in the
            // scan response rather than the advertisement, or not at all until
            // after connecting, so this decides how a row is labelled and
            // nothing else -- the choice of what to connect to is yours.
            f.hid = advertisesHid(record);
            found.add(f);
            changed();
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

    /** How long to wait for a connection before saying so. The stack's own
     *  timeout is around thirty seconds and reports nothing until it expires,
     *  which is indistinguishable from being hung. */
    private static final long CONNECT_MS = 18000;

    private BluetoothDevice connecting;
    private boolean triedAutoConnect = false;
    private boolean retried = false;
    private long connectStartedAt = 0;

    public void connect(BluetoothDevice d) {
        connect(d, false);
    }

    /**
     * @param auto the autoConnect flag. False asks the stack to connect now;
     *             true queues the connection until the device is seen, which
     *             is a different code path in this stack and sometimes
     *             succeeds where the direct one silently does not.
     */
    private void connect(BluetoothDevice d, boolean auto) {
        close();
        connecting = d;
        triedAutoConnect = auto;
        connectStartedAt = System.currentTimeMillis();

        String bond;
        switch (d.getBondState()) {
            case BluetoothDevice.BOND_BONDED: bond = "bonded"; break;
            case BluetoothDevice.BOND_BONDING: bond = "bonding"; break;
            default: bond = "not bonded"; break;
        }
        say("connecting to " + d.getAddress() + (auto ? " (auto)" : "") + ", " + bond);

        gatt = d.connectGatt(ctx, auto, callback);
        if (gatt == null) { say("connectGatt refused"); return; }

        ui.removeCallbacks(connectTimeout);
        ui.postDelayed(connectTimeout, CONNECT_MS);
    }

    /** Says how long it has been waiting, so a slow connect is visibly alive
     *  rather than apparently hung. */
    public String connectProgress() {
        if (connectStartedAt == 0) return "";
        long s = (System.currentTimeMillis() - connectStartedAt) / 1000;
        return s + "s";
    }

    /** Service discovery can hang exactly as the connect can, and reports
     *  nothing when it does. */
    private static final long DISCOVER_MS = 15000;

    private final Runnable discoverTimeout = new Runnable() {
        public void run() {
            say("no services after " + (DISCOVER_MS / 1000) + "s.");
            say("this usually means the link is up but unencrypted");
            say("and the device is refusing to describe itself.");
        }
    };

    private final Runnable connectTimeout = new Runnable() {
        public void run() {
            BluetoothDevice d = connecting;
            if (d == null) return;
            if (!triedAutoConnect) {
                say("no answer in " + (CONNECT_MS / 1000) + "s, retrying the other way");
                connect(d, true);
                return;
            }
            connecting = null;
            connectStartedAt = 0;
            close();
            say("no answer. it is probably connected to something else,");
            say("or asleep. wake it or unpair it from your phone first.");
        }
    };

    public void close() {
        ui.removeCallbacks(connectTimeout);
        ui.removeCallbacks(discoverTimeout);
        if (gatt == null) return;
        // close() rather than just disconnect(): this stack leaks a client
        // interface per unclosed GATT and stops connecting once they run out,
        // silently and permanently until the app is restarted.
        try { gatt.close(); } catch (Exception e) { /* ignore */ }
        gatt = null;
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {

        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothGatt.STATE_CONNECTED) {
                ui.removeCallbacks(connectTimeout);
                connecting = null;
                connectStartedAt = 0;
                say("connected (status " + status + "), discovering");
                ui.removeCallbacks(discoverTimeout);
                ui.postDelayed(discoverTimeout, DISCOVER_MS);
                if (!g.discoverServices()) say("discoverServices refused");
            } else if (state == BluetoothGatt.STATE_DISCONNECTED) {
                ui.removeCallbacks(discoverTimeout);
                if (status == 133 && !retried && connecting == null) {
                    // 133 is GATT_ERROR, this stack's catch-all. After a
                    // successful connect it usually means the link collapsed
                    // because the device wanted it encrypted. A single retry
                    // is the standard workaround and sometimes lands.
                    retried = true;
                    final BluetoothDevice again = g.getDevice();
                    say("133 - retrying once");
                    ui.postDelayed(new Runnable() {
                        public void run() { connect(again, false); }
                    }, 1200);
                    return;
                }
                // Status 8 is a supervision timeout, 19 a remote disconnect,
                // 22 a local one. 133 is the catch-all this stack returns when
                // it has nothing better, and usually means the link dropped
                // before encryption completed.
                say("disconnected (status " + status + ")");
            }
        }

        public void onServicesDiscovered(BluetoothGatt g, int status) {
            ui.removeCallbacks(discoverTimeout);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                say("discovery failed (" + status + ")");
                return;
            }
            BluetoothGattService hid = g.getService(HID_SERVICE);
            if (hid == null) {
                say("no HID service. it offers:");
                for (BluetoothGattService s : g.getServices()) {
                    say("  " + shortUuid(s.getUuid()) + "  " + serviceName(s.getUuid()));
                }
                return;
            }
            say("HID service found");

            // Reading a protected characteristic is the documented way to make
            // the stack start pairing, and its status is the answer we are
            // after: 5 is insufficient authentication, 15 insufficient
            // encryption, and either means the device wants a bonded link.
            BluetoothGattCharacteristic map = hid.getCharacteristic(REPORT_MAP);
            if (map != null) {
                say("reading report map to provoke pairing...");
                if (g.readCharacteristic(map)) return;   // continue in onCharacteristicRead
                say("read refused outright");
            }

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

        public void onCharacteristicRead(BluetoothGatt g,
                                         BluetoothGattCharacteristic c, int status) {
            say("report map read -> " + authWord(status));
            // Whatever the answer, carry on: if pairing was triggered the
            // subscribe below may now succeed, and if it was not, its status
            // will say the same thing again.
            BluetoothGattService hid = g.getService(HID_SERVICE);
            if (hid == null) return;
            toSubscribe.clear();
            for (BluetoothGattCharacteristic ch : hid.getCharacteristics()) {
                UUID u = ch.getUuid();
                if (BOOT_KB_IN.equals(u) || BOOT_MOUSE_IN.equals(u) || REPORT.equals(u)) {
                    toSubscribe.add(ch);
                }
            }
            BluetoothGattCharacteristic mode = hid.getCharacteristic(PROTOCOL_MODE);
            if (mode != null) {
                mode.setValue(new byte[]{0});
                mode.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                g.writeCharacteristic(mode);
            }
            subscribeNext(g);
        }

        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d,
                                      int status) {
            // Status 5 is insufficient authentication and 15 insufficient
            // encryption: both mean the device wants a bonded link, and both
            // are the answer to whether this can work at all.
            say("subscribe " + shortUuid(d.getCharacteristic().getUuid())
                    + " -> " + authWord(status));
            subscribeNext(g);
        }

        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null) return;
            report(!BOOT_MOUSE_IN.equals(c.getUuid()), v);
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

    /** The few standard services worth recognising by name when HID is
     *  missing, since the numbers alone say nothing about what went wrong. */
    /** GATT statuses worth having words for. */
    static String authWord(int status) {
        switch (status) {
            case 0:   return "ok";
            case 5:   return "status 5 - needs authentication (a bond)";
            case 8:   return "status 8 - link timed out";
            case 15:  return "status 15 - needs encryption (a bond)";
            case 133: return "status 133 - generic failure, usually the link dropping";
            case 137: return "status 137 - authentication failed";
            default:  return "status " + status;
        }
    }

    /**
     * Ask the stack to bond, and watch what happens.
     *
     * LE bonding is unreliable on this Android version, which is why this is a
     * deliberate action rather than something attempted silently. Without the
     * receiver below it was also untestable: createBond() returns true the
     * moment the request is accepted and says nothing about the outcome, so a
     * bond that failed a second later looked identical to one that worked.
     */
    public void bond(BluetoothDevice d) {
        watchBonding();
        try {
            say(d.createBond() ? "bond requested, waiting..." : "createBond refused");
        } catch (Exception e) {
            say("createBond threw");
        }
    }

    private boolean bondWatch = false;

    private void watchBonding() {
        if (bondWatch) return;
        bondWatch = true;
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction("android.bluetooth.device.action.PAIRING_REQUEST");
        try { ctx.registerReceiver(bondRx, f); } catch (Exception e) { bondWatch = false; }
    }

    public void stopWatchingBonding() {
        if (!bondWatch) return;
        bondWatch = false;
        try { ctx.unregisterReceiver(bondRx); } catch (Exception e) { /* ignore */ }
    }

    private final BroadcastReceiver bondRx = new BroadcastReceiver() {
        public void onReceive(Context c, Intent in) {
            String a = in.getAction();
            if ("android.bluetooth.device.action.PAIRING_REQUEST".equals(a)) {
                BluetoothDevice d =
                        in.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int variant = in.getIntExtra(
                        "android.bluetooth.device.extra.PAIRING_VARIANT", -1);
                int key = in.getIntExtra(
                        "android.bluetooth.device.extra.PAIRING_KEY", -1);
                say("pairing request, variant " + variant
                        + (key >= 0 ? (" key " + key) : ""));
                // Just Works is what a mouse or trackball almost always asks
                // for; answering it is the whole of the handshake.
                try {
                    java.lang.reflect.Method m = BluetoothDevice.class
                            .getMethod("setPairingConfirmation", boolean.class);
                    m.invoke(d, true);
                    say("confirmed");
                } catch (Exception e) {
                    say("could not confirm: " + e.getClass().getSimpleName());
                }
                return;
            }
            int st = in.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
            int reason = in.getIntExtra("android.bluetooth.device.extra.REASON", -1);
            if (st == BluetoothDevice.BOND_BONDED) say("BONDED");
            else if (st == BluetoothDevice.BOND_BONDING) say("bonding...");
            else if (st == BluetoothDevice.BOND_NONE) say("bond failed, reason " + reason);
        }
    };

    static String serviceName(UUID u) {
        String s = shortUuid(u);
        if (s.equals("0x1800")) return "generic access";
        if (s.equals("0x1801")) return "generic attribute";
        if (s.equals("0x180a")) return "device information";
        if (s.equals("0x180f")) return "battery";
        if (s.equals("0x1812")) return "human interface device";
        if (s.equals("0x1813")) return "scan parameters";
        if (s.equals("0xfe59")) return "device firmware update";
        return "";
    }

    static String shortUuid(UUID u) {
        String s = u.toString();
        return "0x" + s.substring(4, 8);
    }
}
