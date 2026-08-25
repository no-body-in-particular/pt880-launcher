package org.watchlauncher;

import android.bluetooth.BluetoothDevice;

import java.util.List;

/**
 * The Bluetooth app: scan, pair, connect, unpair.
 *
 * It is the way both of the other two things on this watch get attached --
 * headphones, because there is no speaker worth using, and a keyboard, because
 * the terminal is unusable without one. The list says which is which, using
 * the vendor database and the class-of-device bits, so a row reads
 *
 *     Sony                Headset
 *     Logitech            Keyboard
 *
 * rather than two bare addresses.
 */
public class BtScreen extends ListScreen implements BtHelper.Listener {

    private BtHelper bt;
    private int pending = -1;               // index whose action menu is open

    @Override
    public String title() {
        BtHelper h = helper();
        String p = h.prompt();
        if (p != null && p.length() > 0) return p;
        String s = h.status();
        return (s == null || s.length() == 0) ? "Bluetooth" : s;
    }

    private BtHelper helper() {
        if (bt == null) bt = shell.bt();
        return bt;
    }

    @Override
    public void onShow() {
        helper().addListener(this);
        helper().start();
        if (helper().devices().isEmpty()) helper().scan();
        render();
    }

    @Override
    public void onHide() {
        helper().removeListener(this);
        helper().cancelScan();
    }

    /** Only while a scan is running. The heading carries the scan state and
     *  has to retick, but rebuilding twenty rows every second for a list that
     *  is not changing is work this SoC should not be doing. */
    @Override
    public void tick() {
        if (helper().scanning()) render();
    }

    public void onBtChanged(String status) { render(); }

    @Override
    protected List<Item> items() {
        List<Item> l = list();
        BtHelper h = helper();

        if (!h.available()) {
            l.add(new Item("No Bluetooth hardware", null, AppIcons.NONE, Ui.WARN));
            addBack(l);
            return l;
        }

        l.add(new Item(h.scanning() ? "Scanning..." : "Scan for devices",
                null, AppIcons.BLUETOOTH));
        // Classic discovery never finds LE devices, and this build cannot bond
        // them anyway. This is the other road in.
        l.add(new Item("LE input (HID over GATT)", null, AppIcons.KEYBOARD));

        List<BtHelper.Dev> devs = h.devices();
        for (int i = 0; i < devs.size(); i++) {
            BtHelper.Dev d = devs.get(i);
            String right;
            int colour;
            if (d.lowEnergy) {
                // The stack said so outright, so this one really cannot pair.
                right = "LE";
                colour = Ui.FAINT;
            } else if (d.maybeLowEnergy) {
                // A hint from the address only. Still selectable: refusing on
                // a guess would be worse than a pairing that fails.
                right = "LE?";
                colour = Ui.DIM;
            } else if (d.connected) {
                right = "connected"; colour = Ui.OK;
            } else if (d.bonded) {
                right = "paired"; colour = Ui.FG;
            } else {
                right = d.detail; colour = Ui.DIM;
            }
            l.add(new Item(d.label, right, d.glyph, colour));
        }
        addBack(l);
        return l;
    }

    @Override
    protected void onPick(int index) {
        BtHelper h = helper();
        if (!h.available()) { shell.pop(); return; }

        if (index == 0) { h.scan(); render(); return; }
        if (index == 1) { shell.push(new LeInputScreen()); return; }

        List<BtHelper.Dev> devs = h.devices();
        int di = index - 2;                    // scan row, then the LE row
        if (di >= 0 && di < devs.size()) {
            BtHelper.Dev d = devs.get(di);
            if (d.lowEnergy) {
                shell.toast("Low Energy - needs Bluetooth Classic");
                return;
            }
            // A paired device is worth a second question -- connecting and
            // forgetting are both one press away and only one is undoable.
            if (d.bonded) shell.push(new DeviceScreen(h, d));
            else h.pairAndConnect(d.device);
            render();
            return;
        }
        shell.pop();
    }

    /** What to do with a device that is already bonded. */
    private static class DeviceScreen extends ListScreen {
        private final BtHelper bt;
        private final BtHelper.Dev dev;

        DeviceScreen(BtHelper bt, BtHelper.Dev dev) {
            this.bt = bt;
            this.dev = dev;
        }

        @Override
        public String title() { return dev.label; }

        @Override
        protected List<Item> items() {
            List<Item> l = list();
            BluetoothDevice d = dev.device;
            if (BtNames.isInputDevice(d)) {
                l.add(new Item("Connect input", null, AppIcons.KEYBOARD));
            } else {
                l.add(new Item("Connect audio", null, AppIcons.HEADSET));
            }
            l.add(new Item("Address", d.getAddress(), AppIcons.NONE, Ui.DIM));
            l.add(new Item("Radio", BtNames.transportName(d), AppIcons.NONE, Ui.DIM));
            String kind = BtNames.kind(d);
            l.add(new Item("Type", kind == null ? "unknown" : kind,
                    AppIcons.NONE, Ui.DIM));
            addBack(l);
            l.add(new Item("Forget", null, AppIcons.NONE, Ui.WARN));
            return l;
        }

        @Override
        protected void onPick(int index) {
            switch (index) {
                case 0:
                    if (BtNames.isInputDevice(dev.device)) bt.connectHid(dev.device);
                    else bt.connectA2dp(dev.device);
                    shell.pop();
                    break;
                case 4:
                    shell.pop();
                    break;
                case 5:
                    bt.unpair(dev.device);
                    shell.pop();
                    break;
                default:
                    break;                  // the two readouts are not actions
            }
        }
    }
}
