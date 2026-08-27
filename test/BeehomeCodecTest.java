
import org.watchlauncher.BeehomeCodec;

/**
 * The wire format, checked against frames captured from the live server rather than against
 * what the format was assumed to be.
 *
 * Every expected string below is a real frame from a day of server logs, with the device id
 * replaced. That matters more here than in most tests: nobody documented this protocol, the
 * fields are positional with no separators, and a position encoded in decimal degrees instead
 * of degrees-and-minutes still looks like a perfectly valid fix -- just one a few hundred
 * kilometres away. Only a byte-for-byte comparison against something the server actually sent
 * catches that.
 */
public class BeehomeCodecTest {

    static int fails = 0;

    static void check(String what, boolean ok, String detail) {
        System.out.printf("  %-46s %s%s%n", what, ok ? "ok" : "FAILED",
                detail.isEmpty() ? "" : ("   " + detail));
        if (!ok) fails++;
    }

    static void eq(String what, String got, String want) {
        check(what, want.equals(got), want.equals(got) ? "" : "\n      got  " + got + "\n      want " + want);
    }

    public static void main(String[] args) {
        String id = "05700507200008";

        // --- degrees to degrees-and-decimal-minutes -------------------------------
        // 5128.0000 is 51 degrees 28.0000 minutes, not 51.4667000 degrees.
        eq("lat 51.4667000 -> ddmm.mmmm", BeehomeCodec.dm(51.0 + 28.0000 / 60.0, 2), "5128.0000");
        eq("lon 4.5000000 -> dddmm.mmmm", BeehomeCodec.dm(4.0 + 30.0000 / 60.0, 3), "00430.0000");
        eq("lon pads to three degree digits", BeehomeCodec.dm(5.0, 3), "00430.0000");

        // --- a real position frame ------------------------------------------------
        // IWAP01260826A5128.0000N00430.0000E000.2214309015.<id>,204,08,3270,1561888,<wifi>
        String wifi = "AP1|60:a4:b7:68:f1:ae|-55";
        String got = BeehomeCodec.location(id, true,
                51.0 + 28.0000 / 60.0, 4.0 + 30.0000 / 60.0,
                0.2, 15, 2026, 8, 26, 21, 43, 9,
                new String[]{"204", "08", "3270", "1561888"}, wifi);
        eq("AP01 matches a captured frame", got,
                "IWAP01260826A5128.0000N00430.0000E000.2214309015." + id
                        + ",204,08,3270,1561888," + wifi + "#");

        // no fix: the server already understands this, it is what the vendor sends indoors
        String nofix = BeehomeCodec.location(id, false, 0, 0, 0, 356,
                2026, 8, 26, 21, 58, 59, new String[]{"204", "08", "3270", "1561888"}, "");
        eq("AP01 no-fix form", nofix,
                "IWAP01260826V0000.0000N00000.0000E000.0215859356." + id
                        + ",204,08,3270,1561888,#");

        // --- heartbeat, which is also the login -----------------------------------
        eq("AP03 heartbeat", BeehomeCodec.heartbeat(id, 0, 8, 600),
                "IWAP03," + id + ",0,00,8,600#");

        eq("APVR version", BeehomeCodec.version(id, "l009-EU-noAnti-Common-V3.70.20240808.162444"),
                "IWAPVR," + id + ",l009-EU-noAnti-Common-V3.70.20240808.162444#");

        eq("APJK health", BeehomeCodec.health("2026-08-26 21:46:08", 3, 36.35),
                "IWAPJK,2026-08-26 21:46:08,3,36.35#");

        // --- downlink -------------------------------------------------------------
        BeehomeCodec.Frame f = BeehomeCodec.decode("IWBP18," + id + ",080835#");
        check("BP18 opcode", f != null && "18".equals(f.op), f == null ? "null" : f.op);
        eq("BP18 token is echoed, not the device id", f.token(), "080835");
        eq("ack echoes under the same opcode", BeehomeCodec.ack("18", f.token()),
                "IWAP18,080835#");

        // the trailing empty field in BPSQ must survive, or every index after it shifts
        BeehomeCodec.Frame sq = BeehomeCodec.decode("IWBPSQ," + id + ",080835,1,3,#");
        check("BPSQ keeps its trailing empty field",
                sq != null && sq.fields.size() == 5,
                sq == null ? "null" : ("fields=" + sq.fields));

        check("a non-frame decodes to null", BeehomeCodec.decode("hello") == null, "");
        check("an uplink frame is not a downlink", BeehomeCodec.decode("IWAP03,1,2#") == null, "");

        // --- stream splitting -----------------------------------------------------
        String[] parts = BeehomeCodec.split("IWBPXL," + id + ",080835#IWBP50," + id + ",080835#IWBP1");
        check("two whole frames split out", parts.length == 3, "n=" + parts.length);
        eq("partial tail is kept for the next read", parts[parts.length - 1], "IWBP1");

        System.out.println(fails == 0 ? "beehome codec: all checks passed"
                : ("beehome codec: " + fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
