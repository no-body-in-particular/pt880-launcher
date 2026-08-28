import org.watchlauncher.VoiceAssembler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reassembling BP28.
 *
 * No real frame has been captured, so what these check is not "this is the
 * format". They check that a well-formed message comes back byte for byte, and
 * that every way the frame can be wrong - a gap, a restart, a truncated payload,
 * a length that disagrees with itself - is reported rather than written out as
 * an audio file with a hole in it.
 */
public class VoiceAssemblerTest {

    private static int failures = 0;

    public static void main(String[] args) {
        // "#!AMR" then some bytes, split across two packets.
        byte[] whole = concat(bytes("#!AMR"), new byte[]{0x3c, 0x00, 0x11, (byte) 0xff, 0x7f});
        byte[] first = Arrays.copyOfRange(whole, 0, 5);
        byte[] second = Arrays.copyOfRange(whole, 5, whole.length);

        VoiceAssembler a = new VoiceAssembler();
        VoiceAssembler.Status s = a.accept(packet("greeting", 2, 1, first));
        eq("first packet accepted", s.accepted, true);
        eq("not complete yet", s.complete, false);
        eq("no problem", s.problem, null);

        s = a.accept(packet("greeting", 2, 2, second));
        eq("second packet completes it", s.complete, true);
        eq("bytes survive the round trip", hex(s.data), hex(whole));
        eq("and it is AMR", s.problem, null);

        // A gap. Packet 2 missing, 3 arrives: that must not be written as audio.
        a = new VoiceAssembler();
        a.accept(packet("gap", 3, 1, first));
        s = a.accept(packet("gap", 3, 3, second));
        eq("a gap is refused", s.accepted, false);
        has("and says which packet", s.problem, "expected 2");

        // Starting in the middle - a message whose first packets were missed.
        a = new VoiceAssembler();
        s = a.accept(packet("late", 3, 2, first));
        eq("a mid-message start is refused", s.accepted, false);
        has("and says so", s.problem, "not 1");

        // A truncated payload: what a raw-byte frame looks like after the splitter has met a
        // '#' inside the audio. The header still claims the full length.
        List<String> cut = packet("cut", 1, 1, whole);
        cut.set(cut.size() - 1, hex(whole).substring(0, 4));
        a = new VoiceAssembler();
        s = a.accept(cut);
        eq("a short payload is refused", s.accepted, false);
        has("and blames the split", s.problem, "probably not hex");

        // A payload that is the right length but not hex at all.
        List<String> notHex = packet("odd", 1, 1, whole);
        notHex.set(notHex.size() - 1, repeat('z', hex(whole).length()));
        a = new VoiceAssembler();
        s = a.accept(notHex);
        eq("non-hex is refused", s.accepted, false);

        // Complete, but not AMR. Still saved - it is what the server sent - and still said.
        byte[] notAmr = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06};
        a = new VoiceAssembler();
        s = a.accept(packet("other", 1, 1, notAmr));
        eq("still complete", s.complete, true);
        has("but flagged", s.problem, "#!AMR");

        // Nonsense counts.
        a = new VoiceAssembler();
        s = a.accept(packet("bad", 0, 0, first));
        eq("zero packets refused", s.accepted, false);
        s = a.accept(packet("bad", 2, 5, first));
        eq("packet past the count refused", s.accepted, false);

        // Too few fields, and null.
        a = new VoiceAssembler();
        s = a.accept(new ArrayList<String>(Arrays.asList("1", "2")));
        eq("short frame refused", s.accepted, false);
        s = a.accept(null);
        eq("null frame refused", s.accepted, false);

        if (failures > 0) {
            System.out.println("voice assembler: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("voice assembler: all checks passed");
    }

    /**
     * A BP28 frame's fields as the splitter hands them over:
     * imei, name, appendIndex, packetCount, currentId, packetLen, packetDataLen, payload.
     */
    private static List<String> packet(String name, int count, int id, byte[] data) {
        String payload = hex(data);
        return new ArrayList<String>(Arrays.asList(
                "355932600098953", name, "0", "" + count, "" + id,
                "" + payload.length(), "" + data.length, payload));
    }

    private static byte[] bytes(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String hex(byte[] b) {
        if (b == null) return "<null>";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            s.append(Character.forDigit((b[i] >> 4) & 0xf, 16));
            s.append(Character.forDigit(b[i] & 0xf, 16));
        }
        return s.toString();
    }

    private static String repeat(char c, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(c);
        return s.toString();
    }

    private static void eq(String what, Object got, Object want) {
        if (got == null ? want == null : got.equals(want)) return;
        System.out.println("FAIL " + what + ": got " + got + ", wanted " + want);
        failures++;
    }

    private static void has(String what, String got, String needle) {
        if (got != null && got.indexOf(needle) >= 0) return;
        System.out.println("FAIL " + what + ": \"" + got + "\" does not mention " + needle);
        failures++;
    }
}
