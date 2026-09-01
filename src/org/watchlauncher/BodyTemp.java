package org.watchlauncher;

/**
 * A wrist reading converted to a body temperature.
 *
 * The thermometer on this watch reads the wrist, and a wrist is not a body: it sits a few
 * degrees above the room and well below the person wearing it. Converting one to the other is
 * the whole of this class.
 *
 * <h3>Where the numbers come from</h3>
 *
 * They are the vendor's, recovered rather than invented. {@code libICJniUtils.so} exports
 * {@code get_bodytemp_from_wristtemp}, and {@code com.ic.work} calls it from its own
 * {@code onSensorChanged} with the measured ambient where there is one and a constant where
 * there is not. This used to call it too, through JNI. It does the arithmetic itself now, which
 * is 280 bytes of VFP in the library and seven lines here.
 *
 * The function was pulled out of {@code /system/lib/libICJniUtils.so} in the stock image,
 * disassembled, and read off:
 *
 * <pre>
 * d0 = w*A          d1 = d0*w        d3 = -d3 + d1*w      vnmls
 * d2 = w*B          d3 = d2*w        d3 = d3 - d6*a       vmls
 * d5 = w*C          d6 = d5*w        d3 = d3 + d8*a       vmla
 * d8 = w*D                           d3 = d3 - a*E        vmls
 *                                    d3 = d3 + w*F        vmla
 *                                    d8 = d3 - G
 * </pre>
 *
 * with the seven constants in the pool that follows the function, and the two bounds as a
 * {@code vmov.f64 #31.0} immediate and a literal 42.0.
 *
 * <h3>Checked against the thing it replaces</h3>
 *
 * Not trusted to a reading of the disassembly. The 280 bytes were lifted verbatim out of the
 * library, its two {@code __android_log_print} calls blanked - they are the only thing in it
 * that is not arithmetic - and the result run on an ARM core under qemu against this
 * implementation, over 13 wrist temperatures at each of three ambients. All 39 agree to within
 * 1e-9, which for a function whose output is printed to two decimals is equality.
 *
 * The same 39 points are in BodyTempTest as a table, so the agreement is checked on every build
 * rather than having been checked once.
 *
 * <h3>What it is worth</h3>
 *
 * A wrist thermometer, not a clinical one. The conversion leans on the difference between skin
 * and surroundings, so in a cold room or outdoors the result drifts in the direction of the
 * error - and there is no ambient thermometer on this watch, so {@link #AMBIENT_C} is a stand-in
 * for a measurement nobody is making. The curve is also flat: 31 degrees at the wrist and 35
 * degrees at the wrist are a degree apart at the body, so it says much less about a fever than
 * the two decimal places suggest.
 *
 * Outside 31 to 42 it returns 0, which is what the vendor does. That is not a clamp and must not
 * be read as one: 0 means the conversion declined, and every caller treats it as no reading.
 */
public final class BodyTemp {

    /**
     * What com.ic.work passes when it has no ambient reading of its own, and what this passes
     * always, because this watch has no ambient thermometer to read.
     */
    public static final double AMBIENT_C = 26.0;

    /**
     * The vendor's own bounds. Outside these its function returns 0, and so does this one.
     *
     * Not the same question as {@link #PERSON_MIN_C} below, and the two are easy to confuse:
     * this is the range the conversion is willing to produce at all, and that is the range this
     * launcher is willing to call somebody's temperature.
     */
    public static final double MIN_C = 31.0, MAX_C = 42.0;

    /**
     * What this launcher will report as a person's temperature.
     *
     * Narrower than the conversion's own bounds at the bottom, and worked out the hard way: the
     * test used to be 20 to 45, which let the raw wrist reading through, so 21 was filed as a
     * body temperature. Nothing between 31 and 34 is a person either - it is a thermometer
     * reporting a room, or a watch on a table warm enough for the curve to still answer - and
     * reporting it as body heat is worse than reporting nothing.
     */
    public static final double PERSON_MIN_C = 34.0, PERSON_MAX_C = 43.0;

    private BodyTemp() { }

    /** The vendor's conversion, with its own fallback ambient. */
    public static double fromWrist(double wristC) {
        return fromWrist(wristC, AMBIENT_C);
    }

    /**
     * Wrist reading and ambient in, body temperature out, or 0 if the conversion declines.
     *
     * @param wristC   what the thermopile read, in Celsius
     * @param ambientC the surrounding air, in Celsius
     */
    public static double fromWrist(double wristC, double ambientC) {
        double w = wristC, a = ambientC;
        double w2 = w * w;
        double body = 0.02411 * w2 * w
                    - 2.288 * w2
                    - 0.004356 * w2 * a
                    + 0.2851 * w * a
                    - 4.702 * a
                    + 72.46 * w
                    - 728.9;
        // The vendor's own bounds, and its own answer for a reading outside them.
        return (body < MIN_C || body > MAX_C) ? 0.0 : body;
    }
}
