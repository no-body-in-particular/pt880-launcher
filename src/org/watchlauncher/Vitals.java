package org.watchlauncher;

/**
 * One vitals measurement.
 *
 * Every field is zero when that thing was not measured, which is a different statement from
 * measuring zero -- see {@link #toString}.
 *
 * This used to be VendorVitals.Reading, a nested class of the client for the OEM's sensor
 * service, because that service was once the only thing on the watch that could start a
 * measurement. It is not any more: {@code vitalsd} drives the chip directly and produces all of
 * these, and the vendor client has been removed. The reading outlived the thing it was named
 * after, so it is its own class now and named for what it holds.
 */
public final class Vitals {
    public int oxygen;
    public int heartRate;
    public int systolic;
    public int diastolic;
    /**
     * Wrist temperature in Celsius, 0 when the thermometer said nothing.
     *
     * A wrist and not a body: it sits a few degrees above the room and well below its owner,
     * which is how the vendor once filed 21 C as a body temperature. Converting one to the
     * other needs an ambient reading this watch does not have.
     */
    public double temperature;
    public String toString() {
        // Only what was actually measured. Printing "SpO2 0%, 65 bpm, 0/0" reads as a
        // measurement of zero rather than as the absence of one, and the whole point of
        // suppressing a saturation we cannot measure is lost if the log still shows a
        // number for it.
        StringBuilder b = new StringBuilder();
        if (heartRate > 0) b.append(heartRate).append(" bpm");
        if (systolic > 0 && diastolic > 0) {
            if (b.length() > 0) b.append(", ");
            b.append(systolic).append('/').append(diastolic);
        }
        if (oxygen > 0) {
            if (b.length() > 0) b.append(", ");
            b.append("SpO2 ").append(oxygen).append('%');
        }
        if (temperature > 0) {
            if (b.length() > 0) b.append(", ");
            b.append("wrist ").append(temperature).append('C');
        }
        return b.length() > 0 ? b.toString() : "nothing measured";
    }
}
