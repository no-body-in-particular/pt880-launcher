package com.ic.jni;

/**
 * The vendor's sensor library, redeclared so we can call it.
 *
 * No code of ours belongs here. libICJniUtils.so exports its entry points by name -
 * Java_com_ic_jni_ICJniUtils_getPPG and the rest - with no JNI_OnLoad and no RegisterNatives,
 * so the runtime resolves them from the fully qualified class name. The declarations therefore
 * have to be in a class called exactly this, in exactly this package; anywhere else and the
 * symbols do not resolve. That is the only reason this file exists, and why it is not inside
 * {@link org.watchlauncher.SensorInput} where the logic that uses it lives.
 *
 * The signatures are not guesses. They were read out of the method_ids of
 * /system/priv-app/ICTemperatureTest.odex, which declares this same class - every one static
 * native, ints throughout except the temperature pair, which is doubles.
 *
 * The library links only libc, liblog, libm and libstdc++, so it reaches the hardware itself
 * rather than through the sensor HAL. That is the point of using it: the HAL is the broken
 * piece, and this goes around it. See SensorInput for the evidence.
 */
public final class ICJniUtils {

    private ICJniUtils() { }

    public static native int enablePPG();
    public static native int disablePPG();
    public static native int isPPGStarted();
    public static native int isPPGavailable();
    public static native int isWeared();

    /** Heart rate. The vendor's own logging calls this one "ppg". */
    public static native int getPPG();
    public static native int getSPO2();
    public static native int getHighBloodPressure();
    public static native int getLowBloodPressure();
}
