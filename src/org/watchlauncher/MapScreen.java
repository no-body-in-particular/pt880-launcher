package org.watchlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.io.File;
import java.util.List;

/**
 * Where you are, on a map, with the way to somewhere else.
 *
 * The base is 4-bit greyscale raster tiles from the server, cached on the card
 * so the map works with no network at all. The route is drawn from vectors
 * over the top, because it has to stay sharp and because a line in a picture
 * cannot be followed.
 *
 * With no destination set it is simply a map with a dot on it, which is the
 * common case and wants no ceremony. With one, it draws the route, counts down
 * to the next turn, and says the turn out loud through whatever the music is
 * playing on.
 *
 * <h3>Position</h3>
 *
 * Every ten seconds. Continuous GNSS is a few hours of battery against days on
 * the tracker's own ten-minute cycle, so it runs only while this screen is up
 * and stops the moment it is left.
 *
 * There is no compass on this watch, so the map is drawn north-up and the
 * heading arrow comes from course over ground. That is honest: it knows which
 * way you are moving and cannot know which way you are facing, and a map that
 * rotated on a guess would point confidently wrong every time you stopped.
 */
public class MapScreen extends Screen implements LocationListener {

    private static final int ZOOM = 15;

    /**
     * How much ground the screen shows, without a second set of tiles.
     *
     * Only one zoom level is ever downloaded - COUNTRY_ZOOM and DETAIL_ZOOM
     * are both 15 - so zooming cannot mean fetching coarser tiles, because
     * they are not on the card and will not be over a tunnel. It means drawing
     * the tiles that are there at a different size: half size shows four times
     * the area at the same detail, double magnifies it for reading a junction.
     *
     * Costs nothing extra to draw. The visible tile count goes up as the scale
     * goes down, but each tile is drawn correspondingly smaller, so the number
     * of destination pixels stays about one screen either way.
     */
    private static final float[] SCALES = { 0.5f, 1f, 2f };
    private static final String[] SCALE_NAME = { "wide", "", "close" };
    private int scaleAt = 1;
    private float scale() { return SCALES[scaleAt]; }
    private static final long FIX_MS = 10000;

    private MapView view;
    private MapTiles tiles;
    private Speech speech;
    private ServerFix server;
    private LocationManager locations;
    private final Handler ui = new Handler();

    private String country = null;
    /** The country's bounds, so another one is only ever looked up when the
     *  position actually leaves this one. Crossing a border should cost one
     *  request; standing still should cost none. */
    private double cMinX, cMinY, cMaxX, cMaxY;
    private boolean countryKnown = false;

    private Route route;
    private Destination target;

    private double lat = Double.NaN, lon = Double.NaN;
    private float bearing = -1, speedMs = -1;
    private long fixAt = 0;

    /**
     * Speed and arrival, measured off the drive rather than taken from the
     * plan.
     *
     * The route's own cost model is an average for a class of road, which is
     * the right thing to choose a road with and the wrong thing to show next
     * to a speedometer. These two numbers come from consecutive fixes, so
     * they say what is happening now, and the second line of the bottom band
     * is recomputed on each fix rather than each frame - working out how far
     * along a sixty-thousand-point line you are is not a per-frame job.
     */
    private final Drive drive = new Drive();
    private String driveLine = null;

    /**
     * The next-turn line, worked out once a fix rather than once a frame.
     *
     * screenInstruction walks every turn on the route measuring the distance
     * to each, and it was being called twice per frame - once to draw the
     * text and once to decide how tall the band is. On a route with two
     * hundred and fifty turns that is five hundred haversines a frame to
     * answer a question whose answer changes when the watch moves, which is
     * every ten seconds.
     */
    private String turnLine = null;

    /**
     * Speed cameras and motorway exits, if the card has them.
     *
     * Kept out of the graph because they change nothing about which way to go
     * - they are what the watch says while going that way - so a card without
     * the file just gets no warnings and everything else carries on.
     */
    private final Alerts alerts = Alerts.shared();
    private volatile String alertsFor = null;

    /** Cameras already spoken for, so each is announced once rather than on
     *  every fix for the four hundred metres it stays in range. */
    private final java.util.HashSet<Long> saidCamera = new java.util.HashSet<Long>();
    /** Metres still to drive along the route, or -1. Kept so the hint line
     *  and the arrival estimate quote the same journey. */
    private double remainingM = -1;
    private String note = "";
    private boolean listening = false;
    private boolean arrived = false;

    /** True while the position on screen came from the tracker server rather
     *  than from this watch's own receiver. It is a real position, resolved
     *  from wifi and cell, but it can be hundreds of metres out - enough to
     *  pick the right country and centre the map, not enough to navigate by,
     *  so the screen says so. */
    private boolean approximate = false;
    /**
     * When the tracker was last asked where we are.
     *
     * It used to be asked once and never again, which on this watch means
     * once ever: the gps provider is not in Enabled Providers, so a real fix
     * is rare and the server is in practice the only source of position. The
     * map therefore showed wherever you were when you opened it, for as long
     * as you left it open.
     *
     * The tracker has a new position every few minutes, so asking once a
     * minute costs a small request and keeps the map somewhere near the
     * truth. A real gps fix stops the asking, because it is better.
     */
    private long askedServerAt = 0;

    /**
     * How often to re-ask while the only position is the server's.
     *
     * Backed off rather than fixed. The server's answer is resolved from the
     * wifi and cell readings the firmware uploads, so it changes on the order
     * of minutes at best and not at all while the watch is sitting still -
     * asking every minute for the rest of the day answers the same question
     * over and over. It starts at a minute so that opening the map still
     * settles quickly, doubles each time the answer has not moved, and stops
     * lengthening at five.
     *
     * Reset whenever the position does move, or the map is opened again.
     */
    private static final long RESEED_MS = 60000;
    private static final long RESEED_MAX_MS = 300000;
    private long reseedEvery = RESEED_MS;
    private double lastSeedLat = Double.NaN, lastSeedLon;

    /** Why there is nothing on screen. A blank map with no explanation is the
     *  least useful thing this could show, and every reason it can be blank
     *  has a different fix. */
    private String why = "";

    @Override
    public String title() { return "Map"; }

