package org.watchlauncher;

import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read the heart rate sensor from the kernel driver, underneath the sensor HAL.
 *
 * The HAL on this watch does not work. During a measurement the framework shows the sensor
 * activated and connected, and then sits at "First flush pending" for the whole window without
 * delivering a single sample:
 *
 *     gh30x_sensor  handle=0x00000008, active-count=1, connections=1
 *     gh30x_sensor | status: First flush pending | pending flush events 0
 *     last=< 60.0,120.0, 79.0>        unchanged across 29 s and all ten samples taken
 *
 * That stale triple is why readings were identical for hours, and why com.ic.work reports
 * high/low/heart/oxygen all zero: it is handed nothing, so it has nothing to report. Neither
 * the wear check nor the work queue was ever the cause. Both were patched
 * (tools/patch_ic_work.py), and with isWear forced true on a worn watch the zeros survived.
 *
 * The driver underneath is fine. Reading /dev/input/event1 across the same measurement gives a
 * value every second, worn:
 *
 *     REL_RX  0x395c -> 57 bpm,  92 %     sensor settling
 *     REL_RX  0x3916 -> 57 bpm,  22 %
 *     REL_RX  0x3a50 -> 58 bpm,  80 %
 *     REL_RX  0x3b64 -> 59 bpm, 100 %     converged, and steady to the end
 *
 * so the reading is two bytes packed into one REL_RX: heart rate high, SpO2 low. REL_RZ is a
 * once-a-second progress tick (0x0101, 0x0201 ... 0x1d01) and carries no measurement.
 *
 * Blood pressure is not in this stream. The HAL's three-value event has one, so the vendor's
 * native library computes it somewhere we cannot see; it still comes from the service when the
 * service manages to produce it.
 *
 * This does not start the sensor, and nothing in userspace can: goodix_health exposes no sysfs
 * control, there is no character device, and the i2c protocol is the vendor's. com.ic.work is
 * still asked for a measurement in the usual way. Only the answer comes from here, instead of
 * from the HAL that loses it.
 */
final class SensorInput {

    private static final String TAG = "SensorInput";

    /** The gh30x_sensor input device, world readable (crwxrwxrwx). */
    private static final String NODE = "/dev/input/event1";
    private static final String NAME_PATH = "/sys/class/input/event1/device/name";
    private static final String EXPECT_NAME = "gh30x_sensor";

    /** struct input_event on a 32 bit kernel: two longs of timeval, then type, code, value. */
    private static final int EVENT_BYTES = 16;

    private static final int EV_REL = 0x02;

    /** Heart rate in the high byte, SpO2 in the low. */
    private static final int REL_RX = 0x03;

    /**
     * Blood pressure, on the same guess: systolic high, diastolic low.
     *
     * Declared by the driver and not once emitted. capabilities/rel is 0x38 - bits 3, 4 and 5,
     * so RX, RY and RZ - but a full 31 s window produced only RX and RZ. The HAL's three-value
     * event carries a pressure (its stale triple is 60, 120, 79, and 120/79 packs to 0x784f),
     * so the pair exists somewhere; whether the driver ever puts it on this axis, or the
     * vendor's native library computes it above the driver from the waveform, is not something
     * the capture can tell us.
     *
     * Read anyway. It costs one branch, the bounds below refuse anything that is not a
     * plausible pair, and the raw value is logged so that if one ever does arrive we learn the
     * encoding from it rather than from this guess.
     */
    private static final int REL_RY = 0x04;

    /**
     * Ignore the first samples of a measurement.
     *
     * The capture above shows why: 92 %, then 22 %, then 24 % before it settles, and an SpO2 of
     * 22 is not a reading, it is the front of the convergence. Heart rate looks steady from the
     * first sample, but there is no reason to trust one half of a packet and not the other.
     */
    private static final int SETTLE_SAMPLES = 4;

    /** Plausibility bounds. Outside these the sensor is talking to itself. */
    private static final int HR_MIN = 30, HR_MAX = 220;
    private static final int SPO2_MIN = 70, SPO2_MAX = 100;
    private static final int SYS_MIN = 70, SYS_MAX = 260;
    private static final int DIA_MIN = 40, DIA_MAX = 200;

    private SensorInput() { }

    static final class Sample {
        final int heartRate;
        final int oxygen;
        /** Both zero unless the driver emitted a pressure on REL_RY, which so far it has not. */
        final int systolic;
        final int diastolic;

        Sample(int heartRate, int oxygen, int systolic, int diastolic) {
            this.heartRate = heartRate;
            this.oxygen = oxygen;
            this.systolic = systolic;
            this.diastolic = diastolic;
        }

        public String toString() {
            return heartRate + " bpm, SpO2 " + oxygen + "%"
                    + (systolic > 0 ? ", " + systolic + "/" + diastolic : "");
        }
    }

    /** True if the node is there and is the sensor we think it is. */
    static boolean available() {
        java.io.File f = new java.io.File(NODE);
        if (!f.exists() || !f.canRead()) return false;
        String name = name();
        // sysfs may be unreadable while the node is not; that alone is no reason to refuse.
        return name == null || EXPECT_NAME.equals(name);
    }

