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
 * Blood oxygen, the only way this watch can produce it.
 *
 * <h3>Why not the sensor framework</h3>
 *
 * There is no oxygen sensor in it. {@code dumpsys sensorservice} lists five, and the optical
 * one, {@code gh30x_sensor}, reports three values: pulse, systolic, diastolic. That is the
 * whole of what the platform exposes.
 *
 * The vendor gets oxygen from its own service instead, {@code com.ic.work.SensorDataService},
 * which is declared {@code exported="true"} with no permission, so any app on the watch can
 * bind it. Its {@code HeartRate} parcel carries oxygen as its first field.
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
public final class Spo2 {

    private static final String TAG = "Spo2";

    private static final String PKG = "com.ic.work";
    private static final String CLS = "com.ic.work.SensorDataService";

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

    private Spo2() { }

    /** One reading, or null. */
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
        final Reading[] got = new Reading[1];
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
                    if (r != null && got[0] == null) got[0] = r;
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
            Intent i = new Intent();
            i.setClassName(PKG, CLS);
            bound = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
            if (!bound) {
                Log.w(TAG, "could not bind " + CLS);
                return null;
            }

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (service[0] == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            if (service[0] == null) {
                Log.w(TAG, "the sensor service never connected");
                return null;
            }

            if (!register(service[0], callback, ctx.getPackageName())) return null;
            try {
                ask(service[0], ctx.getPackageName());
                while (got[0] == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200);
                }
            } finally {
                unregister(service[0], callback);
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
            // A finger off the sensor reads zero, and zero per cent is not a measurement.
            if (r.oxygen < 50 || r.oxygen > 100) return null;
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "could not read the reading", t);
            return null;
        }
    }
}