    @Override
    protected View build() {
        tiles = MapTiles.of(shell);
        server = new ServerFix(shell);
        locations = (LocationManager) shell.getSystemService(Context.LOCATION_SERVICE);
        view = new MapView(shell);

        LinearLayout col = Ui.column(shell);
        col.addView(view, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }

    // ---------------------------------------------------------------- life

    @Override
    public void onShow() {
        releaseNavigation();      // the screen is on; it holds itself up
        // Opening the map is a reason to ask now, whatever the backoff had
        // wound itself out to while it sat in the background.
        reseedEvery = RESEED_MS;
        askedServerAt = 0;
        voice();                       // start it warming; it takes a moment
        loadDestination();
        loadRoute();
        startFixes();
        seedFromLastFix();
        catchUpFromLastFix();
        seedFromServer();

        // Both lines and the tiles around us went unmaintained while the screen was off, on
        // purpose. Catch them up here, and here rather than at the top of this method, which
        // is where they used to be: the position and the route are only brought up to date by
        // the calls above, so a line built before them was built from where the watch was when
        // the screen went off. The map redrew from the new position and the text beside it
        // still described the old one, which is the half of this that reads as "the line does
        // not update" - the distance and the next turn stayed put while the map moved.
        updateDriveLine();
        updateTurnLine();
        if (!Double.isNaN(lat)) prefetchAround();
        view.invalidate();
    }

    @Override
    /**
     * The screen going off is not the end of a drive.
     *
     * This used to stop the fixes and shut the voice engine down, and it is
     * called whenever the map is covered - including by the screen turning
     * off, which on a watch is thirty seconds after you last touched it. So
     * navigation ended a few seconds into every drive, and the only way to be
     * guided anywhere was to keep the screen lit the whole way, which is the
     * most expensive thing this watch can do.
     *
     * While a route is being followed the fixes and the voice stay up and a
     * partial wake lock keeps the processor available to handle them. That is
     * a fraction of the cost of the backlight, and it is what the feature is
     * for: the turn is spoken whether or not anybody is looking.
     *
     * Everything else about being hidden still applies - no drawing, no
     * prefetching for a screen nobody can see - and the moment the route is
     * done, or the map is left for something else, it all comes down.
     */
    public void onHide() {
        ui.removeCallbacks(saySoon);
        soon = null;
        // The screen going dark never ends a drive, whatever the switch says. Being covered by
        // something else is the case the switch is about, and shell.showing() tells them apart:
        // the shell clears it before hiding anything, so false is the watch asleep and true is
        // another screen in front of this one.
        if (navigating() && (!shell.showing() || backgroundNav())) {
            holdForNavigation();
            return;
        }
        stopFixes();
        releaseNavigation();
        // The engine is shut down rather than kept warm: it holds an audio
        // focus path open, and the music player is the thing that should have
        // it when navigation is not running.
        if (speech != null) speech.stop();
    }

    /** A route in hand that has not been finished. */
    private boolean navigating() {
        return route != null && !arrived;
    }

    /** True while the map's own menu is in front of it. */
    private boolean menuOpen;

    void setMenuOpen(boolean open) {
        menuOpen = open;
    }

    private static final String PREF_BACKGROUND_NAV = "map.backgroundNav";

    /**
     * Whether a route keeps being followed while another screen or the menu is in front.
     *
     * Not whether it survives the screen going off - that it always does, and this switch has
     * no say in it. The screen goes dark thirty seconds after you last touched it, so guidance
     * that ends there is guidance you have to hold the screen lit for, which is the most
     * expensive thing this watch can do and not a setting anybody wants.
     *
     * What is a real choice is whether a route goes on running while you are reading a menu or
     * using something else: that costs a wake lock and the receiver for as long as the route
     * lasts, and someone who set a destination to look at rather than to drive should not pay
     * for it. On by default all the same, because leaving the map to check something mid-drive
     * is the ordinary case.
     */
    boolean backgroundNav() {
        try {
            return shell.prefs().getBoolean(PREF_BACKGROUND_NAV, true);
        } catch (Throwable t) {
            return true;
        }
    }

    void setBackgroundNav(boolean on) {
        try {
            shell.prefs().edit().putBoolean(PREF_BACKGROUND_NAV, on).commit();
        } catch (Throwable t) { /* the default stands */ }
        // Take effect now rather than at the next hide: turning it off while the map is covered
        // and a route is running should stop the receiver, not wait for the drive to end.
        if (!on && menuOpen) {
            ui.removeCallbacks(saySoon);
            soon = null;
            releaseNavigation();
            stopFixes();
        }
    }

    /**
     * Say something the wearer asked for, whatever else is going on.
     *
     * {@link #announce} is for the route talking by itself; this is for a button being pressed.
     */
    private void sayNow(String s) {
        voice().say(s);
    }

    /**
     * Say something the route decided to say.
     *
     * Dropped rather than queued while the map's menu is open. A turn instruction spoken over
     * somebody reading a settings list is an interruption they did not ask for and cannot stop,
     * and by the time they close the menu it is stale anyway - the next fix produces a better
     * one. Arrival and recalculation go the same way for the same reason.
     */
    private void announce(String s) {
        if (s == null) return;
        if (menuOpen) {
            Log.i("watchmap", "menu is open; not speaking \"" + s + "\"");
            return;
        }
        voice().say(s);
    }

    private android.os.PowerManager.WakeLock navLock;

    /**
     * Keep the processor available for fixes while the screen is off.
     *
     * Timed rather than open ended, and renewed on each fix: a wake lock left
     * holding is a flat battery by morning, and the failure mode of the
     * timeout expiring is a missed instruction, which the next fix repairs.
     */
    private void holdForNavigation() {
        try {
            if (navLock == null) {
                android.os.PowerManager pm = (android.os.PowerManager)
                        shell.getSystemService(android.content.Context.POWER_SERVICE);
                if (pm == null) return;
                navLock = pm.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK, "watchlauncher.nav");
                navLock.setReferenceCounted(false);
            }
            navLock.acquire(NAV_HOLD_MS);
            Log.i("watchmap", "screen off with a route: still navigating");
        } catch (Throwable t) {
            Log.w("watchmap", "nav wake lock: " + t);
        }
    }

    private void releaseNavigation() {
        try {
            if (navLock != null && navLock.isHeld()) navLock.release();
        } catch (Throwable t) { /* already gone */ }
    }

    /** Long enough to cover several fixes, short enough that a bug cannot
     *  hold the processor up all night. */
    private static final long NAV_HOLD_MS = 5 * 60 * 1000;

    /**
     * Redraw only when something has changed.
     *
     * The map used to repaint every second regardless. Nothing on it moves
     * between fixes - which arrive every ten seconds - so nine of every ten
     * repaints were redrawing an identical screen, and each one walks the
     * visible tiles and the whole route polyline.
     */
    @Override
    public void tick() {
        // Keep asking the tracker while it is the only thing that knows.
        if (Double.isNaN(lat) || approximate) seedFromServer();

        // A server position is minutes old by nature, so warning that it is
        // thirty seconds old would mean warning permanently - and a warning
        // that is always on is one nobody reads.
        long age = approximate ? 900000 : 30000;
        boolean stale = (fixAt > 0) && (System.currentTimeMillis() - fixAt) > age;
        if (dirty || stale != wasStale) {
            wasStale = stale;
            dirty = false;
            view.invalidate();
        }
        shell.renderHint();
    }

    private boolean dirty = true;
    private boolean wasStale = false;

    /** Something worth looking at again has changed. */
    private void changed() {
        dirty = true;
        if (view != null) view.invalidate();
    }

    /**
     * The places from destination.txt, read once and kept.
     *
     * The menu used to call Destination.load() while building its rows, which
     * opens and parses the whole file - and the menu redraws once a second
     * while a download runs. That is a file read per second on the drawing
     * thread for a file that changes only when someone pushes a new one, and
     * with a long list it is enough to make the watch stutter.
     */
    private List<Destination> destinations = new java.util.ArrayList<Destination>();