    private static String name() {
        InputStream in = null;
        try {
            in = new FileInputStream(NAME_PATH);
            byte[] b = new byte[64];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n).trim() : null;
        } catch (IOException e) {
            return null;
        } finally {
            close(in);
        }
    }

    /**
     * Collect from the driver until {@link Reader#finish} is called.
     *
     * A thread, because the read blocks until the driver writes - which between measurements is
     * for ever. {@link Reader#finish} closes the node underneath it to break it out rather than
     * waiting on a flag the blocked read would never look at.
     *
     * Returns null if the node is not usable, which leaves the caller with the vendor service's
     * answer and no worse off than before.
     */
    static Reader start() {
        if (!available()) {
            Log.i(TAG, NODE + " is not readable; leaving the reading to the service");
            return null;
        }
        try {
            return new Reader();
        } catch (IOException e) {
            Log.w(TAG, "could not open " + NODE, e);
            return null;
        }
    }

    static final class Reader implements Runnable {
        private final FileInputStream in;
        private final Thread thread;
        private final List<Integer> hr = new ArrayList<Integer>();
        private final List<Integer> spo2 = new ArrayList<Integer>();
        private volatile int seen;
        private volatile boolean closing;
        /** Last plausible pressure pair off REL_RY; guarded by the same lock as the lists. */
        private int systolic;
        private int diastolic;

        Reader() throws IOException {
            in = new FileInputStream(NODE);
            thread = new Thread(this, "gh30x");
            thread.setDaemon(true);
            thread.start();
        }

        public void run() {
            byte[] buf = new byte[EVENT_BYTES];
            try {
                while (!closing) {
                    int got = 0;
                    while (got < EVENT_BYTES) {
                        int n = in.read(buf, got, EVENT_BYTES - got);
                        if (n < 0) return;
                        got += n;
                    }
                    if (le16(buf, 8) != EV_REL) continue;
                    int code = le16(buf, 10);
                    int value = le32(buf, 12);

                    if (code == REL_RY) {
                        // Never yet seen. Logged raw so the encoding can be read off a real one
                        // rather than trusted from the guess at the constant.
                        Log.i(TAG, "REL_RY (pressure?) raw 0x" + Integer.toHexString(value));
                        int sys = (value >> 8) & 0xFF;
                        int dia = value & 0xFF;
                        if (sys >= SYS_MIN && sys <= SYS_MAX
                                && dia >= DIA_MIN && dia <= DIA_MAX && dia < sys) {
                            synchronized (hr) {
                                systolic = sys;
                                diastolic = dia;
                            }
                        }
                        continue;
                    }
                    if (code != REL_RX) continue;

                    seen++;
                    if (seen <= SETTLE_SAMPLES) continue;

                    int beats = (value >> 8) & 0xFF;
                    int ox = value & 0xFF;
                    synchronized (hr) {
                        if (beats >= HR_MIN && beats <= HR_MAX) hr.add(Integer.valueOf(beats));
                        if (ox >= SPO2_MIN && ox <= SPO2_MAX) spo2.add(Integer.valueOf(ox));
                    }
                }
            } catch (IOException e) {
                // Expected: finish() closes the node to interrupt this read.
                if (!closing) Log.w(TAG, "reading " + NODE + " stopped", e);
            }
        }

        /**
         * Stop reading and return the reading, taken from the end of the window.
         *
         * Not the median of everything, which is what the first version did and what got a
         * genuine 100 % reported as 82 %. SpO2 does not hold still and average out - it climbs
         * and then plateaus:
         *
         *     22, 24, 80, 81, 81, 81, 82, 82, 100, 100, 100, 100
         *
         * so the median of the whole window is a point on the ramp, and the ramp is the sensor
         * still working it out rather than a lower reading. The plateau is the answer. Heart
         * rate is steady from the first sample and does not care either way, but it comes in
         * the same packet, so both are taken the same way.
         *
         * The median of the last third rather than the final sample: the tail is where the
         * value has settled, and a median over it still absorbs one bad packet arriving as the
         * sensor powers down, which the final sample on its own would not.
         */
        Sample finish() {
            closing = true;
            close(in);
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (hr) {
                if (hr.isEmpty() && spo2.isEmpty() && systolic == 0) {
                    Log.w(TAG, "the driver produced no usable sample (" + seen + " raw)");
                    return null;
                }
                Sample s = new Sample(medianOfTail(hr), medianOfTail(spo2),
                        systolic, diastolic);
                Log.i(TAG, "from the driver: " + s + " (" + seen + " samples, "
                        + hr.size() + " usable)");
                return s;
            }
        }
    }

    /**
     * The median of the last third of the samples, in arrival order.
     *
     * At least three of them where there are that many, so the median is over a real spread
     * and not one packet wearing a median's clothes. Sorting a copy, because the caller's list
     * is in arrival order and the last third only means anything while it stays that way.
     */
    private static int medianOfTail(List<Integer> v) {
        if (v.isEmpty()) return 0;
        int take = Math.max(3, v.size() / 3);
        if (take > v.size()) take = v.size();
        List<Integer> tail = new ArrayList<Integer>(v.subList(v.size() - take, v.size()));
        Collections.sort(tail);
        return tail.get(tail.size() / 2).intValue();
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static void close(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) { }
    }
}
