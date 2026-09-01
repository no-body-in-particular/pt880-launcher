package org.watchlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Tomorrow's rain: one file, fetched from the server.
 *
 * The server pulls Buienradar's model run, draws the next twenty-four hours
 * over a map of the country and hands back an animated GIF. The watch stores
 * it and plays it, and that is the whole of this side.
 *
 * <p>It did once arrive as a packed grid of intensities, which let the watch
 * say "rain here in three hours" for the exact spot it was standing on. That
 * went, deliberately: it meant a wire format, an encoder, a parser and a
 * palette that had to agree across two languages, all to answer a question the
 * picture very nearly answers by being looked at. What is left cannot say
 * anything about a point -- only show the country and let you find yourself on
 * it -- and is perhaps a tenth of the code.
 *
 * <h3>Where it is offered</h3>
 *
 * Only in the Netherlands. The source covers that country and no other, and a
 * Rain row that is always there but only ever works in one place is worse than
 * no row: it looks broken everywhere else rather than absent. {@link #here}
 * answers from the last country the map settled on, which costs nothing and
 * needs no network -- see {@link #noteCountry}.
 */
public final class Rain {

    private static final String PREFS = "watchlauncher";
    private static final String KEY_COUNTRY = "rain.country";
    private static final String COUNTRY = "netherlands";

    /** On the card rather than in memory: it is a couple of hundred kilobytes,
     *  it is worth having when the network is gone, and {@code MapTiles.keeps}
     *  spares it from the cleanup for that reason. */
    static final String CACHE = MapTiles.DIR + "/rain.gif";

    /**
     * How wide a picture to ask for.
     *
     * The screen is 240 across and the country is taller than it is wide, so
     * the limit is the height: 200 comes back about 200x231, which scales to
     * fit with a little to spare. Asking for 340, which is what a browser
     * gets, would be 150 kB to draw at two thirds the size.
     */
    private static final int WIDTH_PX = 200;

    /** A run is hourly and the server re-checks every ten minutes, so asking
     *  more often than this only ever gets the same bytes back. */
    private static final long REFETCH_MS = 15 * 60 * 1000L;

    private Rain() { }

    /** Whether what is on the card is worth replacing. */
    public static boolean stale() {
        File f = new File(CACHE);
        return !f.isFile() || f.length() < 1024
                || System.currentTimeMillis() - f.lastModified() > REFETCH_MS;
    }

    /**
     * The forecast on the card, fetching a new one if the old one has had its
     * hour.
     *
     * Blocking, so it is called from a thread. A failed fetch is not an error
     * worth showing: whatever is already there is at worst an hour old and
     * still runs a day forward, which is a better thing to draw than a message
     * about the network.
     *
     * @return the file, or null if there is nothing to show at all
     */
    public static File fetch(Context ctx, boolean force) {
        File out = new File(CACHE);
        if (!force && !stale()) return out;

        MapTiles tiles = MapTiles.of(ctx);
        File tmp = new File(CACHE + ".new");
        if (tiles.download(tiles.base() + "rain.php?px=" + WIDTH_PX, tmp)) {
            // Only swapped in once it is safely down, so a half transfer
            // cannot replace a good forecast with an unplayable one.
            if (tmp.length() > 1024) {
                out.delete();
                if (tmp.renameTo(out)) return out;
            }
        }
        tmp.delete();
        return out.isFile() && out.length() > 1024 ? out : null;
    }

    // ------------------------------------------------------------ where it is

    /**
     * Remember which country the map settled on.
     *
     * Called from the map, which is the only thing that asks the server. The
     * launcher needs the answer before anything has been opened and with no
     * network to ask over, so it is kept rather than looked up.
     */
    public static void noteCountry(Context ctx, String country) {
        if (country == null || country.length() == 0) return;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (country.equals(p.getString(KEY_COUNTRY, null))) return;
        p.edit().putString(KEY_COUNTRY, country).commit();
    }

    /**
     * Whether there is rain data for where this watch is.
     *
     * The last country the map settled on, and nothing else. Deliberately not
     * a bounding box test against the last fix: the fix may be hours old or
     * absent, the box would be a second definition of the Netherlands to keep
     * in step with the server's, and being wrong in the cautious direction
     * here costs a menu row rather than anything that matters.
     *
     * Unknown counts as yes. On a watch that has never had a fix the map is
     * blank too, and hiding the row would mean the first thing a new device
     * does is offer less than it has.
     */
    public static boolean here(Context ctx) {
        String c = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_COUNTRY, null);
        return c == null || c.equals(COUNTRY);
    }
}
