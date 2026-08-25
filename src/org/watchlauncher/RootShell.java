package org.watchlauncher;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * One long-lived shell, kept open for as long as the app runs.
 *
 * Long-lived rather than one process per command because a shell you have to
 * re-enter every line is not a shell: {@code cd} would not stick, nor would an
 * exported variable, and every command would pay process startup on a 1 GHz
 * SoC.
 *
 * <h3>Getting root</h3>
 *
 * The adbd patch in this repo makes {@code adb shell} run as uid 0, but that
 * does nothing for an app: apps are forked from zygote, not from adbd. What
 * does work is that Android 4.4's zygote never shrinks the capability
 * <em>bounding</em> set and this build has SELinux disabled, so a setuid-root
 * binary exec'd from an app regains full privileges. Three candidates are
 * tried in order:
 *
 *   1. {@code /system/xbin/wsu}   -- the helper in {@code native/}, if installed
 *   2. {@code su}                 -- if anything else has put one there
 *   3. {@code sh}                 -- unprivileged, so the terminal still opens
 *
 * The terminal says which one it got. Falling back to an unprivileged shell is
 * deliberate: a terminal that will not start at all tells you nothing about
 * why, and {@code id} in an unprivileged shell tells you everything.
 *
 * <h3>Knowing when a command has finished</h3>
 *
 * There is no pty, so there is no prompt to watch for. Each command is
 * followed by an echo of a sentinel that includes the exit status; the reader
 * threads stop collecting when they see it. Anything a command writes that
 * happens to contain the sentinel would end the read early -- the sentinel is
 * long and random-looking for that reason alone.
 */
public class RootShell {

    private static final String SENTINEL = "__wl_done_9f3c__";
    private static final String[][] CANDIDATES = {
        {"/system/xbin/wsu"},
        {"/system/bin/wsu"},
        {"su"},
        {"/system/bin/sh"},
        {"sh"},
    };

    /** Long enough for a slow command, short enough that a hung one does not
     *  take the UI with it. */
    private static final long TIMEOUT_MS = 20000;

    private Process proc;
    private Writer in;
    private BufferedReader out;
    private BufferedReader err;
    private String launcher = null;
    private String identity = null;
    private boolean root = false;

    public synchronized boolean open() {
        if (proc != null) return true;
        for (int i = 0; i < CANDIDATES.length; i++) {
            if (start(CANDIDATES[i])) return true;
        }
        return false;
    }

    private boolean start(String[] cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            // A shell with no cwd inherits the app's, which it cannot read.
            pb.directory(new File("/"));
            Process p = pb.start();
            Writer w = new OutputStreamWriter(p.getOutputStream());
            BufferedReader o = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream()));

            proc = p; in = w; out = o; err = e;
            launcher = cmd[0];

            String id = exec("id");
            if (id == null) { close(); return false; }
            identity = id.trim();
            root = identity.contains("uid=0");
            return true;
        } catch (Exception ex) {
            proc = null; in = null; out = null; err = null;
            return false;
        }
    }

    public boolean isRoot() { return root; }

    public String identity() { return identity; }

    /** One line for the About and system-menu rows. */
    public String describe() {
        if (proc == null && launcher == null) return "not opened";
        if (proc == null) return "unavailable";
        if (root) return "root (" + launcher + ")";
        return "uid " + uid() + " (" + launcher + ")";
    }

    private String uid() {
        if (identity == null) return "?";
        int i = identity.indexOf("uid=");
        if (i < 0) return "?";
        int j = i + 4;
        StringBuilder b = new StringBuilder();
        while (j < identity.length() && Character.isDigit(identity.charAt(j))) {
            b.append(identity.charAt(j++));
        }
        return b.length() == 0 ? "?" : b.toString();
    }

    /**
     * Run one command and collect everything it prints. Returns null if the
     * shell is not usable.
     */
    public synchronized String exec(String command) {
        if (proc == null && !open()) return null;
        if (in == null) return null;
        try {
            in.write(command);
            in.write("\n");
            // The status has to be captured before the echo runs, or it is the
            // echo's own status that gets reported.
            in.write("__s=$?; echo \"" + SENTINEL + " $__s\"\n");
            in.flush();
            return collect();
        } catch (Exception e) {
            close();
            return null;
        }
    }

    /** Fire and forget, for the call screen's keyevent fallbacks. */
    public boolean runQuiet(String command) {
        String r = exec(command);
        return r != null;
    }

    private String collect() throws Exception {
        StringBuilder b = new StringBuilder();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                b.append("\n[timed out]");
                return b.toString();
            }
            // Drain stderr opportunistically: a command that only writes there
            // would otherwise look like it produced nothing at all.
            while (err.ready()) {
                String e = err.readLine();
                if (e == null) break;
                b.append(e).append('\n');
            }
            if (!out.ready()) {
                Thread.sleep(15);
                continue;
            }
            String line = out.readLine();
            if (line == null) { close(); return b.toString(); }
            if (line.startsWith(SENTINEL)) {
                while (err.ready()) {
                    String e = err.readLine();
                    if (e == null) break;
                    b.append(e).append('\n');
                }
                String status = line.substring(SENTINEL.length()).trim();
                if (!status.equals("0") && status.length() > 0) {
                    b.append("[exit ").append(status).append("]\n");
                }
                return b.toString();
            }
            b.append(line).append('\n');
        }
    }

    public synchronized void close() {
        try { if (in != null) { in.write("exit\n"); in.flush(); } } catch (Exception e) { /* going anyway */ }
        try { if (proc != null) proc.destroy(); } catch (Exception e) { /* ignore */ }
        proc = null; in = null; out = null; err = null;
    }

    /** The commands offered before a keyboard is attached, and as a shortcut
     *  once one is. Kept short: this is a convenience, not a menu system. */
    public static List<String> builtins() {
        List<String> l = new ArrayList<String>();
        l.add("id");
        l.add("uptime");
        l.add("df -h");
        l.add("ip addr");
        l.add("getprop ro.build.display.id");
        l.add("cat /sys/class/power_supply/battery/capacity");
        l.add("ls /sdcard");
        l.add("ps");
        l.add("dmesg | tail -20");
        l.add("logcat -d -t 40");
        l.add("mount -o remount,rw /system");
        l.add("reboot");
        return l;
    }
}
