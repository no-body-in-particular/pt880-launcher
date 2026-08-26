package org.watchlauncher;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
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
    public String title() {
        if (busy.length() > 0) return busy;
        String live = MapDownload.progress();
        return live != null ? live : "Map";
    }

    @Override
    public void tick() {
        if (busy.length() > 0 || MapDownload.running()) render();
    }

    /**
     * Rows, and what each one does, built together.
     *
     * They used to be a list here and a switch on the row number over there,
     * and every time the rows changed the switch had to be renumbered by hand
     * - which went wrong three times, most recently sending "Retry position"
     * to the storage readout. Pairing them at the point of construction means
     * a row cannot be wired to its neighbour's action.
     */
    private final List<Runnable> actions = new ArrayList<Runnable>();

    private void row(List<Item> l, Item it, Runnable r) {
        l.add(it);
        actions.add(r);
    }

    private static final Runnable NOTHING = new Runnable() { public void run() { } };

    @Override
    protected List<Item> items() {
        List<Item> l = list();
        actions.clear();

        Destination d = map.target();
        row(l, new Item("Route to", d == null ? "no destination" : d.name, AppIcons.CALL),
                new Runnable() { public void run() { routeTo(); } });

        row(l, new Item("Reload destination", null, AppIcons.GEAR),
                new Runnable() { public void run() {
                    map.reloadDestination();
                    shell.toast(Destination.file() == null
                            ? makeExample() : "destination reloaded");
                    render();
                } });

        String live = MapDownload.progress();
        if (live != null) {
            // One row, not one per download kind: there is a single job, and
            // two identical Stop buttons only invited the question of which.
            row(l, new Item("Stop download", live, AppIcons.DEVICE),
                    new Runnable() { public void run() {
                        MapDownload.cancelCurrent();
                        shell.toast("stopping");
                        render();
                    } });
            row(l, new Item("Downloading", "hold to stop", AppIcons.NONE, Ui.DIM),
                    NOTHING);
        } else {
            row(l, new Item("Download area",
                            MapDownload.AREA_RADIUS_KM + " km around you",
                            AppIcons.DEVICE),
                    new Runnable() { public void run() { downloadArea(); } });
            row(l, new Item("Download country",
                            map.country() == null ? "unknown" : map.country(),
                            AppIcons.DEVICE),
                    new Runnable() { public void run() { downloadCountry(); } });
            row(l, new Item("Download route", null, AppIcons.DEVICE),
                    new Runnable() { public void run() { downloadRoute(); } });
        }

        File gf = map.country() == null ? null : RoadGraph.fileFor(map.country());
        boolean haveGraph = gf != null && gf.isFile() && gf.length() > 1024;
        row(l, new Item("Roads for routing",
                        map.country() == null ? "country unknown"
                                : (haveGraph ? MapTiles.mb(gf.length()) + " on card"
                                             : "with Download area"),
                        AppIcons.NONE, haveGraph ? Ui.OK : Ui.DIM), NOTHING);

        row(l, new Item("Storage", MapTiles.mb(MapTiles.bytesOnCard()),
                AppIcons.NONE, Ui.DIM), NOTHING);

        long dead = MapTiles.reclaimable(map.country(), MapDownload.COUNTRY_ZOOM);
        row(l, new Item("Clean up",
                        dead > 0 ? ("free " + MapTiles.mb(dead)) : "nothing to free",
                        AppIcons.GEAR, dead > 0 ? Ui.FG : Ui.DIM),
                new Runnable() { public void run() { cleanUp(); } });

        row(l, new Item("Retry position", map.why().length() > 0 ? map.why() : "ok",
                        AppIcons.GEAR),
                new Runnable() { public void run() { map.retrySeed(); render(); } });

        row(l, new Item("Network", map.tiles().onWifi() ? "wifi"
                        : (map.tiles().online() ? "mobile" : "offline"),
                        AppIcons.NONE, Ui.DIM), NOTHING);

        int before = l.size();
        addBack(l);
        for (int i = before; i < l.size(); i++) {
            actions.add(new Runnable() { public void run() { shell.pop(); } });
        }

        row(l, new Item("Exit map", null, AppIcons.HOME),
                new Runnable() { public void run() { shell.popToRoot(); } });
        return l;
    }

    @Override
    protected void onPick(int index) {
        if (index >= 0 && index < actions.size()) actions.get(index).run();
    }

    /**
     * Delete maps nothing draws any more: other countries, and zoom levels
     * this build does not use.
     *
     * It never touches the country in use at the zoom in use, so it cannot
     * remove the map under your feet, and it says how much it freed rather
     * than doing it quietly.
     */
    private void cleanUp() {
        if (busy.length() > 0) return;
        final String keep = map.country();
        busy = "cleaning...";
        render();
        new Thread(new Runnable() {
            public void run() {
                final long freed = MapTiles.cleanup(keep, MapDownload.COUNTRY_ZOOM);
                shell.runOnUiThread(new Runnable() {
                    public void run() {
                        busy = "";
                        MapTiles.forgetSizes();
                        shell.toast(freed > 0 ? ("freed " + MapTiles.mb(freed))
                                              : "nothing to free");
                        render();
                    }
                });
            }
        }).start();
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
        if (!map.tiles().online() && !map.canRouteOffline()) {
            shell.toast("offline, no road graph");
            return;
        }
        if (busy.length() > 0) return;

        busy = "routing...";
        render();
        final double la = map.lat(), lo = map.lon();
        new Thread(new Runnable() {
            public void run() {
                // On-device first, so a route can be asked for with no
                // network at all; the server is the fallback, not the plan.
                Route found = map.routeHere(la, lo, d.lat, d.lon);
                if (found == null) {
                    File out = new File(MapTiles.DIR + "/route.bin");
                    out.getParentFile().mkdirs();
                    String url = map.tiles().base() + "route.php"
                            + "?flat=" + la + "&flon=" + lo
                            + "&tlat=" + d.lat + "&tlon=" + d.lon;
                    if (map.tiles().download(url, out)) found = Route.read(out);
                }
                final Route r = found;
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

    /**
     * The area you are in: tiles to look at and roads to route along, in one
     * go, for a box around the current position.
     *
     * This is the download people actually want. A country is a fine thing to
     * own and a poor thing to wait for.
     */
    private void downloadArea() {
        if (MapDownload.running()) { bulk(true, null); return; }
        final String c = map.country();
        if (c == null) { shell.toast("country unknown"); return; }
        if (!map.hasFix()) { shell.toast("no position yet"); return; }
        if (!map.tiles().onWifi()) { shell.toast("needs wifi"); return; }
        if (busy.length() > 0) return;
        area(c);
    }

    private void area(final String country) {
        final MapDownload job = MapDownload.claim();
        if (job == null) {
            MapDownload.cancelCurrent();
            shell.toast("stopping download");
            render();
            return;
        }
        busy = "starting...";
        render();
        final double la = map.lat(), lo = map.lon();

        new Thread(new Runnable() {
            public void run() {
                MapDownload.Progress p = new MapDownload.Progress() {
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
                int failed = job.area(map.tiles(), country, la, lo,
                        MapDownload.AREA_RADIUS_KM, p);
                long freed = MapTiles.enforceLimit(map.keepBoxes());
                String msg;
                if (failed < 0) {
                    msg = "needs wifi";
                } else if (!job.graphOk()) {
                    msg = "map ready, no roads";
                } else if (failed == 0) {
                    msg = freed > 0 ? ("area ready, freed " + MapTiles.mb(freed))
                                    : "area ready";
                } else {
                    msg = failed + " tiles missing";
                }
                job.release();
                final String out = msg;
                shell.runOnUiThread(new Runnable() {
                    public void run() {
                        busy = "";
                        shell.toast(out);
                        render();
                    }
                });
            }
        }).start();
    }

    private void downloadCountry() {
        if (MapDownload.running()) { bulk(true, null); return; }
        final String c = map.country();
        if (c == null) { shell.toast("country unknown"); return; }
        if (!map.tiles().onWifi()) { shell.toast("needs wifi"); return; }
        if (busy.length() > 0) return;
        bulk(true, c);
    }

    private void downloadRoute() {
        if (MapDownload.running()) { bulk(false, null); return; }
        final String c = map.country();
        if (c == null) { shell.toast("country unknown"); return; }
        if (!map.tiles().onWifi()) { shell.toast("needs wifi"); return; }
        if (busy.length() > 0) return;
        bulk(false, c);
    }

    private void bulk(final boolean whole, final String country) {
        final MapDownload job = MapDownload.claim();
        if (job == null) {                      // one is already running
            MapDownload.cancelCurrent();
            shell.toast("stopping download");
            render();
            return;
        }
        busy = "starting...";
        render();

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
                MapTiles.enforceLimit(map.keepBoxes());
                String msg;
                if (failed < 0) {
                    msg = "needs wifi";
                } else if (failed == 0) {
                    int had = job.skipped();
                    msg = had > 0 ? ("done, " + had + " already had") : "map downloaded";
                } else {
                    msg = failed + " tiles missing";
                }
                finish(msg);
            }

            private void finish(final String msg) {
                job.release();
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
