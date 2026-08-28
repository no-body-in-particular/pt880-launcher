package org.watchlauncher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The wire format the tracker server speaks, encoded and decoded here and nowhere else.
 *
 * <h3>Framing</h3>
 *
 * <pre>
 *     IW &lt;dir&gt;&lt;opcode&gt; , field , field ... #
 *
 *     IWAP03,&lt;id&gt;,0,00,8,600#        watch -&gt; server   (AP = uplink)
 *     IWBPXL,&lt;id&gt;,080835#            server -&gt; watch   (BP = downlink)
 * </pre>
 *
 * Plaintext, comma separated, terminated by {@code #}. The opcode is two characters, digits or
 * letters. There is no authentication beyond the device id.
 *
 * <h3>Why this class holds no state and touches no Android</h3>
 *
 * So it can be tested on a desktop against frames captured from the real server, which is the
 * only way to be sure of a format nobody documented. {@link TrackerService} owns the socket and
 * the scheduling; this only turns bytes into meaning and back.
 *
 * <h3>The correlation id</h3>
 *
 * Every command the server sends carries a token, and the watch echoes it in its
 * acknowledgement so the server can match the two:
 *
 * <pre>
 *     server: IWBP18,&lt;id&gt;,080835#
 *     watch:  IWAP18,080835#
 * </pre>
 *
 * It is a fixed {@code 080835} in every frame the current server emits, and it would be very
 * easy to hardcode that -- it appears in all 794 command frames of the capture this was written
 * from. It is not hardcoded, and must not be: it is the server's value, not the watch's. It
 * appears nowhere in the vendor firmware, and the day the server starts issuing real sequence
 * numbers a hardcoded echo would ack the wrong command while looking perfectly healthy.
 * {@link Frame#token} carries whatever arrived.
 *
 * <h3>Positions</h3>
 *
 * {@code AP01} is positional, not comma separated, and NMEA-shaped:
 *
 * <pre>
 *     260826 A 5128.0000N 00430.0000E 000.2 214309 015. &lt;id&gt;
 *     YYMMDD fix   lat        lon     speed HHMMSS course
 * </pre>
 *
 * Degrees-and-decimal-minutes, not decimal degrees: {@code 5128.0000N} is 52 degrees 05.1091
 * minutes. Sending decimal degrees would put the watch a few hundred kilometres away and still
 * look like a valid fix, which is the kind of bug that is only caught on a map.
 *
 * Course is three digits and a trailing dot ({@code 015.}), speed {@code NNN.N}. When there is
 * no fix the status is {@code V} and the coordinates are zeroed, which the server already
 * understands -- it is what the vendor sends indoors.
 */
public final class BeehomeCodec {

    /** Uplink prefix: watch to server. */
    private static final String UP = "IWAP";

    private BeehomeCodec() {
    }

    // ------------------------------------------------------------------ decoding

    /** One decoded downlink frame. */
    public static final class Frame {
        /** Two-character opcode without the {@code IWBP} prefix, e.g. {@code "18"}, {@code "XL"}. */
        public final String op;
        /** Fields after the opcode, in order, with the trailing {@code #} removed. */
        public final List<String> fields;

        Frame(String op, List<String> fields) {
            this.op = op;
            this.fields = fields;
        }

        /**
         * The correlation token to echo back, or null if the frame carried none.
         *
         * The server puts the device id first and the token second, but not every command has
         * both, so this takes the last field that looks like a token rather than assuming an
         * index. Echoing the device id back by mistake would be a valid-looking wrong answer.
         */
        public String token() {
            for (int i = fields.size() - 1; i >= 0; i--) {
                String f = fields.get(i);
                if (f.length() >= 4 && f.length() <= 10 && isDigits(f)) return f;
            }
            return null;
        }

        @Override
        public String toString() {
            return "BP" + op + fields;
        }
    }

    /**
     * Parse one downlink frame, or return null if it is not one.
     *
     * Tolerant on purpose: the socket hands over whatever arrived, which can be a partial line,
     * two frames at once, or noise. Anything that is not recognisably {@code IWBP..} is dropped
     * rather than guessed at.
     */
    public static Frame decode(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() < 6) return null;
        if (!s.startsWith("IWBP")) return null;
        if (s.endsWith("#")) s = s.substring(0, s.length() - 1);

        String op = s.substring(4, 6);
        List<String> fields = new ArrayList<String>();
        if (s.length() > 6) {
            String rest = s.substring(6);
            if (rest.startsWith(",")) rest = rest.substring(1);
            // -1 keeps trailing empty fields: "IWBPSQ,<id>,080835,1,3,#" ends with one and
            // dropping it would silently shift every index after it.
            for (String f : rest.split(",", -1)) fields.add(f);
        }
        return new Frame(op, fields);
    }

    /** Split a read buffer into complete frames, returning the unconsumed tail. */
    public static String[] split(String buffered) {
        List<String> out = new ArrayList<String>();
        int from = 0;
        while (true) {
            int end = buffered.indexOf('#', from);
            if (end < 0) break;
            out.add(buffered.substring(from, end + 1));
            from = end + 1;
        }
        String[] r = new String[out.size() + 1];
        for (int i = 0; i < out.size(); i++) r[i] = out.get(i);
        r[out.size()] = buffered.substring(from);      // tail, possibly partial
        return r;
    }

    // ------------------------------------------------------------------ encoding

    /** {@code IWAP<op>,<field>,...#} */
    public static String frame(String op, String... fields) {
        StringBuilder b = new StringBuilder(UP).append(op);
        for (String f : fields) b.append(',').append(f == null ? "" : f);
        return b.append('#').toString();
    }

    /**
     * The login, and the only frame that tells the server whose socket this is.
     *
     * <h3>No comma after the opcode</h3>
     *
     * The server splits on {@code ,} starting six characters in and reads the device id from
     * field 0. {@code IWAP00355932600098953#} puts the id there. {@code IWAP00,355932...} puts
     * an empty string there, {@code pad_imei} turns that into {@code 0000000000000000}, and the
     * whole session is filed against a device that does not exist - while the connection works
     * perfectly and every frame is answered, which is what makes it so hard to see from here.
     *
     * SleepUpload has always sent it this way, which is why sleep readings arrived when nothing
     * else did.
     */
    public static String login(String id) {
        return UP + "00" + id + "#";
    }

    /**
     * Heartbeat.
     *
     * This was the login too, on the reasoning that it is the first frame out and carries the
     * id. It is not: {@link #frame} puts a comma straight after the opcode, so the id lands in
     * field 1 and field 0 - where the server looks - is empty. Sending {@link #login} first is
     * what identifies the socket; this then keeps it alive.
     */
    public static String heartbeat(String id, int steps, int batteryPercent, int cycleSeconds) {
        return frame("03", id, Integer.toString(steps), "00",
                Integer.toString(batteryPercent), Integer.toString(cycleSeconds));
    }

    /** Firmware build string, sent unprompted after a reconnect. */
    public static String version(String id, String build) {
        return frame("VR", id, build);
    }

    /** Bytes per media packet. The server defines one as 1024, the last one shorter. */
    public static final int MEDIA_CHUNK = 1024;

    /**
     * One packet of a picture ({@code AP42}) or a recording ({@code AP07}).
     *
     * <pre>
     *     IWAP42,&lt;yyyymmddhhmmss&gt;,&lt;total&gt;,&lt;packet no&gt;,&lt;length&gt;,&lt;length bytes&gt;#
     * </pre>
     *
     * Built as bytes, not a String, and delimited by the length rather than by the trailing
     * {@code #}. The payload is raw JPEG or AMR: it contains NUL, {@code #} and {@code ,}
     * constantly, so anything that treats it as text truncates the file at the first {@code #}
     * and silently corrupts what survives. Packet numbers are 1-based -- the server starts a
     * new image when it sees packet 1.
     */
    public static byte[] mediaPacket(String op, String devTime, int total, int no,
                                     byte[] data, int off, int len) {
        byte[] head;
        try {
            head = (UP + op + "," + devTime + "," + total + "," + no + "," + len + ",")
                    .getBytes("UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        byte[] out = new byte[head.length + len + 1];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(data, off, out, head.length, len);
        out[out.length - 1] = '#';
        return out;
    }

    /**
     * True if this frame is the acknowledgement that lets the next media packet go.
     *
     * It is {@code BP07}, not the {@code BP42} the manual documents. The picture is pushed
     * through the voice-packet sender, and the only place its position index is written is the
     * BP07 handler -- BP42 is parsed, logged, and then ignored. A client waiting on BP42 sends
     * packet one and stops forever, having been acknowledged at the TCP level the whole time.
     *
     * Five fields, and it only counts when the last is {@code "1"}.
     */
    public static boolean advancesMedia(Frame f, int packetJustSent) {
        if (f == null || !"07".equals(f.op)) return false;
        if (f.fields.size() < 4) return false;
        if (!"1".equals(f.fields.get(f.fields.size() - 1).trim())) return false;
        try {
            return Integer.parseInt(f.fields.get(f.fields.size() - 2).trim()) == packetJustSent;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Acknowledge a server command by echoing its token back under the same opcode. */
    public static String ack(String op, String token) {
        return frame(op, token == null ? "" : token);
    }

    /**
     * Everything the JK frame carries, by type number.
     *
     * One table because the numbers are shared across two senders that know nothing about each
     * other: the vitals path here, and SleepUpload. 1-4 are the vendor's. 5-7 are what the
     * firmware measured, 8-20 what this launcher measures.
     *
     * Index 0 is unused so a type number indexes its own name.
     */
    static final String[] JK_TYPES = {
        null,
        "blood pressure", "heart rate", "temperature", "blood oxygen",
        "deep sleep", "light sleep", "sleep score",
        "total sleep time", "sleep period", "wake after onset", "sleep efficiency",
        "wakeups", "sleeping now", "day total",
        "relative amplitude", "intradaily variability", "interdaily stability",
        "L5 start", "M10 start", "sleep regularity",
    };

    static String jkName(int type) {
        return (type > 0 && type < JK_TYPES.length) ? JK_TYPES[type] : "type " + type;
    }

    /** The two the vitals path is allowed to send. */
    private static final int JK_HEART_RATE = 2;
    private static final int JK_TEMPERATURE = 3;

    /**
     * A vitals reading. Type 3 is temperature in the capture this was built from; the value is
     * sent as the vendor formats it, one decimal.
     *
     * <h3>Why the type is checked</h3>
     *
     * These numbers are one flat namespace shared with sleep, and they are not interchangeable:
     * 5 is deep sleep in minutes. A vitals path that reached this with a 5 - which is exactly
     * what an earlier blood oxygen bug did - files a heart rate as most of a night's sleep, and
     * nothing downstream can tell. So this refuses anything it does not own rather than
     * formatting it faithfully. Sleep readings go through {@link #sleep}.
     */
    public static String health(String isoUtcTime, int type, double value) {
        if (type != JK_HEART_RATE && type != JK_TEMPERATURE) {
            throw new IllegalArgumentException(
                    "health() sends heart rate and temperature; " + type + " is "
                    + jkName(type) + " -- use sleep() for those");
        }
        return frame("JK", isoUtcTime, Integer.toString(type),
                String.format(Locale.US, "%.2f", value));
    }

    /**
     * Blood pressure, which the JK frame carries as one field rather than two.
     *
     * Type 1, value "<diastolic>|<systolic>" - the shape the vendor's own recorded frames use,
     * "1,80|121". The server has kept systolic and diastolic series since long before this
     * client existed, and they were fed by frames of exactly this form.
     */
    public static String bloodPressure(String isoUtcTime, int systolic, int diastolic) {
        return frame("JK", isoUtcTime, "1", diastolic + "|" + systolic);
    }

    /**
     * A sleep reading, types 5 to 20.
     *
     * Separate from {@link #health} so that neither path can reach the other's numbers by
     * passing the wrong constant. Integers, because every one of these is a count, a minute or
     * a percentage - a ratio is multiplied by a hundred before it gets here.
     */
    public static String sleep(String isoUtcTime, int type, int value) {
        if (type < 5 || type >= JK_TYPES.length) {
            throw new IllegalArgumentException(
                    "sleep() sends types 5-" + (JK_TYPES.length - 1) + "; " + type + " is "
                    + jkName(type));
        }
        return frame("JK", isoUtcTime, Integer.toString(type), Integer.toString(value));
    }

    /**
     * The five characters the vendor puts after the battery, unexplained.
     *
     * Constant across every captured frame - "...09900008", "...09800008", "...09300008" - and
     * the block is exactly 59 characters with it and 54 without, which is the length CTracker's
     * sscanf wants. So it is sent as the vendor sends it rather than dropped for not being
     * understood.
     */
    private static final String TAIL = "00008";

    /**
     * A position report.
     *
     * <h3>The layout, from frames the server actually received</h3>
     *
     * <pre>
     * IWAP01 260827 A 5128.0000 N 00430.0000 E 001.0 214302 345.89 073 005 099 00008
     *        yymmdd |    lat    |     lon    |  speed hhmmss course sig sat batt tail
     * </pre>
     *
     * Not from a specification: those are real frames out of the server's own log, from when
     * the vendor client was still running. The block is 59 characters, which is exactly the
     * minimum CTracker checks for before it will read one.
     *
     * <h3>What this used to send</h3>
     *
     * Course as "%03d." - four characters instead of six - then no signal, no satellites, no
     * battery, and the device id appended where the vendor puts none. 58 characters, one short
     * of the check, with every field after the time at the wrong offset. The id is not in this
     * frame at all: the session is identified by the AP00 login.
     *
     * @param valid false sends the no-fix form the server already expects indoors
     * @param cells {@code MCC,MNC,LAC,CI} as four fields
     * @param wifi  {@code AP1|<bssid>|<rssi>&AP2|...}, or null to omit
     */
    public static String location(String id, boolean valid, double lat, double lon,
                                  double speedKmh, double courseDeg,
                                  int year, int month, int day,
                                  int hour, int minute, int second,
                                  int signal, int satellites, int battery,
                                  String[] cells, String wifi) {
        StringBuilder b = new StringBuilder(UP).append("01");
        b.append(String.format(Locale.US, "%02d%02d%02d", year % 100, month, day));
        b.append(valid ? 'A' : 'V');
        if (valid) {
            b.append(dm(lat, 2)).append(lat >= 0 ? 'N' : 'S');
            b.append(dm(lon, 3)).append(lon >= 0 ? 'E' : 'W');
        } else {
            b.append("0000.0000N00000.0000E");
        }
        b.append(String.format(Locale.US, "%05.1f", clamp(speedKmh, 0, 999.9)));
        b.append(String.format(Locale.US, "%02d%02d%02d", hour, minute, second));
        b.append(String.format(Locale.US, "%06.2f", ((courseDeg % 360) + 360) % 360));
        b.append(String.format(Locale.US, "%03d", (int) clamp(signal, 0, 999)));
        b.append(String.format(Locale.US, "%03d", (int) clamp(satellites, 0, 999)));
        b.append(String.format(Locale.US, "%03d", (int) clamp(battery, 0, 999)));
        b.append(TAIL);
        if (cells != null) {
            for (String c : cells) b.append(',').append(c);
        }
        b.append(',').append(wifi == null ? "" : wifi);
        return b.append('#').toString();
    }

    /**
     * Degrees to the protocol's degrees-and-decimal-minutes, zero padded to {@code degDigits}.
     * 51.4667000 -> {@code 5128.0000}.
     */
    public static String dm(double deg, int degDigits) {
        double a = Math.abs(deg);
        int d = (int) a;
        double minutes = (a - d) * 60.0;
        return String.format(Locale.US, "%0" + degDigits + "d%07.4f", d, minutes);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
