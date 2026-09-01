import org.watchlauncher.BodyTemp;

/**
 * BodyTemp against the vendor library it replaces.
 *
 * The table is not hand-written and is not this implementation's own output. Each row is what
 * {@code get_bodytemp_from_wristtemp} returned when the 280 bytes of it were lifted verbatim
 * out of /system/lib/libICJniUtils.so, its two __android_log_print calls blanked, and the result
 * executed on an ARM core under qemu. Thirteen wrist temperatures at each of three ambients,
 * including both ends of the range where the vendor returns 0 rather than a temperature.
 *
 * So this does not check that the arithmetic is self-consistent. It checks that replacing a
 * vendor blob with seven lines of Java did not change a single answer.
 */
public class BodyTempTest {

    /** wrist, ambient, what the vendor's own code returned. */
    private static final double[][] REFERENCE = {
        { 28.0, 20.0, 32.764639999999986 },
        { 30.0, 20.0, 35.28199999999981 },
        { 31.0, 20.0, 35.852689999999825 },
        { 32.0, 20.0, 36.15759999999989 },
        { 33.0, 20.0, 36.34139000000016 },
        { 33.5, 20.0, 36.43307124999967 },
        { 34.0, 20.0, 36.548720000000344 },
        { 34.7, 20.0, 36.7853227300003 },
        { 35.0, 20.0, 36.92425000000014 },
        { 35.5, 20.0, 37.22029625000039 },
        { 36.0, 20.0, 37.6126400000004 },
        { 37.0, 20.0, 38.75855000000058 },
        { 38.0, 20.0, 40.506640000000175 },
        { 28.0, 26.0, 31.958815999999956 },
        { 30.0, 26.0, 34.865599999999745 },
        { 31.0, 26.0, 35.55259400000011 },
        { 32.0, 26.0, 35.921535999999946 },
        { 33.0, 26.0, 36.117086000000086 },
        { 33.5, 26.0, 36.19504524999968 },
        { 34.0, 26.0, 36.28390400000046 },
        { 34.7, 26.0, 36.461046490000285 },
        { 35.0, 26.0, 36.56665000000032 },
        { 35.5, 26.0, 36.796702250000294 },
        { 36.0, 26.0, 37.10998400000028 },
        { 37.0, 26.0, 38.058566000000724 },
        { 38.0, 26.0, 39.557056000000216 },
        { 28.0, 30.0, 31.421600000000012 },
        { 30.0, 30.0, 34.58799999999985 },
        { 31.0, 30.0, 35.35253 },
        { 32.0, 30.0, 35.76416000000029 },
        { 33.0, 30.0, 35.96755000000019 },
        { 33.5, 30.0, 36.036361249999686 },
        { 34.0, 30.0, 36.10736000000054 },
        { 34.7, 30.0, 36.244862330000274 },
        { 35.0, 30.0, 36.32825000000014 },
        { 35.5, 30.0, 36.51430625000023 },
        { 36.0, 30.0, 36.77488000000051 },
        { 37.0, 30.0, 37.591910000000894 },
        { 38.0, 30.0, 38.92400000000009 },
    };

    /** Far tighter than anything that matters: the reading is published to two decimals. */
    private static final double EPS = 1e-9;

    public static void main(String[] args) {
        int bad = 0;
        for (double[] row : REFERENCE) {
            double got = BodyTemp.fromWrist(row[0], row[1]);
            if (Math.abs(got - row[2]) > EPS) {
                System.out.println("  wrist " + row[0] + " ambient " + row[1]
                        + ": vendor " + row[2] + ", ours " + got);
                bad++;
            }
        }

        // The declining cases are the ones a clamp would get wrong, so they are named rather
        // than left to the table: 0 means "no reading", not "31 degrees".
        if (BodyTemp.fromWrist(20.0, 26.0) != 0.0) {
            System.out.println("  a cold wrist should decline, not clamp");
            bad++;
        }
        if (BodyTemp.fromWrist(45.0, 26.0) != 0.0) {
            System.out.println("  a hot wrist should decline, not clamp");
            bad++;
        }

        if (bad > 0) {
            System.out.println(bad + " disagreements with the vendor library");
            System.exit(1);
        }
        System.out.println("  " + REFERENCE.length
                + " points against the vendor library, all equal to 1e-9");
        System.out.println("body temperature: all checks passed");
    }
}
