import org.watchlauncher.AlarmParse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The alarm parser, against the shapes BP75 / BP85 / BPS4 are believed to take.
 *
 * These are not captured frames -- none has been seen -- so the point of the
 * test is not "this is the format". It is that the parser reads what it claims
 * to read and, more importantly, refuses what it does not understand instead of
 * inventing a time. A wrong alarm on a child's watch is the failure worth
 * testing against.
 */
public class AlarmParseTest {

    private static int failures = 0;

    public static void main(String[] args) {
        // Fields arrive already split on commas by BeehomeCodec.decode, so the payload of
        // "IWBP85,<imei>,<token>,0730,ON@2215,OFF#" reaches the parser as separate fields --
        // which is why the items, not the fields, are what carry the @.
        List<String> f = fields("355932600098953", "080835", "0730", "ON@2215", "OFF");
        AlarmParse.Result r = AlarmParse.parse(f, "080835");
        eq("two alarms read", size(r.alarms), 2);
        eq("first is the morning one", str(r.alarms, 0), "07:30 on");
        eq("second is off", str(r.alarms, 1), "22:15 off");
        eq("nothing left over", size(r.unparsed), 0);

        // Colons, and the vendor's CON for a repeating alarm.
        r = AlarmParse.parse(fields("355932600098953", "080835", "07:00", "CON"), "080835");
        eq("colon time", str(r.alarms, 0), "07:00 on");

        // The literals the vendor's handler carries alongside the time.
        r = AlarmParse.parse(fields("355932600098953", "080835", "0645.W.ST", "ON"), "080835");
        eq("week and ST ignored", str(r.alarms, 0), "06:45 on");

        // A four digit number that is not a time must not become one. 9999 is the trap: it
        // parses, it is four digits, and half past ninety-nine is not an alarm.
        r = AlarmParse.parse(fields("355932600098953", "080835", "9999", "ON"), "080835");
        eq("9999 is not a time", size(r.alarms), 0);
        eq("and is reported as unparsed", size(r.unparsed), 1);

        // An item with a count in it, then the time. The count must not win.
        r = AlarmParse.parse(fields("355932600098953", "080835", "2", "0815", "ON"), "080835");
        eq("a count before the time", str(r.alarms, 0), "08:15 on");

        // Off by default: an item that never says ON is not an alarm that rings.
        r = AlarmParse.parse(fields("355932600098953", "080835", "0700"), "080835");
        eq("no switch means off", str(r.alarms, 0), "07:00 off");

        // Seven is the limit the vendor's handler names; the rest are reported, not dropped
        // silently.
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            if (i > 0) many.append('@');
            many.append("0").append(i).append("00,ON");
        }
        r = AlarmParse.parse(fields("355932600098953", "080835", many.toString()), "080835");
        eq("capped at seven", size(r.alarms), 7);
        eq("the extra two are reported", size(r.unparsed), 2);

        // Nothing at all, rather than an exception.
        r = AlarmParse.parse(new ArrayList<String>(), "080835");
        eq("empty frame", size(r.alarms), 0);
        r = AlarmParse.parse(null, "080835");
        eq("null frame", size(r.alarms), 0);

        if (failures > 0) {
            System.out.println("alarm parse: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("alarm parse: all checks passed");
    }

    private static List<String> fields(String... v) {
        return new ArrayList<String>(Arrays.asList(v));
    }

    private static int size(List<?> l) {
        return l == null ? -1 : l.size();
    }

    private static String str(List<AlarmParse.Alarm> l, int i) {
        return (l == null || i >= l.size()) ? "<missing>" : l.get(i).toString();
    }

    private static void eq(String what, Object got, Object want) {
        if (got == null ? want == null : got.equals(want)) return;
        System.out.println("FAIL " + what + ": got " + got + ", wanted " + want);
        failures++;
    }
}
