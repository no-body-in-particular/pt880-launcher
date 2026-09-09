package org.watchlauncher;

import java.util.List;

/**
 * The dialler: the contacts file, the call log, and nothing else.
 *
 * There is no keypad because there is nothing to press it with. A number that
 * is not in {@code contacts.txt} and not in the log cannot be dialled from
 * here -- push it into the file and reload, which takes one adb command and is
 * faster than any on-screen entry would be.
 */
public class CallScreen extends ListScreen {

    private List<Contacts.Entry> entries;

    @Override
    public String title() {
        if (entries != null && entries.isEmpty()) return "No contacts";
        return "Call";
    }

    @Override
    public void onShow() {
        entries = Contacts.load();
        render();
    }

    @Override
    protected List<Item> items() {
        if (entries == null) entries = Contacts.load();
        List<Item> l = list();

        for (int i = 0; i < entries.size(); i++) {
            Contacts.Entry e = entries.get(i);
            l.add(new Item(e.name, shortNumber(e.number), AppIcons.CONTACT));
        }

        if (entries.isEmpty()) {
            String where = Contacts.file();
            l.add(new Item(where == null ? "Create contacts.txt" : "Empty contacts.txt",
                    null, AppIcons.NONE, Ui.WARN));
        }
        l.add(new Item("Call log", null, AppIcons.CALL));
        l.add(new Item("Reload contacts", null, AppIcons.GEAR));
        addBack(l);
        return l;
    }

    /** The tail of the number, which is the part that identifies it; the whole
     *  thing does not fit beside a name at 240px. */
    private static String shortNumber(String n) {
        if (n == null) return null;
        return n.length() <= 9 ? n : ("…" + n.substring(n.length() - 8));
    }

    @Override
    protected void onPick(int index) {
        int n = entries.size();
        if (index < n) {
            Contacts.Entry e = entries.get(index);
            shell.push(new InCallScreen(e.name, e.number, false));
            return;
        }
        int extra = index - n;
        if (entries.isEmpty()) {
            if (extra == 0) {
                String made = Contacts.createExample();
                shell.toast(made == null ? "Cannot write /sdcard" : made);
                entries = Contacts.load();
                render();
                return;
            }
            extra--;
        }
        switch (extra) {
            case 0: shell.push(new CallLogScreen()); break;
            case 1:
                entries = Contacts.load();
                shell.toast(entries.size() + " contacts");
                render();
                break;
            default: shell.pop(); break;
        }
    }
}
