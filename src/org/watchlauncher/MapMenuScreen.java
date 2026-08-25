package org.watchlauncher;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * What the map can be asked to do.
 *
 * Everything that touches the network is here rather than automatic, except
 * the tiles immediately under your feet: a country is tens of megabytes and
 * the decision to spend that belongs to the person wearing it.
 */
public class MapMenuScreen extends ListScreen {

    private final MapScreen map;
    private String busy = "";

    MapMenuScreen(MapScreen map) { this.map = map; }

    @Override
    public String title() { return busy.length() > 0 ? busy : "Map"; }

    @Override
    public void tick() {
        if (busy.length() > 0) render();
    }

    @Override
    protected List<Item> items() {
        List<Item> l = list();

        Destination d = map.target();
        l.add(new Item("Route to", d == null ? "no destination" : d.name, AppIcons.CALL));
        l.add(new Item("Reload destination", null, AppIcons.GEAR));
        l.add(new Item("Download country",
                map.country() == null ? "unknown" : map.country(), AppIcons.DEVICE));
        l.add(new Item("Download route", null, AppIcons.DEVICE));
        l.add(new Item("Storage", MapDownload.estimate(
                (int) (MapTiles.bytesOnCard() / 4096)), AppIcons.NONE, Ui.DIM));
        l.add(new Item("Network", map.tiles().onWifi() ? "wifi"
                : (map.tiles().online() ? "mobile" : "offline"),
                AppIcons.NONE, Ui.DIM));
        addBack(l);
        l.add(new Item("Exit map", null, AppIcons.HOME));
        return l;
    }

    @Override
    protected void onPick(int index) {
        switch (index) {
            case 0: routeTo(); break;
            case 1:
                map.reloadDestination();
                shell.toast(Destination.file() == null
                        ? makeExample() : "destination reloaded");
                render();
                break;
            case 2: downloadCountry(); break;
            case 3: downloadRoute(); break;
            case 4: break;                       // readouts
            case 5: break;
            case 6: shell.pop(); break;
            default: shell.popToRoot(); break;
        }
    }

    private String makeExample() {
        String made = Destination.createExample();
        return made == null ? "cannot write /sdcard" : made;
    }

    // ---------------------------------------------------------------- route

    /** Ask the server for a route and keep it on the card, so it survives the
     *  network going away five minutes into the journey. */
    private void routeTo() {
        final Destination d = map.target();
        if (d == null) { shell.toast("no destination.txt"); return; }
        if (!map.hasFix()) { shell.toast("no fix yet"); return; }
        if (!map.tiles().online()) { shell.toast("offline"); return; }
        if (busy.length() > 0) return;

        busy = "routing...";
        render();
        final double la = map.lat(), lo = map.lon();
        new Thread(new Runnable() {
            public void run() {
                File out = new File(MapTiles.DIR + "/route.bin");
                out.getParentFile().mkdirs();
                String url = map.tiles().base() + "route.php"
                        + "?flat=" + la + "&flon=" + lo
                        + "&tlat=" + d.lat + "&tlon=" + d.lon;
                boolean ok = map.tiles().download(url, out);
                final Route r = ok ? Route.read(out) : null;
                shell.runOnUiThread(new Runnable() {
                    public void run() {
                        busy = "";
                        if (r == null) { shell.toast("no route"); render(); return; }
                        map.setRoute(r);
                        int km = r.totalMetres / 1000;
                        shell.toast(km + " km, " + r.turns.size() + " turns");
                        map.speak("route found, " + km + " kilometres");
                        render();
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- bulk

    private void downloadCountry() {
        final String c = map.country();
        if (c == null) { shell.toast("country unknown"); return; }
        if (!map.tiles().onWifi()) { shell.toast("needs wifi"); return; }
        if (busy.length() > 0) return;
        bulk(true, c);
    }

    private void downloadRoute() {
        final String c = map.country();
        if (c == null) { shell.toast("country unknown"); return; }
        if (!map.tiles().onWifi()) { shell.toast("needs wifi"); return; }
        if (busy.length() > 0) return;
        bulk(false, c);
    }

    private void bulk(final boolean whole, final String country) {
        busy = "starting...";
        render();
        final MapDownload job = new MapDownload();

        new Thread(new Runnable() {
            public void run() {
                final MapDownload.Progress p = new MapDownload.Progress() {
                    public boolean onProgress(final int done, final int total,
                                              final int failed) {
                        shell.runOnUiThread(new Runnable() {
                            public void run() {
                                busy = done + "/" + total
                                        + (failed > 0 ? (" " + failed + " failed") : "");
                            }
                        });
                        return true;
                    }
                };

                int failed;
                if (whole) {
                    String meta = map.tiles().get(map.tiles().base()
                            + "country.php?lat=" + map.lat() + "&lon=" + map.lon());
                    double[] bb = parseBox(meta);
                    if (bb == null) { finish("no bounds"); return; }
                    failed = job.country(map.tiles(), country, bb[0], bb[1], bb[2], bb[3],
                            map.lat(), map.lon(), p);
                } else {
                    Route r = Route.read(new File(MapTiles.DIR + "/route.bin"));
                    if (r == null) { finish("no route yet"); return; }
                    failed = job.route(map.tiles(), country, r, p);
                }
                finish(failed < 0 ? "needs wifi"
                        : (failed == 0 ? "map downloaded" : (failed + " tiles missing")));
            }

            private void finish(final String msg) {
                shell.runOnUiThread(new Runnable() {
                    public void run() {
                        busy = "";
                        shell.toast(msg);
                        render();
                    }
                });
            }
        }).start();
    }

    /** "netherlands,3.32863,50.74230,7.26713,53.53580,1990294" */
    private static double[] parseBox(String reply) {
        if (reply == null) return null;
        String[] f = reply.trim().split("\n")[0].split(",");
        if (f.length < 5) return null;
        try {
            return new double[]{Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                                Double.parseDouble(f[3]), Double.parseDouble(f[4])};
        } catch (Exception e) {
            return null;
        }
    }
}
