
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

    public static void main(String[] args) throws Exception {
        String id = "05700507200008";

        // --- degrees to degrees-and-decimal-minutes -------------------------------
        // 5128.0000 is 51 degrees 28.0000 minutes, not 51.4667000 degrees.
        eq("lat 51.4667000 -> ddmm.mmmm", BeehomeCodec.dm(51.0 + 28.0000 / 60.0, 2), "5128.0000");
        eq("lon 4.5000000 -> dddmm.mmmm", BeehomeCodec.dm(4.0 + 30.0000 / 60.0, 3), "00430.0000");
        eq("lon pads to three degree digits", BeehomeCodec.dm(4.5, 3), "00430.0000");

        // --- a real position frame ------------------------------------------------
        // Straight out of the server's log, from when the vendor client was still running:
        //
        //   IWAP01260827A5128.0000N00430.0000E001.0214302345.8907300509900008,204,08,...
        //          yymmdd|    lat  |    lon   | spd hhmmss course sig sat bat tail
        //
        // 59 characters before the first comma, which is the length CTracker checks for. There
        // is no device id in it: the session is identified by the AP00 login.
        String wifi = "AP1|60:a4:b7:68:f1:ae|-55";
        String got = BeehomeCodec.location(id, true,
                51.0 + 28.0000 / 60.0, 4.0 + 30.0000 / 60.0,
                1.0, 345.89, 2026, 8, 27, 21, 43, 2,
                73, 5, 99,
                new String[]{"204", "08", "3270", "1561888"}, wifi);
        eq("AP01 matches a captured frame", got,
                "IWAP01260827A5128.0000N00430.0000E001.0214302345.8907300509900008"
                        + ",204,08,3270,1561888," + wifi + "#");
        check("the block is 59 characters, as the server's sscanf wants",
                got.indexOf(',') - 6 == 59, "" + (got.indexOf(',') - 6));

        // no fix: the server already understands this, it is what the vendor sends indoors
        String nofix = BeehomeCodec.location(id, false, 0, 0, 0, 356,
                2026, 8, 26, 21, 58, 59, 65, 0, 93,
                new String[]{"204", "08", "3270", "1561888"}, "");
        eq("AP01 no-fix form", nofix,
                "IWAP01260826V0000.0000N00000.0000E000.0215859356.0006500009300008"
                        + ",204,08,3270,1561888,#");

        // --- heartbeat, which is also the login -----------------------------------
        // The login puts the id in field 0 -- the text between character six and the first
        // comma, which is where the server reads it. A comma straight after the opcode leaves
        // field 0 empty, pad_imei turns that into 0000000000000000, and every frame on the
        // session is filed against a device that does not exist while the socket works
        // perfectly. That is the bug this line exists to hold shut.
        eq("AP00 login has no comma after the opcode", BeehomeCodec.login(id),
                "IWAP00" + id + "#");
        check("and the id really is field 0",
                BeehomeCodec.login(id).substring(6).split(",")[0].replace("#", "").equals(id),
                BeehomeCodec.login(id).substring(6).split(",")[0]);

        eq("AP03 heartbeat", BeehomeCodec.heartbeat(id, 0, 8, 600),
                "IWAP03," + id + ",0,00,8,600#");

        eq("APVR version", BeehomeCodec.version(id, "l009-EU-noAnti-Common-V3.70.20240808.162444"),
                "IWAPVR," + id + ",l009-EU-noAnti-Common-V3.70.20240808.162444#");

        // No device id in this frame, and that is the vendor's own shape: its recorded
        // frames read "IWAPJK,2026-08-25 17:33:03,2,57#". The session is identified by the
        // AP00 login, and the server attributes what follows to the connection.
        eq("APJK health", BeehomeCodec.health("2026-08-26 21:46:08", 3, 36.35),
                "IWAPJK,2026-08-26 21:46:08,3,36.35#");

        eq("APJK sleep", BeehomeCodec.sleep("2026-08-24 08:00:00", 5, 675),
                "IWAPJK,2026-08-24 08:00:00,5,675#");

        // The bug this guard exists for: a vitals path reaching the frame with a sleep type.
        // Type 5 is deep sleep in minutes, so a heart rate sent as one is recorded as most of a
        // night and nothing downstream can tell it apart from a real reading.
        boolean refused = false;
        try {
            BeehomeCodec.health("2026-08-26 21:46:08", 5, 59);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check("health() refuses a sleep type", refused, "accepted type 5");

        boolean refusedVitals = false;
        try {
            BeehomeCodec.sleep("2026-08-26 21:46:08", 2, 59);
        } catch (IllegalArgumentException e) {
            refusedVitals = true;
        }
        check("sleep() refuses a vitals type", refusedVitals, "accepted type 2");

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

        // --- media packets ---------------------------------------------------------
        // A payload full of the bytes that would break a text codec: NUL, '#', ','.
        byte[] nasty = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x00, '#', ',', 0x00, (byte) 0xAB};
        byte[] pkt = BeehomeCodec.mediaPacket("42", "20260828120000", 3, 1, nasty, 0, nasty.length);
        String header = new String(pkt, 0, 28, "UTF-8");
        eq("AP42 header", header, "IWAP42,20260828120000,3,1,7,");
        check("payload survives NUL, '#' and ',' intact",
                pkt.length == 28 + 7 + 1
                        && pkt[28] == (byte) 0xFF && pkt[30] == 0x00 && pkt[31] == '#'
                        && pkt[32] == ',' && pkt[pkt.length - 1] == '#',
                "len=" + pkt.length);

        // the ack that actually advances an upload is BP07, not the BP42 the manual documents
        BeehomeCodec.Frame ok7 = BeehomeCodec.decode("IWBP07,20260828120000,3,1,1#");
        check("BP07 with ok=1 advances packet 1", BeehomeCodec.advancesMedia(ok7, 1), "");
        check("BP07 for a different packet does not", !BeehomeCodec.advancesMedia(ok7, 2), "");
        BeehomeCodec.Frame bad7 = BeehomeCodec.decode("IWBP07,20260828120000,3,1,0#");
        check("BP07 with ok=0 does not advance", !BeehomeCodec.advancesMedia(bad7, 1), "");
        BeehomeCodec.Frame bp42 = BeehomeCodec.decode("IWBP42,20260828120000,3,1,1#");
        check("BP42 never advances an upload", !BeehomeCodec.advancesMedia(bp42, 1), "");

        // --- replies must be distinguishable from commands -------------------------
        // Acking a reply sends "IWAP00,#" or "IWAP01,#", which are malformed login and
        // position frames rather than acknowledgements. The server answers them and the
        // exchange loops several times a second. Observed live before the token check existed.
        check("a reply carries no token: BP00 time sync",
                BeehomeCodec.decode("IWBP00,20260827232918,2#").token() == null, "");
        check("a reply carries no token: bare BP01",
                BeehomeCodec.decode("IWBP01#").token() == null, "");
        check("a reply carries no token: bare BP03",
                BeehomeCodec.decode("IWBP03#").token() == null, "");
        check("but a command still has one",
                "080835".equals(BeehomeCodec.decode("IWBP18," + id + ",080835#").token()), "");

        System.out.println(fails == 0 ? "beehome codec: all checks passed"
                : ("beehome codec: " + fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
