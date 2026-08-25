package org.watchlauncher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The phone book, as a text file you edit over adb.
 *
 * There is a contacts provider on this device and a Contacts app in front of
 * it, but neither can be driven without a touchscreen, and getting a number
 * into it would mean typing on a watch. A file you push is the whole feature:
 *
 *     adb push contacts.txt /sdcard/Documents/
 *
 * One entry per line, name first, number after a colon:
 *
 *     Arno Phone:+31619036989
 *     Home:0031619036989
 *     # lines starting with a hash are ignored
 *
 * Either dialling form works -- {@code +31} and {@code 0031} reach the same
 * place -- and spaces, dashes and brackets inside the number are ignored, so a
 * number pasted from anywhere will dial.
 */
public class Contacts {

    /** Searched in order. The first that exists wins, so a file in Documents
     *  beats a stray copy at the root of the card. */
    private static final String[] PATHS = {
        "/sdcard/Documents/contacts.txt",
        "/sdcard/documents/contacts.txt",
        "/sdcard/Documents/Contacts.txt",
        "/sdcard/contacts.txt",
        "/sdcard/Download/contacts.txt",
        "/storage/sdcard1/Documents/contacts.txt",
        "/data/media/0/Documents/contacts.txt",
    };

    /** Where one gets created if there is none. */
    public static final String DEFAULT_PATH = "/sdcard/Documents/contacts.txt";

    public static class Entry {
        public final String name;
        public final String number;

        Entry(String name, String number) {
            this.name = name;
            this.number = number;
        }
    }

    private Contacts() { }

    public static String file() {
        for (int i = 0; i < PATHS.length; i++) {
            File f = new File(PATHS[i]);
            if (f.isFile() && f.canRead()) return PATHS[i];
        }
        return null;
    }

    public static List<Entry> load() {
        List<Entry> out = new ArrayList<Entry>();
        String path = file();
        if (path == null) return out;

        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String line;
            while ((line = r.readLine()) != null) {
                Entry e = parse(line);
                if (e != null) out.add(e);
            }
        } catch (Exception e) {
            // An unreadable file is the same as an empty one as far as the
            // screen is concerned; it says "no contacts" either way.
        } finally {
            try { if (r != null) r.close(); } catch (Exception e) { /* ignore */ }
        }
        return out;
    }

    /**
     * One line to an entry, or null if there is nothing dialable on it.
     *
     * The name is split off at the *last* separator rather than the first, so
     * a name containing a colon still keeps all of itself.
     */
    static Entry parse(String raw) {
        if (raw == null) return null;
        String line = raw.trim();
        if (line.length() == 0 || line.startsWith("#") || line.startsWith("//")) return null;

        int cut = line.lastIndexOf(':');
        if (cut < 0) cut = line.lastIndexOf('=');
        if (cut < 0) cut = line.lastIndexOf(',');
        if (cut < 0) cut = line.lastIndexOf(';');

        String name, number;
        if (cut < 0) {
            // A bare number on its own line is still worth dialling.
            number = dialable(line);
            if (number == null) return null;
            name = number;
            return new Entry(name, number);
        }
        name = line.substring(0, cut).trim();
        number = dialable(line.substring(cut + 1));
        if (number == null) return null;
        if (name.length() == 0) name = number;
        return new Entry(name, number);
    }

    /** Keep the digits, the leading plus, and the characters a network
     *  actually understands. Everything else is presentation. */
    static String dialable(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder();
        boolean digits = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') { b.append(c); digits = true; }
            else if (c == '+' && b.length() == 0) b.append(c);
            else if (c == '*' || c == '#') b.append(c);
            // spaces, dashes, brackets and dots are dropped
        }
        return digits ? b.toString() : null;
    }

    /** Name for an incoming number, matched from the right so that +31 6...,
     *  0031 6... and 06... all find the same person. */
    public static String nameFor(String number) {
        String want = dialable(number);
        if (want == null) return null;
        List<Entry> all = load();
        for (int i = 0; i < all.size(); i++) {
            if (sameNumber(all.get(i).number, want)) return all.get(i).name;
        }
        return null;
    }

    /** Two numbers are the same person if the last nine digits agree. That is
     *  short enough to survive every country's trunk-prefix mangling and long
     *  enough not to collide in a hand-written file. */
    static boolean sameNumber(String a, String b) {
        String x = digitsOnly(a), y = digitsOnly(b);
        if (x == null || y == null) return false;
        if (x.equals(y)) return true;
        int n = Math.min(9, Math.min(x.length(), y.length()));
        if (n < 6) return false;
        return x.regionMatches(x.length() - n, y, y.length() - n, n);
    }

    private static String digitsOnly(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.length() == 0 ? null : b.toString();
    }

    /** Write a commented example, so a watch with no file is one press away
     *  from having one to edit rather than a dead end. */
    public static String createExample() {
        try {
            File f = new File(DEFAULT_PATH);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return null;
            if (f.exists()) return f.getAbsolutePath();
            FileOutputStream o = new FileOutputStream(f);
            try {
                o.write(("# One contact per line: name, a colon, then the number.\n"
                        + "# Both dialling forms work.\n"
                        + "#\n"
                        + "# Arno Phone:+31619036989\n"
                        + "# Home:0031619036989\n").getBytes("UTF-8"));
                o.flush();
            } finally {
                o.close();
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }
}
