package org.watchlauncher;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Discovery, bonding and profile connect, driven from the app.
 *
 * The stock Settings Bluetooth screen cannot be used on this watch: D-pad
 * focus does not move in it and its list only renders the rows that happen to
 * fit, so most discovered devices are invisible. The bond made here is a
 * normal system-level pairing all the same -- {@code createBond()} writes the
 * link key into bluedroid's {@code bt_config.xml}, the connection is owned by
 * {@code com.android.bluetooth}, it survives reboots and reconnects itself,
 * and every app on the watch gets the benefit.
 *
 * <h3>Two kinds of device</h3>
 *
 * Headphones and keyboards need opposite handling at the one moment that
 * matters. A cheap headset uses a fixed {@code 0000} PIN or Just Works, so the
 * request is answered automatically and the user never sees it. A keyboard has
 * no display and expects <em>you</em> to type a code <em>on it</em> -- so the
 * code has to be put on the watch's screen and left there, and answering the
 * request automatically would break the pairing rather than complete it.
 *
 * After bonding, audio devices are connected over A2DP and keyboards over the
 * HID host profile, which has no public constant at this API level.
 */
public class BtHelper {

    /** {@code BluetoothProfile.INPUT_DEVICE} -- @hide on API 19, and the only
     *  way to bring up a keyboard's HID link from an app. */
    private static final int PROFILE_INPUT_DEVICE = 4;

    private static final String ACTION_PAIRING_REQUEST =
            "android.bluetooth.device.action.PAIRING_REQUEST";
    private static final String EXTRA_PAIRING_VARIANT =
            "android.bluetooth.device.extra.PAIRING_VARIANT";
    private static final String EXTRA_PAIRING_KEY =
            "android.bluetooth.device.extra.PAIRING_KEY";
    private static final String ACTION_HID_STATE =
            "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED";

    // Pairing variants, from the framework's BluetoothDevice.
    private static final int VARIANT_PIN = 0;
    private static final int VARIANT_PASSKEY_CONFIRMATION = 2;
    private static final int VARIANT_CONSENT = 3;
    private static final int VARIANT_DISPLAY_PASSKEY = 4;
    private static final int VARIANT_DISPLAY_PIN = 5;

    public interface Listener {
        void onBtChanged(String status);
    }

    /** One row of the scan list, already resolved to something readable. */
    public static class Dev {
        public final BluetoothDevice device;
        public String name;         // what it called itself, or null
        public String label;        // name, else vendor, else address
        public String detail;       // what kind of thing it is
        public int glyph;
        public boolean bonded;
        public boolean connected;

        Dev(Context c, BluetoothDevice d) {
            device = d;
            refresh(c);
        }

        void refresh(Context c) {
            try { name = device.getName(); } catch (Exception e) { name = null; }
            label = BtNames.label(c, device, name);
            detail = BtNames.detail(c, device, name);
            glyph = BtNames.glyph(device);
            bonded = (device.getBondState() == BluetoothDevice.BOND_BONDED);
        }
    }

    private final Context ctx;
    private final List<Dev> found = new ArrayList<Dev>();
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final BluetoothAdapter adapter;
    private final Random random = new Random();

    private BluetoothA2dp a2dp;
    private BluetoothProfile hid;
    private BluetoothDevice pendingConnect;
    private String status = "";
    private String prompt = "";
    private boolean registered = false;

    public BtHelper(Context c) {
        ctx = c.getApplicationContext();
        adapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) { listeners.remove(l); }

    public String status() { return status; }

    /** The pairing code a keyboard is waiting to be told, or "" when there is
     *  nothing for the user to do. */
    public String prompt() { return prompt; }

    public void clearPrompt() { prompt = ""; }

    public List<Dev> devices() { return found; }

    public boolean available() { return adapter != null; }

    public boolean enabled() { return adapter != null && adapter.isEnabled(); }

    public boolean scanning() {
        try { return adapter != null && adapter.isDiscovering(); }
        catch (Exception e) { return false; }
    }

