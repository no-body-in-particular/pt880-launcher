package org.watchlauncher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reading alarm clocks out of a BP75 / BP85 / BPS4 frame.
 *
 * <h3>Where the format comes from</h3>
 *
 * The vendor's own handlers, read out of {@code protocol_beehome} - there is no
 * document for any of these. {@code handleBP85} logs {@code alarmtime},
 * {@code alarmtype}, {@code switch}, {@code relpeat}, {@code allswitch} and
 * {@code all alarm count}, splits its items on {@code @}, and carries the
 * literals {@code W}, {@code ST}, {@code CON}, {@code ON} and {@code OFF}.
 * {@code handleBP75} writes the same shape into
 * {@code content://com.ic.provider.alarm/alarm} with columns {@code name week
 * ring time status}. {@code handleBPS4} names "set 1st alarm" through "set 7th
 * alarm", so seven is the limit.
 *
 * <h3>What is deliberately not guessed</h3>
 *
 * Which comma each of those sits behind is not established, because no real
 * frame has been captured. So this recognises a time and an on-or-off word
 * wherever they appear in an item, and ignores everything else, rather than
 * counting into a layout nobody has confirmed. An item with no time it can read
 * is returned as unparsed rather than filled in with a default.
 *
 * Skipping an alarm is much the better failure. A watch that does not ring is a
 * disappointment; a watch that rings at the wrong hour is one that gets taken
 * off at night, and this is on a child's wrist.
 *
 * <h3>Why this is separate from {@link AlarmClock}</h3>
 *
 * No {@code android} imports, so it compiles and runs off the device and the
 * test can put real frames through it. The scheduling half cannot.
 */
public final class AlarmParse {

    /** "set 7th alarm" is the last one the vendor's handler names. */
    public static final int MAX_ALARMS = 7;

    private AlarmParse() { }

    /** One alarm, as much of it as was understood. */
    public static final class Alarm {
        public int hour = -1;
        public int minute = -1;
        public boolean on = false;

        public boolean valid() {
            return hour >= 0 && hour < 24 && minute >= 0 && minute < 60;
        }

        public String toString() {
            return String.format(Locale.US, "%02d:%02d %s", hour, minute, on ? "on" : "off");
        }
    }

    /** What was understood, and what was not, so the caller can log the rest. */
    public static final class Result {
        public final List<Alarm> alarms = new ArrayList<Alarm>();
        public final List<String> unparsed = new ArrayList<String>();
    }

    /**
     * @param fields the frame's fields
     * @param token  the reply token, which is addressing rather than content
     */
    public static Result parse(List<String> fields, String token) {
        Result r = new Result();
        if (fields == null) return r;

        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            String v = fields.get(i);
            if (v == null) continue;
            v = v.trim();
            // The device id and the token are addressing, not content. Dropping them by
            // length alone was wrong twice over: it also threw away any field longer than
            // eight characters, which is most of an alarm item, and it would keep a short id.
            // The id is what it actually is - a long run of digits - and the token is known.
            if (v.length() == 0 || v.equals(token)) continue;
            if (isDigits(v) && v.length() >= 10) continue;
            if (joined.length() > 0) joined.append(',');
            joined.append(v);
        }

        String[] items = joined.toString().split("@");
        for (int i = 0; i < items.length; i++) {
            String item = items[i].trim();
            if (item.length() == 0) continue;
            if (r.alarms.size() >= MAX_ALARMS) { r.unparsed.add(item); continue; }

            Alarm a = item(item);
            if (a == null) r.unparsed.add(item);
            else r.alarms.add(a);
        }
        return r;
    }

    private static Alarm item(String item) {
        Alarm a = new Alarm();

        String[] parts = item.split("[,.]");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.length() == 0) continue;

            if ("ON".equalsIgnoreCase(p) || "CON".equalsIgnoreCase(p)) { a.on = true; continue; }
            if ("OFF".equalsIgnoreCase(p)) { a.on = false; continue; }
            if (a.valid()) continue;                 // the first time in the item wins

            int colon = p.indexOf(':');
            if (colon > 0) {
                int h = number(p.substring(0, colon));
                int m = number(p.substring(colon + 1));
                if (isTime(h, m)) { a.hour = h; a.minute = m; }
                continue;
            }
            if (p.length() == 4 && isDigits(p)) {
                int h = number(p.substring(0, 2));
                int m = number(p.substring(2));
                // Only a time if it is one: 0730 is, 9999 is not. Which keeps a count or an
                // index sitting in the same item from becoming half past nine.
                if (isTime(h, m)) { a.hour = h; a.minute = m; }
            }
        }
        return a.valid() ? a : null;
    }

    private static boolean isTime(int h, int m) {
        return h >= 0 && h < 24 && m >= 0 && m < 60;
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return s.length() > 0;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
