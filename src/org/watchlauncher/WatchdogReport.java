package org.watchlauncher;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Say what the watch was doing when the readings stopped, from the watch.
 *
 * <h3>Why</h3>
 *
 * The readings stop for half an hour at a time, most nights, and from the server there is
 * nothing to see: the watch stays connected, answers every poll, keeps reporting position and
 * battery, and simply sends no vitals until something reboots it. Every theory about why has
 * had to be argued from the shape of the gaps, and two of them were wrong.
 *
 * What settled it was a hand-run {@code adb logcat} that happened to catch the right minutes:
 * CoreService was alive, its alarms were firing on schedule - SILENT_START every three
 * minutes, TEMP_ALARM every sixty seconds - and no measurement came out of either. That
 * ruled out the wear gate and it ruled out the service having died, because neither is
 * consistent with alarms that keep firing.
 *
 * Catching that by hand means being awake at the right moment. This does it automatically:
 * when {@link PpgWatchdog} decides the watch has gone quiet, it takes a snapshot first and
 * sends it, so the next gap explains itself.
 *
 * <h3>Where it goes</h3>
 *
 * To the tracker server, over the connection the watch already has, as ordinary protocol
 * frames. The server logs every frame it receives into the device's log whether it
 * understands it or not, so this needs no endpoint, no credentials and no new port - and it
 * lands in the same file as the gap it is explaining, in order, which is exactly where
 * somebody diagnosing the gap is already looking.
 *
 * The opcode is IWAPDG. It is not one the vendor protocol defines, which is the point: the
 * server's parser ends its switch in a default that ignores what it does not recognise, so an
 * unknown frame is logged and dropped rather than misfiled as a reading.
 *
 * <h3>What is in it, and what is deliberately not</h3>
 *
 * The processes that own the sensor, the alarms that should be driving it, and the tail of
 * anything the vendor stack logged about heart rate or temperature. That is what the last
 * three wrong theories would each have been settled by.
 *
 * Not the whole logcat. It is a shared connection on a watch, the frames are small, and a
 * megabyte of unfiltered log would cost more than it explains. {@link #MAX_FRAMES} is a hard
 * ceiling and the count of what was dropped is sent, so a truncated report says so rather
 * than looking complete.
 *
 * Nothing here is secret - process names, alarm names, sensor state - but it is worth being
 * deliberate about that rather than accidental, so the collection is a fixed set of commands
 * rather than a general "run this on the watch and send the output" mechanism.
 */
public class WatchdogReport {

    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 6000;

    /** Frames per report. Enough for the process table and a dozen log lines. */
    private static final int MAX_FRAMES = 24;

    /** Protocol frames are '#' terminated, and the server's buffer is not generous. */
    private static final int MAX_TEXT = 180;

    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    static {
        STAMP.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private WatchdogReport() {
    }

    /**
     * Collect and send. Called on the watchdog's thread, never the main one - it shells out
     * and opens a socket, and both can block for seconds.
     */
    public static void sendNow(Context context, String why) {
        send(context, collect(why));
    }

    /**
     * The last crash, if there is one that has not been sent yet.
     *
     * Crashes already land in /sdcard/Documents/crash.txt, which is fine when somebody is
     * holding the watch and a cable. It is no use at all for a crash that happens on a wrist
     * in a car, which is where the ones that matter happen - and those are exactly the ones
     * that get reported as "it quit a few times" with no stack to go on.
     *
     * Sent once. The file is deliberately not cleared - it is the wearer's copy and the
     * launcher's own toast reads from it - so a preference remembers how long it was when it
     * was last sent, and a crash only counts as new when the file has grown.
     */
    public static void sendCrash(Context context) {
        try {
            String text = Crash.last();

            if (text == null || text.length() == 0) {
                return;
            }

            android.content.SharedPreferences p =
                    context.getSharedPreferences("watchlauncher", Context.MODE_PRIVATE);

            if (p.getInt(SENT_LEN, 0) == text.length()) {
                return;                              // already sent this one
            }

            p.edit().putInt(SENT_LEN, text.length()).apply();

            List<String> lines = new ArrayList<String>();
            lines.add("crash report");

            //The top of a stack is the part that says what broke; the bottom is framework
            //plumbing that is the same for every crash. Newest first, because the file
            //accumulates and the last crash is the one being asked about.
            String[] all = text.split("\n");
            int from = Math.max(0, all.length - 40);

            for (int i = from; i < all.length && lines.size() < MAX_FRAMES; i++) {
                String line = all[i].trim();

                if (line.length() > 0) {
                    lines.add(clean(line));
                }
            }

            send(context, lines);

        } catch (Throwable t) {
            //a crash report that cannot be sent must not become a second crash
        }
    }

    /**
     * Report that the previous run was killed rather than having crashed.
     *
     * Worth its own frame because it is the opposite diagnosis from a crash: nothing here
     * went wrong, something else on the device took the memory. The process table and the
     * free memory go with it, because those are what say who.
     */
    public static void sendKilled(Context context, String screen) {
        try {
            List<String> lines = new ArrayList<String>();
            lines.add("killed while showing " + clean(screen.length() > 0 ? screen : "launcher"));
            add(lines, "ps", "ps", 6, "ic.work|enqualcomm|watchlauncher|L009");
            add(lines, "mem", "cat /proc/meminfo", 4, "MemTotal|MemFree|Cached|SwapFree");

            //Who was big enough to be worth killing, and who the killer had already taken.
            //A kill names a victim; the process table names the reason.
            add(lines, "top", "ps", 8, "u0_a|system|app_");
            //A kill has more than one cause and the first report only looked for one of
            //them. It found no am_kill and no lowmemory, and the memory figures beside it
            //said the watch had 151 MB free - so the killer was never the explanation, and
            //the filter had nothing to offer instead. A process that dies with no Java stack
            //and no reclaim is a native crash, so the signatures for one go in too.
            add(lines, "log", "logcat -d -v brief", 10,
                "lowmemory|LowMemory|am_kill|Killing|to free"
                + "|Fatal signal|SIGSEGV|SIGABRT|tombstone|backtrace|ANR in|am_anr|am_proc_died");

            //And whether the kernel left a tombstone, which settles it either way.
            add(lines, "tomb", "ls -l /data/tombstones/ 2>/dev/null | tail -3", 3, "tombstone");
            send(context, lines);

        } catch (Throwable t) {
            //never let the reporter be the thing that fails
        }
    }

    private static final String SENT_LEN = "crash.sentlen";

    private static void send(Context context, List<String> lines) {

        //The imei lives in the vendor's config, which is only readable with root - same way
        //SleepService gets it before an upload.
        TrackerConfig cfg;
        RootShell root = new RootShell();

        try {
            cfg = new TrackerConfig(context, root);
            cfg.load();

        } catch (Throwable t) {
            return;

        } finally {
            try {
                root.close();

            } catch (Throwable t) {
                //ignore
            }
        }

        if (!cfg.usable()) {
            return;                                  // no imei; nothing to file it against
        }

        Socket s = null;

        try {
            s = new Socket();
            s.connect(new InetSocketAddress(cfg.host(), cfg.port()), CONNECT_MS);
            s.setSoTimeout(READ_MS);

            OutputStream out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));

            //no comma after the opcode - the server reads the imei from field 0, and a comma
            //there files everything against 0000000000000000 instead
            write(out, "IWAP00" + cfg.imei() + "#");

            String when = STAMP.format(new Date());
            int n = Math.min(lines.size(), MAX_FRAMES);

            for (int i = 0; i < n; i++) {
                write(out, "IWAPDG," + when + "," + (i + 1) + "/" + n + "," + lines.get(i) + "#");
                read(in);                            //keep the exchange in step
            }

        } catch (Exception e) {
            //a diagnostic that cannot be delivered is not worth failing the watchdog over

        } finally {
            try {
                if (s != null) {
                    s.close();
                }

            } catch (Exception e) {
                //ignore
            }
        }
    }

    // ------------------------------------------------------------------ collection

    private static List<String> collect(String why) {
        List<String> out = new ArrayList<String>();
        out.add("watchdog tripped: " + clean(why));

        //Who owns the sensor, and is it still there. The vendor splits this across three
        //processes - com.ic.work holds the sensor service, com.enqualcomm.support holds
        //CoreService and the alarms - and which of them changed pid between two reports is
        //what tells a restart apart from a hang.
        add(out, "ps", "ps", 6, "ic.work|enqualcomm|L009");

        //Alarms are the half of it the server cannot see. If SILENT_START and TEMP_ALARM are
        //still being scheduled while nothing is being measured, the fault is downstream of
        //the scheduling, which is the single most useful thing this can establish.
        add(out, "alarm", "dumpsys alarm", 8, "SILENT_START|TEMP_ALARM|enqualcomm");

        //And whatever the vendor stack said for itself.
        add(out, "log", "logcat -d -v brief", 10,
            "HeartRate|SILENT_START|TEMP_ALARM|PPG|hrs33|CoreService|SensorData");

        //Memory, because a low memory kill would explain a restart and its absence rules one
        //out. Two lines, not the whole file.
        add(out, "mem", "cat /proc/meminfo", 2, "MemTotal|MemFree");

        return out;
    }

    /**
     * Run one command, keep at most {@code max} lines matching {@code grep}, and label them.
     *
     * Uses {@link RootShell} when it is available and a plain shell when it is not. Most of
     * this is readable without root; dumpsys is the exception, and a report missing the alarm
     * section is still worth sending.
     */
    private static void add(List<String> out, String tag, String command, int max, String grep) {
        String text = run(command);

        if (text == null || text.length() == 0) {
            out.add(tag + ": (no output)");
            return;
        }

        String[] all = text.split("\n");
        int kept = 0;
        int matched = 0;

        for (int i = all.length - 1; i >= 0 && kept < max; i--) {
            String line = all[i].trim();

            if (line.length() == 0 || !matches(line, grep)) {
                continue;
            }

            matched++;

            //newest first: a hang is diagnosed from the last thing that happened, and the
            //frame budget runs out long before an hour of log does
            out.add(tag + ": " + clean(line));
            kept++;
        }

        if (kept == 0) {
            out.add(tag + ": (nothing matched)");
        }
    }

    private static boolean matches(String line, String alternatives) {
        for (String want : alternatives.split("\\|")) {
            if (line.indexOf(want) >= 0) {
                return true;
            }
        }

        return false;
    }

    private static String run(String command) {
        RootShell shell = null;

        try {
            shell = new RootShell();

            if (shell.open()) {
                String out = shell.exec(command);

                //An empty answer from the root shell is not an answer. It comes back that way
                //when the shell is busy or the command was not one it will run, and returning
                //it as-is is what turned /proc/meminfo into "(no output)" in the first report
                //that mattered - the field that would have said who took the memory. Fall
                //through to the plain shell instead, which can read /proc perfectly well
                //without root.
                if (out != null && out.trim().length() > 0) {
                    return out;
                }
            }

        } catch (Throwable t) {
            //fall through to the unprivileged path

        } finally {
            try {
                if (shell != null) {
                    shell.close();
                }

            } catch (Throwable t) {
                //ignore
            }
        }

        return runPlain(command);
    }

    private static String runPlain(String command) {
        Process p = null;

        try {
            p = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            InputStream is = p.getInputStream();
            StringBuilder b = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;

            while ((n = is.read(buf)) > 0 && b.length() < 64 * 1024) {
                b.append(new String(buf, 0, n, "UTF-8"));
            }

            return b.toString();

        } catch (Throwable t) {
            return null;

        } finally {
            if (p != null) {
                try {
                    p.destroy();

                } catch (Throwable t) {
                    //ignore
                }
            }
        }
    }

    /**
     * Make a line safe to put in a frame.
     *
     * Commas and '#' are the protocol's own punctuation - a '#' in the middle of a frame ends
     * it early and leaves the rest to be parsed as a new one - so both are replaced rather
     * than escaped. Nothing downstream needs to reconstruct the original text.
     */
    private static String clean(String s) {
        if (s == null) {
            return "";
        }

        StringBuilder b = new StringBuilder(Math.min(s.length(), MAX_TEXT));

        for (int i = 0; i < s.length() && b.length() < MAX_TEXT; i++) {
            char c = s.charAt(i);

            if (c == ',' || c == '#') {
                b.append(' ');

            } else if (c >= 32 && c < 127) {
                b.append(c);

            } else {
                b.append('.');                       //keeps the log one line per frame
            }
        }

        return b.toString();
    }

    private static void write(OutputStream out, String frame) throws Exception {
        out.write(frame.getBytes("US-ASCII"));
        out.flush();
    }

    private static String read(BufferedReader in) {
        try {
            StringBuilder b = new StringBuilder();

            for (int i = 0; i < 128; i++) {
                int c = in.read();

                if (c < 0) {
                    break;
                }

                b.append((char) c);

                if (c == '#') {
                    break;
                }
            }

            return b.length() == 0 ? null : b.toString();

        } catch (Exception e) {
            return null;                             //an unknown frame may not be acked at all
        }
    }
}
