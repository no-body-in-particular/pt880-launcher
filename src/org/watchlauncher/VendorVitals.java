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
        // onHeartRateGet is the finished measurement; onHeartRateUpdate is progress towards
        // it, and arrives with the fields that are not ready yet still at zero. Taking the
        // first callback that carried any number at all meant taking a partial one: a pulse
        // with "SpO2 0%, 0/0" beside it, seconds before the real answer.
        final Reading[] got = new Reading[1];
        final Reading[] partial = new Reading[1];
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
                    if (r != null) {
                        if (code == CB_GET) got[0] = r;
                        else partial[0] = r;
                    }
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
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;

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
            try {
                ask(service[0], ctx.getPackageName());
                while (got[0] == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200);
                }
            } finally {
                unregister(service[0], callback);
            }

            if (got[0] == null && partial[0] != null) {
                // The finished callback never came, but something was measured. Better than
                // nothing, and said plainly so a partial reading is not mistaken for a full one.
                Log.i(TAG, "only a progress reading: " + partial[0]);
                return partial[0];
            }
            if (got[0] == null) {
                Log.w(TAG, "no reading in " + (timeoutMs / 1000) + "s; the work queue is "
                        + "probably wedged, which is its known failure and not worth retrying");
                return null;
            }
            Log.i(TAG, "" + got[0]);
            return got[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "oxygen reading failed", t);
            return null;
        } finally {
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
    static Reading parse(Parcel p) {
        try {
            if (p.readInt() == 0) return null;          // the object was null
            Reading r = new Reading();
            r.oxygen = p.readInt();
            p.readInt();                                // from, echoed back
            r.heartRate = p.readInt();
            r.systolic = p.readInt();
            r.diastolic = p.readInt();
            // Each field stands on its own: a wrist can give a good pulse and no oxygen, and
            // throwing the reading away because one number is out of range loses the rest.
            if (r.oxygen < 50 || r.oxygen > 100) r.oxygen = 0;
            if (r.heartRate < 25 || r.heartRate > 250) r.heartRate = 0;
            if (r.systolic < 60 || r.systolic > 260) r.systolic = 0;
            if (r.diastolic < 30 || r.diastolic > 200) r.diastolic = 0;
            if (r.diastolic >= r.systolic) { r.systolic = 0; r.diastolic = 0; }
            return (r.oxygen > 0 || r.heartRate > 0) ? r : null;
        } catch (Throwable t) {
            Log.w(TAG, "could not read the reading", t);
            return null;
        }
    }
}
