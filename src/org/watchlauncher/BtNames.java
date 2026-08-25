package org.watchlauncher;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;

/**
 * Turning a discovered device into something a human can pick off a list.
 *
 * Three sources, in the order they are trusted:
 *
 *   1. the name the device advertises -- always right when it exists;
 *   2. the vendor that owns its address prefix, from {@link OuiDb};
 *   3. the class-of-device bits, which every device sets and which say what
 *      kind of thing it is even when it will not say who made it.
 *
 * A scan that used to read
 *
 *     AC:9B:0A:11:22:33
 *     00:16:94:AA:BB:CC
 *
 * reads
 *
 *     Sony              Headset
 *     Sennheiser        Headset
 *
 * which is the difference between guessing and choosing.
 */
public final class BtNames {

    private BtNames() { }

    /** The row's main label: the advertised name, else the vendor, else the
     *  address. The address is kept as the last resort rather than dropped --
     *  it is what you would type into {@code -e pair}. */
    public static String label(Context c, BluetoothDevice d, String advertised) {
        if (advertised != null && advertised.length() > 0
                && !advertised.equals(d.getAddress())) {
            return advertised;
        }
        String vendor = OuiDb.get(c).vendor(d.getAddress());
        if (vendor != null) return vendor;
        return d.getAddress();
    }

    /** The dim trailing column: what the device says it is. When the label
     *  already fell back to the vendor there is no point repeating it. */
    public static String detail(Context c, BluetoothDevice d, String advertised) {
        String kind = kind(d);
        boolean namedItself = advertised != null && advertised.length() > 0
                && !advertised.equals(d.getAddress());
        if (!namedItself) return kind;

        String vendor = OuiDb.get(c).vendor(d.getAddress());
        if (vendor == null) return kind;
        if (kind == null) return vendor;
        // Both fit only if they are short; the type is the more useful half
        // when a device has already given a brand-shaped name.
        return kind;
    }

    /** The class-of-device, in one word. */
    public static String kind(BluetoothDevice d) {
        BluetoothClass k = null;
        try { k = d.getBluetoothClass(); } catch (Exception e) { /* ignore */ }
        if (k == null) return null;

        // The minor class is the specific answer where there is one.
        switch (k.getDeviceClass()) {
            case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
            case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                return "Headset";
            case BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES:
                return "Headphones";
            case BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER:
            case BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO:
                return "Speaker";
            case BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO:
                return "Audio";
            case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                return "Car";
            case BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE:
                return "Mic";
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER:
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR:
                return "Display";
            case BluetoothClass.Device.PHONE_SMART:
            case BluetoothClass.Device.PHONE_CELLULAR:
                return "Phone";
            case BluetoothClass.Device.COMPUTER_LAPTOP:
            case BluetoothClass.Device.COMPUTER_DESKTOP:
                return "Computer";
            case BluetoothClass.Device.WEARABLE_WRIST_WATCH:
                return "Watch";
            default:
                break;
        }

        // The peripheral minor class is a bitfield, not an enum, so keyboards
        // have to be picked out of the raw class rather than switched on.
        if (k.getMajorDeviceClass() == BluetoothClass.Device.Major.PERIPHERAL) {
            String p = peripheral(k);
            if (p != null) return p;
        }

        switch (k.getMajorDeviceClass()) {
            case BluetoothClass.Device.Major.AUDIO_VIDEO: return "Audio";
            case BluetoothClass.Device.Major.PHONE:       return "Phone";
            case BluetoothClass.Device.Major.COMPUTER:    return "Computer";
            case BluetoothClass.Device.Major.WEARABLE:    return "Wearable";
            case BluetoothClass.Device.Major.HEALTH:      return "Health";
            case BluetoothClass.Device.Major.IMAGING:     return "Imaging";
            case BluetoothClass.Device.Major.TOY:         return "Toy";
            case BluetoothClass.Device.Major.NETWORKING:  return "Network";
            case BluetoothClass.Device.Major.PERIPHERAL:  return "Input";
            default: return null;
        }
    }

    /**
     * Bits 6 and 7 of the minor class carry the keyboard and pointer flags,
     * and the low nibble carries a device type. A combo keyboard/mouse sets
     * both, which is why this cannot be a switch.
     */
    private static String peripheral(BluetoothClass k) {
        int minor = (k.getDeviceClass() & 0xFC) >> 2;
        boolean kbd = (minor & 0x10) != 0;
        boolean ptr = (minor & 0x20) != 0;
        if (kbd && ptr) return "Keyboard";
        if (kbd) return "Keyboard";
        if (ptr) return "Mouse";
        switch (minor & 0x0F) {
            case 1: return "Joystick";
            case 2: return "Gamepad";
            case 3: return "Remote";
            case 5: return "Tablet";
            default: return null;
        }
    }

    /** The launcher glyph that matches {@link #kind}. */
    public static int glyph(BluetoothDevice d) {
        String k = kind(d);
        if (k == null) return AppIcons.DEVICE;
        if (k.equals("Headset") || k.equals("Headphones")) return AppIcons.HEADSET;
        if (k.equals("Speaker") || k.equals("Audio") || k.equals("Car")) return AppIcons.SPEAKER;
        if (k.equals("Phone")) return AppIcons.PHONE;
        if (k.equals("Computer")) return AppIcons.COMPUTER;
        if (k.equals("Watch") || k.equals("Wearable")) return AppIcons.WATCH;
        if (k.equals("Keyboard")) return AppIcons.KEYBOARD;
        return AppIcons.DEVICE;
    }

