package org.watchlauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole watch, in one activity.
 *
 * Everything is here rather than in five separate apps because there is no
 * task switcher, no touchscreen and no working way back: an activity that
 * finishes on this device drops you on the stock clock face, which is a dead
 * end. A single activity with a screen stack means "back" always has somewhere
 * to go, and the clock and battery never leave the top of the display.
 *
 * <h3>The button quirk</h3>
 *
 * The firmware does not hand the main key to apps normally:
 *
 * <ul>
 *   <li>a <b>tap</b> is swallowed and re-emitted as a synthetic {@code BACK}
 *       ({@code deviceId=-1}) -- the app never sees {@code DPAD_CENTER};
 *   <li>a <b>hold</b> leaks {@code DPAD_CENTER} auto-repeats first, and then
 *       still ends in that same synthetic {@code BACK}.
 * </ul>
 *
 * So {@code BACK} is the only reliable "key released" signal, and the
 * auto-repeat count seen beforehand is the only measure of how long the key
 * was held. {@code BACK} is swallowed so it can never quietly close the app.
 * Two rapid taps are collapsed into one {@code BACK} by the firmware, which is
 * why there is no double-tap gesture anywhere.
 *
 * <h3>Keyboards</h3>
 *
 * Once a Bluetooth keyboard is paired from the Bluetooth screen its keys
 * arrive here too, and would otherwise be read as watch buttons -- Enter is
 * button A's keycode and the arrows are button B's. They are told apart by
 * their input device: a real keyboard reports {@code KEYBOARD_TYPE_ALPHABETIC},
 * the watch's two gpio keys do not. Keyboard events go to the screen first;
 * what it does not want falls back to arrows-move, Enter-picks, Escape-backs.
 */
public class ShellActivity extends Activity {

    public static final int BTN_A = 0;
    public static final int BTN_B = 1;

    public static final int TAP = 1;
    public static final int HOLD = 2;
    public static final int XHOLD = 3;

    private static final int LONG_MS = 600;

    /** Auto-repeats seen before a hold counts as the "extra long" gesture. */
    private static final int XLONG_REPEATS = 60;

    /** How far a mouse has to travel, in pixels, to step the selection once.
     *  A row is about 28px, so this is a little under one row of movement per
     *  step -- fast enough to cross the launcher in a flick, slow enough to
     *  land on the row you meant. */
    private static final float MOUSE_STEP_PX = 24f;

    /** The same for a trackball, whose events carry relative movement in
     *  units of roughly one per detent rather than pixels. */
    private static final float BALL_STEP = 0.7f;

    /** Set by the caller to open straight onto one app -- used by the incoming
     *  call receiver, and by {@code am start -e app camera} from a shell. */
    public static final String EXTRA_APP = "app";

    /** The caller's number, alongside {@code app=incoming}. */
    public static final String EXTRA_NUMBER = "number";

    private final Handler ui = new Handler();
    private final List<Screen> stack = new ArrayList<Screen>();

    private SharedPreferences prefs;
    private StatusBar status;
    private FrameLayout body;
    private TextView vHint;
    private AudioManager audio;

    private boolean twoButtons = false;
    private boolean keepAwake = false;
    private boolean started = false;

    // Shared services, built once and handed to whichever screens want them.
    private BtHelper bt;
    private RootShell root;
    private MusicService music;
    private boolean musicBound = false;

    // pointer state
    private float pointerAccum = 0;
    private float lastHoverY = Float.NaN;

    // key state
    private int downKey = -1;
    private boolean longFired = false;
    private int holdRepeats = 0;
    private Runnable longTask;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // Before anything else: a crash on any thread kills this process, and
        // as the home screen it is relaunched immediately, so a failure looks
        // like the app navigating rather than dying. Record it on the card.
        Crash.install(this);

        // Before the marker is re-armed, so this reports the run before this one.
        final boolean killed = Crash.wasKilled(this);
        final String lastScreen = Crash.lastScreen(this);