    private void say(String s) {
        status = s;
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).onBtChanged(s);
    }

    // ---------------------------------------------------------------- lifecycle

    public void start() {
        if (adapter == null) { say("No Bluetooth"); return; }
        if (registered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(ACTION_PAIRING_REQUEST);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(ACTION_HID_STATE);
        ctx.registerReceiver(receiver, f);
        registered = true;

        if (!adapter.isEnabled()) adapter.enable();
        adapter.getProfileProxy(ctx, proxyListener, BluetoothProfile.A2DP);
        adapter.getProfileProxy(ctx, proxyListener, PROFILE_INPUT_DEVICE);
        loadBonded();
    }

    public void stop() {
        if (!registered) return;
        try { ctx.unregisterReceiver(receiver); } catch (Exception e) { /* ignore */ }
        registered = false;
        cancelScan();
    }

    private void loadBonded() {
        if (adapter == null) return;
        Set<BluetoothDevice> set = adapter.getBondedDevices();
        if (set == null) return;
        for (BluetoothDevice d : set) addOrUpdate(d);
    }

    // ---------------------------------------------------------------- discovery

    public void scan() {
        if (adapter == null) return;
        if (!adapter.isEnabled()) { adapter.enable(); say("Turning Bluetooth on"); return; }
        found.clear();
        prompt = "";
        loadBonded();                       // bonded devices first, always
        if (adapter.isDiscovering()) adapter.cancelDiscovery();
        boolean ok = adapter.startDiscovery();
        say(ok ? "Scanning..." : "Scan failed");
    }

    public void cancelScan() {
        try { if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery(); }
        catch (Exception e) { /* ignore */ }
    }

    private void addOrUpdate(BluetoothDevice d) {
        for (int i = 0; i < found.size(); i++) {
            if (found.get(i).device.getAddress().equals(d.getAddress())) {
                found.get(i).refresh(ctx);
                return;
            }
        }
        found.add(new Dev(ctx, d));
    }

    // ---------------------------------------------------------------- pairing

    /** Pair with a known address, whether or not it is in the current scan --
     *  buds only advertise while they are in pairing mode. */
    public void pairAddress(String addr) {
        if (adapter == null || addr == null) return;
        try {
            BluetoothDevice d = adapter.getRemoteDevice(addr.toUpperCase());
            addOrUpdate(d);
            pairAndConnect(d);
        } catch (Exception e) {
            say("Bad address");
        }
    }

    /** Bond if needed, then bring up whichever profile the device is for. */
    public void pairAndConnect(BluetoothDevice d) {
        if (adapter == null) return;
        cancelScan();
        pendingConnect = d;
        prompt = "";
        if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
            connectProfile(d);
        } else {
            say(BtNames.isKeyboard(d) ? "Pairing keyboard..." : "Pairing...");
            try {
                d.createBond();
            } catch (Exception e) {
                say("Pair failed");
            }
        }
    }

    /** Drop the bond, so a device that paired wrong can be tried again. */
    public void unpair(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("removeBond");
            m.invoke(d);
            say("Unpaired");
        } catch (Exception e) {
            say("Unpair failed");
        }
    }

    /**
     * Answer the pairing request -- or deliberately do not.
     *
     * A keyboard is the one case where the right move is to say nothing and
     * put the code on screen: the remote end has no display and is waiting for
     * the code to be typed into it. Confirming from here would answer a
     * question the keyboard never asked and leave the bond half made.
     */
    private void onPairingRequest(BluetoothDevice d, int variant, int key) {
        if (d == null) return;
        boolean keyboard = BtNames.isKeyboard(d);

        try {
            if (variant == VARIANT_DISPLAY_PASSKEY || variant == VARIANT_DISPLAY_PIN) {
                prompt = "Type " + pad(key) + " then Enter";
                say("Waiting for keyboard");
                return;
            }

            if (variant == VARIANT_PIN) {
                if (keyboard) {
                    // No fixed PIN exists for a keyboard: we choose one, hand
                    // it to the stack, and show it for the user to type.
                    String pin = pad(100000 + random.nextInt(900000));
                    setPin(d, pin);
                    prompt = "Type " + pin + " then Enter";
                    say("Waiting for keyboard");
                } else {
                    // Cheap headsets almost always use a fixed 0000 PIN.
                    setPin(d, "0000");
                    say("Pairing...");
                }
                return;
            }

            if (variant == VARIANT_PASSKEY_CONFIRMATION) {
                prompt = "Code " + pad(key);
            }
            confirm(d, true);
            if (!keyboard) quietUi(d);
            say("Pairing...");
        } catch (Exception e) {
            say("Auto-pair failed");
        }
    }

    private static String pad(int key) {
        if (key < 0) return "";
        String s = Integer.toString(key);
        while (s.length() < 6) s = "0" + s;
        return s;
    }

    private void setPin(BluetoothDevice d, String pin) throws Exception {
        byte[] bytes;
        try {
            Method conv = BluetoothDevice.class.getMethod("convertPinToBytes", String.class);
            bytes = (byte[]) conv.invoke(null, pin);
        } catch (Exception e) {
            bytes = pin.getBytes("UTF-8");
        }
        Method sp = BluetoothDevice.class.getMethod("setPin", byte[].class);
        sp.invoke(d, (Object) bytes);
    }

    private void confirm(BluetoothDevice d, boolean yes) throws Exception {
        Method sc = BluetoothDevice.class.getMethod("setPairingConfirmation", boolean.class);
        sc.invoke(d, yes);
    }

    /** Stop the system's own pairing dialog appearing behind us, which on this
     *  build cannot be dismissed without a touchscreen. */
    private void quietUi(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("cancelPairingUserInput");
            m.invoke(d);
        } catch (Exception e) { /* fine */ }
    }

    // ---------------------------------------------------------------- profiles

    private void connectProfile(BluetoothDevice d) {
        if (BtNames.isKeyboard(d)) connectHid(d);
        else connectA2dp(d);
    }

    public void connectA2dp(BluetoothDevice d) {
        if (adapter == null) return;
        if (a2dp == null) {
            adapter.getProfileProxy(ctx, proxyListener, BluetoothProfile.A2DP);
            pendingConnect = d;
            say("Waiting for audio profile");
            return;
        }
        say(connectVia(a2dp, d) ? "Connecting audio..." : "Audio connect refused");
    }

    public void connectHid(BluetoothDevice d) {
        if (adapter == null) return;
        if (hid == null) {
            adapter.getProfileProxy(ctx, proxyListener, PROFILE_INPUT_DEVICE);
            pendingConnect = d;
            say("Waiting for input profile");
            return;
        }
        say(connectVia(hid, d) ? "Connecting keyboard..." : "Keyboard connect refused");
    }

    /** {@code setPriority} and {@code connect} are @hide on both profiles at
     *  this API level. Priority 1000 is PRIORITY_AUTO_CONNECT, which is what
     *  makes the device come back on its own after a reboot. */
    private boolean connectVia(BluetoothProfile profile, BluetoothDevice d) {
        try {
            try {
                Method sp = profile.getClass().getMethod("setPriority",
                        BluetoothDevice.class, int.class);
                sp.invoke(profile, d, 1000);
            } catch (Exception ignored) { /* older stacks lack it */ }

            Method m = profile.getClass().getMethod("connect", BluetoothDevice.class);
            Object r = m.invoke(profile, d);
            return !Boolean.FALSE.equals(r);
        } catch (Exception e) {
            return false;
        }
    }

    public BluetoothDevice connectedAudio() {
        if (a2dp == null) return null;
        List<BluetoothDevice> l = a2dp.getConnectedDevices();
        return (l == null || l.isEmpty()) ? null : l.get(0);
    }

    public BluetoothDevice connectedKeyboard() {
        if (hid == null) return null;
        try {
            List<BluetoothDevice> l = hid.getConnectedDevices();
            return (l == null || l.isEmpty()) ? null : l.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private final BluetoothProfile.ServiceListener proxyListener =
            new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) a2dp = (BluetoothA2dp) proxy;
            else if (profile == PROFILE_INPUT_DEVICE) hid = proxy;
            else return;

            if (pendingConnect != null
                    && pendingConnect.getBondState() == BluetoothDevice.BOND_BONDED) {
                connectProfile(pendingConnect);
            }
            markConnected();
        }
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) a2dp = null;
            else if (profile == PROFILE_INPUT_DEVICE) hid = null;
        }
    };

    private void markConnected() {
        BluetoothDevice audio = connectedAudio();
        BluetoothDevice kbd = connectedKeyboard();
        for (int i = 0; i < found.size(); i++) {
            Dev d = found.get(i);
            String a = d.device.getAddress();
            d.connected = (audio != null && a.equals(audio.getAddress()))
                    || (kbd != null && a.equals(kbd.getAddress()));
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent in) {
            String a = in.getAction();
            BluetoothDevice d = in.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            if (BluetoothDevice.ACTION_FOUND.equals(a)
                    || BluetoothDevice.ACTION_NAME_CHANGED.equals(a)) {
                if (d != null) { addOrUpdate(d); say(status); }

            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                say(found.isEmpty() ? "Nothing found" : "Scan done");

            } else if (ACTION_PAIRING_REQUEST.equals(a)) {
                onPairingRequest(d, in.getIntExtra(EXTRA_PAIRING_VARIANT, -1),
                        in.getIntExtra(EXTRA_PAIRING_KEY, -1));

            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(a)) {
                int st = in.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                if (d != null) addOrUpdate(d);
                if (st == BluetoothDevice.BOND_BONDED) {
                    prompt = "";
                    say("Paired");
                    if (d != null) connectProfile(d);
                } else if (st == BluetoothDevice.BOND_NONE) {
                    prompt = "";
                    say("Pairing failed");
                }

            } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(a)) {
                int st = in.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                markConnected();
                if (st == BluetoothProfile.STATE_CONNECTED) say("Audio connected");
                else if (st == BluetoothProfile.STATE_DISCONNECTED) say("Audio disconnected");

            } else if (ACTION_HID_STATE.equals(a)) {
                int st = in.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                markConnected();
                if (st == BluetoothProfile.STATE_CONNECTED) say("Keyboard connected");
                else if (st == BluetoothProfile.STATE_DISCONNECTED) say("Keyboard disconnected");
            }
        }
    };
}
