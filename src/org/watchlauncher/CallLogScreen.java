package org.watchlauncher;

import android.database.Cursor;
import android.provider.CallLog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Recent calls, from the system's own log, so calls made or missed while the
 * watch was showing something else are still reachable.
 *
 * Names come from {@code contacts.txt} rather than from the log's cached name
 * column -- the contacts provider on this device is empty, so the log has no
 * names of its own to offer.
 */
public class CallLogScreen extends ListScreen {

    private static final int LIMIT = 40;
    private static final SimpleDateFormat WHEN =
            new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());

    private static class Row {
        String label;
        String number;
        String when;
        int glyph;
    }

    private List<Row> rows;
    private String problem;

    @Override
    public String title() { return problem != null ? problem : "Call log"; }

    @Override
    public void onShow() {
        load();
        render();
    }

    private void load() {
        rows = new ArrayList<Row>();
        problem = null;
        Cursor c = null;
        try {
            c = shell.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE},
                    null, null, CallLog.Calls.DATE + " DESC");
            if (c == null) { problem = "No call log"; return; }
            while (c.moveToNext() && rows.size() < LIMIT) {
                Row r = new Row();
                r.number = c.getString(0);
                int type = c.getInt(1);
                long when = c.getLong(2);
                String name = Contacts.nameFor(r.number);
                r.label = (name != null) ? name
                        : (r.number == null || r.number.length() == 0 ? "Unknown" : r.number);
                r.when = WHEN.format(new Date(when));
                r.glyph = (type == CallLog.Calls.MISSED_TYPE) ? AppIcons.BACK : AppIcons.CALL;
                rows.add(r);
            }
        } catch (Exception e) {
            problem = "Call log unreadable";
        } finally {
            try { if (c != null) c.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    @Override
    protected List<Item> items() {
        if (rows == null) load();
        List<Item> l = list();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            l.add(new Item(r.label, r.when, r.glyph));
        }
        if (rows.isEmpty()) l.add(new Item("Nothing yet", null, AppIcons.NONE, Ui.DIM));
        addBack(l);
        return l;
    }

    @Override
    protected void onPick(int index) {
        if (index < rows.size()) {
            Row r = rows.get(index);
            if (r.number == null || r.number.length() == 0) return;
            shell.push(new InCallScreen(r.label, r.number, false));
            return;
        }
        shell.pop();
    }
}
