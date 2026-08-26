package org.watchlauncher;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of the places in destination.txt to head for.
 *
 * The file has always held a list - one per line - and the map has always
 * taken the first and ignored the rest, which made the other lines look like
 * a feature that did not work. This is that feature.
 *
 * Each row carries how far away it is, because on a watch with no keyboard
 * the distance is the only thing that distinguishes two names you wrote
 * months ago.
 */
public class DestinationScreen extends ListScreen {

    private final MapScreen map;
    private List<Destination> all = new ArrayList<Destination>();

    public DestinationScreen(MapScreen map) {
        this.map = map;
    }

    @Override
    public String title() { return "Route to"; }

    @Override
    protected List<Item> items() {
        all = Destination.load();
        List<Item> l = list();

        if (all.isEmpty()) {
            l.add(new Item("No destinations", "see Documents", AppIcons.NONE, Ui.DIM));
            addBack(l);
            return l;
        }

        Destination current = map.target();
        for (int i = 0; i < all.size(); i++) {
            Destination d = all.get(i);
            String away = distanceTo(d);
            boolean chosen = current != null && current.name.equals(d.name);
            l.add(new Item(d.name, away, chosen ? AppIcons.HOME : AppIcons.CALL,
                    chosen ? Ui.OK : Ui.FG));
        }
        addBack(l);
        return l;
    }

    /** Blank rather than a guess when there is no fix: "12 km" that is
     *  measured from the middle of the country is worse than nothing. */
    private String distanceTo(Destination d) {
        if (!map.hasFix()) return null;
        int m = (int) Route.metresBetween(map.lat(), map.lon(), d.lat, d.lon);
        return m >= 1000 ? ((m / 100) / 10.0 + " km") : (m + " m");
    }

    @Override
    protected void onPick(int index) {
        if (index < 0 || index >= all.size()) {      // Back, or the empty row
            shell.pop();
            return;
        }
        Destination d = all.get(index);
        map.setTarget(d);
        shell.pop();
        shell.toast("routing to " + d.name);
        map.routeToTarget();
    }

    @Override
    public String hint() {
        return shell.twoButtons() ? "A:go  B:down" : "tap:move  hold:go";
    }
}
