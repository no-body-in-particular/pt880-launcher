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
 * Force a pulse measurement out of the vendor stack, past the check that refuses one.
 *
 * <h3>Not currently used, and why</h3>
 *
 * {@link PpgWatchdog} used to call this and does not any more. This route runs through
 * {@code com.ic.work.SensorDataService}, which is the component that stalls - it keeps one
 * work queue for both sensors with no timeout on the item at its head - so asking it for a
 * reading during a stall only queues another item behind the stuck one. The watchdog reads
 * the platform sensor instead, which reaches the same hardware without touching that queue.
 *
 * The reasoning below about the wear gate also did not survive. Heart rate and temperature
 * stop within a minute of each other and return together, and temperature never goes through
 * {@code triggerPPGTest} - so the gate cannot be what stops them. It is kept because the
 * protocol work is correct and hard-won, and because a watch whose queue is healthy can
 * still be asked for a reading this way.
 *
 * <h3>Why it was written</h3>
 *
 * The readings stop. Not rarely - most nights. The watch answers every
 * {@code HEARTRATE#} the server sends with a cheerful {@code IWAPXL} ack and
 * then measures nothing, for hours, until something restarts it. On the server
 * side that looks like a wedged sensor, and the fix there is a reboot: the
 * watch goes dark for several minutes in the middle of the night and comes back
 * reporting normally.
 *
 * It is not a wedged sensor. It is a deliberate refusal, and it is in
 * {@code HeartRateManager.triggerPPGTest} in the vendor's {@code L009_Protocol}
 * app:
 *
 * <pre>
 *     if (runtime.Anti_off_flag == 1 || runtime.Cut_off_flag == 1) {
 *         ICLogger.i("未佩戴 or 剪断，取消 triggerPPGtest！");
 *         return;                     // "not worn or strap-cut, cancel triggerPPGtest!"
 *     }
 * </pre>
 *
 * Two latched flags - taken off the wrist, and strap cut - and either one makes
 * every subsequent measurement a no-op. Nothing in the ack says so. A reboot
 * clears the flags, which is exactly why rebooting appears to fix a sensor that
 * was never broken.
 *
 * <h3>Why going around it is safe</h3>
 *
 * The check lives in {@code HeartRateManager}, not in the sensor. The actual
 * measurement is behind {@code com.ic.work.SensorDataService}, which is declared
 * {@code exported="true"} with no permission, and which has no wear or cut test
 * anywhere in it - all four of its classes were checked. So a plain bind and one
 * transaction gets a measurement whatever the flags say.
 *
 * <h3>The part that matters</h3>
 *
 * The result does not come back only to us. {@code HeartRateManager} registers
 * its own callback with that same service, and its {@code onHeartRateGet} logs
 * "心率测试结果来了..." - the heart rate test result has arrived - and then calls
 * {@code uploadResult()}, which is what sends the reading to the tracker server.
 *
 * So triggering the service here makes the vendor's own stack upload a reading
 * it had decided not to take. That is the whole point: no reboot, no dark
 * screen, and the number lands in the same place it always did.
 *
 * <h3>What this does not do</h3>
 *
 * It does not clear the flags - they live in another process and there is no
 * setter reachable from here. Every reading has to be asked for individually.
 * It also does not disable the anti-off feature itself; if that turns out to be
 * settable from the server side it would be the better fix, and this would
 * become a fallback.
 *
 * <h3>The wire format</h3>
 *
 * Read out of the deodexed service (baksmali needs {@code -a 19}; without it
 * most of the interesting classes fail with truncated-instruction errors and
 * you get a misleadingly empty picture).
 *
 * <pre>
 *     interface com.ic.work.IHeartRateSensorService
 *       1  registerCallback(IHeartRateSensorCallback, String pkg)   reply
 *       2  unRegisterCallback(IHeartRateSensorCallback)             reply
 *       3  getHeartRateInfo(int from, String pkg)                   no reply - must be oneway
 *
 *     interface com.ic.work.IHeartRateSensorCallback
 *       1  onHeartRateGet(HeartRate)      2  onHeartRateUpdate(HeartRate)
 *       3  onGettingData()                4  onWaiting()
 *
 *     HeartRate parcel: five ints, in this order
 *       oxygen, from, heartRate, bloodHeight, bloodLow
 * </pre>
 *
 * Transaction 3 writes nothing to the reply parcel - it returns straight out of
 * {@code onTransact} without {@code writeNoException} - so calling it
 * synchronously would leave the caller reading an empty reply. It goes
 * {@code FLAG_ONEWAY}. Transactions 1 and 2 do write a reply and must not.
 */
public class Ppg {

    private static final String TAG = "Ppg";

    private static final String PKG = "com.ic.work";
    private static final String SERVICE = "com.ic.work.SensorDataService";
    private static final String IFACE = "com.ic.work.IHeartRateSensorService";
    private static final String CB_IFACE = "com.ic.work.IHeartRateSensorCallback";

    private static final int TXN_REGISTER_CALLBACK = 1;
    private static final int TXN_UNREGISTER_CALLBACK = 2;
    private static final int TXN_GET_HEART_RATE = 3;

    private static final int CB_ON_HEART_RATE_GET = 1;
    private static final int CB_ON_HEART_RATE_UPDATE = 2;
    private static final int CB_ON_GETTING_DATA = 3;
    private static final int CB_ON_WAITING = 4;

    //HeartRateOxygenTestType: 0 ALL, 1 JUST_OXYGEN, 2 JUST_HEART_RATE. ALL is what the
    //vendor's own scheduled test asks for, and it is the one whose result carries a pulse,
    //a blood pressure pair and an oxygen figure - the same set the server logs.
    public static final int TEST_ALL = 0;
    public static final int TEST_JUST_OXYGEN = 1;
    public static final int TEST_JUST_HEART_RATE = 2;

    //the "from" tag is echoed back in the result and is otherwise free. A value of our own
    //makes our readings tellable from the firmware's own scheduled ones in a log.
    private static final int FROM_LAUNCHER = 77;

    //a PPG measurement takes seconds, not milliseconds, and the service says nothing when it
    //gives up. Unbind after this so a failed attempt cannot hold the binding open forever.
    private static final long MEASURE_TIMEOUT_MS = 45000;

    public interface Listener {
        /** A completed reading. Any field may be zero if the sensor could not get it. */
        void onReading(int bpm, int systolic, int diastolic, int oxygen);

        /** The attempt finished without a reading - sensor could not lock, or it timed out. */
        void onFailed(String why);
    }

    private final Context context;
    private final Listener listener;

    private IBinder service;
    private ServiceConnection connection;
    private boolean finished;
    //Bound to the main looper explicitly. A bare new Handler() takes the looper of whatever
    //thread constructs the object, and throws outright when that thread has none - which is
    //every background thread. This is constructed from a watchdog running on one.
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    public Ppg(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * The callback the sensor service talks back through.
     *
     * A raw {@link Binder} rather than a generated stub: the AIDL is the vendor's and is not
     * in our build, and the whole interface is four one-way calls carrying five ints. Writing
     * the descriptor and the codes out by hand is less machinery than shipping a copy of
     * their interface, and it keeps the wire format visible next to the notes that explain it.
     */
    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {

            switch (code) {
                case INTERFACE_TRANSACTION:
                    reply.writeString(CB_IFACE);
                    return true;

                case CB_ON_HEART_RATE_GET:
                case CB_ON_HEART_RATE_UPDATE:
                    data.enforceInterface(CB_IFACE);
                    readResult(data, code == CB_ON_HEART_RATE_GET);
                    return true;

                case CB_ON_GETTING_DATA:
                case CB_ON_WAITING:
                    data.enforceInterface(CB_IFACE);
                    //the sensor is looking for a pulse. Nothing to do but let it.
                    return true;

                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    private void readResult(Parcel data, boolean isFinal) {
        //a null-flag int, then the parcelable itself
        if (data.readInt() == 0) {
            if (isFinal) {
                done(null, "no reading in the result");
            }

            return;
        }

        int oxygen = data.readInt();
        data.readInt();                 //"from", echoed back
        int bpm = data.readInt();
        int systolic = data.readInt();
        int diastolic = data.readInt();

        //onHeartRateUpdate is the running estimate while it settles; only the final one counts
        if (!isFinal) {
            return;
        }

        if (bpm <= 0) {
            done(null, "sensor could not get a pulse");
            return;
        }

        done(new int[] { bpm, systolic, diastolic, oxygen }, null);
    }

    /**
     * Ask for a measurement, whatever the wear and cut flags say.
     *
     * Binds, registers, asks, and unbinds once an answer arrives or the wait runs out.
     */
    public void request(int testType) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(PKG, SERVICE));

        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                service = binder;

                if (!registerCallback() || !getHeartRateInfo(testType)) {
                    done(null, "the sensor service refused the call");
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                service = null;
                done(null, "the sensor service went away");
            }
        };

        boolean bound;

        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);

        } catch (SecurityException e) {
            //declared exported with no permission on this build, but a different firmware
            //could lock it down and there is no reason for that to be fatal
            done(null, "not allowed to bind the sensor service: " + e.getMessage());
            return;
        }

        if (!bound) {
            connection = null;
            done(null, "the sensor service is not there");
            return;
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                done(null, "no reading within " + (MEASURE_TIMEOUT_MS / 1000) + "s");
            }
        }, MEASURE_TIMEOUT_MS);
    }

    private boolean registerCallback() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        try {
            data.writeInterfaceToken(IFACE);
            data.writeStrongBinder(callback);
            data.writeString(context.getPackageName());
            service.transact(TXN_REGISTER_CALLBACK, data, reply, 0);
            reply.readException();
            return true;

        } catch (RemoteException e) {
            Log.w(TAG, "registerCallback failed", e);
            return false;

        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean getHeartRateInfo(int testType) {
        Parcel data = Parcel.obtain();

        try {
            data.writeInterfaceToken(IFACE);
            data.writeInt(testType == TEST_ALL ? FROM_LAUNCHER : testType);
            data.writeString(context.getPackageName());
            //no reply is written for this one, so it has to be one-way
            service.transact(TXN_GET_HEART_RATE, data, null, IBinder.FLAG_ONEWAY);
            return true;

        } catch (RemoteException e) {
            Log.w(TAG, "getHeartRateInfo failed", e);
            return false;

        } finally {
            data.recycle();
        }
    }

    private void unregisterCallback() {
        if (service == null) {
            return;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        try {
            data.writeInterfaceToken(IFACE);
            data.writeStrongBinder(callback);
            service.transact(TXN_UNREGISTER_CALLBACK, data, reply, 0);
            reply.readException();

        } catch (RemoteException e) {
            //on the way out anyway

        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * One exit for every path - answer, refusal, disconnect, timeout - so the binding is
     * always let go and the caller is always told exactly once.
     */
    private void done(final int[] reading, final String why) {
        if (finished) {
            return;
        }

        finished = true;
        handler.removeCallbacksAndMessages(null);
        unregisterCallback();

        if (connection != null) {
            try {
                context.unbindService(connection);

            } catch (IllegalArgumentException e) {
                //already gone
            }

            connection = null;
        }

        service = null;

        if (listener == null) {
            return;
        }

        //the callback arrives on a binder thread; the listener will want to touch the screen
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (reading != null) {
                    listener.onReading(reading[0], reading[1], reading[2], reading[3]);

                } else {
                    listener.onFailed(why);
                }
            }
        });
    }

    /** Give up on an attempt in flight. */
    public void cancel() {
        done(null, "cancelled");
    }
}