        // And say so, once, if the last run ended badly. A crash that only
        // happens in the field is otherwise invisible: the watch relaunches
        // so fast that the wearer sees a screen change, not a failure.
        ui.postDelayed(new Runnable() {
            public void run() {
                // The one line version. The full stack still goes to the card
                // and out over the report, but a wall of package names on a
                // 240px label tells the wearer nothing except that something
                // alarming happened.
                String why = Crash.summary();
                if (why != null && Crash.freshlyCrashed()) toast(why);

                // A kill leaves no stack, so it has to be inferred from a marker
                // that a clean exit would have cleared. Said out loud, because
                // "the map disappeared" and "the map crashed" are different
                // problems and the wrist cannot tell them apart.
                if (killed) {
                    String on = lastScreen.length() > 0 ? lastScreen : "the launcher";
                    toast("killed while showing " + on);
                }

                // And send it, so a crash on a wrist in a car is diagnosable
                // without the watch and a cable being in the same room. Off the
                // main thread: it shells out for the imei and opens a socket.
                new Thread(new Runnable() {
                    public void run() {
                        WatchdogReport.sendCrash(ShellActivity.this);

                        if (killed) {
                            WatchdogReport.sendKilled(ShellActivity.this, lastScreen);
                        }
                    }
                }).start();
            }
        }, 1200);
        prefs = getSharedPreferences("watchlauncher", MODE_PRIVATE);
        twoButtons = prefs.getBoolean("twoButtons", false);

        // The stock in-call screen cannot be dismissed on a watch with no
        // touchscreen, and it covers this one the moment a call is placed.
        claimCallUi();

        // Car or bicycle, as it was left. RoadGraph decides which file to map
        // from this, and it is a static because everything that asks whether a
        // graph is on the card asks before it has a graph to ask through.
        RoadGraph.useMode(Travel.mode(this));

        // An earlier build switched the GNSS receiver on by itself, which is a
        // system setting that outlives the build that did it. Hand it back,
        // once, unless the wearer has since asked for it.
        new Thread(new Runnable() {
            public void run() {
                try {
                    android.location.LocationManager lm =
                            (android.location.LocationManager)
                            getSystemService(android.content.Context.LOCATION_SERVICE);
                    Gps.undoAutoEnable(ShellActivity.this, lm);
                } catch (Throwable t) { /* nothing to undo */ }
            }
        }).start();
        keepAwake = prefs.getBoolean("keepAwake", false);

        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        buildUi();
        applyKeepAwake();

        push(new LauncherScreen());
        openRequestedApp(getIntent());

        // Sleep tracking is armed by default and needs no switching on. The
        // alarm is idempotent -- FLAG_UPDATE_CURRENT replaces any pending one
        // rather than stacking a second -- so re-arming on every start is the
        // cheapest way to recover from anything that stopped it.
        if (SleepLog.enabled(this)) SleepService.schedule(this, 10000);

        // Same idempotent re-arm for the pulse watchdog, and for the same
        // reason: whatever stopped it, the next launch puts it back. It is
        // cheap when nothing is wrong -- a clock comparison every ten minutes
        // -- and it does not ask the sensor for anything unless the watch has
        // gone quiet for more than half an hour.
        PpgWatchdog.start(this);

