package org.watchlauncher;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Reassembling a voice message pushed from the server, BP28.
 *
 * <h3>The frame</h3>
 *
 * {@code handleBP28} logs its fields in order: {@code name}, {@code
 * appendIndex}, {@code packetCount}, {@code currentId}, {@code packetLen},
 * {@code packetDataLen}, and then the payload; it writes the result to
 * {@code /Android/VoiceCache/receive_<name>.amr} and broadcasts
 * {@code NewVoice}.
 *
 * <h3>Why this needs no change to the read loop</h3>
 *
 * The media going the other way - AP42 pictures and AP07 voice - is
 * length-delimited rather than {@code #}-delimited, because a JPEG is full of
 * {@code #} and {@code ,} bytes. That is what made this look like it needed the
 * socket reader taken out of frame splitting for a counted number of bytes.
 *
 * But the uplink payload is <em>hex</em>: protocol/README records a 1024 byte
 * chunk arriving as 2048 characters of {@code ffd8ffe0...}, and the length field
 * counting characters rather than bytes. Hex is {@code [0-9a-fA-F]} - it
 * contains no {@code #} and no {@code ,} - so a hex-payload frame splits
 * correctly on the existing path, and the two separate length fields are exactly
 * what a hex encoding needs: {@code packetLen} the characters, {@code
 * packetDataLen} the bytes they decode to.
 *
 * So this assembles from the fields the ordinary splitter already produced.
 *
 * <h3>And if it turns out to be raw bytes after all</h3>
 *
 * Then the splitter will have cut the frame at the first {@code #} or {@code ,}
 * inside the audio and the payload arrives short. That is detectable rather than
 * silent: the decoded length will not match {@code packetDataLen}, and
 * {@link #accept} says so instead of writing a truncated file. No frame has been
 * captured to settle it, so the code handles the shape the evidence points at
 * and reports the other rather than guessing twice.
 */
public final class VoiceAssembler {

    /** An AMR file starts with this. Worth checking before calling something audio. */
    static final String AMR_MAGIC = "#!AMR";

    /** Nothing this device sends is minutes long; a runaway count is a bad frame. */
    private static final int MAX_PACKETS = 4096;

    /** 1 MB of AMR is about twenty minutes. Past this something is wrong. */
    private static final int MAX_BYTES = 1024 * 1024;

    /** What a frame did to the assembly. */
    public static final class Status {
        public boolean accepted;
        /** Set when the message is complete; the bytes are then in {@link #data}. */
        public boolean complete;
        public byte[] data;
        /** Null when nothing was wrong. */
        public String problem;
        public String name;
        public int packet;
        public int packets;
    }

    private String name;
    private int packets;
    private int next = 1;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    /**
     * Take one BP28 frame.
     *
     * The header is read from the end rather than by counting from the front: whether the
     * device id leads the frame is not established, and the payload is unambiguously last, so
     * the five numbers in front of it are unambiguous too.
     */
    public Status accept(List<String> fields) {
        Status s = new Status();
        if (fields == null || fields.size() < 6) {
            s.problem = "BP28 too short: " + (fields == null ? "null" : "" + fields.size())
                    + " fields";
            return s;
        }

        int n = fields.size();
        String payload = trim(fields.get(n - 1));
        int dataLen = number(fields.get(n - 2));
        int packetLen = number(fields.get(n - 3));
        int currentId = number(fields.get(n - 4));
        int packetCount = number(fields.get(n - 5));
        String who = (n >= 7) ? trim(fields.get(n - 7)) : "voice";

        if (packetCount <= 0 || packetCount > MAX_PACKETS
                || currentId <= 0 || currentId > packetCount) {
            s.problem = "BP28 packet " + currentId + " of " + packetCount + " is not a packet";
            return s;
        }
        s.name = who;
        s.packet = currentId;
        s.packets = packetCount;

        if (packetLen >= 0 && payload.length() != packetLen) {
            // The payload was cut, which is what a raw-byte payload looks like after the
            // splitter has met a '#' inside the audio.
            s.problem = "BP28 packet " + currentId + ": " + payload.length()
                    + " characters, header says " + packetLen
                    + " -- payload is probably not hex, and the frame was split inside it";
            return s;
        }

        byte[] bytes = unhex(payload);
        if (bytes == null) {
            s.problem = "BP28 packet " + currentId + ": payload is not hex";
            return s;
        }
        if (dataLen >= 0 && bytes.length != dataLen) {
            s.problem = "BP28 packet " + currentId + ": decoded " + bytes.length
                    + " bytes, header says " + dataLen;
            return s;
        }

        // A new message, or the first one.
        if (name == null || !name.equals(who) || currentId == 1) {
            if (currentId == 1) reset(who, packetCount);
            else {
                s.problem = "BP28 " + who + " starts at packet " + currentId + ", not 1";
                return s;
            }
        }
        if (currentId != next) {
            s.problem = "BP28 " + who + ": packet " + currentId + " arrived, expected " + next
                    + " -- not writing an audio file with a hole in it";
            return s;
        }
        if (buf.size() + bytes.length > MAX_BYTES) {
            s.problem = "BP28 " + who + ": over " + MAX_BYTES + " bytes";
            reset(null, 0);
            return s;
        }

        buf.write(bytes, 0, bytes.length);
        next++;
        s.accepted = true;

        if (currentId >= packets) {
            s.complete = true;
            s.data = buf.toByteArray();
            if (!looksLikeAmr(s.data)) {
                // Not fatal - it is still whatever the server sent, and saying so beats
                // renaming it silently.
                s.problem = "BP28 " + who + ": complete, but does not start with " + AMR_MAGIC;
            }
            reset(null, 0);
        }
        return s;
    }

    static boolean looksLikeAmr(byte[] b) {
        if (b == null || b.length < AMR_MAGIC.length()) return false;
        for (int i = 0; i < AMR_MAGIC.length(); i++) {
            if (b[i] != (byte) AMR_MAGIC.charAt(i)) return false;
        }
        return true;
    }

    private void reset(String who, int count) {
        name = who;
        packets = count;
        next = 1;
        buf.reset();
    }

    /** @return the bytes, or null if this is not an even-length run of hex digits. */
    static byte[] unhex(String s) {
        if (s == null || s.length() == 0 || (s.length() & 1) != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = digit(s.charAt(i * 2));
            int lo = digit(s.charAt(i * 2 + 1));
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(trim(s));
        } catch (Exception e) {
            return -1;
        }
    }
}
