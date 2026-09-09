package org.watchlauncher;

import android.database.Cursor;
import android.net.Uri;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Texts that have arrived, which nothing on this watch was showing.
 *
 * They were being received and stored the whole time - the inbox holds forty-nine of them - but
 * {@code com.android.mms} ships here with no launcher category, so it has no icon and no way in,
 * and this launcher builds its menu from a fixed list rather than from
 * {@code queryIntentActivities}. A message arrived, went into the provider, and was never seen
 * again.
 *
 * <h3>Sorted by id, not by date</h3>
 *
 * The provider's {@code date} column on this device is not usable. Of the messages in the inbox
 * one is stamped 2035, a dozen are stamped 1997, and several are negative - the modem's clock
 * before the network sets it, written straight through. Ordering by it interleaves this week's
 * messages with 1997. The row id is monotonic and is what the order here comes from; a date is
 * only shown when it is inside a range a watch could plausibly have been running in.
 *
 * <h3>Control messages are not correspondence</h3>
 *
 * The remote-control channel arrives as SMS - see {@link SmsControl} - and its own notes say a
 * control channel that fills the message list is one nobody leaves enabled. It aborts those
 * broadcasts so they never reach the inbox, but the abort only runs on an ordered broadcast and
 * SMS_RECEIVED is not ordered here, so twenty-odd reboot commands are sitting in the list burying
 * the two messages a person actually sent. They are filtered out of this view rather than shown:
 * the intent was always that they should not be here.
 */
public class MessagesScreen extends ListScreen {

    private static final int LIMIT = 40;
    private static final Uri INBOX = Uri.parse("content://sms/inbox");
    private static final SimpleDateFormat WHEN =
            new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());

    /** Dates outside this are the modem's clock rather than a time anything happened. */
    private static final long PLAUSIBLE_FROM = 1420070400000L;   // 2015
    private static final long PLAUSIBLE_TO   = 4102444800000L;   // 2100

    private static class Row {
        String from;
        String body;
        String when;
    }

    private List<Row> rows;
    private String problem;

    @Override
    public String title() { return problem != null ? problem : "Messages"; }

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
                    INBOX, new String[]{"address", "date", "body"}, null, null, "_id DESC");
            if (c == null) { problem = "No inbox"; return; }
            while (c.moveToNext() && rows.size() < LIMIT) {
                String body = c.getString(2);
                if (SmsFilter.isCommand(body)) continue;
                Row r = new Row();
                String addr = c.getString(0);
                String name = (addr == null) ? null : Contacts.nameFor(addr);
                r.from = (name != null) ? name
                        : (addr == null || addr.length() == 0 ? "Unknown" : addr);
                r.body = (body == null) ? "" : body.replace('\n', ' ').trim();
                long when = c.getLong(1);
                r.when = (when > PLAUSIBLE_FROM && when < PLAUSIBLE_TO)
                        ? WHEN.format(new Date(when)) : null;
                rows.add(r);
            }
        } catch (Exception e) {
            problem = "Inbox unreadable";
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
            // The sender is the label and the message is what is worth reading, so the message
            // goes in the wide column with the sender trailing it.
            String text = r.body.length() > 38 ? r.body.substring(0, 37) + "…" : r.body;
            l.add(new Item(text.length() == 0 ? r.from : text, r.from, AppIcons.CONTACT));
        }
        if (rows.isEmpty()) l.add(new Item("Nothing yet", null, AppIcons.NONE, Ui.DIM));
        addBack(l);
        return l;
    }

    @Override
    protected void onPick(int index) {
        if (index < rows.size()) {
            Row r = rows.get(index);
            shell.push(new MessageScreen(r.from, r.when, r.body));
            return;
        }
        shell.pop();
    }
}