    private void loadDestination() {
        List<Destination> all = Destination.load();
        destinations = all;
        // Keep the chosen one if it is still in the file, so reloading does
        // not silently send you somewhere else.
        if (target != null) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).name.equals(target.name)) {
                    target = all.get(i);
                    return;
                }
            }
        }
        target = all.isEmpty() ? null : all.get(0);
    }

    /** The list as last read. Reloaded by Reload destination, not by drawing. */
    List<Destination> destinations() { return destinations; }

    /**
     * The route left over from last time, read off the card.
     *
     * On a background thread, and its tables built there too. A long route is
     * four thousand points; parsing it and measuring it is not work to do
     * while the screen is waiting to be drawn for the first time.
     */
    private void loadRoute() {
        new Thread(new Runnable() {
            public void run() {
                File f = new File(MapTiles.DIR + "/route.bin");
                final Route r = f.isFile() ? Route.read(f) : null;
                if (r != null) r.prepare();
                ui.post(new Runnable() {
                    public void run() {
                        // Only if nothing better arrived meanwhile: a route
                        // just asked for beats one left on the card.
                        if (route == null) {
                            route = r;
                            if (r != null) r.signs(signs);
                            changed();
                        }
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- fixes

    private void startFixes() {
        if (locations == null || listening) return;

        /*
         * Switch the receiver on before asking it for anything.
         *
         * The watch ships with gps left out of location_providers_allowed, and
         * the loop below only subscribes to providers that are enabled - so on
         * a stock watch the map subscribed to nothing that could see a
         * satellite, and every position it showed came from the tracker
         * server. That is resolved from wifi and cell, it arrives when the
         * server has one rather than when the watch asks, and it is the whole
         * of "the fixes take a long time to start and then are sporadic".
         *
         * On a thread: it is a root shell, and the first frame should not wait
         * for it. When it succeeds the framework calls onProviderEnabled and
         * the subscription is redone there.
         */
        // Whatever else happens, hand the receiver the time and a rough
        // position: a cold start with neither is a minute or two of searching
        // the sky, and this watch has both already.
        Gps.assist(locations);

        // Only if it has been asked for. Not for the health sensor's sake -
        // that turned out to wedge just as often with the provider off - but
        // because the receiver costs battery and it is the wearer's call.
        if (Gps.wanted(shell) && Gps.off(locations)) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        final boolean on = Gps.enable(shell, locations);
                        if (!on) {
                            // Worth saying. Silently falling back to the
                            // server's position is what made this look like a
                            // slow receiver rather than a switch left off.
                            ui.post(new Runnable() {
                                public void run() {
                                    if (note == null || note.length() == 0) {
                                        note = "gps needs the root helper";
                                    }
                                    changed();
                                }
                            });
                        }
                    } catch (Throwable t) {
                        Log.w("watchmap", "enabling gps: " + t);
                    }
                }
            }).start();
        }

        listening = true;
        boolean any = false;
        try {
            List<String> ps = locations.getAllProviders();
            for (int i = 0; i < ps.size(); i++) {
                String p = ps.get(i);
                try {
                    if (!locations.isProviderEnabled(p)) continue;
                    locations.requestLocationUpdates(p, FIX_MS, 0f, this);
                    any = true;
                } catch (Exception e) { /* not ours to use */ }
            }
        } catch (Exception e) { /* ignore */ }
        if (!any) {
            note = "no location provider enabled";
            Log.w("watchmap", "no location provider is enabled; only the server seed will work");

        } else if (Gps.off(locations) && !Gps.wanted(shell)) {
            //Network positioning counts as a provider, so the branch above stays quiet and
            //the map just looks slow to find you - which is how "the gps is slow" gets
            //reported for a receiver that was never switched on. Cell and wifi
            //trilateration is hundreds of metres on a good day and barely updates while
            //moving, so it is not a fix that improves with patience.
            note = "gps off - Sports, hold A";
            Log.i("watchmap", "gps provider is off; running on network position only");
        }
    }

    private void stopFixes() {
        if (!listening) return;
        listening = false;
        try { locations.removeUpdates(this); } catch (Exception e) { /* ignore */ }
    }

    /**
     * When the fix being shown was taken, on the monotonic clock, or 0 if unknown.
     *
     * Not {@link #fixAt}, which is when it was received. Only this one can be compared with a
     * {@link Location}'s own stamp.
     */
    private long fixTakenEt;

    /** When a fix was taken, on the monotonic clock, or 0 if it cannot be told. */
    private static long etOf(Location l) {
        if (l == null) return 0;
        try {
            long ns = l.getElapsedRealtimeNanos();
            if (ns > 0) return ns / 1000000L;
        } catch (Throwable ignored) { /* pre-17 shape */ }
        return 0;
    }

    /**
     * Catch up on whatever arrived while the map was not listening.
     *
     * Without a route, onHide() drops the subscription - so the position freezes while the
     * screen is off, and the map used to come back still showing wherever the watch was when
     * the screen went off. It stayed wrong until a fresh fix arrived, which outdoors is
     * seconds and indoors can be never.
     *
     * Nothing extra has to be switched on for this. The tracker keeps taking fixes on its own
     * schedule the whole time the screen is off, and those land in getLastKnownLocation, so
     * the newer position is usually sitting there already.
     *
     * Compared on the monotonic clock, for the reason {@code TrackerSources.ageOf} sets out at
     * length: a fix's getTime() is satellite time and this watch sets its clock from the
     * network, so wall-clock comparisons between the two have already been seen to come out
     * negative. A last known fix whose stamp cannot be read is left alone rather than guessed
     * at - the subscription is running again by now, so the cost of skipping it is one fix.
     */
    private void catchUpFromLastFix() {
        if (locations == null) return;
        Location best = null;
        long bestEt = fixTakenEt;
        try {
            List<String> ps = locations.getAllProviders();
            for (int i = 0; i < ps.size(); i++) {
                long et = etOf(locations.getLastKnownLocation(ps.get(i)));
                if (et > bestEt) {
                    best = locations.getLastKnownLocation(ps.get(i));
                    bestEt = et;
                }
            }
        } catch (Exception e) { /* nothing known */ }
        if (best != null) {
            Log.i("watchmap", "catching up from a fix taken while the screen was off");
            take(best);
        }
    }

    /** Something to draw before the first fix arrives, rather than a blank. */
    private void seedFromLastFix() {
        if (locations == null || !Double.isNaN(lat)) return;
        try {
            List<String> ps = locations.getAllProviders();
            for (int i = 0; i < ps.size(); i++) {
                Location l = locations.getLastKnownLocation(ps.get(i));
                if (l != null) { take(l); return; }
            }
        } catch (Exception e) { /* nothing known */ }
    }

    /**
     * The cold start.
     *
     * A GNSS receiver takes a minute or two to find itself from cold, and this
     * watch keeps its own off most of the time - so on opening the map there
     * is usually nothing to draw and no way to know which country to fetch.
     * The tracker server already holds a position resolved from the wifi and
     * cell readings the firmware uploads, which is the same source the sports
     * screen uses. Good enough to pick a map and centre it; the first real fix
     * replaces it.
     */
    private void seedFromServer() {
        // A real fix beats the server's, so stop asking once we have one.
        if (!Double.isNaN(lat) && !approximate) return;
        long now = System.currentTimeMillis();
        if (now - askedServerAt < reseedEvery) return;
        askedServerAt = now;
        new Thread(new Runnable() {
            public void run() {
                server.refresh();
                final double la = server.lat(), lo = server.lon();
                final long at = server.at();
                final String problem = server.problem();
                if (at == 0 || (la == 0 && lo == 0)) {
                    ui.post(new Runnable() {
                        public void run() {
                            why = (problem == null) ? "no position from the server"
                                                    : problem;
                            Log.w("watchmap", "no seed: " + why);
                            // Without a position there is still a map to pick,
                            // if the server offers only one.
                            adoptOnlyCountry();
                            view.invalidate();
                        }
                    });
                    return;
                }
                ui.post(new Runnable() {
                    public void run() {
                        // A real fix that arrived while we were asking wins.
                        if (!Double.isNaN(lat) && !approximate) return;
                        why = "";
                        lat = la;
                        lon = lo;
                        fixAt = at;
                        approximate = true;
                        bearing = -1;
                        speedMs = -1;
                        // Moved: worth asking briskly again. Standing still:
                        // ask less often, because the answer is not changing.
                        if (!Double.isNaN(lastSeedLat)
                                && Geo.metresFlat(lastSeedLat, lastSeedLon, la, lo) < 50) {
                            slowDownReseed();
                        } else {
                            reseedEvery = RESEED_MS;
                        }
                        lastSeedLat = la;
                        lastSeedLon = lo;
                        if (!countryKnown) findCountry();
                        prefetchAround();
                        changed();
                    }
                });
            }
        }).start();
    }

    public void onLocationChanged(Location l) { take(l); }
    /**
     * A provider that has just come up is one to start listening to.
     *
     * startFixes() subscribes to whatever is enabled at the moment it runs, so
     * without this a receiver switched on after the map opened was never
     * subscribed to at all - the map went on using the server's position until
     * the screen was closed and reopened.
     */
    public void onProviderEnabled(String p) {
        Log.i("watchmap", "provider up: " + p);
        stopFixes();
        startFixes();          // which injects the assistance data again
    }

    public void onProviderDisabled(String p) {
        Log.i("watchmap", "provider down: " + p);
    }
    public void onStatusChanged(String p, int s, Bundle b) { }

    private void take(Location l) {
        if (l == null) return;
        Log.i("watchmap", "fix from " + l.getProvider() + ": "
                + l.getLatitude() + "," + l.getLongitude());
        approximate = false;                 // this one is ours
        lat = l.getLatitude();
        lon = l.getLongitude();
        bearing = l.hasBearing() ? l.getBearing() : -1;
        speedMs = l.hasSpeed() ? l.getSpeed() : -1;
        fixAt = System.currentTimeMillis();
        // A fix with no readable stamp counts as taken now: it is the one being shown, and
        // treating it as ancient would let catchUpFromLastFix replace it with something older.
        fixTakenEt = etOf(l);
        if (fixTakenEt == 0) fixTakenEt = android.os.SystemClock.elapsedRealtime();
        drive.fix(fixAt, lat, lon, speedMs);
        // Held for the whole of a background drive, and renewed here so the timeout never runs
        // out mid-route.
        //
        // This used to require navLock != null, which made the renewal depend on onHide having
        // run first - that being the only place the lock was ever created. Every way of
        // reaching a screen-off state without MapScreen.onHide firing therefore navigated with
        // no wake lock at all: the map menu left open (the shell hides only its top screen),
        // another screen pushed on top, or the process restarted mid-drive with the screen
        // already off. Caught in the act during a real drive - screen off, a route running, and
        // "Wake Locks: size=0" - so the processor was free to suspend, the fixes stopped, and
        // with them the map and the voice.
        //
        // Taking it here instead makes the lock a property of navigating rather than of a
        // lifecycle callback that may not come. Still timed, still renewed per fix, so a bug
        // cannot hold the processor up all night.
        if (navigating() && (!shell.showing() || backgroundNav())) holdForNavigation();
        // The watch's own fixes often arrive without a speed, so take the one
        // worked out from the ground covered instead of showing nothing.
        if (speedMs < 0) speedMs = drive.speedMs();

        /*
         * With the screen off, only the parts that make a sound are worth
         * doing.
         *
         * A fix arriving while the map is covered ran the whole lot: format
         * two lines of text, walk the route for the next turn, fetch and
         * decode the nine tiles around the position, and invalidate a view
         * nobody can see. Prefetching is the expensive one - a tile that is
         * missing pulls the block containing it, up to a megabyte and a half
         * over the radio and as much again written to the card - and the most
         * obviously pointless, because the map is not on screen and will be
         * redrawn from wherever the watch is when it comes back.
         *
         * What still has to happen is the driving: the speed estimate, the
         * camera warnings and the turn instructions, none of which anybody
         * needs to be looking at to hear.
         */
        boolean visible = shell.showing();
        if (visible) {
            updateDriveLine();
            updateTurnLine();
        }
        // Not inside follow(): a camera is worth knowing about whether or not
        // the watch happens to be navigating anywhere.
        warnCameras();
        // Cheap unless the drive has left the box that was fetched.
        loadAlerts(country);

        // Only when the position is outside what we already hold.
        if (!countryKnown || lon < cMinX || lon > cMaxX || lat < cMinY || lat > cMaxY) {
            findCountry();
        }
        if (visible) prefetchAround();
        follow();
        if (visible) changed();
    }

    /** Kept either side of an active route when the card fills up. */
    private static final double ROUTE_KEEP_KM = 45;

    /**
     * The parts of the map worth keeping.
     *
     * A corridor along the route if there is one, because that is where you
     * are going; otherwise a box around where you are. Returned empty when
     * there is no position at all, which prune() treats as "do nothing" -
     * deleting the whole map because the gps has not woken up yet would be a
     * poor trade.
     */
    java.util.List<double[]> keepBoxes() {
        java.util.List<double[]> out = new java.util.ArrayList<double[]>();

        Route r = route;
        if (r != null && r.line.size() > 1) {
            // Sampled along the line rather than one box round the lot: a
            // route from one corner of the country to the other would
            // otherwise "keep" everything in between, which is no limit at
            // all.
            double stepKm = ROUTE_KEEP_KM;
            double sinceKm = stepKm;
            double[] prev = null;
            for (int i = 0; i < r.line.size(); i++) {
                double[] p = r.line.get(i);
                if (prev != null) {
                    sinceKm += Route.metresBetween(prev[0], prev[1], p[0], p[1]) / 1000.0;
                }
                if (sinceKm >= stepKm || i == r.line.size() - 1) {
                    out.add(MapTiles.boxAround(p[0], p[1], ROUTE_KEEP_KM));
                    sinceKm = 0;
                }
                prev = p;
            }
        }

        if (!Double.isNaN(lat)) {
            out.add(MapTiles.boxAround(lat, lon, MapDownload.AREA_RADIUS_KM));
        }
        return out;
    }

    /** The road network on the card, and the search over it. Opened lazily:
     *  most of the time the map is being looked at, not navigated. */
    private final RoadGraph graph = RoadGraph.shared();
    private Router router;

    /**
     * Route here, without asking anyone.
     *
     * @return a route, or null if there is no graph for this country or no
     *         way through it - in which case the caller falls back to the
     *         server, which knows about turn restrictions and traffic rules
     *         this graph does not.
     */
    Route routeHere(double fromLat, double fromLon, double toLat, double toLon) {
        String c = country;
        if (c == null) return null;
        if (!graph.open(c)) return null;
        if (router == null) router = new Router(graph);
        int[] path = router.path(fromLat, fromLon, toLat, toLon);
        if (path == null) return null;
        Route r = Route.fromNodes(graph, path);
        // This is called from the routing thread, which is the right place to
        // pay for the route's tables rather than the first fix after it.
        if (r != null) r.prepare();
        return r;
    }

    boolean canRouteOffline() {
        return country != null && RoadGraph.fileFor(country).isFile();
    }

    private int offRouteFixes = 0;
    private boolean rerouting = false;
    private long lastReroute = 0;

    /**
     * Ask for a new route from where we actually are.
     *
     * Missing a turn is the one moment navigation has to do something rather
     * than repeat an instruction for a junction that is now behind you.
     *
     * Rate limited to once every half minute. Being off route is a state, not
     * an event: without a limit, a road running parallel to the route - a
     * service road, the other carriageway - would ask the server for a fresh
     * route every ten seconds for as long as you drove along it.
     */
    private void reroute() {
        if (rerouting) return;
        if (target == null || !tiles.online()) return;
        long now = System.currentTimeMillis();
        if (now - lastReroute < 30000) return;

        rerouting = true;
        lastReroute = now;
        note = "recalculating";
        announce("recalculating");

        final double la = lat, lo = lon;
        final Destination to = target;
        new Thread(new Runnable() {
            public void run() {
                // The graph on the card first. Missing a turn is exactly the
                // moment the network is least likely to help - a tunnel, a
                // cutting, a dead spot - and a route computed here needs
                // none of it.
                Route local = routeHere(la, lo, to.lat, to.lon);
                if (local == null) {
                    File out = new File(MapTiles.DIR + "/route.bin");
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    String url = tiles.base() + "route.php"
                            + "?flat=" + la + "&flon=" + lo
                            + "&tlat=" + to.lat + "&tlon=" + to.lon;
                    if (tiles.download(url, out)) local = Route.read(out);
                    // Off the UI thread while we are still on this one.
                    if (local != null) local.prepare();
                }
                final Route r = local;
                ui.post(new Runnable() {
                    public void run() {
                        rerouting = false;
                        offRouteFixes = 0;
                        if (r == null) {
                            // Keep the old one. A stale route still shows
                            // where the road went, which is more use than a
                            // blank screen with no line on it at all.
                            note = "no new route";
                            changed();
                            return;
                        }
                        setRoute(r);
                        note = "";
                        changed();
                    }
                });
            }
        }).start();
    }

    /** Speak the next turn, notice arrival, notice leaving the route. */
    private void follow() {
        if (route == null) return;
        // Never navigate off a server position: it can be hundreds of metres
        // out, and an instruction spoken at the wrong junction is worse than
        // no instruction at all.
        if (approximate) { note = "waiting for gps"; return; }

        double[] end = route.destination();
        if (end != null) {
            double left = Route.metresBetween(lat, lon, end[0], end[1]);
            if (left <= Route.ARRIVED_M) {
                if (!arrived) {
                    arrived = true;
                    releaseNavigation();          // the drive is over
                    announce("you have arrived");
                    // The route has done its job. Keeping it drawn would leave
                    // a line to somewhere you already are.
                    route = null;
                    note = "arrived";
                }
                return;
            }
        }

        String say = route.instruction(lat, lon, speedMs);
        if (say != null) announce(say);

        scheduleImminent();

        if (route.offRouteMetres(lat, lon) > Route.OFF_ROUTE_M) {
            note = OFF_ROUTE;
            offRouteFixes++;
            // Two fixes, not one. A single bad position - and this watch
            // takes plenty, seeded from the tracker or bounced off a
            // building - would otherwise throw away a good route and ask the
            // server for another one at eighty metres of noise.
            if (offRouteFixes >= 2) reroute();
        } else {
            offRouteFixes = 0;
            /*
             * Only the note this method put there.
             *
             * It used to blank the note outright on every fix that was on
             * route - which is every ten seconds - and that quietly erased
             * everything else that had anything to say: the warning that the
             * map under the route is not on the card, the one that says the
             * receiver could not be switched on, the camera that was just
             * announced. All of them were being set and then wiped before
             * anyone could read them.
             */
            if (OFF_ROUTE.equals(note)) note = "";
        }
    }

    private static final String OFF_ROUTE = "off route";

    // ---------------------------------------------------------------- data

    private boolean lookingUp = false;

    /**
     * Which map covers this position.
     *
     * Asked once, and again only when a fix falls outside the bounds the last
     * answer gave. A country's tiles stay on the card once downloaded, so
     * crossing a border fetches a new map and crossing back finds the old one
     * already there.
     */
    private void findCountry() {
        if (lookingUp) return;
        lookingUp = true;
        final double la = lat, lo = lon;
        new Thread(new Runnable() {
            public void run() {
                String r = null;
                if (tiles.online()) {
                    r = tiles.get(tiles.base() + "country.php?lat=" + la + "&lon=" + lo);
                }
                final String reply = r;
                ui.post(new Runnable() {
                    public void run() {
                        lookingUp = false;
                        if (reply == null) {
                            // Offline: whatever is already on the card for
                            // this area is still the right map to draw.
                            if (country == null) {
                                country = offlineGuess();
                                loadAlerts(country);
                            }
                            view.invalidate();
                            return;
                        }
                        String first = reply.trim().split("\n")[0];
                        if (first.length() == 0 || first.startsWith("none")) return;
                        String[] f = first.split(",");
                        if (f.length < 5) return;
                        country = f[0];
                        Log.i("watchmap", "country = " + country);
                        loadAlerts(country);
                        try {
                            cMinX = Double.parseDouble(f[1]);
                            cMinY = Double.parseDouble(f[2]);
                            cMaxX = Double.parseDouble(f[3]);
                            cMaxY = Double.parseDouble(f[4]);
                            countryKnown = true;
                        } catch (Exception e) { countryKnown = false; }
                        view.invalidate();
                    }
                });
            }
        }).start();
    }

    /**
     * With no position at all, a country still has to be chosen or nothing can
     * be downloaded and the map stays blank for good.
     *
     * If the server offers exactly one, that is the answer - which is the
     * common case here, and turns "country unknown" from a dead end into a map
     * that can at least be filled and looked at.
     */
    private void adoptOnlyCountry() {
        if (country != null || !tiles.online()) return;
        new Thread(new Runnable() {
            public void run() {
                String all = tiles.get(tiles.base() + "country.php");
                if (all == null) return;
                String[] lines = all.trim().split("\n");
                if (lines.length != 1 || lines[0].length() == 0) return;
                final String[] f = lines[0].split(",");
                if (f.length < 5) return;
                ui.post(new Runnable() {
                    public void run() {
                        country = f[0];
                        Log.i("watchmap", "country = " + country);
                        loadAlerts(country);
                        try {
                            cMinX = Double.parseDouble(f[1]);
                            cMinY = Double.parseDouble(f[2]);
                            cMaxX = Double.parseDouble(f[3]);
                            cMaxY = Double.parseDouble(f[4]);
                            countryKnown = true;
                            // Centre on the middle of it, so there is a map to
                            // look at while waiting for a real fix.
                            if (Double.isNaN(lat)) {
                                lat = (cMinY + cMaxY) / 2;
                                lon = (cMinX + cMaxX) / 2;
                                approximate = true;
                                fixAt = System.currentTimeMillis();
                                why = "no fix - showing " + country;
                                prefetchAround();
                            }
                        } catch (Exception e) { /* leave it unknown */ }
                        view.invalidate();
                    }
                });
            }
        }).start();
    }

    /** With no network, the only countries that can be drawn are the ones
     *  already downloaded, so pick whichever of those has tiles. */
    private String offlineGuess() {
        File dir = new File(MapTiles.DIR);
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (int i = 0; i < kids.length; i++) {
            if (kids[i].isDirectory()) return kids[i].getName();
        }
        return null;
    }

    /** Keep the tiles under and just around the position on the card. One
     *  screen's worth is four kilobytes; doing it on every fix means the map
     *  is already there when the next street arrives. */
    private void prefetchAround() {
        if (country == null || !tiles.online()) return;
        final String c = country;
        final int tx = (int) Mercator.xOf(lon, ZOOM);
        final int ty = (int) Mercator.yOf(lat, ZOOM);
        new Thread(new Runnable() {
            public void run() {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        tiles.fetch(c, ZOOM, tx + dx, ty + dy);
                        // Decoded here rather than in onDraw: this thread has
                        // time and the frame does not.
                        tiles.warm(c, ZOOM, tx + dx, ty + dy);
                    }
                }
                keepCardInBounds();
                ui.post(new Runnable() {
                    public void run() { changed(); }
                });
            }
        }).start();
    }

    /**
     * Keep the map inside its limit while simply driving.
     *
     * The limit was only ever applied from the map menu - once by the cleanup
     * item and once at the end of a download - and neither happens on a drive.
     * But prefetchAround runs on every fix, and a prefetch is not one tile: a
     * tile that is missing pulls the whole block containing it, two hundred
     * and fifty six of them, because that is the unit the card stores. So an
     * afternoon of driving through country that was never downloaded fills the
     * card a block at a time with nothing counting, until either the card is
     * full or somebody happens to open the menu.
     *
     * It is cheap to ask: the size is cached for a minute, the check returns
     * immediately while under the limit, and the scan behind it skips itself
     * entirely while a download is running. The interval here is about not
     * walking the tree on every fix, not about the cost of the question.
     *
     * Already on the prefetch thread, which is where a card walk belongs.
     */
    private void keepCardInBounds() {
        long now = System.currentTimeMillis();
        if (now - lastLimitCheck < LIMIT_EVERY_MS) return;
        lastLimitCheck = now;
        try {
            long freed = MapTiles.enforceLimit(keepBoxes());
            if (freed > 0) {
                final String msg = "freed " + MapTiles.mb(freed);
                Log.i("watchmap", "cache over limit while driving; " + msg);
                ui.post(new Runnable() {
                    public void run() { note = msg; changed(); }
                });
            }
        } catch (Throwable t) {
            Log.w("watchmap", "cache limit: " + t);
        }
    }

    private long lastLimitCheck = 0;
    private static final long LIMIT_EVERY_MS = 300000;      // five minutes

    // ---------------------------------------------------------------- keys

    @Override
    public boolean onGesture(int button, int kind) {
        if (button == ShellActivity.BTN_A) {
            if (kind == ShellActivity.TAP) {
                seedFromLastFix();
                view.invalidate();
                return true;
            }
            shell.push(new MapMenuScreen(this));
            return true;
        }
        // B did nothing at all on this screen - it was swallowed and returned
        // true. It steps the zoom, which is the thing you want while driving
        // and could not do: out to see where the next turn is, in to read a
        // junction you are already at.
        if (button == ShellActivity.BTN_B && kind == ShellActivity.TAP) {
            scaleAt = (scaleAt + 1) % SCALES.length;
            String n = SCALE_NAME[scaleAt];
            if (n.length() > 0) shell.toast(n);
            view.rescaled();
            changed();
            return true;
        }
        return true;
    }

    /*
     * The hint line, built only when something in it has changed.
     *
     * It is asked for once a second whether or not the watch has moved, and
     * the answer is a concatenation - a name, a number, a unit - so a
     * stationary watch was allocating the same string sixty times a minute
     * for the pleasure of comparing it with itself.
     */
    private String hintCache = null;
    private double hintLat = Double.NaN, hintLon = Double.NaN;
    private Destination hintTarget = null;
    private String hintNote = null;

    @Override
    public String hint() {
        if (Double.isNaN(lat)) return "waiting for a fix   hold:menu";

        if (hintCache != null && target == hintTarget && note.equals(hintNote)
                && lat == hintLat && lon == hintLon) {
            return hintCache;
        }
        hintLat = lat;
        hintLon = lon;
        hintTarget = target;
        hintNote = note;

        // The next turn is drawn on the map itself now, so this line is free
        // to say how far there is left to go.
        if (target != null) {
            // Along the road while a route is loaded, as the crow flies
            // otherwise. Showing 37 km next to an arrival time worked out
            // over 43 km invites the reader to check the arithmetic and find
            // it wrong.
            int m = remainingM >= 0 ? (int) Math.round(remainingM)
                    : (int) Route.metresBetween(lat, lon, target.lat, target.lon);
            hintCache = target.name + "  "
                    + (m >= 1000 ? ((m / 100) / 10.0 + " km") : (m + " m"));
        } else {
            hintCache = note.length() > 0 ? note : "hold:menu";
        }
        return hintCache;
    }

    // ---------------------------------------------------------------- drawing

    /** The map itself. North-up, position centred. */
    private class MapView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        /** Kept apart from the shared paint: the route wants round joins, and
         *  restoring them on every other user of that paint is one setter
         *  away from a bug. */
        private final Paint routeInk = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint casing = new Paint(Paint.ANTI_ALIAS_FLAG);

        {
            routeInk.setStyle(Paint.Style.STROKE);
            routeInk.setStrokeWidth(4);
            routeInk.setStrokeJoin(Paint.Join.ROUND);
            routeInk.setStrokeCap(Paint.Cap.ROUND);
            routeInk.setColor(Ui.ROUTE);

            casing.setStyle(Paint.Style.STROKE);
            casing.setStrokeWidth(7);
            casing.setStrokeJoin(Paint.Join.ROUND);
            casing.setStrokeCap(Paint.Cap.ROUND);
            casing.setColor(Ui.ROUTE_CASING);
        }
        private final Path path = new Path();

        MapView(Context c) {
            super(c);
            setBackgroundColor(Color.BLACK);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            if (Double.isNaN(lat)) {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setColor(Ui.MUTED);
                paint.setTextSize(12);
                canvas.drawText("no position yet", w / 2f, h / 2f - 6, paint);
                paint.setTextSize(9);
                paint.setColor(Ui.FAINT);
                // Every blank has a different cause and a different fix, so
                // the cause goes on the screen rather than in a log nobody
                // can reach on a watch.
                String line = why.length() > 0 ? why
                        : (tiles.lastError() != null ? tiles.lastError()
                        : (tiles.online() ? "asking the tracker..." : "no network"));
                canvas.drawText(line, w / 2f, h / 2f + 10, paint);
                canvas.drawText(tiles.onWifi() ? "wifi"
                        : (tiles.online() ? "mobile" : "no network"),
                        w / 2f, h / 2f + 22, paint);
                return;
            }

            // World pixel coordinates of the centre, so everything else is an
            // offset from it and the projection is applied exactly once.
            double cx = Mercator.xOf(lon, ZOOM) * Mercator.TILE_PX;
            double cy = Mercator.yOf(lat, ZOOM) * Mercator.TILE_PX;

            drawTiles(canvas, w, h, cx, cy);
            drawRoute(canvas, w, h, cx, cy);
            drawMe(canvas, w, h);
            drawTurn(canvas, w, h);
            drawOverlay(canvas, w, h);
        }

        private final android.graphics.RectF dst = new android.graphics.RectF();

        private void drawTiles(Canvas canvas, int w, int h, double cx, double cy) {
            if (country == null) return;

            // How much world the screen covers, which grows as the scale
            // shrinks: at half size the same 240 pixels show twice the ground
            // in each direction, so four times the tiles.
            float sc = scale();
            double halfW = w / 2.0 / sc, halfH = h / 2.0 / sc;
            int t0x = (int) Math.floor((cx - halfW) / Mercator.TILE_PX);
            int t1x = (int) Math.floor((cx + halfW) / Mercator.TILE_PX);
            int t0y = (int) Math.floor((cy - halfH) / Mercator.TILE_PX);
            int t1y = (int) Math.floor((cy + halfH) / Mercator.TILE_PX);

            float side = Mercator.TILE_PX * sc;
            for (int tx = t0x; tx <= t1x; tx++) {
                for (int ty = t0y; ty <= t1y; ty++) {
                    Bitmap b = tiles.cached(country, ZOOM, tx, ty);
                    if (b == null) continue;
                    float px = (float) ((tx * Mercator.TILE_PX - cx) * sc + w / 2.0);
                    float py = (float) ((ty * Mercator.TILE_PX - cy) * sc + h / 2.0);
                    if (sc == 1f) {
                        canvas.drawBitmap(b, px, py, null);
                    } else {
                        // Into a rectangle rather than at a point. The
                        // destination area is about one screen whatever the
                        // scale, so this costs what drawing it unscaled did.
                        dst.set(px, py, px + side, py + side);
                        canvas.drawBitmap(b, null, dst, null);
                    }
                }
            }
        }

        /*
         * The route line, clipped to what can be seen and built only when it
         * moves.
         *
         * A route from the server carries its full geometry - up to sixty-five
         * thousand points - and at this zoom a forty kilometre route is eight
         * thousand pixels long, so nearly all of them are off the screen. The
         * whole lot was being put into one Path and stroked twice a frame,
         * which is a great deal of tessellation to ask of the renderer that
         * has already taken this process down once.
         *
         * Clipped to the screen and a margin, with points landing on a pixel
         * already used dropped, what is left is a few hundred at most. And
         * since it only changes when the map moves, it is kept between frames.
         */
        private final Path routePath = new Path();
        private double pathAtX = Double.NaN, pathAtY = Double.NaN;
        private Route pathOf = null;

        private void drawRoute(Canvas canvas, int w, int h, double cx, double cy) {
            if (route == null || route.line.size() < 2) return;

            if (route != pathOf || Math.abs(cx - pathAtX) > 0.5
                    || Math.abs(cy - pathAtY) > 0.5) {
                buildRoutePath(w, h, cx, cy);
                pathOf = route;
                pathAtX = cx;
                pathAtY = cy;
            }
            if (routePath.isEmpty()) return;

            // Casing first, then the line on top of it. Two strokes of the
            // same path is what keeps the route readable where it runs along
            // a white road, which a single stroke of any colour does not.
            canvas.drawPath(routePath, casing);
            canvas.drawPath(routePath, routeInk);
        }

        /** The cached route path was projected at the old scale; throw it away
         *  so the next frame rebuilds it at the new one. */
        void rescaled() {
            routePath.reset();
            pathOf = null;
        }

        private float[] routeXY = new float[RouteLine.MAX_POINTS * 2];

        private void buildRoutePath(int w, int h, double cx, double cy) {
            routePath.reset();
            int n = RouteLine.project(route.line, ZOOM, cx, cy, w, h, scale(), routeXY);
            int i = 0;
            while (i + 1 < n) {
                if (Float.isNaN(routeXY[i])) {
                    i += 2;
                    if (i + 1 < n) {
                        routePath.moveTo(routeXY[i], routeXY[i + 1]);
                        i += 2;
                    }
                } else {
                    routePath.lineTo(routeXY[i], routeXY[i + 1]);
                    i += 2;
                }
            }
        }

        /** The position, and which way it is moving. */
        private void drawMe(Canvas canvas, int w, int h) {
            float x = w / 2f, y = h / 2f;
            paint.setStyle(Paint.Style.FILL);

            if (bearing >= 0 && speedMs > 0.5f) {
                canvas.save();
                canvas.rotate(bearing, x, y);
                paint.setColor(Ui.ACCENT);
                path.reset();
                path.moveTo(x, y - 9);
                path.lineTo(x - 6, y + 7);
                path.lineTo(x, y + 3);
                path.lineTo(x + 6, y + 7);
                path.close();
                canvas.drawPath(path, paint);
                canvas.restore();
            } else {
                // Stationary, or no course: a dot, because an arrow would be
                // pointing somewhere it does not know.
                paint.setColor(approximate ? Ui.MUTED : Ui.ACCENT);
                canvas.drawCircle(x, y, 5, paint);
                paint.setColor(Ui.BG);
                canvas.drawCircle(x, y, 2, paint);
                if (approximate) {
                    // A ring for the uncertainty, so a position good to a few
                    // hundred metres does not look like one good to five.
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1);
                    paint.setColor(Ui.MUTED);
                    canvas.drawCircle(x, y, 14, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
        }

        /**
         * The next turn, along the bottom of the map.
         *
         * On its own band rather than over the map, because a line of text
         * laid straight on top of roads at this size is unreadable against
         * half the backgrounds it lands on. Amber, matching the route it
         * refers to, so it is obvious which line the instruction is about.
         */
        /**
         * The bottom band: what to do next, and how the drive is going.
         *
         * Two lines, and the order matters. The instruction is the one that
         * has to be read at a glance while moving, so it keeps the larger
         * type and the position closest to the map; speed and arrival sit
         * under it in smaller, dimmer type, because wanting them is never
         * urgent. Either line may be missing - before the first turn is
         * known, or before enough driving has happened to say how fast - and
         * the band shrinks to whatever is actually there rather than leaving
         * a black bar across a quarter of a 240 pixel screen.
         */
        private void drawTurn(Canvas canvas, int w, int h) {
            if (route == null || Double.isNaN(lat)) return;
            String say = turnLine;
            String info = driveLine;
            if (say == null && info == null) return;

            final int band = (say != null ? 20 : 0) + (info != null ? 14 : 0);
            // bandHeight() must agree with this, or the overlay lands on top
            // of the text.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xD0000000);                 // dark, but not opaque
            canvas.drawRect(0, h - band, w, h, paint);
            paint.setTextAlign(Paint.Align.CENTER);

            int y = h - band;
            if (say != null) {
                paint.setColor(approximate ? Ui.MUTED : Ui.ROUTE);
                paint.setTextSize(13);
                // Shrink rather than clip: "in 1.2 km turn sharp right" is
                // longer than 240px at 13px, and half an instruction is worse
                // than a small one.
                fit(say, w);
                canvas.drawText(say, w / 2f, y + 14, paint);
                y += 20;
            }
            if (info != null) {
                paint.setColor(Ui.MUTED);
                paint.setTextSize(11);
                fit(info, w);
                canvas.drawText(info, w / 2f, y + 11, paint);
            }
        }

        /** How tall the bottom band is, so nothing else is drawn under it. */
        private int bandHeight() {
            if (route == null || Double.isNaN(lat)) return 0;
            int b = 0;
            if (turnLine != null) b += 20;
            if (driveLine != null) b += 14;
            return b;
        }

        /** Drop the type size until the line fits the width. */
        private void fit(String s, int w) {
            while (paint.measureText(s) > w - 6 && paint.getTextSize() > 8) {
                paint.setTextSize(paint.getTextSize() - 1);
            }
        }

        private void drawOverlay(Canvas canvas, int w, int h) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(10);

            if (country == null) {
                paint.setColor(Ui.WARN);
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(tiles.online() ? "finding map..." : "offline, no map",
                        2, 10, paint);
            }
            if (approximate) {
                paint.setColor(Ui.WARN);
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText("approx", 2, h - bandHeight() - 4, paint);
            }
            long age = (fixAt == 0) ? -1 : (System.currentTimeMillis() - fixAt) / 1000;
            if (age > 30) {
                paint.setColor(Ui.WARN);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(age + "s", w - 2, 10, paint);
            }
        }
    }

    // ---------------------------------------------------------------- menu

    MapTiles tiles() { return tiles; }
    Destination target() { return target; }

    /** Chosen from the destination list rather than being whichever line
     *  happened to be first in the file. */
    void setTarget(Destination d) {
        target = d;
        // A route to somewhere else is not a route to here.
        route = null;
        arrived = false;
        note = "";
        changed();
    }

    /**
     * Route to the current target, on device if the roads are on the card and
     * from the server otherwise. Safe to call from another screen.
     */
    void routeToTarget() {
        final Destination d = target;
        if (d == null || !hasFix()) return;
        if (rerouting) return;
        rerouting = true;
        note = "routing";
        final double la = lat, lo = lon;
        new Thread(new Runnable() {
            public void run() {
                Route found = routeHere(la, lo, d.lat, d.lon);
                if (found == null && tiles.online()) {
                    File out = new File(MapTiles.DIR + "/route.bin");
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    String url = tiles.base() + "route.php"
                            + "?flat=" + la + "&flon=" + lo
                            + "&tlat=" + d.lat + "&tlon=" + d.lon;
                    if (tiles.download(url, out)) found = Route.read(out);
                    if (found != null) found.prepare();
                }
                final Route r = found;
                ui.post(new Runnable() {
                    public void run() {
                        rerouting = false;
                        if (r == null) { note = "no route"; changed(); return; }
                        setRoute(r);
                        note = "";
                        int km = r.totalMetres / 1000;
                        announce("route found, " + km + " kilometres");
                        changed();
                    }
                });
            }
        }).start();
    }
    String country() { return country; }
    double lat() { return lat; }
    double lon() { return lon; }
    boolean hasFix() { return !Double.isNaN(lat); }

    /**
     * What the sign at a junction says, for the voice.
     *
     * A motorway junction within eighty metres of the manoeuvre is the one it
     * refers to; anything further is a different junction, and naming the
     * wrong one is worse than naming none.
     */
    private final Route.Signs signs = new Route.Signs() {
        public String junctionAt(double la, double lo) {
            if (!alerts.loaded()) return null;
            Alerts.Near n = alerts.nearest(la, lo, Alerts.EXIT, EXIT_NAME_M);
            return n == null ? null : n.name;
        }
    };

    private static final double EXIT_NAME_M = 80;

    void setRoute(Route r) {
        ui.removeCallbacks(saySoon);
        soon = null;
        route = r;
        if (r != null) r.signs(signs);
        arrived = false;
        // The moment there is a route, not at the next fix and not at the next hide.
        //
        // Renewing on each fix is not enough on its own: the first renewal is a fix away, and
        // if the processor suspends inside that gap there is no fix to renew on and nothing
        // wakes it. Taking it here means the drive begins with the lock already held, whatever
        // the screen is doing and whichever screen happens to be in front.
        // No switch to consult here: a route always survives the screen going off, and that is
        // the case this guards against - the processor suspending before the first fix has
        // arrived to take the lock.
        if (r != null) holdForNavigation();
        updateTurnLine();
        checkRouteCached(r);
        // Last drive's average is not evidence about this one - a route asked
        // for after parking would otherwise start out predicting arrival at
        // the speed of the walk to the car.
        drive.restart();
        driveLine = null;
        updateDriveLine();
        view.invalidate();
    }

    /**
     * The speed-and-arrival line, rebuilt on a fix.
     *
     * Speed appears as soon as two fixes are far enough apart to divide.
     * Arrival waits for a made-good average, or for a route that carries the
     * time it was planned to take - the on-device router knows it, the
     * server's format does not - because an arrival time invented from
     * nothing is worse than an empty half of a line.
     */
    /** Recomputed on a fix, when the route changes, and when a turn is
     *  spoken - the three things that can change what it says. */
    private void updateTurnLine() {
        turnLine = (route == null || Double.isNaN(lat))
                ? null : route.screenInstruction(lat, lon);
    }

    private void updateDriveLine() {
        if (route == null || Double.isNaN(lat) || approximate) {
            driveLine = null;
            remainingM = -1;
            return;
        }

        StringBuilder b = new StringBuilder();
        int kmh = drive.kmh();
        if (kmh >= 0) b.append(kmh).append(" km/h");

        double left = route.metresRemaining(lat, lon);
        remainingM = left;
        int eta = left < 0 ? -1 : drive.etaSeconds(left, route.plannedMs());
        String t = Drive.shortTime(eta);
        if (t != null) {
            if (b.length() > 0) b.append("  \u00b7  ");
            b.append(t);
            String at = Drive.arrivalClock(System.currentTimeMillis(),
                    java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()), eta);
            if (at != null) b.append("  ").append(at);
        }
        driveLine = b.length() > 0 ? b.toString() : null;
        // One line a fix, alongside the fix itself: when an arrival estimate
        // looks wrong on the wrist there is otherwise no way to tell whether
        // the distance or the speed was the wrong half.
        Log.i("watchmap", "drive: " + driveLine + "  left="
                + (left < 0 ? "?" : Math.round(left) + "m"));
    }

    void reloadDestination() {
        loadDestination();
        view.invalidate();
    }

    /**
     * A speed camera far enough ahead to do something about it.
     *
     * Announced once each, and only when moving: a camera four hundred metres
     * away is fourteen seconds at road speed and worth knowing, and the same
     * camera while parked next to it is not news. Distance alone would fire
     * on cameras on the far carriageway and on the road just left, so it also
     * has to be roughly ahead - within a right angle of where the watch is
     * pointing.
     */
    private void warnCameras() {
        if (!alerts.loaded() || Double.isNaN(lat)) return;
        // A server position can be hundreds of metres out, which is enough to
        // put the warning on the wrong road entirely.
        if (approximate) return;
        if (speedMs < CAMERA_MIN_MS) return;

        for (Alerts.Near n : alerts.near(lat, lon, Alerts.CAMERA, CAMERA_WARN_M)) {
            long id = Math.round(n.lat * 1e5) * 40000000L + Math.round(n.lon * 1e5);
            if (saidCamera.contains(id)) continue;
            if (bearing >= 0) {
                double to = Route.bearing(lat, lon, n.lat, n.lon);
                double off = Math.abs(((to - bearing + 540) % 360) - 180);
                if (off > 90) continue;                 // behind us
            }
            saidCamera.add(id);
            /*
             * One announcement per site, not per camera.
             *
             * A gantry carries a camera per lane and the data has each of
             * them, so driving under one produced "speed camera ahead" twice
             * in a row. Anything within a couple of hundred metres of what
             * was just announced is the same site from the driver's point of
             * view, whatever the map calls it - so it is marked as said and
             * passed over silently.
             */
            boolean sameSite = !Double.isNaN(lastCameraLat)
                    && Geo.metresFlat(lastCameraLat, lastCameraLon, n.lat, n.lon)
                       < CAMERA_SITE_M;
            if (sameSite) continue;
            lastCameraLat = n.lat;
            lastCameraLon = n.lon;

            announce("speed camera ahead");
            note = "speed camera " + Route.screenDistance((int) Math.round(n.metres));
            break;                                      // one at a time
        }
        // The set would otherwise grow for the whole drive.
        if (saidCamera.size() > 200) saidCamera.clear();
    }

    /** Far enough ahead to lift off, close enough to be about this road. */
    private static final double CAMERA_WARN_M = 400;

    /** Below this the watch is not driving, and a camera is scenery. */
    private static final float CAMERA_MIN_MS = 5f;      // 18 km/h

    /** Cameras closer together than this are one site with several lenses. */
    private static final double CAMERA_SITE_M = 250;

    private double lastCameraLat = Double.NaN, lastCameraLon;

    /**
     * Fetch the alert layer for where the watch is.
     *
     * A box, not a country. The server builds this from its store per request
     * rather than serving a precomputed file, so asking for a hundred
     * kilometres costs a hundred kilometres - the Netherlands is 114 kB whole
     * and 3 kB around one town, and a continent would be neither.
     *
     * Refetched when the drive leaves the middle of what was fetched, which
     * over a day's driving is a handful of requests. Optional throughout:
     * this never blocks anything and never retries, and failing means no
     * warnings, which is what a watch without the layer has anyway.
     */
    private void loadAlerts(final String c) {
        if (c == null || Double.isNaN(lat)) return;
        if (System.currentTimeMillis() < alertsRetryAt) return;
        boolean sameCountry = c.equals(alertsFor);
        if (sameCountry && !Double.isNaN(alertsAtLat)
                && Geo.metresFlat(alertsAtLat, alertsAtLon, lat, lon) < ALERTS_REFRESH_M) {
            return;
        }
        alertsFor = c;
        alertsAtLat = lat;
        alertsAtLon = lon;

        final double la = lat, lo = lon;
        // Remembered so a failure can put them back. Marking the box fetched
        // before knowing whether it was meant no warnings for the rest of the
        // drive whenever the first attempt failed - and the first attempt is
        // the one most likely to, because it happens as the map opens, while
        // the country lookup and the route are competing for the same radio.
        new Thread(new Runnable() {
            public void run() {
                try {
                    double dLat = ALERTS_RADIUS_M / Geo.perLat(la);
                    double dLon = ALERTS_RADIUS_M / Geo.perLon(la);
                    File f = Alerts.fileFor(c);
                    if (f.getParentFile() != null) f.getParentFile().mkdirs();
                    String url = tiles.base() + "alerts.php?c=" + c
                            + "&w=" + (lo - dLon) + "&s=" + (la - dLat)
                            + "&e=" + (lo + dLon) + "&n=" + (la + dLat);
                    if (!tiles.download(url, f)) {
                        Log.i("watchmap", "no alert layer for " + c + "; will retry");
                        forgetAlertBox();
                        return;
                    }
                    if (!alerts.open(c)) forgetAlertBox();
                } catch (Throwable t) {
                    Log.w("watchmap", "alerts: " + t);
                    forgetAlertBox();
                }
            }
        }).start();
    }

    /**
     * Let the next fix try the alert layer again, but not the one after that.
     *
     * Forgetting the box outright would retry on every fix, which for a watch
     * with no signal is a download attempt every ten seconds for the whole
     * drive. Moving the remembered centre a long way instead means the
     * distance test passes again, and the minute of backoff is what stops it
     * becoming a loop.
     */
    private void forgetAlertBox() {
        alertsFor = null;
        alertsAtLat = Double.NaN;
        alertsRetryAt = System.currentTimeMillis() + ALERTS_RETRY_MS;
    }

    private volatile long alertsRetryAt = 0;
    private static final long ALERTS_RETRY_MS = 60000;

    /** How much of the world to hold warnings for, and how far the watch may
     *  travel before asking for a fresh box. Two hours of motorway between
     *  requests, and never a stale edge. */
    private static final double ALERTS_RADIUS_M = 100000;
    private static final double ALERTS_REFRESH_M = 40000;

    /** Volatile: forgetAlertBox() runs on the download thread and loadAlerts()
     *  reads these from the fix that follows, on the UI thread. */
    private volatile double alertsAtLat = Double.NaN, alertsAtLon;

    /**
     * Is the map for this route actually on the card?
     *
     * A route is followed offline - that is the whole point of computing it
     * here - but the tiles under it are a separate thing, and nothing said so.
     * The map fetches a three by three block around wherever you are, which is
     * about forty seconds of lookahead at road speed and only while there is a
     * signal. Drive out of the downloaded area and into a dead spot and the
     * screen simply goes blank, with the route line drawn over nothing.
     *
     * The corridor download that fixes this already exists, in the map menu.
     * Nobody starting a drive would think to go and find it. So the coverage
     * is counted when the route arrives and said out loud if it is poor -
     * before setting off, which is the only time the answer is useful.
     *
     * Sampled rather than exhaustive: a block is sixteen tiles square, so
     * every twentieth point of the line is far more than enough to notice a
     * gap, and this runs off the UI thread either way.
     */
    private void checkRouteCached(final Route r) {
        if (r == null || r.line.size() < 2 || country == null) return;
        final String c = country;
        new Thread(new Runnable() {
            public void run() {
                int have = 0, want = 0;
                java.util.HashSet<Long> seen = new java.util.HashSet<Long>();
                for (int i = 0; i < r.line.size(); i += 20) {
                    double[] pt = r.line.get(i);
                    int tx = (int) Mercator.xOf(pt[1], ZOOM);
                    int ty = (int) Mercator.yOf(pt[0], ZOOM);
                    long key = ((long) (tx >> MapTiles.BLOCK_BITS) << 32)
                             | ((ty >> MapTiles.BLOCK_BITS) & 0xFFFFFFFFL);
                    if (!seen.add(key)) continue;
                    want++;
                    if (tiles.haveBlock(c, ZOOM, tx >> MapTiles.BLOCK_BITS,
                                        ty >> MapTiles.BLOCK_BITS)) {
                        have++;
                    }
                }
                if (want == 0) return;
                final int pct = have * 100 / want;
                if (pct >= 90) return;               // good enough to drive on
                ui.post(new Runnable() {
                    public void run() {
                        note = "map " + pct + "% cached - see menu";
                        Log.i("watchmap", "route corridor " + pct + "% on the card");
                        changed();
                    }
                });
            }
        }).start();
    }

    /**
     * Say the turn at the turn, not at the next fix.
     *
     * A fix arrives every ten seconds. The instruction meant for the junction
     * itself was spoken only when a fix happened to land near it, which at
     * fifty kilometres an hour is anywhere in a hundred and forty metre
     * window - so it was reliably late, by up to ten seconds, which is exactly
     * as much use as not saying it.
     *
     * The watch knows where it is and how fast it is going, so it can work out
     * when it will be at the junction and wait for that moment. Recomputed on
     * every fix, so a slowdown, a stop or a wrong reading is corrected by the
     * next one rather than being locked in.
     *
     * Speed comes from the drive rather than the plan, which is the whole
     * reason Drive exists - and falls back to the planned average only when
     * there is not yet enough driving to measure.
     */
    private void scheduleImminent() {
        ui.removeCallbacks(saySoon);
        soon = null;
        if (route == null || Double.isNaN(lat) || approximate) return;

        Route.Turn t = route.imminent(lat, lon);
        if (t == null) return;

        float v = drive.speedMs();
        if (v < 1f) v = route.plannedMs();
        if (v < 1f) return;                       // nothing to predict with

        double away = Route.metresBetween(lat, lon, t.lat, t.lon) / v;
        if (away > IMMINENT_WINDOW_S) return;     // too far off to time yet

        // Ahead of the junction by a couple of seconds, so the words are
        // finished before the turn rather than started at it. Never negative:
        // a turn already behind the last fix is said at once or not at all.
        long delay = (long) Math.max(0, (away - IMMINENT_LEAD_S) * 1000);
        soon = t;
        ui.postDelayed(saySoon, delay);
    }

    private Route.Turn soon = null;

    private final Runnable saySoon = new Runnable() {
        public void run() {
            Route.Turn t = soon;
            soon = null;
            if (route == null || t == null || t.announced) return;
            String what = Route.action(t.kind);
            if (what == null) return;
            route.markSaid(t);
            announce(what);
            updateTurnLine();
            changed();
        }
    };

    /** Only worth timing once the junction is this close; further off and the
     *  next fix will do a better job of it. */
    private static final double IMMINENT_WINDOW_S = 25;

    /** Said this far ahead of the junction, so it finishes before the turn. */
    private static final double IMMINENT_LEAD_S = 3;

    /**
     * Ask less often, up to a point.
     *
     * Called when the answer was no use - nothing came back, or it is the same
     * position as last time. Anything that actually moves resets it.
     */
    private void slowDownReseed() {
        reseedEvery = Math.min(RESEED_MAX_MS, reseedEvery * 2);
    }

    /**
     * The engine, started if it is not running.
     *
     * onHide shuts it down, and onHide is called whenever this screen is
     * merely covered - by the map menu, which is the only way to choose a
     * destination and start navigating. So the engine was being shut down on
     * the way to asking for a route and never started again, and the voice
     * was dead for the whole of every drive. It is rebuilt here rather than
     * kept alive because the reason for shutting it down is real: it holds an
     * audio path open that the music player should have when navigation is
     * not running.
     */
    private Speech voice() {
        if (speech == null || speech.stopped()) speech = new Speech(shell);
        return speech;
    }

    /** From a menu action, so it is said even though the menu is what is showing. */
    void speak(String s) { sayNow(s); }

    String voiceStatus() { return voice().status(); }

    boolean voiceReady() { return voice().ready(); }

    /** Say something on demand, bypassing the repeat guard: when the
     *  complaint is silence, hearing it twice is the point. */
    void testVoice() {
        voice().sayAgain("in three hundred metres, turn left");
    }

    String why() { return why; }

    /** For the menu, so a stuck map can be prodded without leaving it. */
    void retrySeed() {
        askedServerAt = 0;
        why = "";
        seedFromServer();
        adoptOnlyCountry();
        view.invalidate();
    }
}