        // BouncyCastle registers a few hundred algorithms when it is first
        // touched, which is a visible stall if it happens inside a screen
        // opening. Done here, on a thread, so it is ready before anything
        // wants a connection.
        new Thread(new Runnable() {
            public void run() {
                try { Tls12SocketFactory.create(ShellActivity.this); }
                catch (Throwable t) { /* the caller will find out */ }
            }
        }).start();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        openRequestedApp(i);
    }

    /** {@code -e app music|bluetooth|camera|call|terminal}, plus the internal
     *  {@code incoming} used by the phone-state receiver. */
    private void openRequestedApp(Intent i) {
        if (i == null) return;
        String app = i.getStringExtra(EXTRA_APP);
        if (app == null) return;
        i.removeExtra(EXTRA_APP);

        if (app.equals("incoming")) {
            String number = i.getStringExtra(EXTRA_NUMBER);
            popToRoot();
            push(new InCallScreen(Contacts.nameFor(number), number, true));
            return;
        }
        Screen s = LauncherScreen.byName(app, this);
        if (s != null) {
            popToRoot();
            push(s);
        }
    }

    /**
     * Take the in-call screen off the stock dialler, once per install.
     *
     * On a thread: it opens a root shell and asks the package manager two
     * questions, neither of which the first frame should wait for. Silent when
     * there is no root or no stock screen to take - the watch simply behaves
     * as it did.
     */
    private void claimCallUi() {
        if (CallUi.taken(this)) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    CallUi.takeOver(ShellActivity.this);
                } catch (Throwable t) {
                    android.util.Log.w("watchcall", "call ui: " + t);
                }
            }
        }).start();
    }

    /** Whether the launcher is on screen. False from the moment something
     *  covers it, including the screen turning off. */
    public boolean showing() { return started; }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        status.start();
        ui.post(tick);
        if (!stack.isEmpty()) current().onShow();
    }

    /**
     * Give memory back when the system asks, instead of making it take some.
     *
     * Nothing here implemented this before, which had a consequence worth spelling out. This
     * is the home activity, so Android protects it and reclaims from something else - and on
     * this watch the something else is the vendor's tracker. When that is killed the watch
     * stops talking to the server entirely, so the server cannot poll it and cannot send the
     * reboot that clears a stalled sensor, and a half hour gap becomes an hour and ten.
     *
     * The routing graph is the thing worth giving back. It is a memory mapped file, so it
     * costs no heap, but mapped pages still count in the RSS the low memory killer weighs,
     * and RoadGraph.shared() is a static - once the map has been opened once it stays mapped
     * for the life of the process, whether or not anyone is navigating.
     *
     * Only when the map is not the screen in front of you. Navigation carries on behind a
     * dark screen by design, and the graph is read without a null check on every position
     * fix, so unmapping it under a live route would not save memory, it would crash.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);

        if (level < TRIM_MEMORY_RUNNING_LOW) {
            return;                              // a hint, not pressure
        }

        if (!stack.isEmpty() && current() instanceof MapScreen) {
            return;                              // in use, possibly mid route
        }

        // Off the main thread, and this is not tidiness.
        //
        // onTrimMemory is delivered on the main thread, and both RoadGraph.shared() and
        // close() are synchronized - shared() on the class, close() on the instance. A route
        // being computed holds that instance for as long as the search takes, which on this
        // graph is seconds. Closing from the main thread means the UI thread waiting on a
        // router, which is an ANR and a black screen, for the sake of freeing memory nobody
        // was short of.
        new Thread(new Runnable() {
            public void run() {
                try {
                    RoadGraph.shared().close();  // open() maps it again on next use

                } catch (Throwable t) {
                    // never let housekeeping be the thing that fails
                }
            }
        }).start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        ui.removeCallbacks(tick);
        status.stop();
        if (!stack.isEmpty()) current().onHide();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Crash.noteCleanExit(this);
        for (int i = stack.size() - 1; i >= 0; i--) stack.get(i).onHide();
        if (bt != null) bt.stop();
        if (root != null) root.close();
        if (musicBound) {
            // The binding belongs to the activity, not to the music screen:
            // a screen that gets popped while the music plays on has nowhere
            // left to release it from, and an unreleased ServiceConnection is
            // a leak the framework will name us for.
            try { unbindService(musicConn); } catch (Exception e) { /* not bound */ }
            musicBound = false;
        }
    }

    // ---------------------------------------------------------------- shared services

    /** One Bluetooth helper for the whole app: the Bluetooth screen pairs with
     *  it, the music screen reads which headphones are connected from it, and
     *  the terminal asks it whether a keyboard is attached. */
    public BtHelper bt() {
        if (bt == null) {
            bt = new BtHelper(this);
            // Started here rather than by the Bluetooth screen: the music
            // screen asks which headphones are connected, and it should not
            // have to have visited Bluetooth first to get an answer.
            bt.start();
        }
        return bt;
    }

    /**
     * The music service, started on first use and bound for the life of the
     * activity. Returns null until the binding lands, which is a frame or two
     * after the first call.
     */
    public MusicService music() {
        if (!musicBound) {
            musicBound = true;
            Intent i = new Intent(this, MusicService.class);
            startService(i);
            bindService(i, musicConn, BIND_AUTO_CREATE);
        }
        return music;
    }

    /** Stop playback and let the service go. Rebinds on the next music(). */
    public void stopMusic() {
        if (music != null) music.pause();
        if (musicBound) {
            try { unbindService(musicConn); } catch (Exception e) { /* not bound */ }
            musicBound = false;
        }
        music = null;
        stopService(new Intent(this, MusicService.class));
    }

    private final ServiceConnection musicConn = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder binder) {
            music = ((MusicService.LocalBinder) binder).get();
            if (!stack.isEmpty() && current() instanceof MusicScreen) {
                ((MusicScreen) current()).onServiceReady();
            }
        }
        public void onServiceDisconnected(ComponentName n) { music = null; }
    };

    /** One shell, kept open, so cd and exported variables survive between
     *  commands and the call screen can borrow it to press ENDCALL. */
    public RootShell root() {
        if (root == null) root = new RootShell();
        return root;
    }

    public SharedPreferences prefs() { return prefs; }
    public AudioManager audio() { return audio; }
    public boolean twoButtons() { return twoButtons; }

    public boolean keepAwake() { return keepAwake; }

    public void setKeepAwake(boolean on) {
        keepAwake = on;
        prefs.edit().putBoolean("keepAwake", on).commit();
        applyKeepAwake();
    }

    private void applyKeepAwake() {
        if (keepAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ---------------------------------------------------------------- ui

    private void buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(6, 6, 6, 4);

        status = new StatusBar(this);
        body = new FrameLayout(this);
        vHint = Ui.text(this, Ui.HINT_PX, Ui.FAINT, false);
        vHint.setGravity(Gravity.CENTER_HORIZONTAL);

        root.addView(status.view(), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        // No LayoutParams argument: the spacer carries its own fixed height and
        // handing it a fresh set here would replace that with WRAP_CONTENT.
        root.addView(Ui.spacer(this, 3));
        root.addView(body, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(vHint, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));

        setContentView(root);
    }

    /** The once-a-second retick: the clock, and whatever the screen is showing
     *  that moves -- track position, call duration, scan progress. */
    private final Runnable tick = new Runnable() {
        public void run() {
            status.render();
            if (!stack.isEmpty()) current().tick();
            ui.postDelayed(this, 1000);
        }
    };

    private String shownHint = null;

    /** The hint line, set only when it differs. Like the status bar, this is
     *  called once a second and nearly always has the same thing to say, and
     *  setText measures and lays out regardless. */
    public void renderHint() {
        if (stack.isEmpty()) return;
        String h = current().hint();
        if (h == null) h = "";
        if (!h.equals(shownHint)) {
            vHint.setText(h);
            shownHint = h;
        }
    }

    public StatusBar statusBar() { return status; }

    /** A one-shot line in place of the hint, for "Saved", "No music", and
     *  other things not worth a screen. */
    public void toast(String s) {
        vHint.setText(s);
        ui.removeCallbacks(clearToast);
        ui.postDelayed(clearToast, 2500);
    }

    private final Runnable clearToast = new Runnable() {
        public void run() { renderHint(); }
    };

    // ---------------------------------------------------------------- screen stack

    public Screen current() { return stack.get(stack.size() - 1); }

    public void push(Screen s) {
        if (!stack.isEmpty()) current().onHide();
        s.attach(this);
        stack.add(s);
        show(s);
        // So a kill can say what was open. The map is both the heaviest screen
        // and the one being asked about, and a process that is gone cannot
        // report anything about itself afterwards.
        Crash.noteScreen(this, s.getClass().getSimpleName());
    }

    /** Leave the current screen. The launcher is the floor: backing out of it
     *  opens its menu instead of exiting, so the app cannot be left by
     *  accident. Only the menu's Exit really finishes. */
    public void pop() {
        if (stack.size() <= 1) {
            push(new SystemMenuScreen());
            return;
        }
        Screen gone = stack.remove(stack.size() - 1);
        gone.onHide();
        show(current());        // show() delivers onShow when we are started
    }

    public void popToRoot() {
        while (stack.size() > 1) {
            Screen gone = stack.remove(stack.size() - 1);
            gone.onHide();
        }
        show(current());
    }

    private void show(Screen s) {
        body.removeAllViews();
        body.addView(s.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (started) s.onShow();
        renderHint();
    }

    /** Really leave: used only by the system menu's Exit. */
    public void quit() {
        finish();
    }

    // ---------------------------------------------------------------- keys

    private boolean isButtonA(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
    }

    /** The watch's second key -- the former power key, remapped to DPAD_DOWN.
     *  Deliberately not the volume keys: those belong to the headphones' own
     *  volume buttons over AVRCP. */
    private boolean isButtonB(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return false;
        }
    }

    private boolean isVolumeKey(int k) {
        return k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    /**
     * Is this event from a real keyboard rather than from the watch's own two
     * buttons? The synthetic BACK carries deviceId -1, the gpio keys report a
     * non-alphabetic keyboard type, and a paired HID keyboard reports an
     * alphabetic one. The name check is belt and braces for a build that
     * mislabels its own keypad.
     */
    private boolean fromKeyboard(KeyEvent e) {
        int id = e.getDeviceId();
        if (id <= 0) return false;
        InputDevice d = InputDevice.getDevice(id);
        if (d == null) return false;
        if (d.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false;
        if ((d.getSources() & InputDevice.SOURCE_KEYBOARD) == 0) return false;
        String n = d.getName();
        if (n == null) return true;
        n = n.toLowerCase();
        return !(n.contains("gpio-keys") || n.contains("sprd-keypad")
                || n.contains("headset") || n.contains("avrcp"));
    }

    /** True when something that can actually type is attached. The terminal
     *  says so on screen, and the Bluetooth screen offers to pair one. */
    public boolean keyboardAttached() {
        int[] ids = InputDevice.getDeviceIds();
        for (int i = 0; i < ids.length; i++) {
            InputDevice d = InputDevice.getDevice(ids[i]);
            if (d == null) continue;
            if (d.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) continue;
            if ((d.getSources() & InputDevice.SOURCE_KEYBOARD) == 0) continue;
            String n = d.getName();
            if (n == null) return true;
            n = n.toLowerCase();
            if (!(n.contains("gpio-keys") || n.contains("sprd-keypad"))) return true;
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (fromKeyboard(e)) {
            if (!stack.isEmpty() && current().onKeyboard(e)) return true;
            // Screens that ignore a key still get the standard mapping, so a
            // keyboard drives the whole UI and not only the terminal.
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                switch (e.getKeyCode()) {
                    case KeyEvent.KEYCODE_ESCAPE:
                    case KeyEvent.KEYCODE_BACK:
                        pop();
                        return true;
                    default:
                        break;
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /*
     * Only watch buttons reach onKeyDown/onKeyUp; keyboard events are taken in
     * dispatchKeyEvent above.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Volume from the headphones (AVRCP) or anywhere else, on every screen.
        // Repeats are honoured so holding the bud button ramps.
        if (isVolumeKey(keyCode)) { bumpVolume(keyCode); return true; }

        if (keyCode == KeyEvent.KEYCODE_BACK) return true;      // acted on in onKeyUp

        if (isButtonA(keyCode)) {
            // Auto-repeats are the only part of the hold the app is allowed to
            // see, so their count is the length of the press.
            if (event.getRepeatCount() > holdRepeats) holdRepeats = event.getRepeatCount();
            return true;
        }
        if (!isButtonB(keyCode)) return super.onKeyDown(keyCode, event);
        if (event.getRepeatCount() > 0) return true;

        downKey = keyCode;
        longFired = false;
        cancel(longTask);
        longTask = new Runnable() {
            public void run() {
                longFired = true;
                act(BTN_B, HOLD);
            }
        };
        ui.postDelayed(longTask, LONG_MS);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isVolumeKey(keyCode)) return true;                  // handled on the way down

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            int reps = holdRepeats;
            holdRepeats = 0;
            int kind = (reps == 0) ? TAP : (reps < XLONG_REPEATS ? HOLD : XHOLD);
            act(BTN_A, kind);
            return true;
        }
        if (isButtonA(keyCode)) return true;                    // BACK is authoritative

        if (!isButtonB(keyCode)) return super.onKeyUp(keyCode, event);
        cancel(longTask);
        if (longFired || downKey != keyCode) { downKey = -1; return true; }
        downKey = -1;

        if (!twoButtons) {                           // a real second key exists
            twoButtons = true;
            prefs.edit().putBoolean("twoButtons", true).commit();
            renderHint();
        }
        act(BTN_B, TAP);
        return true;
    }

    private void cancel(Runnable r) { if (r != null) ui.removeCallbacks(r); }

    /** Hand the gesture to the screen; back out if it does not want it.
     *
     *  Any hold on button A is a hold, however long. People hold a watch
     *  button for five or six seconds, and an earlier build of the player that
     *  treated a longer hold as a separate gesture just meant the menu could
     *  never be reached in practice. */
    private void act(int button, int kind) {
        if (stack.isEmpty()) return;
        if (current().onGesture(button, kind)) return;
        if (button == BTN_A && (kind == HOLD || kind == XHOLD)) pop();
    }

    /** Nudge STREAM_MUSIC. Flag 0 suppresses the stock volume panel, which
     *  does not fit a 240px screen. */
    private void bumpVolume(int keyCode) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                keyCode == KeyEvent.KEYCODE_VOLUME_UP
                        ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        if (!stack.isEmpty() && current() instanceof MusicScreen) {
            ((MusicScreen) current()).flashVolume();
        }
    }

    // ---------------------------------------------------------------- pointer

    /*
     * A mouse or trackball drives the same vocabulary the two buttons do,
     * rather than a cursor and hit-testing.
     *
     * That is not laziness about pointers: this is a 240x240 screen whose
     * every screen is a list moved by an index, and a cursor would need every
     * row to become a hit-testable target for the sake of pointing at
     * something a wheel can already select. Mapping movement onto button B
     * and a click onto button A means the launcher, the player, the camera,
     * the terminal and the call screen all accept a pointer without one line
     * changing in any of them.
     *
     *   move / scroll     step the selection, the way B does
     *   left click        select, the way a tap on A does
     *   right click       back out, the way a hold on A does
     */

    /** Movement, in whatever unit the device speaks, accumulated until it is
     *  worth a step. Sub-threshold movement is kept, not discarded, so slow
     *  travel still gets there. */
    private void pointerMove(float delta, float step) {
        if (stack.isEmpty()) return;
        pointerAccum += delta;
        while (pointerAccum >= step) {
            pointerAccum -= step;
            act(BTN_B, TAP);              // down
        }
        while (pointerAccum <= -step) {
            pointerAccum += step;
            act(BTN_B, HOLD);             // up
        }
    }

    /** Hover and scroll from a mouse. */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            if (e.getAction() == MotionEvent.ACTION_SCROLL) {
                // A wheel notch is one step, and up on the wheel means up the
                // list, so the sign is inverted.
                float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                pointerMove(-v * MOUSE_STEP_PX, MOUSE_STEP_PX);
                return true;
            }
            float y = e.getY();
            if (!Float.isNaN(lastHoverY)) pointerMove(y - lastHoverY, MOUSE_STEP_PX);
            lastHoverY = y;
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    /** A trackball reports relative movement and its own button. */
    @Override
    public boolean onTrackballEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            act(BTN_A, TAP);
            return true;
        }
        pointerMove(e.getY(), BALL_STEP);
        return true;
    }

    /** Mouse buttons arrive as touch events carrying SOURCE_MOUSE. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_MOUSE) != InputDevice.SOURCE_MOUSE) {
            return super.dispatchTouchEvent(e);
        }
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            // getButtonState reports what is held; a secondary button backs
            // out, anything else selects.
            boolean secondary =
                    (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;
            act(BTN_A, secondary ? HOLD : TAP);
        }
        return true;
    }

    /** True when something that can point is attached. Shown on the About
     *  screen beside the keyboard, since "paired" and "connected" are
     *  different things and only the second one moves anything. */
    public boolean pointerAttached() {
        int[] ids = InputDevice.getDeviceIds();
        for (int i = 0; i < ids.length; i++) {
            InputDevice d = InputDevice.getDevice(ids[i]);
            if (d == null) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) return true;
            if ((s & InputDevice.SOURCE_TRACKBALL) == InputDevice.SOURCE_TRACKBALL) return true;
        }
        return false;
    }

    /** Long-press of the main key arrives here as BACK when nothing consumed
     *  it. Leaving the app is deliberate only, via the system menu's Exit. */
    @Override
    public void onBackPressed() { pop(); }
}