    public static final int TRANSPORT_UNKNOWN = 0;
    public static final int TRANSPORT_CLASSIC = 1;
    public static final int TRANSPORT_LE = 2;
    public static final int TRANSPORT_DUAL = 3;

    /**
     * Classic, Low Energy, or both.
     *
     * This matters more than it looks. Android 4.4 can scan and connect LE
     * GATT, but it has no HOGP host and no working LE bonding: createBond() on
     * an LE-only device fails in about thirty milliseconds, before any radio
     * exchange happens, and the stack reports a status AOSP has no name for --
     * which surfaces as UNBOND_REASON_REMOVED and reads like something deleted
     * the pairing. Nothing did. The platform simply cannot pair it, and no
     * amount of retrying changes that. Saying so up front is the whole point
     * of this method.
     */
    public static int transport(BluetoothDevice d) {
        int t = TRANSPORT_UNKNOWN;
        try {
            t = d.getType();          // API 18, so present here
        } catch (Exception e) {
            t = TRANSPORT_UNKNOWN;
        }
        if (t == TRANSPORT_CLASSIC || t == TRANSPORT_LE || t == TRANSPORT_DUAL) {
            return t;
        }
        // getType had nothing to say. A random address is still proof of LE --
        // but only together with the vendor lookup failing, because a public
        // OUI can legitimately begin with the same two bits (F0: is Huawei).
        return looksRandom(d.getAddress()) ? TRANSPORT_LE : TRANSPORT_UNKNOWN;
    }

    /**
     * True only when the stack itself says the device is LE-only.
     *
     * Nothing here is inferred, and that is deliberate. An earlier version
     * also treated a random-looking address with no vendor as proof, which is
     * wrong twice over: BR/EDR addresses are always public, so a random one
     * does suggest LE -- but cheap peripherals ship unregistered MACs
     * routinely, so the vendor lookup failing means very little. An i35 earbud
     * could have matched that test and been refused, and it is emphatically
     * Classic: it reports a Class of Device, which is a BR/EDR field with no
     * LE equivalent, and it speaks A2DP, which is a Classic-only profile.
     *
     * Refusing a device that would have worked is far worse than letting one
     * through that fails, so the guess only warns. See {@link #suspectedLe}.
     */
    public static boolean isLowEnergyOnly(Context c, BluetoothDevice d) {
        try {
            return d.getType() == TRANSPORT_LE;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The device might be LE, on the address alone, when the stack would not
     * say. Shown as a hint, never acted on.
     *
     * A device that turned up in a classic inquiry has a classic radio by
     * definition, so this being true at the same time is a contradiction worth
     * seeing rather than resolving silently.
     */
    public static boolean suspectedLe(Context c, BluetoothDevice d) {
        if (isLowEnergyOnly(c, d)) return false;          // already certain
        try {
            if (d.getType() != TRANSPORT_UNKNOWN) return false;
        } catch (Exception e) { /* keep going */ }
        if (!looksRandom(d.getAddress())) return false;
        if (OuiDb.get(c).vendor(d.getAddress()) != null) return false;
        // A Class of Device is a BR/EDR field. Having one settles it.
        return kind(d) == null;
    }

    /** For the device screen, so the truth is visible rather than inferred. */
    public static String transportName(BluetoothDevice d) {
        switch (transport(d)) {
            case TRANSPORT_CLASSIC: return "classic";
            case TRANSPORT_LE: return "low energy";
            case TRANSPORT_DUAL: return "dual";
            default: return "unknown";
        }
    }

    /**
     * The top two bits of the most significant octet. In LE these say the
     * address is random rather than IEEE-assigned: 11 static, 01 resolvable
     * private, 00 non-resolvable private.
     */
    static boolean looksRandom(String address) {
        if (address == null || address.length() < 2) return false;
        try {
            int top = Integer.parseInt(address.substring(0, 2), 16) >> 6;
            return top == 0x3 || top == 0x1 || top == 0x0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Does this look like something you would type on? Decides whether the
     *  pairing code has to be shown rather than auto-confirmed. */
    public static boolean isKeyboard(BluetoothDevice d) {
        String k = kind(d);
        return k != null && k.equals("Keyboard");
    }

    /**
     * Anything that speaks HID: keyboards, mice, combos, remotes, gamepads.
     *
     * The profile to connect is decided by this and not by {@link #isKeyboard},
     * which asks a narrower question for the pairing prompt. Routing on the
     * narrow one sent every non-keyboard -- a mouse included -- down the A2DP
     * path, which is an audio profile and has nothing to say to a pointing
     * device, so it bonded and then sat there doing nothing.
     */
    public static boolean isInputDevice(BluetoothDevice d) {
        BluetoothClass k = null;
        try { k = d.getBluetoothClass(); } catch (Exception e) { /* ignore */ }
        if (k == null) return false;
        if (k.getMajorDeviceClass() == BluetoothClass.Device.Major.PERIPHERAL) return true;
        // Some combo keyboards declare themselves uncategorised but still set
        // the keyboard or pointer bit, so check those directly too.
        int minor = (k.getDeviceClass() & 0xFC) >> 2;
        return (minor & 0x30) != 0;
    }

    /** Does this look like something you would listen to? */
    public static boolean isAudio(BluetoothDevice d) {
        BluetoothClass k = null;
        try { k = d.getBluetoothClass(); } catch (Exception e) { /* ignore */ }
        return k != null
                && k.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO;
    }
}
