package org.watchlauncher;

import java.util.List;

/**
 * The LE devices in range, and a way to try one.
 *
 * Classic discovery never finds these -- it is a different radio procedure --
 * and this build cannot bond them anyway, so the ordinary Bluetooth screen is
 * no help. This lists whatever is advertising and lets you pick.
 *
 * Everything seen is listed, not only devices advertising the HID service.
 * Plenty of keyboards put that UUID in the scan response rather than the
 * advertisement, or expose it only after connecting, so filtering on it would
 * hide exactly the device being looked for. The HID marker is a hint on the
 * row; the choice is yours.
 */
public class LeInputScreen extends ListScreen implements LeHid.Listener {

    private LeHid le;
    private String status = "";

    private LeHid le() {
        if (le == null) le = new LeHid(shell, this);
        return le;
    }

    @Override
    public String title() {
        return status.length() > 0 ? status : "LE input";
    }

    @Override
    public void onShow() {
        // Taken back from the report screen, which borrows them while it is up.
        le().setListener(this);
        render();
    }

    @Override
    public void onHide() {
        if (le != null) le.stopScan();
    }

    /** The heading carries the scan state, so it has to retick. */
    @Override
    public void tick() {
        if (le != null && le.scanning()) render();
    }

    public void onLeHidStatus(String line) {
        status = line;
        render();
    }

    public void onLeHidFound() { render(); }

    public void onLeHidReport(boolean keyboard, byte[] report) { /* on the log screen */ }

    @Override
    protected List<Item> items() {
        List<Item> l = list();
        l.add(new Item(le().scanning() ? "Scanning..." : "Scan for LE devices",
                null, AppIcons.BLUETOOTH));

        List<LeHid.Found> devs = le().devices();
        for (int i = 0; i < devs.size(); i++) {
            LeHid.Found f = devs.get(i);
            l.add(new Item(f.label(), f.rssi + " dBm",
                    f.hid ? AppIcons.KEYBOARD : AppIcons.DEVICE,
                    f.hid ? Ui.FG : Ui.DIM));
        }
        if (devs.isEmpty() && !le().scanning()) {
            l.add(new Item("Nothing found yet", null, AppIcons.NONE, Ui.DIM));
        }
        addBack(l);
        return l;
    }

    @Override
    protected void onPick(int index) {
        if (index == 0) { le().scan(); render(); return; }

        List<LeHid.Found> devs = le().devices();
        int di = index - 1;
        if (di >= 0 && di < devs.size()) {
            le().stopScan();
            shell.push(new LeReportScreen(le(), devs.get(di)));
            return;
        }
        shell.pop();
    }
}
