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
    private String note = "";
    private boolean listening = false;
    private boolean arrived = false;

    /** True while the position on screen came from the tracker server rather
     *  than from this watch's own receiver. It is a real position, resolved
     *  from wifi and cell, but it can be hundreds of metres out - enough to
     *  pick the right country and centre the map, not enough to navigate by,
     *  so the screen says so. */
    private boolean approximate = false;
    private boolean askedServer = false;

    /** Why there is nothing on screen. A blank map with no explanation is the
     *  least useful thing this could show, and every reason it can be blank
     *  has a different fix. */
    private String why = "";

    @Override
    public String title() { return "Map"; }

    @Override
    protected View build() {
        tiles = new MapTiles(shell);
        speech = new Speech(shell);
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
        loadDestination();
        loadRoute();
        startFixes();
        seedFromLastFix();
        seedFromServer();
        view.invalidate();
    }

    @Override
    public void onHide() {
        stopFixes();
        // The engine is shut down rather than kept warm: it holds an audio
        // focus path open, and the music player is the thing that should have
        // it when navigation is not running.
        if (speech != null) speech.stop();
    }

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
        boolean stale = (fixAt > 0) && (System.currentTimeMillis() - fixAt) > 30000;
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

    private void loadDestination() {
        List<Destination> all = Destination.load();
        target = all.isEmpty() ? null : all.get(0);
    }

    private void loadRoute() {
        File f = new File(MapTiles.DIR + "/route.bin");
        route = f.isFile() ? Route.read(f) : null;
    }

    // ---------------------------------------------------------------- fixes

    private void startFixes() {
        if (locations == null || listening) return;
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
        }
    }

    private void stopFixes() {
        if (!listening) return;
        listening = false;
        try { locations.removeUpdates(this); } catch (Exception e) { /* ignore */ }
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
        if (askedServer || !Double.isNaN(lat)) return;
        askedServer = true;
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
                        if (!countryKnown) findCountry();
                        prefetchAround();
                        changed();
                    }
                });
            }
        }).start();
    }

    public void onLocationChanged(Location l) { take(l); }
    public void onProviderEnabled(String p) { }
    public void onProviderDisabled(String p) { }
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

        // Only when the position is outside what we already hold.
        if (!countryKnown || lon < cMinX || lon > cMaxX || lat < cMinY || lat > cMaxY) {
            findCountry();
        }
        prefetchAround();
        follow();
        changed();
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
                    speech.say("you have arrived");
                    // The route has done its job. Keeping it drawn would leave
                    // a line to somewhere you already are.
                    route = null;
                    note = "arrived";
                }
                return;
            }
        }

        String say = route.instruction(lat, lon, speedMs);
        if (say != null) speech.say(say);

        if (route.offRouteMetres(lat, lon) > Route.OFF_ROUTE_M) {
            note = "off route";
        } else {
            note = "";
        }
    }

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
                            if (country == null) country = offlineGuess();
                            view.invalidate();
                            return;
                        }
                        String first = reply.trim().split("\n")[0];
                        if (first.length() == 0 || first.startsWith("none")) return;
                        String[] f = first.split(",");
                        if (f.length < 5) return;
                        country = f[0];
                        Log.i("watchmap", "country = " + country);
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
                ui.post(new Runnable() {
                    public void run() { changed(); }
                });
            }
        }).start();
    }

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
        return true;
    }

    @Override
    public String hint() {
        if (Double.isNaN(lat)) return "waiting for a fix   hold:menu";
        // The next turn is drawn on the map itself now, so this line is free
        // to say how far there is left to go.
        if (target != null) {
            int m = (int) Route.metresBetween(lat, lon, target.lat, target.lon);
            return target.name + "  " + (m >= 1000 ? ((m / 100) / 10.0 + " km") : (m + " m"));
        }
        return note.length() > 0 ? note : "hold:menu";
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

        private void drawTiles(Canvas canvas, int w, int h, double cx, double cy) {
            if (country == null) return;
            int t0x = (int) Math.floor((cx - w / 2.0) / Mercator.TILE_PX);
            int t1x = (int) Math.floor((cx + w / 2.0) / Mercator.TILE_PX);
            int t0y = (int) Math.floor((cy - h / 2.0) / Mercator.TILE_PX);
            int t1y = (int) Math.floor((cy + h / 2.0) / Mercator.TILE_PX);

            for (int tx = t0x; tx <= t1x; tx++) {
                for (int ty = t0y; ty <= t1y; ty++) {
                    Bitmap b = tiles.cached(country, ZOOM, tx, ty);
                    if (b == null) continue;
                    float px = (float) (tx * Mercator.TILE_PX - cx + w / 2.0);
                    float py = (float) (ty * Mercator.TILE_PX - cy + h / 2.0);
                    canvas.drawBitmap(b, px, py, null);
                }
            }
        }

        private void drawRoute(Canvas canvas, int w, int h, double cx, double cy) {
            if (route == null || route.line.size() < 2) return;

            path.reset();
            boolean first = true;
            for (int i = 0; i < route.line.size(); i++) {
                double[] p = route.line.get(i);
                float px = (float) (Mercator.xOf(p[1], ZOOM) * Mercator.TILE_PX - cx + w / 2.0);
                float py = (float) (Mercator.yOf(p[0], ZOOM) * Mercator.TILE_PX - cy + h / 2.0);
                // Well off screen is not worth a path segment, but the points
                // either side of the edge are, or the line stops at the border.
                if (first) { path.moveTo(px, py); first = false; }
                else { path.lineTo(px, py); }
            }

            // Casing first, then the line on top of it. Two strokes of the
            // same path is what keeps the route readable where it runs along
            // a white road, which a single stroke of any colour does not.
            canvas.drawPath(path, casing);
            canvas.drawPath(path, routeInk);
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
        private void drawTurn(Canvas canvas, int w, int h) {
            if (route == null || Double.isNaN(lat)) return;
            String say = route.screenInstruction(lat, lon);
            if (say == null) return;

            final int band = 20;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xD0000000);                 // dark, but not opaque
            canvas.drawRect(0, h - band, w, h, paint);

            paint.setColor(approximate ? Ui.MUTED : Ui.ROUTE);
            paint.setTextSize(13);
            paint.setTextAlign(Paint.Align.CENTER);

            // Shrink rather than clip: "in 1.2 km turn sharp right" is longer
            // than 240px at 13px, and half an instruction is worse than a
            // small one.
            while (paint.measureText(say) > w - 6 && paint.getTextSize() > 9) {
                paint.setTextSize(paint.getTextSize() - 1);
            }
            canvas.drawText(say, w / 2f, h - 6, paint);
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
                canvas.drawText("approx", 2,
                        (route != null ? h - 24 : h - 2), paint);
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
    String country() { return country; }
    double lat() { return lat; }
    double lon() { return lon; }
    boolean hasFix() { return !Double.isNaN(lat); }

    void setRoute(Route r) {
        route = r;
        arrived = false;
        view.invalidate();
    }

    void reloadDestination() {
        loadDestination();
        view.invalidate();
    }

    void speak(String s) { speech.say(s); }

    String why() { return why; }

    /** For the menu, so a stuck map can be prodded without leaving it. */
    void retrySeed() {
        askedServer = false;
        why = "";
        seedFromServer();
        adoptOnlyCountry();
        view.invalidate();
    }
}
