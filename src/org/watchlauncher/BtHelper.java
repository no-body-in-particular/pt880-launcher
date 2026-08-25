package org.watchlauncher;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

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
    private static final String EXTRA_REASON =
            "android.bluetooth.device.extra.REASON";
    private static final String ACTION_HID_STATE =
            "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED";

    // Pairing variants, from the framework's BluetoothDevice.
    private static final int VARIANT_PIN = 0;
    private static final int VARIANT_PASSKEY = 1;
    private static final int VARIANT_PASSKEY_CONFIRMATION = 2;
    private static final int VARIANT_CONSENT = 3;
    private static final int VARIANT_DISPLAY_PASSKEY = 4;
    private static final int VARIANT_DISPLAY_PIN = 5;
    private static final int VARIANT_OOB_CONSENT = 6;

    /** The last pairing variant seen, kept so a failure can say which kind of
     *  handshake it was that failed. Without it "pairing failed" is a dead
     *  end: the six variants fail for entirely different reasons. */
    private int lastVariant = -1;

    /** Set while a removal we asked for is in flight. The BOND_NONE that
     *  follows is our own doing and is not a pairing failure; reporting it as
     *  one sent an entire evening chasing a fault that was the report itself. */
    private String removing = null;

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

        /** LE-only, which this platform cannot bond. Only ever set from
         *  BluetoothDevice.getType(), never inferred. */
        public boolean lowEnergy;

        /** Might be LE, on the address alone. A hint on the row; never a
         *  reason to refuse. */
        public boolean maybeLowEnergy;

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
            lowEnergy = BtNames.isLowEnergyOnly(c, device);
            maybeLowEnergy = BtNames.suspectedLe(c, device);
        }
    }

    private final Context ctx;
    private final List<Dev> found = new ArrayList<Dev>();
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final BluetoothAdapter adapter;
    private final Random random = new Random();

    private final Handler wait = new Handler(Looper.getMainLooper());

    /** A bond that is waiting for the radio to stop scanning. */
    private BluetoothDevice pendingBond;

    private BluetoothA2dp a2dp;
    private BluetoothProfile hid;

    /** Whether the stack ever handed us the HID host proxy. This is the
     *  question behind "would a classic mouse work at all": if this build was
     *  compiled without a HID host, no app can connect a keyboard or a mouse
     *  and there is no point buying one. */
    private boolean hidAsked = false;
    private boolean hidAvailable = false;
    private long hidAskedAt = 0;
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

    /**
     * Can this build drive a keyboard or mouse at all?
     *
     * "yes" means the HID host profile exists and answered. "no" means it does
     * not, and no classic peripheral will ever work here whatever is paired --
     * worth knowing before buying one.
     */
    public String hidHost() {
        if (adapter == null) return "no bluetooth";
        // getProfileProxy answers synchronously about whether the profile
        // exists at all, so a false here is a real verdict.
        if (!hidAsked) return "absent from this build";
        if (hidAvailable) return "available";
        // The proxy arrives on a callback. Reading before it lands says
        // nothing, and an earlier version of this reported that silence as a
        // failure -- a diagnostic that answered before it knew.
        if (System.currentTimeMillis() - hidAskedAt < 6000) return "asking...";
        return "present but silent";
    }

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
        // getProfileProxy returning false means the stack has no such profile,
        // which is an immediate and complete answer.
        hidAsked = adapter.getProfileProxy(ctx, proxyListener, PROFILE_INPUT_DEVICE);
        hidAskedAt = System.currentTimeMillis();
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
        if (BtNames.isLowEnergyOnly(ctx, d)) {
            // createBond() would fail in about thirty milliseconds without
            // ever reaching the device. Refusing is the honest answer.
            say("Low Energy - needs Bluetooth Classic");
            return;
        }
        pendingConnect = d;
        prompt = "";
        if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
            cancelScan();
            connectProfile(d);
            return;
        }

        lastVariant = -1;
        say(BtNames.isKeyboard(d) ? "Pairing keyboard..." : "Pairing...");

        /*
         * The radio has to have stopped scanning before the bond starts.
         *
         * cancelDiscovery() is asynchronous, and bluedroid refuses a bond
         * while an inquiry is still running -- it fails in about thirty
         * milliseconds with a generic status, before any pairing request is
         * ever sent, which looks exactly like the remote device rejecting us.
         * It is not. Calling createBond() on the line after cancelDiscovery()
         * is a race, and one that is lost most of the time.
         *
         * This is also why headphones seemed to work: the scan runs about
         * twelve seconds, so anything picked after it finished never raced.
         * Anything picked the moment it appeared always did.
         */
        boolean scanning = false;
        try { scanning = adapter.isDiscovering(); } catch (Exception e) { /* assume not */ }

        if (!scanning) {
            startBond(d);
            return;
        }
        pendingBond = d;
        cancelScan();
        // ACTION_DISCOVERY_FINISHED normally arrives within a few hundred
        // milliseconds and starts the bond. This is the safety net for a stack
        // that cancels without announcing it.
        wait.removeCallbacks(bondWhenIdle);
        wait.postDelayed(bondWhenIdle, 2000);
    }

    private final Runnable bondWhenIdle = new Runnable() {
        public void run() {
            BluetoothDevice d = pendingBond;
            pendingBond = null;
            if (d != null) startBond(d);
        }
    };

    private void startBond(BluetoothDevice d) {
        try {
            if (d.getBondState() == BluetoothDevice.BOND_BONDING) {
                say("Already pairing, wait");
                return;
            }
            if (!d.createBond()) say("Pairing refused by the stack");
        } catch (Exception e) {
            say("Pair failed");
        }
    }

    /** Drop the bond, so a device that paired wrong can be tried again. */
    public void unpair(BluetoothDevice d) {
        try {
            removing = d.getAddress();
            Method m = BluetoothDevice.class.getMethod("removeBond");
            m.invoke(d);
            say("Unpaired");
        } catch (Exception e) {
            removing = null;
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
        lastVariant = variant;
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

            if (variant == VARIANT_PASSKEY) {
                // "Enter the passkey shown on the other device" -- a different
                // call entirely. Answering it with setPairingConfirmation, as
                // the fall-through below used to, confirms a handshake nobody
                // asked about and the bond dies.
                setPasskey(d, key >= 0 ? key : 0);
                say("Pairing...");
                return;
            }

            if (variant == VARIANT_PASSKEY_CONFIRMATION) {
                prompt = "Code " + pad(key);
            }
            confirm(d, true);
            say("Pairing...");
        } catch (Exception e) {
            say("Auto-pair failed");
        }
    }

    /**
     * The reason the stack gives for a bond disappearing.
     *
     * These are the UNBOND_REASON_* constants, which are @hide, so they are
     * written out here rather than referenced. They matter: "timeout" means
     * the device stopped listening, "auth failed" means the code was wrong,
     * and "repeated attempts" means the stack is refusing to try again until
     * the old bond is forgotten -- three different things to do next, where
     * "pairing failed" tells you to do none of them.
     */
    private String why(int reason) {
        String kind = variantName(lastVariant);
        switch (reason) {
            case 1:  return "auth failed" + kind;
            case 2:  return "rejected by device" + kind;
            case 3:  return "cancelled" + kind;
            case 4:  return "device not responding" + kind;
            case 5:  return "still scanning" + kind;
            case 6:  return "timed out" + kind;
            case 7:  return "too many attempts, forget it first" + kind;
            case 8:  return "cancelled by device" + kind;
            case 9:  return "removed" + kind;
            default: return (lastVariant < 0)
                    ? "no pairing request arrived"
                    : ("unknown" + kind);
        }
    }

    /** Which handshake was in progress, for the failure line. */
    private static String variantName(int v) {
        switch (v) {
            case VARIANT_PIN: return " (pin)";
            case VARIANT_PASSKEY: return " (passkey entry)";
            case VARIANT_PASSKEY_CONFIRMATION: return " (confirm)";
            case VARIANT_CONSENT: return " (just works)";
            case VARIANT_DISPLAY_PASSKEY: return " (type on device)";
            case VARIANT_DISPLAY_PIN: return " (type on device)";
            case VARIANT_OOB_CONSENT: return " (oob)";
            default: return "";
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

    private void setPasskey(BluetoothDevice d, int passkey) throws Exception {
        Method m = BluetoothDevice.class.getMethod("setPasskey", int.class);
        m.invoke(d, passkey);
    }

    private void confirm(BluetoothDevice d, boolean yes) throws Exception {
        Method sc = BluetoothDevice.class.getMethod("setPairingConfirmation", boolean.class);
        sc.invoke(d, yes);
    }

    /*
     * There is deliberately no call to cancelPairingUserInput() here.
     *
     * The name reads like "dismiss the system's dialog", and that is what it
     * was here for. It is not what it does: on this platform it calls
     * cancelBondProcess(), so it cancelled the bond a line after
     * setPairingConfirmation(true) accepted it. Headphones survived the race
     * often enough to hide it; a trackball failed instantly and every time,
     * which is what finally showed it up.
     *
     * The system dialog may briefly appear behind us. That is harmless -- the
     * request is answered from here before anyone could act on it -- and it is
     * a much better outcome than a pairing that cannot succeed.
     */

    // ---------------------------------------------------------------- profiles

    /** Audio goes to A2DP, anything that speaks HID goes to the input profile,
     *  and anything that says nothing useful about itself is tried as audio --
     *  headphones are what most devices paired with this watch will be. */
    private void connectProfile(BluetoothDevice d) {
        if (BtNames.isInputDevice(d)) connectHid(d);
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
            else if (profile == PROFILE_INPUT_DEVICE) { hid = proxy; hidAvailable = true; }
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
                // The radio is idle, so a bond held back for it can start now.
                if (pendingBond != null) {
                    wait.removeCallbacks(bondWhenIdle);
                    BluetoothDevice b = pendingBond;
                    pendingBond = null;
                    startBond(b);
                    return;
                }
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
                    String addr = (d == null) ? null : d.getAddress();
                    if (removing != null && removing.equals(addr)) {
                        removing = null;          // our own removal, not a failure
                        say("Forgotten");
                    } else {
                        int reason = in.getIntExtra(EXTRA_REASON, -1);
                        // Reason 9 is a local removeBond. Nothing in this app
                        // asked for it, so something else on the device did --
                        // worth saying, because no amount of retrying helps.
                        say(reason == 9 ? "Bond deleted by the system"
                                        : ("Failed: " + why(reason)));
                    }
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
