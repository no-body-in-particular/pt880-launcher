package org.watchlauncher;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import com.ic.jni.ICJniUtils;

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

    /** Samples arrive once a second, so this much silence means the measurement has ended. */
    private static final long QUIET_MS = 5000;

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

    /**
     * The current pressure pair from the vendor's library, or null.
     *
     * Read only, and deliberately: no enablePPG here. Driving the chip ourselves was tried and
     * gives heart rate alone - enablePPG() starts it in heart rate mode, and the library said
     * so on every sample of a full window, "event ppg 59 , spo2 0 , weared 1". SpO2 mode is
     * started by the HAL ("command, GH_30X gh30x_Spo2Start" lives in sensors.sl8521e.so), so
     * the service still starts the measurement and this only reads out of the chip while it
     * runs. Starting a second one underneath it would be two callers on one i2c device.
     *
     * The pressures are the one thing that has to come from here. They are not on the input
     * device - the measurement that produced 123/81 emitted eleven REL_RX and no REL_RY at all
     * - so they are derived above the driver, and this library is where.
     */
    static int[] pressure() {
        if (!LIB_OK) return null;
        try {
            int h = ICJniUtils.getHighBloodPressure();
            int l = ICJniUtils.getLowBloodPressure();
            if (h >= SYS_MIN && h <= SYS_MAX && l >= DIA_MIN && l <= DIA_MAX && l < h) {
                return new int[] { h, l };
            }
        } catch (Throwable t) {
            Log.w(TAG, "the vendor library would not give a pressure", t);
        }
        return null;
    }

    /**
     * A body temperature, or 0 if the thermometer will not give one.
     *
     * The library still reads the thermopile - that is the sensor, and GXTS02S in the platform
     * sensor list is a mirror sitting at last=&lt;0.0,0.0,0.0&gt; - but the conversion from wrist
     * to body is {@link BodyTemp} now rather than a JNI call into the same blob. See that class
     * for where its constants came from and how they were checked.
     */
    static float bodyTemperature() {
        if (!LIB_OK) return 0f;
        try {
            if (ICJniUtils.isTemperatureDevAvailable() <= 0) return 0f;
            ICJniUtils.enableTemperature();
            try {
                double wrist = ICJniUtils.getTemperature();
                if (wrist <= 0) return 0f;
                double body = BodyTemp.fromWrist(wrist);
                Log.i(TAG, String.format("wrist %.2f C -> body %.2f C", wrist, body));
                return (float) body;
            } finally {
                ICJniUtils.disableTemperature();
            }
        } catch (Throwable t) {
            Log.w(TAG, "the vendor library would not give a temperature", t);
            return 0f;
        }
    }

    /**
     * What counts as a body temperature at all.
     *
     * Was duplicated in TrackerSources, which is where it was worked out: a wrist sits a few
     * degrees above the room, so 21 passed a 20-to-45 test and was filed as a body temperature.
     * Nothing in that band below about 34 is a person - it is the thermometer reporting the
     * room, or a watch on a table - and reporting it as body heat is worse than reporting
     * nothing. One copy, next to the conversion it qualifies.
     */
    static final float BODY_MIN = 34f, BODY_MAX = 43f;

    /**
     * A wrist reading converted to a body temperature, or 0 if it cannot be.
     *
     * For readings that arrive from somewhere other than this class. vitalsd measures the same
     * thermopile and says so on every line it sends - "it is a wrist and not a body, so
     * converting it is the launcher's business, not this daemon's" - and the launcher was not
     * doing it. Every temperature from a vitals cycle went to the server as the wrist reading
     * it was: 31 to 35 where the converted path was reporting 36 and a half, on the same watch
     * on the same afternoon, which reads as a person getting steadily colder all day.
     *
     * No library involved, which matters here rather than being tidiness: this is the path
     * every vitals cycle takes, and it used to return nothing at all on a watch where
     * libICJniUtils.so had not loaded.
     */
    static float bodyFromWrist(double wristC) {
        if (wristC <= 0) return 0f;
        return (float) BodyTemp.fromWrist(wristC);
    }

    /** Whether libICJniUtils.so loaded. Checked once; a missing library is not an error here. */
    private static final boolean LIB_OK = loadLib();

    private static boolean loadLib() {
        try {
            System.loadLibrary("ICJniUtils");
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "libICJniUtils.so did not load (" + t + "); using the input device only");
            return false;
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
    static Reader start(Context ctx) {
        return start(ctx, true);
    }

    /**
     * @param viaHal whether to switch the sensor on through the sensor framework.
     *
     * False when {@link Gh30x} has started the chip directly, which is the point of that shim:
     * registering a listener here would put the HAL back in a path that exists to avoid it, and
     * the HAL is the piece that wedges. The reader still watches the same input device either
     * way - only who turned the sensor on differs.
     */
    static Reader start(Context ctx, boolean viaHal) {
        if (!available()) {
            Log.i(TAG, NODE + " is not readable; leaving the reading to the service");
            return null;
        }
        try {
            return new Reader(ctx, viaHal);
        } catch (IOException e) {
            Log.w(TAG, "could not open " + NODE, e);
            return null;
        }
    }

    /**
     * Switch the sensor on the way com.ic.work does, and get SpO2 mode with it.
     *
     * The HAL is only half broken. It never delivers an event - a measurement sits at "First
     * flush pending" for its whole window and last= never moves - but activating a sensor
     * through it does work, and that is what puts the chip in the right mode: "command, GH_30X
     * gh30x_Spo2Start" is a string in sensors.sl8521e.so, not in libICJniUtils. Driving the
     * chip ourselves with the library's enablePPG() gets heart rate mode and nothing else, and
     * the library said so on every sample of a full window: "event ppg 59 , spo2 0 , weared 1".
     *
     * So register a listener that is never expected to hear anything. The activation is the
     * whole point of it; the samples come off the input device, which is not on the path that
     * loses them. com.ic.work is not needed for this - the vendor app registers the same way,
     * a plain SensorEventListener rather than a TriggerEventListener, despite dumpsys calling
     * the sensor on-demand.
     *
     * Found by name rather than by type constant: it is a vendor sensor with a vendor type
     * number, and the name is the thing that is actually documented anywhere - it is what
     * /sys/class/input/event1/device/name says too.
     */
    private static final SensorEventListener DEAF = new SensorEventListener() {
        public void onSensorChanged(SensorEvent e) { }
        public void onAccuracyChanged(Sensor s, int a) { }
    };

    private static Sensor find(SensorManager sm) {
        for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
            if (EXPECT_NAME.equals(s.getName())) return s;
        }
        return null;
    }

    /**
     * The one thread that reads the node, for the life of the process.
     *
     * There used to be one per measurement, and they leaked: finish() closed the node to break
     * the thread out of read(), and on Linux that does not wake a thread already inside read()
     * on a character device. A thread dump caught two at once, both parked in libc read+8, each
     * still holding the node open. Between measurements the driver is silent for minutes, so
     * that is where they stayed.
     *
     * Polling available() instead was worse, and briefly shipped: available() is FIONREAD,
     * evdev does not implement it, and every reader died on its first call with "ioctl failed:
     * EINVAL" - which showed up as every measurement seeing 0 raw samples.
     *
     * So the blocking read was right and the thread per measurement was wrong. One pump, never
     * stopped, parked in read() when there is nothing to read - which costs nothing - and a
     * measurement is a window over what it saw rather than a thread of its own.
     */
    private static Thread pump;

    /** {elapsedRealtime, heart rate, SpO2} per sample, newest last. Guarded by SAMPLES. */
    private static final List<long[]> SAMPLES = new ArrayList<long[]>();

    /** {elapsedRealtime, systolic, diastolic} off REL_RY. Guarded by SAMPLES, like the rest. */
    private static final List<long[]> PRESSURES = new ArrayList<long[]>();

    /** Several minutes of once-a-second samples; older ones interest nobody. */
    private static final int KEEP = 600;

    private static synchronized void ensurePump() {
        if (pump != null && pump.isAlive()) return;
        pump = new Thread(new Runnable() {
            public void run() {
                byte[] buf = new byte[EVENT_BYTES];
                FileInputStream in = null;
                try {
                    in = new FileInputStream(NODE);
                    while (true) {
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
                            // It emits after all. Nothing produced one until the measurement ran
                            // long enough - the vendor's own window stops at ten readings and
                            // this axis had not appeared once in any of them. Starting the chip
                            // ourselves runs it far longer, and the pair turns up:
                            //
                            //     0x5f00 -> 95 / 0     while it is still working it out
                            //     0x5f42 -> 95 / 66    a pair
                            //
                            // Same packing as REL_RX: high byte then low. Kept rather than only
                            // logged - it was logging-only while nothing ever sent one, and
                            // that oversight quietly discarded the first real ones.
                            long now = android.os.SystemClock.elapsedRealtime();
                            int sys = (value >> 8) & 0xFF, dia = value & 0xFF;
                            if (sys >= SYS_MIN && sys <= SYS_MAX
                                    && dia >= DIA_MIN && dia <= DIA_MAX && dia < sys) {
                                synchronized (SAMPLES) {
                                    PRESSURES.add(new long[] { now, sys, dia });
                                    while (PRESSURES.size() > KEEP) PRESSURES.remove(0);
                                }
                            }
                            continue;
                        }
                        if (code != REL_RX) continue;

                        long now = android.os.SystemClock.elapsedRealtime();
                        synchronized (SAMPLES) {
                            SAMPLES.add(new long[] { now, (value >> 8) & 0xFF, value & 0xFF });
                            while (SAMPLES.size() > KEEP) SAMPLES.remove(0);
                        }
                    }
                } catch (IOException e) {
                    Log.w(TAG, "the reader stopped; readings will be missing until it restarts",
                            e);
                } finally {
                    close(in);
                }
            }
        }, "gh30x");
        pump.setDaemon(true);
        pump.start();
    }

    /** One measurement: the sensor switched on, and a window over what the pump sees meanwhile. */
    static final class Reader {
        private final SensorManager sm;
        private boolean registered;
        private final long from;

        Reader(Context ctx, boolean viaHal) throws IOException {
            ensurePump();
            from = android.os.SystemClock.elapsedRealtime();
            sm = viaHal ? (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE) : null;
            Sensor s = sm == null ? null : find(sm);
            if (s != null) {
                sm.registerListener(DEAF, s, SensorManager.SENSOR_DELAY_NORMAL);
                registered = true;
                Log.i(TAG, "switched on " + s.getName() + " (type " + s.getType() + ")");
            } else if (viaHal) {
                Log.w(TAG, "no " + EXPECT_NAME + " in the sensor list; leaving the start to "
                        + "the service");
            }
            // No message when viaHal is false: the caller has started the chip itself and the
            // framework is deliberately not involved.
        }

        /**
         * A pressure that arrived late, after the caller stopped the chip.
         *
         * The pair comes at the end of a measurement rather than during it: the one capture that
         * produced one had it in the final six seconds, and the service's own short measurements
         * - which reliably yielded a pressure for hours - end after twenty-odd seconds rather
         * than running on. Reading PRESSURES before the stop therefore looks in the wrong place.
         *
         * The pump keeps running regardless, so this is only a matter of asking again a moment
         * later. Cheap, and the alternative is a pressure that was emitted and thrown away.
         */
        int[] latePressure(long waitMs) {
            long until = android.os.SystemClock.elapsedRealtime() + waitMs;
            while (android.os.SystemClock.elapsedRealtime() < until) {
                synchronized (SAMPLES) {
                    for (int i = PRESSURES.size() - 1; i >= 0; i--) {
                        long[] p = PRESSURES.get(i);
                        if (p[0] >= from) return new int[] { (int) p[1], (int) p[2] };
                    }
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return null;
        }

        /** Whether a plausible pressure has turned up on REL_RY since this measurement began. */
        private boolean havePressure() {
            synchronized (SAMPLES) {
                for (int i = PRESSURES.size() - 1; i >= 0; i--) {
                    if (PRESSURES.get(i)[0] >= from) return true;
                }
            }
            return false;
        }

        /** How many samples have arrived since this measurement began. */
        private int since() {
            int n = 0;
            synchronized (SAMPLES) {
                for (int i = SAMPLES.size() - 1; i >= 0 && SAMPLES.get(i)[0] >= from; i--) n++;
            }
            return n;
        }

        /**
         * Let the measurement run its course, then stop and return what it saw.
         *
         * There is no "done" signal to wait for - the chip simply stops producing - so this
         * waits for the samples to stop arriving rather than for a fixed time. A good window is
         * twenty to thirty of them, and once several seconds pass with no new one there is
         * nothing to gain by holding the sensor on.
         */
        Sample collect(long timeoutMs) {
            long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
            int last = -1;
            long changed = android.os.SystemClock.elapsedRealtime();
            try {
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(500);
                    long now = android.os.SystemClock.elapsedRealtime();
                    int n = since();
                    if (n != last) {
                        last = n;
                        changed = now;
                    } else if (n > SETTLE_SAMPLES && now - changed > QUIET_MS
                               && (havePressure() || now >= deadline - 1000)) {
                        // Quiet, and either the pressure has arrived or there is no time left
                        // to wait for it.
                        //
                        // The pair comes late. In the capture that first produced one it
                        // appeared about sixty seconds in, a second before the measurement
                        // ended, and measurements that stop at fifty-eight samples never see
                        // it at all - which is why REL_RY looked like an axis that never fired.
                        // Stopping the moment the samples pause gives up a second before the
                        // one number that needs the longest.
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // While the chip is still running, which is the only time it has one. Reading it
            // after the stop returned the command buffer instead - "driver report: 6 0 0 0 0 0",
            // 6 being the ppgStop opcode just written to it.
            held = Gh30x.read();
            // Once, while the chip is still running and has just finished computing: if the
            // pressure is behind one of the driver's other commands, this is when it is there.
            Gh30x.probeOnce();
            return finish();
        }

        /** The driver's report, taken at the end of the window before anything was stopped. */
        private int[] held;

        /**
         * Switch the sensor off and work out the reading.
         *
         * Taken from the end of the window rather than the middle of it. The median of
         * everything reported a genuine 100 % as 82 %, because SpO2 climbs and then plateaus -
         * 22, 24, 80, 81, 82, 100, 100 - so the median of the whole window sits on the ramp,
         * and the ramp is the sensor still working it out. The median of the last third
         * instead: the plateau is the answer, and a median over it still absorbs one bad packet
         * arriving as the sensor powers down.
         */
        Sample finish() {
            if (registered) {
                try { sm.unregisterListener(DEAF); } catch (Throwable ignored) { }
                registered = false;
            }
            List<Integer> hr = new ArrayList<Integer>();
            List<Integer> spo2 = new ArrayList<Integer>();
            int seen = 0;
            synchronized (SAMPLES) {
                for (long[] s : SAMPLES) {
                    if (s[0] < from) continue;
                    seen++;
                    if (seen <= SETTLE_SAMPLES) continue;
                    int beats = (int) s[1], ox = (int) s[2];
                    if (beats >= HR_MIN && beats <= HR_MAX) hr.add(Integer.valueOf(beats));
                    if (ox >= SPO2_MIN && ox <= SPO2_MAX) spo2.add(Integer.valueOf(ox));
                }
            }
            // The driver's own pressure first, when it sent one. It only does so on a long
            // measurement - see the REL_RY note in the pump - and when it does it is the same
            // stream the pulse came from rather than a separate poll of the algorithm.
            int[] bp = null;
            synchronized (SAMPLES) {
                for (long[] p : PRESSURES) {
                    if (p[0] >= from) bp = new int[] { (int) p[1], (int) p[2] };
                }
            }
            if (bp != null) {
                Log.i(TAG, "pressure from the driver: " + bp[0] + "/" + bp[1]);
            } else {
                // The driver's report next: six words out of _IOR('G', 11, 24), which the
                // vendor's own log line names as worn, ppg, spo2, bph, bpl. This is the only
                // pressure available once the chip is started directly - the library's
                // getHighBloodPressure reads an algorithm that runs during *its* measurement,
                // and going around it means that algorithm never ran. Losing blood pressure
                // was the price of independence until this was wired up.
                int[] rep = held != null ? held : Gh30x.read();
                if (rep != null && rep.length >= 5) {
                    int sys = rep[3], dia = rep[4];
                    if (sys >= SYS_MIN && sys <= SYS_MAX
                            && dia >= DIA_MIN && dia <= DIA_MAX && dia < sys) {
                        bp = new int[] { sys, dia };
                        Log.i(TAG, "pressure from the driver report: " + sys + "/" + dia);
                    } else if (sys != 0 || dia != 0) {
                        Log.i(TAG, "driver report pressure " + sys + "/" + dia
                                + " is outside " + SYS_MIN + "-" + SYS_MAX + " over "
                                + DIA_MIN + "-" + DIA_MAX + "; not reporting it");
                    }
                }
                // And the library last, for the path where it started the measurement itself.
                if (bp == null) bp = pressure();
            }

            // No SpO2 in range at all means the sensor never got a lock, and the heart rate
            // from such a window is not a measurement either. Two of them reported 107 and 86
            // bpm on a sleeping wrist, both with SpO2 0, while every window that did lock sat
            // at 51 to 53. So the whole reading goes, rather than the good-looking half of it.
            if (spo2.isEmpty()) {
                Log.w(TAG, "no lock: " + seen + " samples, no SpO2 in range; discarding the "
                        + "heart rate from this window too");
                return null;
            }
            if (hr.isEmpty() && bp == null) {
                Log.w(TAG, "the driver produced no usable sample (" + seen + " raw)");
                return null;
            }

            // SpO2 from the plateau, wherever in the window it happens to be.
            //
            // It converges in two stages, and the first one looks exactly like an answer:
            //
            //     22, 24, 80, 81, 81, 81, 82, 82, 96, 97, 97
            //
            // Five or six samples sitting at 81-82, then a jump to the high nineties. When the
            // measurement ends inside that false plateau - and the vendor's window is not
            // always long enough to leave it - the tail median faithfully reports 81, which is
            // a reading nobody took. A window that is still rising when it ends has not
            // finished, so the pulse is kept and the percentage is not.
            int ox = plateau(spo2);
            if (ox == 0) {
                Log.i(TAG, "SpO2 never held a value in this window (" + tail(spo2)
                        + "); reporting the pulse only");
            }
            Sample s = new Sample(medianOfTail(hr), ox,
                    bp == null ? 0 : bp[0], bp == null ? 0 : bp[1]);
            Log.i(TAG, "from the driver: " + s + " (" + seen + " samples, "
                    + hr.size() + " usable)");
            return s;
        }
    }

    /**
     * The median of the last third of the samples, in arrival order.
     *
     * At least three where there are that many, so the median is over a real spread rather than
     * one packet wearing a median for a hat.
     */
    /**
     * Has this series arrived somewhere, or is it still on its way?
     *
     * Two conditions, because either alone gets it wrong on this sensor.
     *
     * The last two samples must agree. A first attempt compared the third-from-last against the
     * last, and threw away good readings: the tail 82, 82, 100, 100 has settled at 100, but the
     * jump falls inside the last three, so it looked like a climb. Comparing the final pair
     * says settled, and says not-settled for 81, 81, 81, 82.
     *
     * And the value has to be one this sensor produces when it has actually converged. Stability
     * alone is not enough, because the false plateau is stable too - it sits at 81 or 82 for
     * five or six samples before jumping to the high nineties, so "the last two agree" is as
     * true there as at the end. Every converged reading observed on this watch landed at 96 to
     * 100 and every ramp passed through 80 to 82, so a settled-looking value below the floor is
     * the ramp pretending.
     *
     * The cost is real and worth stating: a genuine desaturation into the eighties would be
     * refused rather than reported. On a watch whose every measurement passes through that same
     * band on its way up, an 82 cannot be told from the artefact, and reporting it as a reading
     * would be wrong far more often than right.
     */
    private static final int SPO2_SETTLED_MIN = 90;

    /** How much the last few samples may disagree and still count as one value. */
    private static final int SPO2_SPREAD = 3;

    /**
     * Either of two ways of having arrived, because a wrist at rest and a wrist driving do not
     * look alike.
     *
     * At rest the signal plateaus exactly - 97, 97 or 100, 100 - and requiring the final pair to
     * match is the cleanest test there is. In a moving car it never matches: the same watch on
     * the same person gave 82, 93, 90, 91 and 93, 93, 96, 94, jittering by a few points from
     * one second to the next. Demanding equality refused all of it, and with the service wedged
     * at the time that left no SpO2 at all.
     *
     * So: the final pair agreeing, or the last three sitting within a few points of each other.
     * The floor applies to both, which is what keeps the false plateau out - 81, 81, 81 is as
     * steady as any real reading and is still the sensor on its way up.
     */
    /** A run has to be at least this long before it counts as somewhere the value settled. */
    private static final int PLATEAU_MIN = 3;

    /**
     * The value this series held, or 0 if it never held one.
     *
     * The longest run of consecutive samples within {@link #SPO2_SPREAD} of each other, taken
     * as its median, provided that median is at or above the floor.
     *
     * Looking at the end of the window was right while the window was the vendor's ten readings
     * and stopped the moment it had them. Running the measurement ourselves makes it far longer
     * - sixty-seven samples against eight - and the end is then the sensor winding down rather
     * than the answer: a real capture read 100, 100, 91, 78 at its tail, having sat at 100 for
     * some time before. Taking the last three of that reported nothing.
     *
     * So the plateau is looked for wherever it falls. Ramp at the start and decay at the end are
     * both short and both moving; the middle is where the value stays put, and the longest place
     * it stays put is the reading.
     */
    private static int plateau(List<Integer> v) {
        int bestStart = -1, bestLen = 0;
        int i = 0;
        while (i < v.size()) {
            int j = i + 1;
            while (j < v.size()
                    && Math.abs(v.get(j).intValue() - v.get(j - 1).intValue()) <= SPO2_SPREAD) {
                j++;
            }
            if (j - i > bestLen) { bestLen = j - i; bestStart = i; }
            i = j;
        }
        if (bestLen < PLATEAU_MIN) return 0;
        List<Integer> run = new ArrayList<Integer>(v.subList(bestStart, bestStart + bestLen));
        Collections.sort(run);
        int med = run.get(run.size() / 2).intValue();
        return med >= SPO2_SETTLED_MIN ? med : 0;
    }

    /** The last few values, for saying in a log line why a reading was refused. */
    private static String tail(List<Integer> v) {
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, v.size() - 4); i < v.size(); i++) {
            if (b.length() > 0) b.append(", ");
            b.append(v.get(i));
        }
        return b.toString();
    }

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
