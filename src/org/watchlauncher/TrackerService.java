package org.watchlauncher;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Holds the connection to the tracker server, in the launcher, instead of the vendor app.
 *
 * <h3>Why the launcher should own it</h3>
 *
 * The vendor's {@code com.enqualcomm.support} is an ordinary app as far as Android is concerned,
 * so anything reclaiming processes takes it like any other, and it is restarted only by
 * make that less likely. The launcher is the home activity and survives. Moving the socket here
 * means the thing that holds the server link is the thing the system is least willing to kill,
 * and the recovery path stops depending on a process that can be taken at any moment.
 *
 * <h3>Two clients cannot share one device id</h3>
 *
 * The server binds a device to whichever connection last identified as it -- its own log says
 * "command ownership taken by connection &lt;id&gt;". If this ran while the vendor app was still
 * connected the two would take ownership from each other in a loop, and commands would land on
 * whichever happened to hold it. That is why this used to be off until {@link #setEnabled} was
 * called.
 *
 * The vendor app is not on this watch any more -- no package, no apk in
 * {@code /system/priv-app}, no data directory -- so there is no second client to fight, and
 * nothing else reports at all. Off by default now means a watch that silently tracks nothing,
 * which is the worse of the two failures by a long way. So it is on unless it has been turned
 * off, and there is no longer a switch on the watch to turn it off with.
 *
 * <h3>Protocol</h3>
 *
 * {@link BeehomeCodec} owns the wire format; this owns the socket, the timing and the replies.
 * The sequence on a new connection is what the watch actually does, taken from server logs
 * rather than from the specification:
 *
 * <ol>
 *   <li>connect;
 *   <li>send {@code AP03} -- the heartbeat doubles as the login, and its device id is how the
 *       server decides which device this socket is. There is no {@code AP00} on the wire;
 *   <li>send {@code APVR} with the build string;
 *   <li>answer whatever arrives, echoing each command's token back under the same opcode.
 * </ol>
 */
public class TrackerService extends Service {

    private static final String TAG = "TrackerService";

    private static final String PREFS = "tracker";
    private static final String KEY_ENABLED = "client_enabled";
    static final String KEY_HOST = "client_host";
    static final String KEY_PORT = "client_port";
    private static final String KEY_CYCLE = "client_cycle_s";
    private static final String KEY_VITALS = "client_vitals_s";
    private static final String KEY_SPO2 = "client_spo2_s";
    private static final String KEY_TEMP = "client_temp_s";
    private static final String KEY_HOURS = "client_hours_format";
    private static final String KEY_PHONE = "client_phone_setting";
    private static final String KEY_MOTION = "client_motion";
    private static final String KEY_WORKMODE = "client_work_mode";
    private static final String KEY_LOCMODE = "client_loc_mode";
    private static final String KEY_MESSAGE = "client_last_message";
    private static final String KEY_LANG = "client_language";
    private static final String KEY_CONTACTS = "client_contacts";
    private static final String KEY_WORN = "client_worn";

    /** How often to decide whether the watch is on a wrist. */
    private static final int WEAR_CHECK_MS = 5 * 60 * 1000;

    /**
     * Movement below this, in m/s^2 of mean absolute deviation, is a watch that is not on
     * anybody. A worn watch never sits this still: a wrist drifts even when its owner is
     * asleep. Measured on a table it reads close to zero.
     */
    private static final float STILL_THRESHOLD = 0.12f;
    private static final String KEY_SOS = "client_sos_numbers";
    private static final String KEY_WHITELIST = "client_whitelist";
    private static final String KEY_WEATHER = "client_weather";
    private static final String KEY_THRESHOLDS = "client_health_thresholds";
    private static final String KEY_BOUND = "client_bound";
    private static final String KEY_QR = "client_qr_url";
    private static final String KEY_VOICE = "client_voice_file";
    private static final String KEY_CAL_BPH = "client_cal_bph";
    private static final String KEY_CAL_BPL = "client_cal_bpl";
    private static final String KEY_CAL_PPG = "client_cal_ppg";
    private static final String KEY_CAL_SPO2 = "client_cal_spo2";
    private static final String KEY_ALLOW_WIPE = "client_allow_factory_reset";

    /**
     * Reading types on the JK frame, as the vendor's own frames number them.
     *
     * 1 is blood pressure as {@code <diastolic>|<systolic>}, 2 heart rate, 3 temperature,
     * 4 blood oxygen. TrackerLog reads these same numbers back out of the frames the firmware
     * itself recorded, and docs/protocol-commands.md in the root repository has them from the
     * server's side. Only the two this client sends are named here.
     */
    private static final int JK_PULSE = 2;
    private static final int JK_TEMPERATURE = 3;
    private static final int JK_OXYGEN = 4;

    /**
     * What to measure, for {@link #measureAsync}.
     *
     * Not JK types, and deliberately not numbered like them. Blood pressure does not travel on
     * a JK frame at all - it goes as APJZ - so there is no wire type to share, and an earlier
     * version that used 4 and 5 here as though there were let 5 reach the wire: an SPO2#
     * request was answered with a heart rate, labelled as a reading type the vendor's own
     * numbering does not define.
     */
    private static final int MEASURE_PULSE = 1;
    private static final int MEASURE_BP = 2;
    private static final int MEASURE_SPO2 = 3;

    /** Matches the cadence the vendor used, and what the server expects to see. */
    private static final int HEARTBEAT_MS = 10 * 60 * 1000;

    /*
     * There is deliberately no session recycle here.
     *
     * The server buffers a connection's log lines and writes them when the connection closes,
     * so a long-lived session looks silent in the log while it is working perfectly. Closing
     * the socket every ten minutes would make the log timely, and it was tried -- but the fix
     * for a display delay is not to keep dropping a healthy connection. Frames are flushed to
     * the socket as they are written, which is the part this end is responsible for.
     */

    /**
     * Backoff between reconnects: quick at first, then out of the way.
     *
     * The last step used to be five minutes, and on a flaky link that is most of the outage.
     * The server's own log counted the cost - "device reconnected after 569 seconds", "after
     * 924", "after 2199" - because each failed retry waited the full cap again, so a WiFi blip
     * lasting seconds cost a quarter of an hour of readings. A minute is long enough to stop
     * hammering a server that is genuinely down and short enough that a link which has come
     * back is noticed while it is still worth noticing.
     */
    private static final int[] BACKOFF_MS = {5000, 15000, 30000, 60000};

    /**
     * A session that lasted this long was working, whatever ended it.
     *
     * Without this the backoff only reset on a clean return, so hours of healthy connection
     * followed by one dropped socket started the next reconnect at the longest wait, as though
     * the server had been refusing all along.
     */
    private static final long SESSION_OK_MS = 30000;

    /** How long to wait for a media packet's BP07 before sending that packet again. Longer than
     *  a tick, so a slow ack is not mistaken for a lost one. */
    private static final long MEDIA_ACK_MS = 45000;

    /** Sends of one packet before the upload is abandoned. */
    private static final int MEDIA_TRIES = 4;

    /** How often the loop wakes to check its timers when the socket is quiet. */
    private static final int TICK_MS = 20000;

    private volatile boolean running;
    private Thread worker;
    private volatile Socket sock;

    /** The live output stream, so sensor work running off the loop can answer without
     *  handing the stream around. Null between sessions. */
    private volatile OutputStream outStream;

    /** One writer at a time. Two threads interleaving inside a frame would corrupt it, and
     *  media packets are long enough that this is not theoretical. */
    private final Object sendLock = new Object();
    private volatile String lastState = "not started";

    /**
     * Open the receiver for a window, so the next position frame has something in it.
     *
     * After the frame rather than before it: waiting for a fix before sending would hold the
     * loop for a minute and delay the heartbeat with it, and on a ten minute cycle a fix taken
     * now is well inside the half hour a frame will still call fresh.
     */
    /**
     * Blood oxygen, over the vendor's own binder, because nothing else on this watch has it.
     *
     * Off the loop thread and never retried on failure: that service has one work queue with no
     * timeout, so a reading that never comes back holds it for ever, and asking again during a
     * stall only queues more behind the stuck item. Spo2 has the full account. The pulse and
     * the pressures do not come from there, so a wedge costs this reading and nothing else.
     */
    /** Consecutive measurements that came back with nothing. */
    private volatile int vitalsMisses;

    /** When a measurement last produced something, which is also proof of a wrist. */
    private volatile long lastVitalsOkAt;

    /** A reading this recent means a wrist, for the wear check. */
    private static final long WORN_BY_PULSE_MS = 10 * 60 * 1000;

    /** Misses in a row before the sensor service is assumed wedged rather than unlucky. */
    private static final int WEDGE_MISSES = 2;

    /**
     * Set while any vendor measurement is in flight, so two cannot overlap.
     *
     * Heart rate and temperature are separate binder calls on this side but the same
     * single-worker queue inside com.ic.work, and the three timers that drive them - vitals,
     * oxygen and temperature - are all seeded from the same instant, so they come around
     * together and fire in one pass of the loop. The log caught them 7 ms apart: temperature
     * admitted, heart rate arriving to find "is running == true".
     *
     * A stock watch survived that because the service's own mutex refused the second request.
     * tools/patch_ic_work.py removes that mutex, because with no timeout behind it one stuck
     * measurement deadlocked the queue for ever - so the second request now overlaps the first
     * rather than being refused, and the completion path ends in "disable ppg", which means
     * whichever finishes first powers the sensor down underneath the other. Taking the
     * vendor's lock away is only defensible if this side holds one, so this side holds one.
     *
     * Claimed from the loop thread, which is the only thread that starts a measurement, so a
     * plain check-and-set is enough; it is cleared from the worker that finishes.
     */
    private volatile boolean measuring;

    /**
     * How many cycles to sit out after a failure.
     *
     * A measurement that finds no wrist holds the sensor for the full timeout and, on this
     * firmware, takes a turn on a work queue that cannot be recovered if it jams. Off the wrist
     * that is every cycle, all night, for nothing. So each miss doubles the wait, to a cap;
     * a reading resets it.
     */
    private int skipCycles() {
        int n = vitalsMisses;
        if (n <= 0) return 0;
        int skip = 1;
        for (int i = 1; i < n && skip < 8; i++) skip *= 2;
        return skip;
    }

    private volatile int vitalsSkipped;

    /** The cycle's own measurement, which the backoff is allowed to skip. */
    private void measureVitalsAsync() {
        measureVitalsAsync(false);
    }

    /**
     * @param asked true when the server sent XL, XY, OX or XZ.
     *
     * A request is answered whatever the backoff has wound itself out to. The backoff is there
     * to stop this taking a turn on the sensor queue every three minutes for a watch on a
     * table; it is not there to ignore somebody pressing a button on the other end, and a
     * command that silently does nothing is worse than one that tries and reports nothing.
     */
    /** When the last measurement was started, for the floor below. */
    private volatile long lastMeasureAt;

    /**
     * The shortest gap between two measurements, from any source.
     *
     * Three things ask for one: the vitals cycle, the wear check when the watch goes on, and
     * the server, which sends XL about as often as the cycle runs. Nothing coordinated them,
     * and the vendor's service has no way to be told to stop, so each request left the optical
     * sensor running - dumpsys showed gh30x_sensor with four connections open at once and the
     * LED never going off, which is a flat battery and a hot wrist rather than more readings.
     *
     * A measurement takes seconds and the values do not move meaningfully inside a minute, so
     * a floor costs nothing and is the only lever available: the queue behind it cannot be
     * cancelled once a request is in.
     */
    private static final long MEASURE_FLOOR_MS = 150 * 1000;

    /**
     * Longer than any measurement can legitimately take, and shorter than a wasted afternoon.
     *
     * A red measurement is about eighty seconds; five minutes is comfortably past that.
     */
    private static final long MEASURE_STUCK_MS = 5 * 60 * 1000;

    /** How often the red pass runs, in cycles. Green carries the rate on every one of them. */
    private static final int RED_EVERY = 4;

    private int redCycle = 0;

    private void measureVitalsAsync(boolean asked) {
        if (measuring) {
            // Unless it has been running so long that it is not running at all.
            //
            // measuring is cleared in a finally, which covers a measurement that throws but not
            // one that hangs - and a thread blocked on a socket never reaches the finally. The
            // flag then stays true for the life of the process and no cycle ever measures again.
            // That is exactly what happened: one stuck thread, three hours of nothing, and a
            // service that looked healthy the whole time because it was still running.
            long busy = SystemClock.elapsedRealtime() - lastMeasureAt;
            if (busy < MEASURE_STUCK_MS) {
                Log.i(TAG, "a measurement is already running; not starting another");
                return;
            }
            Log.w(TAG, "the last measurement has been running " + (busy / 1000)
                    + "s, which it cannot be; treating it as lost and measuring again");
            measuring = false;
        }
        long since = SystemClock.elapsedRealtime() - lastMeasureAt;
        if (lastMeasureAt > 0 && since < MEASURE_FLOOR_MS) {
            Log.i(TAG, "last measurement was " + (since / 1000) + "s ago; too soon for another"
                    + (asked ? " even though the server asked" : ""));
            return;
        }
        if (!asked && vitalsSkipped < skipCycles()) {
            vitalsSkipped++;
            return;
        }
        vitalsSkipped = 0;
        measuring = true;
        lastMeasureAt = SystemClock.elapsedRealtime();
        new Thread(new Runnable() {
            public void run() {
                try {
                    // Our own measurement first. It drives the chip directly and owes nothing
                    // to gh3011_service; checked against the vendor on the same wrist minutes
                    // apart, 47/50/52 against its 49. It returns null rather than guessing when
                    // its windows disagree, which is what a moving wrist looks like - so the
                    // vendor path stays as the fallback, and is still the only source of SpO2
                    // and of a pressure.
                    // Green first, for the rate.
                    //
                    // This asked for red first, because red samples at 100 Hz and green at 25,
                    // and a systolic upstroke is barely four samples at 25 Hz - so red was the
                    // only mode that could resolve a pulse shape. That reasoning is sound about
                    // the pressure and wrong about the rate, and the rate is what this watch is
                    // asked for most.
                    //
                    // Green is what every fitness tracker uses and the physics is not close.
                    // Haemoglobin absorbs green strongly, so the pulsatile signal is large:
                    // measured here green swings 200 to 900 counts where red manages 1 to 80.
                    // Green also penetrates only as far as the surface capillaries, while red
                    // and infrared reach deeper tissue - which is what makes them useful for a
                    // saturation and exactly what makes them worse when the arm is moving,
                    // because deep tissue moves and shallow capillaries move less.
                    //
                    // A rate needs 25 Hz and no more: 200 bpm is 3.3 Hz, and the sampling
                    // theorem asks for a fraction of what green already gives. Only the pulse
                    // shape behind the pressure needs 100, so only that asks for red.
                    // Ask the thermometer before lighting anything. Off the wrist every path
                    // below fails anyway - ours on no_agreement, the vendor's on its own wear
                    // detector - but they take a minute or more between them to get there with
                    // the LEDs lit the whole time. Only a definite no skips the cycle; -1 means
                    // the thermometer would not say, and then it is better to measure.
                    if (OwnVitals.worn(TrackerService.this) == 0) {
                        Log.i(TAG, "not on a wrist; skipping this cycle without measuring");
                        return;
                    }

                    VendorVitals.Reading r = OwnVitals.measure(TrackerService.this, false);

                    // Red only every fourth cycle, and only for what green cannot give.
                    //
                    // The condition here was "run red if green produced no pressure", which
                    // reads sensibly and is useless: green samples at 25 Hz and can never
                    // produce a pressure, so red ran every cycle exactly as before. Putting
                    // green first changed which rate was published and nothing at all about how
                    // long the red LED was lit.
                    //
                    // It is the expensive pass by a distance - a red request is two passes,
                    // twenty-five seconds balanced for the ratio and forty-five for the shape,
                    // against green's thirty - so running it a quarter as often is most of the
                    // sensor time back. A pressure every fortieth minute is enough for something
                    // that moves as slowly as blood pressure does, and the rate, which is what
                    // this watch is asked for, still comes every cycle from green.
                    boolean wantShape = (redCycle++ % RED_EVERY) == 0;
                    if (wantShape || r == null) {
                        VendorVitals.Reading red = OwnVitals.measure(TrackerService.this, true);
                        if (red != null) {
                            // Green's rate wins where both found one: red ran for the shape.
                            if (r != null && r.heartRate > 0) red.heartRate = r.heartRate;
                            r = red;
                        }
                    }
                    if (r == null) {
                        r = VendorVitals.measure(TrackerService.this, VITALS_TIMEOUT_MS);
                    }
                    if (r == null) {
                        vitalsMisses++;
                        // Nothing from the service. The platform sensor is not a fallback: it
                        // mirrors this service's last result, so it would answer with the same
                        // stale triple that made every reading identical for hours.
                        Log.w(TAG, "no vitals measurement (" + vitalsMisses + " in a row); "
                                + "sending nothing rather than the cached value gh30x would "
                                + "hand back, and sitting out " + skipCycles() + " cycles");
                        if (vitalsMisses >= WEDGE_MISSES) recoverSensorService();
                        return;
                    }
                    vitalsMisses = 0;
                    lastVitalsOkAt = System.currentTimeMillis();

                    // A reading with a pulse but neither a pressure nor an SpO2 is the wedge:
                    // the driver answered and the service did not. See serviceSilent.
                    //
                    // Only the vendor's silence means that. Ours looks identical and is normal:
                    // we report a pressure only when the pulse shape supports one, and we report
                    // no saturation at all while channel 1 carries two counts of signal. Left
                    // unqualified this fired every third measurement and force-stopped
                    // com.ic.work each time, which is a running system being restarted for
                    // behaving exactly as designed.
                    if (!r.fromOwn && r.systolic <= 0 && r.oxygen <= 0 && r.heartRate > 0) {
                        if (++serviceSilent >= SILENT_WEDGE) {
                            Log.w(TAG, "the service has given neither a pressure nor an SpO2 for "
                                    + serviceSilent + " measurements while the driver kept "
                                    + "answering; that is the wedge");
                            recoverSensorService();
                        }
                    } else {
                        serviceSilent = 0;
                    }

                    String when = TrackerSources.stamp();
                    if (r.heartRate > 0) {
                        int bpm = calibratedPulse(r.heartRate);
                        sendAsync(BeehomeCodec.health(when, JK_PULSE, bpm));
                        TrackerLog.recordPulse(TrackerService.this, bpm,
                                System.currentTimeMillis());
                    }
                    if (r.systolic > 0 && r.diastolic > 0) {
                        int sys = clamp(r.systolic + prefs(TrackerService.this)
                                .getInt(KEY_CAL_BPH, 0), 60, 260);
                        int dia = clamp(r.diastolic + prefs(TrackerService.this)
                                .getInt(KEY_CAL_BPL, 0), 30, 200);
                        if (dia < sys) sendAsync(BeehomeCodec.bloodPressure(when, sys, dia));
                    }
                    if (r.oxygen > 0) {
                        sendAsync(BeehomeCodec.health(when, JK_OXYGEN, r.oxygen));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "vitals measurement failed", t);
                } finally {
                    measuring = false;
                }
            }
        }, "vitals").start();
    }

    /**
     * How long to wait for a measurement before deciding it is not coming.
     *
     * Has to be longer than the measurement, and that is not a fixed number: the service stops
     * after a set count of readings, and tools/patch_ic_work.py can raise it. At the stock ten
     * a measurement runs 21 to 31 s and 45 s was ample. Raised to twenty, it runs closer to 40,
     * and 45 stopped being ample - which cost blood pressure twice over.
     *
     * The pressures arrive in the callback that ends the measurement. Timing out before it does
     * not mean waiting a bit less; it means taking the intermediate callbacks instead, whose
     * pressures are whatever the algorithm had reached so far. That showed up first as noise -
     * a systolic band that had sat at 118 to 123 spreading to 103 to 127 on a sleeping wrist -
     * and then as nothing at all, once measurements ran past the timeout entirely.
     *
     * So this has to have room for the longest measurement the odex allows, plus the several
     * seconds the sensor spends finding a pulse before the first reading counts.
     */
    private static final int VITALS_TIMEOUT_MS = 110 * 1000;

    /** How often oxygen is measured unasked. Slower than the pulse deliberately -- every
     *  reading is a turn on a queue that cannot be recovered if it jams. */
    private int oxygenSeconds() {
        return prefs(this).getInt(KEY_SPO2, 900);
    }

    /**
     * Restart com.ic.work, which is the only way its work queue is ever cleared.
     *
     * It keeps one queue for both sensors with no timeout anywhere, so a measurement whose
     * callback never arrives holds it for ever: every later request hangs, the optical sensor
     * stays powered, and nothing comes back. protocol/README.md section 10 has the derivation,
     * and its own conclusion is that it stays stuck "until the process restarts".
     *
     * So restart it. Done by hand once to confirm - force-stop, and the next measurement came
     * back immediately - and there is no reason a watch should need a person for that. It is a
     * system app other things bind on demand, so it returns when it is next wanted.
     *
     * Only after two misses in a row, so one wrist that would not give up a reading does not
     * cost a process restart.
     */
    /**
     * Consecutive measurements where the driver answered and the service did not.
     *
     * The wedge this counts does not look like a failure from here, which is why it went
     * unnoticed for so long. The HAL stops delivering, so the service has nothing to report and
     * contributes neither a pressure nor an SpO2 - but the driver keeps producing a pulse, so a
     * reading still comes back and vitalsMisses resets to zero every cycle. On the server it
     * shows as heart rate carrying on while blood pressure and SpO2 stop together, which is the
     * shape to recognise:
     *
     *     blood pressure  last 13:40:38   77|117
     *     SpO2            last 13:40:38   97
     *     heart rate      13:44, 13:57 ... still going
     *
     * The HAL's own last= sticks at the final delivered triple, which is the confirmation.
     */
    private volatile int serviceSilent;

    /** Three of them, about nine minutes. Long enough not to fire on one bad window. */
    private static final int SILENT_WEDGE = 3;

    private void recoverSensorService() {
        Log.w(TAG, "the sensor service looks wedged; restarting com.ic.work");
        // Force-stop leaves the package in the stopped state, where it receives no broadcasts
        // and will not start itself. Starting it explicitly is what actually brings it back;
        // without this the wedge was traded for an absence.
        boolean stopped = shell("am force-stop com.ic.work");
        boolean started = shell("am startservice -n com.ic.work/.SensorDataService");
        if (stopped) {
            vitalsMisses = 0;
            serviceSilent = 0;
            vitalsSkipped = 0;
            lastMeasureAt = 0;
            Log.i(TAG, "com.ic.work restarted" + (started ? "" : " but would not start again")
                    + "; the next cycle will measure");
        } else {
            Log.w(TAG, "could not restart com.ic.work; needs the root helper at "
                    + "/system/xbin/wsu");
        }
    }

    private void acquireFixAsync() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    android.location.Location l =
                            TrackerSources.acquireGps(TrackerService.this, GPS_WINDOW_MS);
                    if (l == null) return;

                    // A fix arrived, and the frame that went out a moment ago said there was
                    // none. Report it now rather than sitting on a real position until the next
                    // cycle: the whole difference between a track and a row of dots is whether
                    // the first fix after a cold start waits ten minutes to be mentioned.
                    String id = deviceId;
                    if (id != null) sendAsync(TrackerSources.positionFrame(TrackerService.this, id));
                } catch (Throwable t) {
                    Log.w(TAG, "fix attempt failed", t);
                }
            }
        }, "gps").start();
    }

    /** How long the receiver is held open for one fix. A cold start with assistance is
     *  seconds; without a sky view no amount of waiting helps, so this gives up rather than
     *  holding the receiver on indoors. */
    private static final int GPS_WINDOW_MS = 90 * 1000;

    /** Set by requestFix() so an out-of-band ask does not wait for the next cycle. */
    private volatile boolean fixNow;

    // ---- media upload, driven by the read loop so acks and packets interleave ----
    /** The whole picture or recording, held until the last packet is acknowledged. */
    private volatile byte[] mediaData;
    private volatile String mediaOp = "42";
    private volatile String mediaTime = "";
    private volatile int mediaTotal;
    private volatile int mediaNext;
    /** True while a packet is out and its BP07 has not come back. The device is expected to
     *  wait: sending ahead makes the server start the image over. */
    private volatile boolean mediaWaiting;
    /** When the outstanding packet went out, for the retransmit below. */
    private volatile long mediaSentAt;
    /** Sends of the current packet, the first included. */
    private volatile int mediaTries;

    /** The running instance, so the SMS plane can poke it. Cleared in onDestroy so a stopped
     *  service is not mistaken for a live one. */
    private static volatile TrackerService live;

    /** BPJK replies seen on the live connection.
     *
     *  The socket's reader belongs to this loop, so anything else that sends a JK reading on it
     *  cannot read its own acknowledgement. Counting them here lets that caller watch the count
     *  move instead, which says the same thing: the server answered. */
    private final java.util.concurrent.atomic.AtomicInteger jkAcks =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Voice messages arrive a packet at a time, so the assembly outlives one frame. */
    private final VoiceAssembler voice = new VoiceAssembler();

    /** The id of the session in progress, for the readings that are sent off the loop thread. */
    private volatile String deviceId;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enabled(this)) {
            // Not an error: this is the normal state until the vendor app is retired.
            Log.i(TAG, "tracker client disabled; not connecting");
            stopSelf();
            return START_NOT_STICKY;
        }
        live = this;
        TrackerSources.watchSignal(this);
        if (worker == null) {
            running = true;
            worker = new Thread(new Runnable() {
                public void run() { loop(); }
            }, "tracker");
            worker.setDaemon(true);
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (live == this) live = null;
        closeQuietly();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ connection

    private void loop() {
        int attempt = 0;
        while (running) {
            long began = SystemClock.elapsedRealtime();
            try {
                connectAndServe();
                attempt = 0;                       // a clean session resets the backoff
            } catch (Throwable t) {
                lastState = "disconnected: " + t;
                // A session that ran for a while was a working one, so the drop is news about
                // the link rather than about the server, and the next attempt should be as
                // eager as the first.
                long ran = SystemClock.elapsedRealtime() - began;
                if (ran > SESSION_OK_MS) {
                    Log.w(TAG, "session of " + (ran / 1000) + "s ended; treating it as healthy "
                            + "and reconnecting straight away", t);
                    attempt = 0;
                } else {
                    Log.w(TAG, "session ended after " + (ran / 1000) + "s", t);
                }
            }
            if (!running) break;
            int wait = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
            attempt++;
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void connectAndServe() throws Exception {
        TrackerConfig cfg = config();
        String id = cfg.imei();
        if (id == null || id.length() < 8) {
            lastState = "no device id";
            throw new IllegalStateException("no device id");
        }

        Socket s = new Socket();
        s.connect(new InetSocketAddress(host(this, cfg), port(this, cfg)), 15000);
        // Short, deliberately. The read below is what paces the whole loop, so the
        // timeout has to be shorter than the soonest timer or a three-minute vitals
        // cycle would not fire until an eleven-minute read returned.
        s.setSoTimeout(TICK_MS);
        sock = s;
        lastState = "connected";

        OutputStream out = s.getOutputStream();
        outStream = out;
        InputStream in = s.getInputStream();

        // Identify first, and in the one shape the server reads an id out of: no comma after
        // the opcode, so the id is field 0. Without this the session is filed against
        // 0000000000000000 and nothing the watch says appears under its own device, though the
        // socket works and every frame is answered.
        deviceId = id;
        send(out, BeehomeCodec.login(id));
        send(out, BeehomeCodec.heartbeat(id, TrackerSources.steps(this),
                TrackerSources.battery(this), cycleSeconds()));
        send(out, BeehomeCodec.version(id, android.os.Build.DISPLAY));

        // First position straight away: a server that has just seen a device reconnect wants to
        // know where it is, and waiting a full cycle to say so is the difference between a gap
        // on the map and a continuous track.
        send(out, TrackerSources.positionFrame(this, id));
        acquireFixAsync();

        // Then whatever was measured while there was nowhere to send it. After the position
        // rather than before: the server has just watched this device reconnect and the first
        // thing it should learn is where the watch is now, not where it was an hour ago.
        final OutputStream spoolOut = out;
        int waiting = Spool.size(this);
        if (waiting > 0) {
            Log.i(TAG, waiting + " frame(s) waiting from while the connection was down");
            Spool.drain(this, new Spool.Sender() {
                public boolean send(String frame) {
                    try {
                        TrackerService.this.send(spoolOut, frame);
                        return true;
                    } catch (Throwable t) {
                        // The socket has gone again. Spool keeps the rest.
                        return false;
                    }
                }
            });
        }

        long nextBeat = SystemClock.elapsedRealtime() + HEARTBEAT_MS;
        long nextFix = SystemClock.elapsedRealtime() + cycleSeconds() * 1000L;
        long nextVitals = SystemClock.elapsedRealtime() + vitalsSeconds() * 1000L;
        long nextOxygen = SystemClock.elapsedRealtime() + oxygenSeconds() * 1000L;
        long nextTemp = SystemClock.elapsedRealtime() + tempSeconds() * 1000L;
        long nextWear = SystemClock.elapsedRealtime() + WEAR_CHECK_MS;
        StringBuilder buf = new StringBuilder();
        byte[] chunk = new byte[2048];

        while (running) {
            long now = SystemClock.elapsedRealtime();
            if (now >= nextBeat) {
                send(out, BeehomeCodec.heartbeat(id, TrackerSources.steps(this),
                        TrackerSources.battery(this), cycleSeconds()));
                nextBeat = now + HEARTBEAT_MS;
            }
            if (mediaData != null) {
                if (!mediaWaiting) {
                    sendMediaPacket(out);
                } else if (SystemClock.elapsedRealtime() - mediaSentAt > MEDIA_ACK_MS) {
                    // The ack never came. Waiting on it forever is the worst outcome available:
                    // mediaData stays set, so every later photo and recording is dropped as
                    // "already running" too, and one lost packet quietly disables the camera and
                    // the microphone until the process restarts.
                    if (mediaTries >= MEDIA_TRIES) {
                        Log.w(TAG, "packet " + mediaNext + " unacknowledged after " + mediaTries
                                + " sends; abandoning the upload");
                        mediaData = null;
                        mediaWaiting = false;
                    } else {
                        Log.i(TAG, "no ack for packet " + mediaNext + "; sending it again");
                        sendMediaPacket(out);
                    }
                }
            }
            if (fixNow) {
                fixNow = false;
                sendKeeping(out, TrackerSources.positionFrame(this, id));
                nextFix = now + cycleSeconds() * 1000L;
            }
            if (now >= nextFix) {
                sendKeeping(out, TrackerSources.positionFrame(this, id));
                nextFix = now + cycleSeconds() * 1000L;
                acquireFixAsync();
            }
            if (now >= nextVitals) {
                measureVitalsAsync();
                nextVitals = now + vitalsSeconds() * 1000L;
            }
            if (now >= nextOxygen) {
                measureVitalsAsync();
                nextOxygen = now + oxygenSeconds() * 1000L;
            }
            if (now >= nextTemp) {
                sendTemperatureAsync();
                nextTemp = now + tempSeconds() * 1000L;
            }
            if (now >= nextWear) {
                checkWornAsync(id);
                nextWear = now + WEAR_CHECK_MS;
            }
            int n;
            try {
                n = in.read(chunk);
            } catch (java.net.SocketTimeoutException te) {
                continue;                          // idle is normal; the beat above drives us
            }
            if (n < 0) throw new java.io.EOFException("server closed");
            buf.append(new String(chunk, 0, n, "UTF-8"));

            String[] parts = BeehomeCodec.split(buf.toString());
            buf.setLength(0);
            buf.append(parts[parts.length - 1]);    // keep the partial tail
            for (int i = 0; i < parts.length - 1; i++) {
                handle(out, id, BeehomeCodec.decode(parts[i]));
            }
        }
    }

    /**
     * Answer one command.
     *
     * Unknown opcodes are acknowledged rather than ignored. The server retries a command it got
     * no answer to, and a silent client turns one unsupported command into a permanent retry
     * loop -- which costs the radio far more than the command would have.
     */
    private void handle(OutputStream out, String id, BeehomeCodec.Frame f) throws Exception {
        if (f == null) return;
        Log.i(TAG, "<- " + f);
        if ("JK".equals(f.op)) jkAcks.incrementAndGet();

        if ("18".equals(f.op)) {                    // reboot
            ackIfCommand(out, f);
            reboot();
            return;
        }
        if ("15".equals(f.op)) {                    // set location interval
            // IWBP15,<id>,<token>,60#  -- the interval is the field after the token.
            applyInterval(KEY_CYCLE, f, 30, 24 * 3600);
            ackIfCommand(out, f);
            return;
        }
        if ("SQ".equals(f.op)) {                    // vitals measurement period
            applyInterval(KEY_VITALS, f, 60, 24 * 3600);
            ackIfCommand(out, f);
            return;
        }
        if ("50".equals(f.op)) {                    // a poll: answer with where we are
            ackIfCommand(out, f);
            send(out, TrackerSources.positionFrame(this, id));
            return;
        }
        if ("XL".equals(f.op)) {
            // HEARTRATE# on the server sends this. It was being answered with a bare ack and
            // nothing else, which is why it is the most frequent downlink in the logs and never
            // produced a reading: the server kept asking.
            ackIfCommand(out, f);
            measureVitalsAsync(true);
            return;
        }
        if ("XY".equals(f.op)) {                    // BLOODPRESSURE#
            ackIfCommand(out, f);
            measureVitalsAsync(true);
            return;
        }
        if ("OX".equals(f.op) || "XZ".equals(f.op)) {   // SPO2# / OXYGEN#
            ackIfCommand(out, f);
            measureVitalsAsync(true);
            return;
        }
        if ("00".equals(f.op)) {
            // SYNCTIME#. IWBP00,<YYYYMMDDHHMMSS>,<tz># -- not BPTM, which the server never
            // sends. The watch clock runs about ten minutes fast, so this is worth honouring.
            ackIfCommand(out, f);
            applyTime(f);
            return;
        }
        if ("16".equals(f.op)) {                    // LOCATE# -- report position now
            ackIfCommand(out, f);
            send(out, TrackerSources.positionFrame(this, id));
            return;
        }
        if ("88".equals(f.op)) {                    // FIND# -- make the watch findable
            ackIfCommand(out, f);
            findMe();
            return;
        }
        if ("46".equals(f.op)) {                    // take a picture now
            // Not acknowledged. The generic reply sends "IWAP46,<token>#", and the server reads
            // AP46 as a location frame -- its log says
            //
            //     Invalid location package recieved. ... got message: IWAP46,080835
            //
            // which is the same shape of mistake as acking BPJK or BP42: a bare token in a
            // frame whose opcode means something with a payload. The picture is the answer to
            // this command, and it goes up as AP42 packets that the server acknowledges one by
            // one, so nothing is waiting on a reply here.
            beginPhoto();
            return;
        }
        if ("JK".equals(f.op) || "T6".equals(f.op) || "BL".equals(f.op)) {
            // Responses to frames this client sent, not commands. The generic reply below
            // would answer "IWAPJK,<token>#", which the server reads as a health reading with
            // a garbage value -- the same shape of mistake as acknowledging BP42.
            return;
        }
        if ("20".equals(f.op)) {
            // LANG=. "<language>,<time zone>", 1 English and 0 Chinese.
            storeSetting(KEY_LANG, f);
            ackIfCommand(out, f);
            return;
        }
        if ("51".equals(f.op) || "52".equals(f.op)) {   // CONTACT= / DELCONTACT=
            storeList(KEY_CONTACTS, f);
            ackIfCommand(out, f);
            return;
        }
        if ("19".equals(f.op)) {
            // SERVER=. Acked but not obeyed: this is the command that hands the watch to
            // another server, arriving over an unauthenticated plaintext link with no sender to
            // check. The SMS path gates the same thing behind an allowlist; there is no
            // equivalent here, so it is logged and left for a human.
            ackIfCommand(out, f);
            Log.w(TAG, "refusing an over-the-wire server change: " + f.fields);
            return;
        }
        if ("SM".equals(f.op)) {
            // A text tunnel. The server writes '#' as '@' inside it, because a real '#' would
            // terminate the packet, so its payload arrives as "@monitor@" rather than
            // "#monitor#". Anything else in this frame is an ordinary pushed message.
            ackIfCommand(out, f);
            for (int i = 0; i < f.fields.size(); i++) {
                String v = f.fields.get(i);
                if (v.indexOf("monitor") >= 0) {
                    captureAudioAsync();
                    return;
                }
            }
            storeMessage(f);
            return;
        }
        if ("84".equals(f.op)) {                    // WHITELIST=
            storeList(KEY_WHITELIST, f);
            ackIfCommand(out, f);
            return;
        }
        if ("86".equals(f.op)) {
            // HEALTHINT=. IWBP86,<imei>,<serial>,<switch>,<value># -- the switch opens (1) or
            // closes (0) health monitoring, the value is the interval. A 0 switch is honoured
            // by parking the cycle at a day rather than by adding a separate on/off flag that
            // could disagree with the interval.
            applyHealthInterval(f);
            ackIfCommand(out, f);
            return;
        }
        if ("U8".equals(f.op)) {
            // The text tunnel, and the other way the server asks for a picture. handleBPU8
            // carries the literal ">*photo@1*<" alongside "received Txt Msg111", so one frame
            // does both jobs depending on its payload -- the same shape as BPSM and @monitor@.
            ackIfCommand(out, f);
            for (int i = 0; i < f.fields.size(); i++) {
                String v = f.fields.get(i).trim();
                // The marker, not the word. handleBPU8 matches the literal ">*photo@1*<", and
                // this frame's other job is carrying arbitrary pushed text - so "photo"
                // anywhere in a field would let someone open the camera by mentioning it in a
                // message. A spurious picture is the worse failure of the two available.
                if (v.indexOf(">*photo") >= 0 || v.equals("photo")) {
                    beginPhoto();
                    return;
                }
            }
            storeMessage(f);
            return;
        }
        if ("TQ".equals(f.op)) {
            // Weather. handleBPTQ formats it as text, id, current, maximum and minimum
            // temperature and broadcasts com.ic.action_weather_recveived.
            //
            // Stored whole rather than split into named fields: the vendor's handler logs the
            // labels but not which comma each sits behind, and no real frame has been captured.
            // A screen can show the line; inventing a field order would make it wrong in a way
            // that reads as right.
            storeJoined(KEY_WEATHER, f);
            ackIfCommand(out, f);
            return;
        }
        if ("89".equals(f.op)) {
            // Heart-rate and blood-pressure alarm thresholds. Kept, not acted on: this client
            // has nowhere to send an alarm - there is no threshold-breach opcode in the set it
            // speaks - so the server, which has the readings anyway, is the right place to
            // decide one was crossed.
            storeJoined(KEY_THRESHOLDS, f);
            ackIfCommand(out, f);
            return;
        }
        if ("X1".equals(f.op) || "JZ".equals(f.op)) {
            // Sensor calibration. handleBPX1 reads BPH, BPL, PPG and Sp02; handleBPJZ reads
            // BPH, BPL, Age and Sex, which is the blood-pressure calibration command.
            //
            // Taken as offsets, which is what the vendor does with them (setBPHAdjust and the
            // rest). Applied when a reading is sent rather than stored into it, so the raw
            // number stays raw and a bad calibration can be undone by sending another.
            storeCalibration(f);
            ackIfCommand(out, f);
            return;
        }
        if ("68".equals(f.op)) {
            // Binding state: "bound successfully" or "not activated or unbound".
            storeJoined(KEY_BOUND, f);
            ackIfCommand(out, f);
            return;
        }
        if ("87".equals(f.op)) {
            // The QR code URL the vendor wrote to persist.sys.qrUriFile -- the pairing link an
            // app scans. Kept as a string; nothing here needs a system property for it.
            storeJoined(KEY_QR, f);
            ackIfCommand(out, f);
            return;
        }
        if ("75".equals(f.op) || "85".equals(f.op) || "S4".equals(f.op)) {
            // The three ways the server sets alarm clocks. AlarmClock reads what it can
            // recognise out of the frame and schedules only what parsed cleanly; see the note
            // there about why it does not assume a field order.
            ackIfCommand(out, f);
            AlarmParse.Result r = AlarmParse.parse(f.fields, f.token());
            for (int i = 0; i < r.unparsed.size(); i++) {
                Log.w(TAG, "BP" + f.op + ": no time understood in \"" + r.unparsed.get(i)
                        + "\"; that alarm is not set");
            }
            if (r.alarms.isEmpty()) {
                Log.w(TAG, "BP" + f.op + ": nothing to set from " + f.fields);
                return;
            }
            AlarmClock.apply(this, r.alarms);
            return;
        }
        if ("28".equals(f.op)) {
            // A voice message pushed from the server, a packet at a time. VoiceAssembler has
            // the frame layout and why this needs no change to the read loop.
            ackIfCommand(out, f);
            receiveVoice(f);
            return;
        }
        if ("TF".equals(f.op) || "PH".equals(f.op)) {   // HOURS= / PHONE=
            storeSetting("TF".equals(f.op) ? KEY_HOURS : KEY_PHONE, f);
            ackIfCommand(out, f);
            return;
        }
        if ("MC".equals(f.op)) {                    // MOTION=
            storeSetting(KEY_MOTION, f);
            ackIfCommand(out, f);
            return;
        }
        if ("33".equals(f.op) || "34".equals(f.op)) {   // MODE= / LOCMODE=
            storeSetting("33".equals(f.op) ? KEY_WORKMODE : KEY_LOCMODE, f);
            ackIfCommand(out, f);
            return;
        }
        if ("01".equals(f.op) || "05".equals(f.op) || "40".equals(f.op)) {
            // MSG=. Text pushed from the server. Kept rather than shown: putting a dialog over
            // whatever screen is open, from an unauthenticated link, is the kind of thing that
            // interrupts navigation at a junction. The launcher can surface it when asked.
            storeMessage(f);
            ackIfCommand(out, f);
            return;
        }
        if ("TE".equals(f.op)) {
            // IWBPTE,<imei>,<serial>,<minutes>#  -- a period, not a switch. Sending a bare 1
            // means "every minute", which is how this watch ended up taking 257 temperature
            // readings in a day. Stored in seconds; clamped like every other server-set
            // interval, because it arrives unauthenticated and a 1 is expensive.
            applyMinutes(KEY_TEMP, f, 1, 24 * 60);
            ackIfCommand(out, f);
            return;
        }
        if ("42".equals(f.op)) {
            // The manual's media acknowledgement. Parsed and dropped, exactly as the vendor
            // does -- and specifically NOT acked. The generic reply below would send
            // "IWAP42,<token>#", which the server reads as an image packet header with a
            // nonsense length, in the middle of an upload. It is a reply, not a command.
            return;
        }
        if ("07".equals(f.op)) {                    // media packet acknowledgement
            if (BeehomeCodec.advancesMedia(f, mediaNext)) {
                mediaNext++;
                mediaWaiting = false;
                mediaTries = 0;                     // a fresh budget for the next packet
                if (mediaNext > mediaTotal) {
                    Log.i(TAG, "media upload complete, " + mediaTotal + " packets");
                    mediaData = null;
                }
            }
            return;                                 // never acked: it is a reply, not a command
        }
        if ("TM".equals(f.op)) {                    // time sync
            ackIfCommand(out, f);
            applyTime(f);
            return;
        }
        if ("31".equals(f.op)) {                    // power off
            ackIfCommand(out, f);
            shell("reboot -p");
            return;
        }
        if ("32".equals(f.op)) {                    // server-initiated dial
            ackIfCommand(out, f);
            dial(numberIn(f));
            return;
        }
        if ("12".equals(f.op) || "14".equals(f.op)) {   // SOS numbers / whitelist
            // Stored, not acted on: the command only says what the list is. Whatever consults
            // it later reads the preference.
            storeList("12".equals(f.op) ? KEY_SOS : KEY_WHITELIST, f);
            ackIfCommand(out, f);
            return;
        }
        if ("17".equals(f.op)) {                    // factory reset
            // Acked but not obeyed unless explicitly allowed. It arrives over an unauthenticated
            // plaintext link with no sender to check, and it is the one command whose cost
            // cannot be undone. The vendor obeyed it unconditionally; that is a decision worth
            // taking again deliberately rather than inheriting.
            ackIfCommand(out, f);
            if (prefs(this).getBoolean(KEY_ALLOW_WIPE, false)) {
                shell("am broadcast -a android.intent.action.MASTER_CLEAR");
            } else {
                Log.w(TAG, "refusing factory reset; set " + KEY_ALLOW_WIPE + " to allow it");
            }
            return;
        }
        // XL, TE and anything else: echo the token so the server can close it out.
        ackIfCommand(out, f);
    }

    /** Same as {@link #applyInterval} but the wire value is in minutes, not seconds. */
    private void applyMinutes(String key, BeehomeCodec.Frame f, int loMin, int hiMin) {
        String tok = f.token();
        for (int i = f.fields.size() - 1; i >= 0; i--) {
            String v = f.fields.get(i);
            if (v.equals(tok) || v.length() == 0) continue;
            try {
                int n = Integer.parseInt(v.trim());
                if (n < loMin || n > hiMin) {
                    Log.w(TAG, "refusing out-of-range period " + n + " min for " + key);
                    return;
                }
                prefs(this).edit().putInt(key, n * 60).commit();
                Log.i(TAG, key + " set to " + n + " min by the server");
                return;
            } catch (NumberFormatException e) {
                // not the field we wanted; keep looking backwards
            }
        }
    }


    /**
     * Take an interval out of a command's trailing numeric field.
     *
     * Clamped, because the interval arrives over an unauthenticated plaintext link and a value
     * of zero or one would turn the watch into a beacon that flattens itself in an afternoon.
     * A refused value is logged rather than silently corrected, so a server setting something
     * impossible finds out from the log rather than from the battery.
     */
    private void applyInterval(String key, BeehomeCodec.Frame f, int lo, int hi) {
        String tok = f.token();
        for (int i = f.fields.size() - 1; i >= 0; i--) {
            String v = f.fields.get(i);
            if (v.equals(tok) || v.length() == 0) continue;
            try {
                int n = Integer.parseInt(v.trim());
                if (n < lo || n > hi) {
                    Log.w(TAG, "refusing out-of-range interval " + n + " for " + key);
                    return;
                }
                prefs(this).edit().putInt(key, n).commit();
                Log.i(TAG, key + " set to " + n + "s by the server");
                return;
            } catch (NumberFormatException e) {
                // not the field we wanted; keep looking backwards
            }
        }
    }

    /**
     * A vitals reading, taken here rather than listened for.
     *
     * While the vendor app was running these arrived as broadcasts and the watchdog only
     * had to notice them. Once it is gone nothing else takes a measurement, so the client has to
     * ask the sensor itself.
     */

    private void send(OutputStream out, String frame) throws Exception {
        synchronized (sendLock) {
            Log.i(TAG, "-> " + frame);
            out.write(frame.getBytes("UTF-8"));
            out.flush();
        }
    }

    /**
     * Send something worth keeping, and keep it if the send fails.
     *
     * For the frames that carry their own timestamp and describe a moment - a position, a
     * reading. The exception is still thrown, because a failed write means the session is over
     * and the loop above has to hear that; the frame is simply saved on the way past.
     *
     * Not for login, heartbeat or acknowledgements. Those describe the connection rather than
     * the wearer, and replaying one into a later session would be describing that session
     * wrongly.
     */
    private void sendKeeping(OutputStream out, String frame) throws Exception {
        try {
            send(out, frame);
        } catch (Exception e) {
            Spool.add(this, frame);
            throw e;
        }
    }

    /**
     * Send from a background task, if the session is still up.
     *
     * Quiet when it is not: a measurement that finishes after a disconnect has nowhere to go,
     * and that is an ordinary outcome rather than an error.
     */
    private void sendAsync(String frame) {
        OutputStream o = outStream;
        if (o == null) {
            // Measured with the socket down. The frame stamps itself, so it is still worth
            // having when the connection comes back - see Spool.
            Spool.add(this, frame);
            return;
        }
        try {
            send(o, frame);
        } catch (Throwable t) {
            Log.w(TAG, "could not send " + frame + "; keeping it for the next connection", t);
            Spool.add(this, frame);
        }
    }

    private void closeQuietly() {
        Socket s = sock;
        sock = null;
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------ inputs

    private TrackerConfig config() {
        // No root shell any more: the settings are this client's own, in its own
        // preferences. The vendor file this used to read is not on the watch.
        TrackerConfig c = new TrackerConfig(this);
        c.load();
        return c;
    }

    private int battery() {
        try {
            Intent i = registerReceiver(null,
                    new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return 0;
            int level = i.getIntExtra("level", -1);
            int scale = i.getIntExtra("scale", 100);
            return (level < 0 || scale <= 0) ? 0 : (level * 100 / scale);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Location cycle, as the server last set it. 600 s is what the vendor shipped. */
    private int cycleSeconds() {
        return prefs(this).getInt(KEY_CYCLE, 600);
    }

    /** Temperature period, as the server last set it with BPTE. Ten minutes by default -- the
     *  vendor was left on sixty seconds by accident and took 257 readings in a day. */
    private int tempSeconds() {
        return prefs(this).getInt(KEY_TEMP, 600);
    }

    /**
     * Read the temperature off the loop thread and report it.
     *
     * Behind the same gate as the vitals, for the reason {@link #measuring} gives: one vendor
     * work queue, and no lock left inside it. Dropped rather than deferred when the sensor is
     * busy - the period is ten minutes and the next round is close enough that carrying a
     * pending flag around would be more machinery than the problem deserves.
     */
    private void sendTemperatureAsync() {
        if (measuring) {
            Log.i(TAG, "a measurement is running; the temperature waits for the next round");
            return;
        }
        measuring = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    float t = TrackerSources.temperature(TrackerService.this);
                    if (t > 0) {
                        sendAsync(BeehomeCodec.health(TrackerSources.stamp(), JK_TEMPERATURE, t));
                    }
                } finally {
                    measuring = false;
                }
            }
        }, "temp").start();
    }

    /** Vitals period. The firmware managed one every three minutes when it was working. */
    private int vitalsSeconds() {
        return prefs(this).getInt(KEY_VITALS, 180);
    }

    /**
     * Restart the watch, and say so either way.
     *
     * This acknowledges the server's command before trying, so a restart that cannot happen is
     * indistinguishable from one that did - the server is told yes and the watch stays up. That
     * is worth a log line rather than silence: the shell falls back through wsu, su and finally
     * a plain sh, and a plain sh opens successfully while being no use for this, so "opened" and
     * "can reboot" are different questions.
     */
    private void reboot() {
        RootShell sh = new RootShell();
        try {
            if (!sh.open()) {
                Log.w(TAG, "restart asked for, but no shell would open");
                return;
            }
            if (!sh.isRoot()) {
                Log.w(TAG, "restart asked for, but the shell is not root (" + sh.identity()
                        + "); " + sh.failure());
                return;
            }
            if (!sh.runQuiet("reboot")) Log.w(TAG, "restart: the reboot command failed");
            else Log.i(TAG, "restart: reboot issued");
        } catch (Throwable t) {
            Log.w(TAG, "reboot failed", t);
        } finally {
            try { sh.close(); } catch (Throwable ignored) { }
        }
    }




    /**
     * Take a reading and send it, off the connection thread.
     *
     * The optical sensor needs several seconds of clean signal. Doing that inline would stop
     * the client answering the server for the whole measurement, and the server polls heart
     * rate often enough that the connection would spend much of its life deaf.
     */

    /**
     * Answer FIND#: make the watch draw attention to itself.
     *
     * Sound and vibration together, and at full volume deliberately. This is the command whose
     * entire purpose is to be noticed from under a cushion, so respecting a quiet volume
     * setting here would defeat it.
     */
    private void findMe() {
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                    getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM);
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, max, 0);
            }
            android.media.Ringtone r = android.media.RingtoneManager.getRingtone(this,
                    android.media.RingtoneManager.getDefaultUri(
                            android.media.RingtoneManager.TYPE_ALARM));
            if (r != null) r.play();

            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(new long[]{0, 400, 200, 400, 200, 400}, -1);
        } catch (Throwable t) {
            Log.w(TAG, "could not answer find-device", t);
        }
    }


    /** Store the last non-token field of a settings command verbatim. */
    private void storeSetting(String key, BeehomeCodec.Frame f) {
        String tok = f.token();
        for (int i = f.fields.size() - 1; i >= 0; i--) {
            String v = f.fields.get(i).trim();
            if (v.length() == 0 || v.equals(tok)) continue;
            prefs(this).edit().putString(key, v).commit();
            Log.i(TAG, key + " = " + v);
            return;
        }
    }

    /**
     * One packet of a pushed voice message, and the file when the last one lands.
     *
     * Saved where the vendor saved them, {@code /Android/VoiceCache}, so anything already
     * looking there still finds them. Stored and announced rather than played: audio from an
     * unauthenticated link should not start playing on a wrist by itself, for the same reason
     * pushed text is kept rather than thrown on screen.
     */
    private void receiveVoice(BeehomeCodec.Frame f) {
        VoiceAssembler.Status s = voice.accept(f.fields);
        if (s.problem != null) Log.w(TAG, s.problem);
        if (s.accepted && !s.complete) {
            Log.i(TAG, "BP28 " + s.name + ": packet " + s.packet + " of " + s.packets);
            return;
        }
        if (!s.complete || s.data == null) return;

        try {
            File dir = new File(android.os.Environment.getExternalStorageDirectory(),
                    "Android/VoiceCache");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Log.w(TAG, "BP28: cannot make " + dir);
                return;
            }
            File outFile = new File(dir, "receive_" + safeName(s.name) + ".amr");
            FileOutputStream os = new FileOutputStream(outFile);
            try {
                os.write(s.data);
            } finally {
                os.close();
            }
            prefs(this).edit().putString(KEY_VOICE, outFile.getAbsolutePath()).commit();
            Log.i(TAG, "BP28 voice message saved: " + outFile + " (" + s.data.length + " bytes)");
            sendBroadcast(new Intent("org.watchlauncher.NEW_VOICE"));
        } catch (Throwable t) {
            Log.w(TAG, "BP28: could not save the voice message", t);
        }
    }

    /** The name comes off the wire, so it does not get to choose a path. */
    private static String safeName(String s) {
        if (s == null || s.length() == 0) return "voice";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 40; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            b.append(ok ? c : '_');
        }
        return b.length() == 0 ? "voice" : b.toString();
    }

    /** Every field that is not addressing, joined and kept verbatim. */
    private void storeJoined(String key, BeehomeCodec.Frame f) {
        StringBuilder b = new StringBuilder();
        String tok = f.token();
        for (int i = 0; i < f.fields.size(); i++) {
            String v = f.fields.get(i).trim();
            if (v.length() == 0 || v.equals(tok)) continue;
            if (b.length() > 0) b.append(',');
            b.append(v);
        }
        if (b.length() == 0) return;
        prefs(this).edit().putString(key, b.toString()).commit();
        Log.i(TAG, key + " = " + b);
    }

    /**
     * Calibration offsets from BPX1 or BPJZ.
     *
     * The numeric fields after the addressing, in the order the vendor's handlers read them:
     * BPH, BPL, then PPG and SpO2 for X1, Age and Sex for JZ. Only the first two are shared,
     * so only the ones present are written.
     *
     * Clamped. These arrive unauthenticated, they are added to a number a person may read as a
     * medical one, and an offset larger than the reading it adjusts is not a calibration.
     */
    private void storeCalibration(BeehomeCodec.Frame f) {
        java.util.List<Integer> n = new java.util.ArrayList<Integer>();
        String tok = f.token();
        for (int i = 0; i < f.fields.size(); i++) {
            String v = f.fields.get(i).trim();
            if (v.equals(tok) || v.length() == 0 || v.length() > 6) continue;
            try {
                n.add(Integer.valueOf(Integer.parseInt(v)));
            } catch (NumberFormatException e) { /* not a number, not an offset */ }
        }
        if (n.isEmpty()) {
            Log.w(TAG, "BP" + f.op + ": no calibration numbers in " + f.fields);
            return;
        }

        SharedPreferences.Editor e = prefs(this).edit();
        String[] keys = "X1".equals(f.op)
                ? new String[]{KEY_CAL_BPH, KEY_CAL_BPL, KEY_CAL_PPG, KEY_CAL_SPO2}
                : new String[]{KEY_CAL_BPH, KEY_CAL_BPL};
        StringBuilder said = new StringBuilder();
        for (int i = 0; i < keys.length && i < n.size(); i++) {
            int v = clamp(n.get(i).intValue(), -CAL_LIMIT, CAL_LIMIT);
            e.putInt(keys[i], v);
            said.append(' ').append(keys[i]).append('=').append(v);
        }
        e.commit();
        Log.i(TAG, "BP" + f.op + " calibration:" + said);
    }

    /** No offset may move a reading further than this. */
    private static final int CAL_LIMIT = 30;

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Blood pressure, from the same reading the pulse came from.
     *
     * This is what the original did and this client was not. gh30x_sensor answers with three
     * values at once -- dumpsys shows "last=<59.0,120.0,79.0>", pulse and the two pressures --
     * and the vendor uploaded all of it together, which is what "server received uploaded heart
     * rate and blood pressure" in its own APHT handler is describing. This client read the same
     * three values, sent the first, threw the other two away, and sent a pressure only when the
     * server explicitly asked with BPXY.
     *
     * So the server's systolic and diastolic series stop dead on the day the vendor app was
     * retired, while the heart rate carries on. Nothing was broken; two thirds of every reading
     * was simply being discarded.
     */
    private void sendPressure(OutputStream out, HeartRate hr) {
        String f = pressureFrame(hr);
        if (f == null) return;
        try {
            send(out, f);
        } catch (Throwable t) {
            Log.w(TAG, "could not send blood pressure", t);
        }
    }

    private void sendPressureAsync(HeartRate hr) {
        String f = pressureFrame(hr);
        if (f != null) sendAsync(f);
    }

    /** Null when the sensor gave no pressure, which is not the same as a pressure of zero. */
    private String pressureFrame(HeartRate hr) {
        int sys = hr.systolic();
        int dia = hr.diastolic();
        if (sys <= 0 || dia <= 0) return null;
        sys = clamp(sys + prefs(this).getInt(KEY_CAL_BPH, 0), 60, 260);
        dia = clamp(dia + prefs(this).getInt(KEY_CAL_BPL, 0), 30, 200);
        if (dia >= sys) {
            Log.w(TAG, "blood pressure " + sys + "/" + dia + " is not a pressure; not sending");
            return null;
        }
        return BeehomeCodec.bloodPressure(TrackerSources.stamp(), sys, dia);
    }

    /** A pulse with the server's PPG offset applied, still inside a believable range. */
    private int calibratedPulse(int bpm) {
        int adj = prefs(this).getInt(KEY_CAL_PPG, 0);
        if (adj == 0) return bpm;
        return clamp(bpm + adj, 25, 250);
    }

    /** Text pushed by the server, kept with the time it arrived. */
    private void storeMessage(BeehomeCodec.Frame f) {
        StringBuilder b = new StringBuilder();
        String tok = f.token();
        for (int i = 0; i < f.fields.size(); i++) {
            String v = f.fields.get(i).trim();
            if (v.length() == 0 || v.equals(tok) || isDigits(v)) continue;
            if (b.length() > 0) b.append(' ');
            b.append(v);
        }
        if (b.length() == 0) return;
        prefs(this).edit().putString(KEY_MESSAGE, TrackerSources.stamp() + "  " + b).commit();
        Log.i(TAG, "message from the server: " + b);
    }

    /**
     * HEALTHINT=: a switch and an interval in one command.
     *
     * Switch 0 parks the cycle at a day rather than setting a separate disable flag. Two pieces
     * of state that can disagree about whether monitoring is on is how a watch ends up measuring
     * when it was told not to.
     */
    private void applyHealthInterval(BeehomeCodec.Frame f) {
        String tok = f.token();
        java.util.List<Integer> nums = new java.util.ArrayList<Integer>();
        for (int i = 0; i < f.fields.size(); i++) {
            String v = f.fields.get(i).trim();
            if (v.length() == 0 || v.equals(tok)) continue;
            try {
                nums.add(Integer.valueOf(Integer.parseInt(v)));
            } catch (NumberFormatException e) {
                // the imei and anything else non-numeric
            }
        }
        if (nums.isEmpty()) return;
        int onOff = nums.get(0).intValue();
        int minutes = nums.size() > 1 ? nums.get(1).intValue() : 0;
        int seconds;
        if (onOff == 0) {
            seconds = 24 * 3600;
            Log.i(TAG, "health monitoring switched off by the server");
        } else if (minutes >= 1 && minutes <= 24 * 60) {
            seconds = minutes * 60;
        } else {
            Log.w(TAG, "refusing health interval " + minutes + " min");
            return;
        }
        prefs(this).edit().putInt(KEY_VITALS, seconds).commit();
        Log.i(TAG, "vitals cycle set to " + seconds + "s by the server");
    }



    /**
     * Acknowledge a frame, but only if it was a command.
     *
     * A command carries the server's correlation token; a reply to something this client sent
     * does not. Answering a reply is not merely redundant, it is a loop: the acknowledgement
     * goes out as "IWAP00,#" or "IWAP01,#", which are not acknowledgements at all but malformed
     * login and position frames, so the server answers them, and the exchange runs several
     * times a second until something stops it. That was observed against the live server before
     * this check existed.
     *
     * The token is therefore the test for "is this addressed to me as an order". It is also why
     * BeehomeCodec.Frame.token() returns null rather than guessing: an invented token here
     * would put the loop straight back.
     */
    private void ackIfCommand(OutputStream out, BeehomeCodec.Frame f) throws Exception {
        if (f == null) return;
        String tok = f.token();
        if (tok == null) {
            Log.i(TAG, "no token on BP" + f.op + "; treating it as a reply, not acknowledging");
            return;
        }
        if (NEVER_ACK.contains(f.op)) {
            Log.i(TAG, "BP" + f.op + " is a reply by definition; not acknowledging");
            return;
        }
        send(out, BeehomeCodec.ack(f.op, tok));
    }

    /**
     * Opcodes whose uplink form is a data frame rather than an acknowledgement.
     *
     * Echoing one of these back sends a malformed login, position, heartbeat, health reading or
     * image packet. The token check above catches them in practice; this catches them even if a
     * future server starts putting a token on one.
     */
    private static final java.util.Set<String> NEVER_ACK =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "00", "01", "02", "03", "07", "10", "42", "JK", "T6", "BL", "VR", "WR",
                    // The server's own log strings give these away: handleBPHP is "server
                    // received APHP heart rate blood pressure blood oxygen" and handleBPTP is
                    // "BPTP server received body temperature data". Both are it confirming an
                    // upload, so echoing one back sends a malformed health frame.
                    "HP", "TP"));


    /** Seconds of audio per remote capture. Each second is 16 kB, and every kilobyte is a
     *  packet that has to be acknowledged before the next one goes, so this is kept short. */
    private static final int AUDIO_SECONDS = 15;

    /**
     * Capture audio and upload it, off the connection thread.
     *
     * Recording blocks for its whole duration, so doing it inline would stop the client
     * answering the server for fifteen seconds. The result goes up the same packet path a
     * picture uses, under AP07 rather than AP42 -- the server tells the two apart by the
     * payload's leading bytes, which for this is "RIFF".
     */
    private void captureAudioAsync() {
        new Thread(new Runnable() {
            public void run() {
                byte[] wav = Recorder.record(AUDIO_SECONDS, Recorder.DEFAULT_GAIN);
                if (wav == null) {
                    Log.w(TAG, "audio requested but the microphone gave nothing");
                    return;
                }
                offerMedia("07", wav);
            }
        }, "audio").start();
    }

    // ------------------------------------------------------------------ wear detection

    /**
     * Decide whether the watch is being worn, and tell the server when that changes.
     *
     * <h3>Why from the sensors and not the strap switch</h3>
     *
     * This unit is the noAnti build: {@code persist.sys.hasAntisensor} is false and the strap
     * contact the vendor used is not armed. The optical sensor and the accelerometer are both
     * present and working, and between them they answer the same question.
     *
     * <h3>Two signals, because either alone is wrong</h3>
     *
     * A pulse is proof of a wrist, but its absence is not proof of no wrist: the optical sensor
     * fails on tattoos, in cold weather and whenever the strap is loose, and a client that cried
     * removal every time it missed a reading would be ignored within a day.
     *
     * Stillness is the opposite. A watch on a table is unmistakably still, but so is a sleeping
     * arm for minutes at a time.
     *
     * So removal needs both to agree - no pulse and no movement - and either one alone puts it
     * back on the wrist. That biases hard towards "worn", which is the right way round: a missed
     * removal is a gap in a log, a false one is an alarm that wakes somebody.
     *
     * The state is kept in a preference rather than a field, because the launcher is restarted
     * far more often than a watch is taken off, and an in-memory flag would report "put on"
     * every time the process came back.
     */
    /** Wear is checked this often, which is also how soon a measurement follows putting it on. */
    private void checkWornAsync(final String id) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    float motion = TrackerSources.motionEnergy(TrackerService.this, 4000);
                    boolean moving = motion > STILL_THRESHOLD;

                    boolean pulse = false;
                    if (!moving) {
                        // Only worth lighting the LED when stillness has already made removal
                        // plausible. Running the optical sensor every five minutes to confirm
                        // something the accelerometer already settled would cost battery for
                        // nothing.
                        // From the measurement this client already takes, rather than a
                        // second grab at the optical sensor.
                        //
                        // The probe that was here read gh30x directly, and gh30x is a mirror of
                        // the vendor service's last result - so with its cached value correctly
                        // rejected as stale it always came back empty, and a still wrist was
                        // reported as a removal. Worse, registering on that sensor every five
                        // minutes collides with the measurement the vendor service is running,
                        // which is a good way to wedge a queue that has no timeout.
                        //
                        // A reading that arrived recently is proof of a wrist, and costs
                        // nothing to consult.
                        long sincePulse = System.currentTimeMillis() - lastVitalsOkAt;
                        pulse = lastVitalsOkAt > 0 && sincePulse < WORN_BY_PULSE_MS;
                    }

                    boolean worn = moving || pulse;
                    boolean was = prefs(TrackerService.this).getBoolean(KEY_WORN, true);

                    // Put back on: measure now, and forget the backoff.
                    //
                    // The backoff exists because a measurement off the wrist finds nothing,
                    // holds the sensor for the full timeout and takes a turn on a queue this
                    // firmware cannot recover if it jams - so misses double the wait, up to
                    // eight cycles. That is right while it sits on a table and wrong the
                    // moment it goes on a wrist: twenty-four minutes of no readings after
                    // putting a watch on is exactly when somebody is looking for one.
                    if (worn && !was) {
                        vitalsMisses = 0;
                        vitalsSkipped = 0;
                        Log.i(TAG, "worn again; measuring now rather than sitting out the "
                                + "backoff");
                        measureVitalsAsync();
                    }
                    Log.i(TAG, "wear check: motion=" + motion + " moving=" + moving
                            + " pulse=" + pulse + " -> " + (worn ? "worn" : "removed"));
                    if (worn == was) return;

                    prefs(TrackerService.this).edit().putBoolean(KEY_WORN, worn).commit();
                    sendAsync(BeehomeCodec.frame("WR", id, worn ? "1" : "0"));
                    Log.i(TAG, worn ? "reported: watch put on" : "reported: watch removed");
                } catch (Throwable t) {
                    Log.w(TAG, "wear check failed", t);
                }
            }
        }, "wear").start();
    }

    // ------------------------------------------------------------------ media upload

    /**
     * Capture off the loop thread, then hand the bytes over for sending.
     *
     * The camera takes seconds and the read loop has to keep answering the server while it
     * does, or the connection looks dead for the duration of every photo.
     */
    private void beginPhoto() {
        new Thread(new Runnable() {
            public void run() {
                java.io.File f = Capture.once();
                if (f == null) {
                    Log.w(TAG, "photo requested but capture failed");
                    return;
                }
                try {
                    byte[] b = new byte[(int) f.length()];
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    try {
                        int off = 0, n;
                        while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                    } finally {
                        in.close();
                    }
                    offerMedia("42", b);
                } catch (Throwable t) {
                    Log.w(TAG, "could not read the photo back", t);
                }
            }
        }, "photo").start();
    }

    /** Queue a picture or recording for upload. One at a time: the protocol has no way to
     *  interleave two transfers, and the server keys the whole thing on its start time. */
    private synchronized void offerMedia(String op, byte[] data) {
        if (mediaData != null) {
            Log.w(TAG, "a media upload is already running; dropping the new one");
            return;
        }
        mediaOp = op;
        mediaData = data;
        mediaTime = TrackerSources.stamp().replaceAll("[^0-9]", "");
        mediaTotal = (data.length + BeehomeCodec.MEDIA_CHUNK - 1) / BeehomeCodec.MEDIA_CHUNK;
        mediaNext = 1;
        mediaWaiting = false;
        mediaTries = 0;
        Log.i(TAG, "media upload queued: " + data.length + " bytes, " + mediaTotal + " packets");
    }

    private void sendMediaPacket(OutputStream out) throws Exception {
        byte[] data = mediaData;
        if (data == null) return;
        int off = (mediaNext - 1) * BeehomeCodec.MEDIA_CHUNK;
        if (off >= data.length) {
            mediaData = null;
            return;
        }
        int len = Math.min(BeehomeCodec.MEDIA_CHUNK, data.length - off);
        byte[] pkt = BeehomeCodec.mediaPacket(mediaOp, mediaTime, mediaTotal, mediaNext,
                data, off, len);
        Log.i(TAG, "-> AP" + mediaOp + " packet " + mediaNext + "/" + mediaTotal
                + " (" + len + " bytes)");
        out.write(pkt);
        out.flush();
        mediaWaiting = true;
        mediaSentAt = SystemClock.elapsedRealtime();
        mediaTries++;
    }

    // ------------------------------------------------------------------ commands

    /**
     * Set the clock from a time-sync command.
     *
     * Worth having on this watch specifically: its clock ran about ten minutes fast against the
     * server across a whole day of logs, and the position frames carry their own timestamp, so a
     * drifting clock puts every fix at the wrong moment rather than merely showing the wrong
     * time on screen.
     *
     * The field is found by shape rather than by index, because the frame's layout is not
     * documented and guessing an index would set the clock from whatever happened to be there.
     */
    private void applyTime(BeehomeCodec.Frame f) {
        for (int i = 0; i < f.fields.size(); i++) {
            String v = f.fields.get(i).trim();
            if (v.length() != 14 || !isDigits(v)) continue;       // YYYYMMDDhhmmss
            String arg = v.substring(0, 8) + "." + v.substring(8);
            if (shell("date -s " + arg)) Log.i(TAG, "clock set from server: " + v);
            return;
        }
        Log.w(TAG, "time sync carried no recognisable timestamp: " + f.fields);
    }

    /** The first field that looks like a dialable number, or null. */
    private static String numberIn(BeehomeCodec.Frame f) {
        for (int i = 0; i < f.fields.size(); i++) {
            String v = f.fields.get(i).trim();
            // Long enough to be a number, short enough not to be the device id.
            if (v.length() >= 5 && v.length() <= 15 && isDialable(v)) return v;
        }
        return null;
    }

    private void dial(String number) {
        if (number == null) {
            Log.w(TAG, "dial command carried no number");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:" + number));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "could not dial", t);
        }
    }

    /** Keep every field that looks like a number, comma separated. */
    private void storeList(String key, BeehomeCodec.Frame f) {
        StringBuilder b = new StringBuilder();
        String tok = f.token();
        for (int i = 0; i < f.fields.size(); i++) {
            String v = f.fields.get(i).trim();
            if (v.length() < 5 || v.equals(tok) || !isDialable(v)) continue;
            if (b.length() > 0) b.append(',');
            b.append(v);
        }
        prefs(this).edit().putString(key, b.toString()).commit();
        Log.i(TAG, key + " set (" + b.length() + " chars)");
    }

    private boolean shell(String command) {
        RootShell sh = new RootShell();
        try {
            if (!sh.open() || !sh.isRoot()) return false;
            return sh.runQuiet(command);
        } catch (Throwable t) {
            return false;
        } finally {
            try { sh.close(); } catch (Throwable ignored) { }
        }
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isDialable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!Character.isDigit(ch) && ch != '+' && ch != '*' && ch != '#') return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ settings + hooks

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean(KEY_ENABLED, true);
    }

    /**
     * Start the client if it is switched on, and do nothing if it is not.
     *
     * Every automatic entry point goes through here rather than calling startService directly,
     * so "should this be running" is answered in exactly one place. {@link #onStartCommand}
     * checks the flag again and stops itself, which makes an unguarded start harmless rather
     * than merely untidy.
     */
    public static void start(Context c) {
        if (!enabled(c)) return;
        c.startService(new Intent(c, TrackerService.class));
    }

    /** Turning this on is the same decision as disabling the vendor app. See the class note. */
    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ENABLED, on).commit();
        Intent i = new Intent(c, TrackerService.class);
        if (on) c.startService(i); else c.stopService(i);
    }

    /**
     * Is there a live connection to the server right now?
     *
     * For callers that have readings of their own to send and a transport of their own to fall
     * back on. Racy by nature - the socket can drop between this and {@link #offer} - which is
     * why offer reports its own failure rather than trusting an earlier answer to this.
     */
    public static boolean connected() {
        TrackerService t = live;
        return t != null && t.outStream != null;
    }

    /**
     * Send one frame on the tracker's own connection, if it has one.
     *
     * The alternative is a second TCP session to the same host and port, and the server takes
     * command ownership per connection: two sessions open at once with the same device id
     * leaves it holding two of them for one watch, and splits that watch's log across both.
     * When the client is already connected and identified, its socket is the right one to use,
     * and no IWAP00 is needed on top - the heartbeat that opened it is what identified it.
     *
     * @return false if there is no live connection, or the write failed. The caller still has
     *         its own transport and should use it rather than dropping the reading.
     */
    public static boolean offer(String frame) {
        TrackerService t = live;
        if (t == null) return false;
        OutputStream o = t.outStream;
        if (o == null) return false;
        try {
            t.send(o, frame);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "could not send " + frame + " on the live connection", e);
            return false;
        }
    }

    /** How many BPJK replies the live connection has seen, or -1 if there is none. */
    public static int jkAckCount() {
        TrackerService t = live;
        return t == null ? -1 : t.jkAcks.get();
    }

    static String host(Context c, TrackerConfig cfg) {
        String h = prefs(c).getString(KEY_HOST, null);
        return (h != null && h.length() > 0) ? h : cfg.host();
    }

    static int port(Context c, TrackerConfig cfg) {
        int p = prefs(c).getInt(KEY_PORT, 0);
        return p > 0 ? p : cfg.port();
    }

    /** {@code host=1.2.3.4} or {@code host=1.2.3.4:9000}, from the SMS control plane. */
    public static void setEndpoint(Context c, String hostAndPort) {
        String h = hostAndPort;
        int p = 0;
        int colon = hostAndPort.lastIndexOf(':');
        if (colon > 0) {
            h = hostAndPort.substring(0, colon);
            try {
                p = Integer.parseInt(hostAndPort.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                p = 0;
            }
        }
        SharedPreferences.Editor e = prefs(c).edit().putString(KEY_HOST, h.trim());
        if (p > 0) e.putInt(KEY_PORT, p);
        e.commit();

        // Restart so the change takes effect now rather than at the next disconnect.
        if (enabled(c)) {
            c.stopService(new Intent(c, TrackerService.class));
            c.startService(new Intent(c, TrackerService.class));
        }
    }

    /**
     * Report position now rather than at the next cycle. Returns false if the client is not
     * connected, so the caller can say so instead of implying it worked.
     */
    public static boolean requestFix(Context c) {
        TrackerService t = live;
        if (t == null || !t.running) return false;
        t.fixNow = true;
        return true;
    }

    /**
     * Take a photo off the calling thread. The camera settles for over a second and the
     * callback is asynchronous, and a broadcast receiver that blocked on that would be killed
     * for taking too long before the shutter fired.
     */
    public static void requestPhoto(final Context c) {
        new Thread(new Runnable() {
            public void run() { Capture.once(); }
        }, "capture").start();
    }

    /** One line for the SMS {@code status} reply. */
    public static String describe(Context c) {
        if (!enabled(c)) return "tracker client off (vendor app still owns the link)";
        return "tracker client on";
    }
}
