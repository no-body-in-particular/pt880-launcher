package org.watchlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * A real vitals measurement: the only thing on this watch that starts one.
 *
 * <h3>Why the platform sensor is not enough</h3>
 *
 * {@code gh30x_sensor} looks like the obvious route and it is a mirror, not a measurement.
 * Watch it while asking for readings:
 *
 * <pre>
 * gh30x_sensor | Goodix | 0x08 | on-demand | last=&lt; 59.0,120.0, 79.0&gt;
 * Active sensors:
 * 0 active connections
 * </pre>
 *
 * That {@code last=} has not changed in hours, through dozens of requests, and the green LED
 * never comes on. registerListener replays the cached triple immediately and
 * requestTriggerSensor returns without the HAL starting anything: the measurement is driven by
 * the vendor's own service, and the platform sensor only reports whatever it last produced. So
 * a client reading gh30x directly gets the same frozen numbers for ever - 59 bpm and 120/79,
 * hour after hour, which is exactly what the server's chart showed.
 *
 * <h3>What does start one</h3>
 *
 * {@code com.ic.work.SensorDataService}, declared {@code exported="true"} with no permission,
 * so any app on the watch can bind it. Its {@code HeartRate} parcel carries all four numbers:
 * oxygen, heart rate, and the two pressures.
 *
 * <h3>The interface</h3>
 *
 * No AIDL is shipped, so the transactions are written by hand from the service's own
 * {@code onTransact} - see protocol/README.md section 10 in the root repository:
 *
 * <pre>
 * com.ic.work.IHeartRateSensorService
 *   1  registerCallback(IHeartRateSensorCallback, String pkg)   writes a reply
 *   2  unRegisterCallback(IHeartRateSensorCallback)             writes a reply
 *   3  getHeartRateInfo(int from, String pkg)                   writes NO reply
 *
 * com.ic.work.IHeartRateSensorCallback
 *   1  onHeartRateGet(HeartRate)     3  onGettingData()
 *   2  onHeartRateUpdate(HeartRate)  4  onWaiting()
 *
 * HeartRate parcel: a null-flag int, then five ints:
 *   oxygen, from, heartRate, bloodHeight, bloodLow
 * </pre>
 *
 * Transaction 3 returns without {@code writeNoException}, so calling it synchronously leaves
 * the caller reading an empty reply: it has to go {@code FLAG_ONEWAY}. One and two do write a
 * reply and must not.
 *
 * {@code from} is echoed back in the result and is otherwise free, so {@link #FROM} is how a
 * reading we asked for is told apart from the firmware's own scheduled ones.
 *
 * <h3>The hazard, and what is done about it</h3>
 *
 * That service keeps one work queue for both its sensors with no timeout anywhere: a
 * measurement whose callback never arrives holds the queue for ever, and both sensors stop
 * until the process restarts. It is why the code that used to talk to this was removed.
 *
 * So this asks for one reading, waits a bounded time, unregisters and unbinds whatever happens,
 * and does not retry on failure - a caller that hammered it during a stall would only queue
 * more work behind the stuck item. The pulse and the pressures do not come from here; they come
 * from {@link HeartRate} through the platform sensor, which is a different route that does not
 * touch this queue. So a wedge here costs the oxygen reading and nothing else.
 */
public final class VendorVitals {

    private static final String TAG = "VendorVitals";

    private static final String PKG = "com.ic.work";
    private static final String CLS = "com.ic.work.SensorDataService";

    /**
     * The action decides which binder comes back, and the manifest does not say so.
     *
     * An explicit component is not enough: bindService returns true, because the system found
     * the class, and onServiceConnected never fires, because onBind returned null. Nor is the
     * action from the package manager's resolver table - action.WORK_SERVICE_SECOND_TIMER is
     * what starts its timer, not what binds it.
     *
     * onBind's own constants, read out of ICL02WorkService.odex, are the answer:
     *
     * <pre>
     * "on service bind package name -- > "  " action name is == > "
     * "com.ic.blood"  "com.ic.sensor.data.action.HEART_RATE"
     * "com.ic.temp"   "com.ic.sensor.data.action.TEMPERATURE"
     * </pre>
     *
     * So there are two binders behind one service, chosen by action, and the vendor's own
     * callers are com.ic.blood and com.ic.temp. Tried in order, the real one first, with the
     * others kept because they cost one bind each and rule themselves out in the log.
     */
    private static final String[][] CANDIDATES = {
        {"com.ic.work.SensorDataService", "com.ic.sensor.data.action.HEART_RATE"},
        {"com.ic.work.SensorDataService", "action.WORK_SERVICE_SECOND_TIMER"},
        {"com.ic.work.SensorDataService", null},
    };

    private static final String SERVICE = "com.ic.work.IHeartRateSensorService";
    private static final String CALLBACK = "com.ic.work.IHeartRateSensorCallback";

    private static final int TX_REGISTER = 1;
    private static final int TX_UNREGISTER = 2;
    private static final int TX_GET = 3;

    private static final int CB_GET = 1;
    private static final int CB_UPDATE = 2;

    /** Ours, so a reading we asked for is distinguishable from the firmware's own. */
    private static final int FROM = 4242;

    /** How long to keep listening for the pressures after the pulse says the measurement is
     *  done. They come in their own callback and lag it; waiting the whole budget for them
     *  would hold the sensor on for nothing when the wrist is not giving them up. */
    private static final long BP_GRACE_MS = 8000;

    /**
     * Fill the gaps in what we have with what just arrived.
     *
     * Each field independently: a callback carrying only a pulse must not wipe a pressure an
     * earlier one delivered, and a later zero is "not ready", not "zero".
     */
    private static Reading merge(Reading have, Reading fresh) {
        if (fresh == null) return have;
        if (have == null) return fresh;
        if (fresh.oxygen > 0) have.oxygen = fresh.oxygen;
        if (fresh.heartRate > 0) have.heartRate = fresh.heartRate;
        if (fresh.systolic > 0) have.systolic = fresh.systolic;
        if (fresh.diastolic > 0) have.diastolic = fresh.diastolic;
        return have;
    }

    /** 0 ALL, 1 JUST_OXYGEN, 2 JUST_HEART_RATE -- ALL, so one measurement answers everything. */
    private static final int TYPE_ALL = 0;

    private VendorVitals() { }

    /** One measurement: whatever of the four the sensor managed. */
    public static final class Reading {
        public int oxygen;
        public int heartRate;
        public int systolic;
        public int diastolic;

        public String toString() {
            return "SpO2 " + oxygen + "%, " + heartRate + " bpm, "
                    + systolic + "/" + diastolic;
        }
    }

    /**
     * Ask for a reading and wait for it.
     *
     * Blocking, so not on the UI thread. Returns null if the service is not there, refuses the
     * bind, or says nothing inside {@code timeoutMs} - which on this build means the queue is
     * wedged, and is not worth retrying.
     */
    public static Reading measure(Context ctx, long timeoutMs) {
        // Driving the sensor ourselves through libICJniUtils was tried and gives heart rate
        // only: enablePPG() starts the chip in heart rate mode, and the library's own event
        // said so on every sample of a full 45 s window -
        //
        //     GH_gh30x   event ppg 59 , spo2 0 , weared 1
        //
        // SpO2 mode is started by the HAL, not by that library: "command, GH_30X
        // gh30x_Spo2Start" is a string in sensors.sl8521e.so. Which is why SpO2 appears on the
        // input device only while com.ic.work is running the measurement.
        //
        // So the service still starts it. What has changed is that nothing depends on the HAL
        // delivering the result any more: heart rate and SpO2 come off the input device, and
        // the pressures come from the library, polled during the same window.

        // onHeartRateGet is the finished measurement; onHeartRateUpdate is progress towards
        // it, and arrives with the fields that are not ready yet still at zero. Taking the
        // first callback that carried any number at all meant taking a partial one: a pulse
        // with "SpO2 0%, 0/0" beside it, seconds before the real answer.
        // Merged across every callback rather than taken from one of them.
        //
        // The service streams a measurement: onHeartRateUpdate carries whatever is ready so
        // far with the rest at zero, and onHeartRateGet ends it. Taking the first callback
        // gave a pulse with "0/0" beside it; taking only the finished one lost the pressures,
        // which is what stopped blood pressure arriving. Neither snapshot is the reading - the
        // reading is what all of them together said.
        final Reading[] acc = new Reading[1];
        final long[] finishedAt = new long[1];
        final IBinder[] service = new IBinder[1];

        final Binder callback = new Binder() {
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(CALLBACK);
                    return true;
                }
                if (code == CB_GET || code == CB_UPDATE) {
                    data.enforceInterface(CALLBACK);
                    Reading r = parse(data);
                    if (r != null) acc[0] = merge(acc[0], r);
                    if (code == CB_GET) finishedAt[0] = System.currentTimeMillis();
                    // The caller may or may not be waiting on a reply; answering when it is not
                    // is harmless, not answering when it is would hang it.
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                if (code == 3 || code == 4) {          // onGettingData, onWaiting
                    data.enforceInterface(CALLBACK);
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };

        ServiceConnection conn = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder b) {
                service[0] = b;
            }

            public void onServiceDisconnected(ComponentName name) {
                service[0] = null;
            }
        };

        boolean bound = false;
        // Declared out here so the finally can release it: it owns a thread and an open file
        // descriptor on the sensor node, and an exception on the way through must not leak
        // either. The measurement path below clears it once it has taken the samples.
        SensorInput.Reader driver = null;
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;

            // Starting it ourselves and waiting was tried here, and only wasted the budget.
            // Registering a listener does switch the sensor on - "switched on gh30x_sensor
            // (type 21)" - but that alone runs no measurement: 45 s of it produced no sample at
            // all, and the request then had to be made anyway, having spent the timeout. The
            // vendor's native start is what actually runs the PPG.
            //
            // So ask the service first, as before. The reader below still registers a listener
            // while it collects, which costs nothing and keeps the sensor up for the window.
            for (int a = 0; a < CANDIDATES.length && service[0] == null; a++) {
                if (bound) {
                    try { ctx.unbindService(conn); } catch (Throwable ignored) { }
                    bound = false;
                }
                String cls = CANDIDATES[a][0];
                String action = CANDIDATES[a][1];
                Intent i = new Intent();
                i.setClassName(PKG, cls);
                if (action != null) i.setAction(action);

                bound = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
                Log.i(TAG, "bind " + cls + " / " + (action == null ? "<no action>" : action)
                        + " -> " + bound);
                if (!bound) continue;

                // Short per attempt: a bind that is going to succeed does so promptly, and
                // there are three of these to get through inside one budget.
                long attemptEnds = Math.min(deadline, System.currentTimeMillis() + 4000);
                while (service[0] == null && System.currentTimeMillis() < attemptEnds) {
                    Thread.sleep(100);
                }
            }

            if (service[0] == null) {
                Log.w(TAG, "the sensor service never connected; onBind returned null for every "
                        + "component and action tried");
                return null;
            }
            // Which interface actually came back. The transactions below are numbered for
            // IHeartRateSensorService; sending them to something else would be a guess.
            try {
                Log.i(TAG, "bound interface: " + service[0].getInterfaceDescriptor());
            } catch (Throwable ignored) { }

            if (!register(service[0], callback, ctx.getPackageName())) return null;
            // Start listening to the driver before asking for the measurement, so nothing is
            // missed between the request going in and the sensor powering up. See SensorInput:
            // the HAL loses every sample, so this is where the heart rate and SpO2 come from.
            driver = SensorInput.start(ctx);
            try {
                ask(service[0], ctx.getPackageName());
                while (System.currentTimeMillis() < deadline) {
                    Reading a = acc[0];
                    if (finishedAt[0] > 0 && a != null && a.heartRate > 0) {
                        // The measurement says it is done and a pulse is in. Give the
                        // pressures a moment to follow - they arrive in their own callback
                        // and lag the pulse - but do not wait out the whole budget for them.
                        if (a.systolic > 0
                                || System.currentTimeMillis() - finishedAt[0] > BP_GRACE_MS) {
                            break;
                        }
                    }
                    Thread.sleep(200);
                }
            } finally {
                unregister(service[0], callback);
            }

            // What the driver saw, which on this watch is the only place a pulse or an SpO2
            // actually comes from. Taken whether or not the service answered: its zeros are
            // not a reading, they are the HAL's silence written down.
            SensorInput.Sample raw = driver == null ? null : driver.finish();
            driver = null;

            if (acc[0] == null && raw == null) {
                Log.w(TAG, "no reading in " + (timeoutMs / 1000) + "s, from the service or the "
                        + "driver");
                return null;
            }
            Reading out = acc[0] != null ? acc[0] : new Reading();
            if (raw != null) {
                // The driver wins on both. The service reports these as zero because the HAL
                // hands it nothing; a zero here is an absence, never a measurement.
                if (raw.heartRate > 0) out.heartRate = raw.heartRate;
                if (raw.oxygen > 0) out.oxygen = raw.oxygen;
                // Only if the service did not supply one: the pressures are the one thing it
                // has actually delivered correctly on this watch, and REL_RY has never fired,
                // so this is the fallback of the two rather than the winner.
                if (raw.systolic > 0 && out.systolic <= 0) {
                    out.systolic = raw.systolic;
                    out.diastolic = raw.diastolic;
                }
            }
            if (finishedAt[0] == 0 && acc[0] != null) {
                Log.i(TAG, "measurement did not finish; reporting what arrived: " + out);
            }
            Log.i(TAG, "" + out);
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "oxygen reading failed", t);
            return null;
        } finally {
            // Normally already null - the measurement path takes its samples and clears it.
            // This is the exception route, where the sensor node would otherwise stay open
            // with a thread blocked on it until the process died.
            if (driver != null) driver.finish();
            if (bound) {
                try { ctx.unbindService(conn); } catch (Throwable ignored) { }
            }
        }
    }

    private static boolean register(IBinder b, IBinder cb, String pkg) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE);
            data.writeStrongBinder(cb);
            data.writeString(pkg);
            b.transact(TX_REGISTER, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "registerCallback failed", t);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void ask(IBinder b, String pkg) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE);
            // The one argument the interface takes besides the package. TYPE_ALL and FROM are
            // the same number here, which is why ALL is the value chosen: it is correct whether
            // this field is the test type or the free tag.
            data.writeInt(TYPE_ALL);
            data.writeString(pkg);
            // No reply is written by this transaction, so a synchronous call would block on an
            // empty parcel.
            b.transact(TX_GET, data, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable t) {
            Log.w(TAG, "getHeartRateInfo failed", t);
        } finally {
            data.recycle();
        }
    }

    private static void unregister(IBinder b, IBinder cb) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE);
            data.writeStrongBinder(cb);
            b.transact(TX_UNREGISTER, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "unRegisterCallback failed", t);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // ---------------------------------------------------------------- temperature

    private static final String TEMP_ACTION = "com.ic.sensor.data.action.TEMPERATURE";
    private static final String TEMP_SERVICE = "com.ic.work.ITempSensorService";
    private static final String TEMP_CALLBACK = "com.ic.work.ITempSensorCallback";

    /**
     * Body temperature, from the second binder behind the same service.
     *
     * The platform's GXTS02S is a mirror in the same way gh30x is - dumpsys has it sitting at
     * {@code last=<  0.0,  0.0,  0.0>}, which is what it has always reported - so this is the
     * only route to a reading, just as it is for the pulse.
     *
     * <h3>What is known and what is inferred</h3>
     *
     * Known, from the dex: the action, the descriptor {@code com.ic.work.ITempSensorService},
     * a callback interface {@code ITempSensorCallback}, and a {@code Temperature} parcel whose
     * own toString names its fields in order - bodyTemp, wristTemp, envTemp.
     *
     * Inferred: the transaction numbers, taken to match the heart rate service beside it
     * (1 register, 2 unregister, 3 ask). onTransact carries no strings to read them from. A
     * wrong number fails the call and is logged; it cannot produce a wrong reading.
     *
     * The parcel's field type is not settled either, so both are tried: ints first, since the
     * protocol carries hundredths elsewhere, then floats, and whichever yields a plausible body
     * temperature wins. Anything outside 20-45 is not one.
     */
    public static float temperature(Context ctx, long timeoutMs) {
        final float[] got = new float[]{0f};
        final IBinder[] service = new IBinder[1];

        final Binder callback = new Binder() {
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(TEMP_CALLBACK);
                    return true;
                }
                if (code >= 1 && code <= 4) {
                    data.enforceInterface(TEMP_CALLBACK);
                    float v = parseTemp(data);
                    if (v > 0 && got[0] == 0f) got[0] = v;
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };

        ServiceConnection conn = new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) { service[0] = b; }

            public void onServiceDisconnected(ComponentName n) { service[0] = null; }
        };

        boolean bound = false;
        try {
            Intent i = new Intent();
            i.setClassName(PKG, CLS);
            i.setAction(TEMP_ACTION);
            bound = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
            if (!bound) return 0f;

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (service[0] == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            if (service[0] == null) {
                Log.w(TAG, "the temperature binder never connected");
                return 0f;
            }
            Log.i(TAG, "bound interface: " + service[0].getInterfaceDescriptor());

            if (!call(service[0], TEMP_SERVICE, TX_REGISTER, callback, ctx.getPackageName())) {
                return 0f;
            }
            try {
                Parcel d = Parcel.obtain();
                try {
                    d.writeInterfaceToken(TEMP_SERVICE);
                    d.writeInt(TYPE_ALL);
                    d.writeString(ctx.getPackageName());
                    service[0].transact(TX_GET, d, null, IBinder.FLAG_ONEWAY);
                } finally {
                    d.recycle();
                }
                while (got[0] == 0f && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200);
                }
            } finally {
                call(service[0], TEMP_SERVICE, TX_UNREGISTER, callback, null);
            }

            if (got[0] == 0f) {
                Log.w(TAG, "no temperature in " + (timeoutMs / 1000) + "s");
                return 0f;
            }
            Log.i(TAG, "body temperature " + got[0]);
            return got[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0f;
        } catch (Throwable t) {
            Log.w(TAG, "temperature reading failed", t);
            return 0f;
        } finally {
            if (bound) {
                try { ctx.unbindService(conn); } catch (Throwable ignored) { }
            }
        }
    }

    /** register or unregister, which differ only in whether the package goes with it. */
    private static boolean call(IBinder b, String descriptor, int code, IBinder cb, String pkg) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeStrongBinder(cb);
            if (pkg != null) data.writeString(pkg);
            b.transact(code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "transaction " + code + " on " + descriptor + " failed", t);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Temperature{bodyTemp, wristTemp, envTemp}, in whichever of the two encodings it uses. */
    static float parseTemp(Parcel p) {
        try {
            if (p.readInt() == 0) return 0f;
            int at = p.dataPosition();

            int body = p.readInt();
            float asInt = (body > 100) ? body / 100f : body;
            if (asInt > 20f && asInt < 45f) return asInt;

            p.setDataPosition(at);
            float asFloat = p.readFloat();
            if (asFloat > 100f) asFloat = asFloat / 100f;
            if (asFloat > 20f && asFloat < 45f) return asFloat;

            return 0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** The HeartRate parcel: a null flag, then oxygen, from, heartRate, bloodHeight, bloodLow. */
    /**
     * The HeartRate parcel, as the service actually fills it.
     *
     * A null flag, then five ints. Four consecutive live ones, from a wrist:
     *
     * <pre>
     * 1 0 115 76 71 98
     * 1 0 114 75 75 97
     * 1 0 118 78 74 98
     * 1 0 111 73 81 99
     *   |  |  |  |  `- 97-99, a blood oxygen saturation
     *   |  |  |  `---- 71-81, a resting pulse
     *   |  |  `------- 73-78, a diastolic
     *   |  `---------- 111-118, a systolic
     *   `------------- 0 every time: the argument passed to getHeartRateInfo, echoed
     * </pre>
     *
     * So the order is {@code from, systolic, diastolic, heartRate, oxygen} - not the
     * "oxygen, from, heartRate, bloodHeight, bloodLow" the interface was written up as.
     *
     * The echo is what settles it. Every other reading of these five is defensible on one
     * sample; only this one has field 0 matching what was sent on all of them, and only this
     * one makes each remaining field a plausible value for what it would then be. Read the
     * documented way, the pulse comes out of the diastolic slot - which is why the server's
     * heart rate chart has been tracking a diastolic, and why the pressures, read from the
     * pulse and oxygen slots, came out inverted and were thrown away by the sanity check.
     *
     * Ranges are still checked per field. A wrist that gives up half a measurement is normal
     * and the zeroes are "not measured", not zero.
     */
    static Reading parse(Parcel p) {
        try {
            if (p.readInt() == 0) return null;          // the object was null

            p.readInt();                                // from, echoed back
            int sys = p.readInt();
            int dia = p.readInt();
            int hr = p.readInt();
            int spo2 = p.readInt();

            Reading r = new Reading();
            if (hr >= 25 && hr <= 250) r.heartRate = hr;
            if (spo2 >= 50 && spo2 <= 100) r.oxygen = spo2;
            if (sys >= 60 && sys <= 260 && dia >= 30 && dia <= 200 && dia < sys) {
                r.systolic = sys;
                r.diastolic = dia;
            }

            return (r.oxygen > 0 || r.heartRate > 0 || r.systolic > 0) ? r : null;
        } catch (Throwable t) {
            Log.w(TAG, "could not read the reading", t);
            return null;
        }
    }
}
