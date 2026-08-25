package org.watchlauncher;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The home screen: five apps, one per row, moved through and picked with the
 * same two buttons as everything else.
 *
 * It replaces the stock launcher, which is a clock face with no app list and
 * no key handling -- once you left an app on this watch there was no way back
 * in short of adb. This is the bottom of the screen stack, so every app can
 * always back out to somewhere.
 *
 * The heading carries the date. The clock is already in the status bar two
 * pixels above it, and a watch that shows the time but not the day is only
 * half a watch.
 */
public class LauncherScreen extends ListScreen {

    private static final SimpleDateFormat DATE =
            new SimpleDateFormat("EEE d MMM", Locale.getDefault());

    private String shownDate = "";

    @Override
    public String title() { return DATE.format(new Date()); }

    /** The apps, in the order they appear. One table rather than a list built
     *  in one method and a switch in another, so a row can never open the app
     *  above it. */
    private static final String[] NAMES =
            {"Music", "Sports", "Bluetooth", "Camera", "Call", "Terminal"};
    private static final int[] GLYPHS = {
        AppIcons.MUSIC, AppIcons.HEART, AppIcons.BLUETOOTH,
        AppIcons.CAMERA, AppIcons.CALL, AppIcons.TERMINAL,
    };

    @Override
    protected List<Item> items() {
        List<Item> l = list();
        for (int i = 0; i < NAMES.length; i++) {
            l.add(new Item(NAMES[i], null, GLYPHS[i]));
        }
        return l;
    }

    @Override
    protected void onPick(int index) {
        Screen s = open(NAMES[index]);
        if (s != null) shell.push(s);
    }

    private static Screen open(String name) {
        if (name.equals("Music")) return new MusicScreen();
        if (name.equals("Sports")) return new SportsScreen();
        if (name.equals("Bluetooth")) return new BtScreen();
        if (name.equals("Camera")) return new CameraScreen();
        if (name.equals("Call")) return new CallScreen();
        if (name.equals("Terminal")) return new TermScreen();
        return null;
    }

    /** The launcher is where the watch sits when nothing is happening, so the
     *  once-a-second tick has to cost nothing. The only thing on this screen
     *  that ever changes is the date, and that once a day. */
    @Override
    public void tick() {
        if (!title().equals(shownDate)) render();
    }

    @Override
    public void render() {
        shownDate = title();
        super.render();
    }

    @Override
    public String hint() {
        return shell.twoButtons() ? "A:open  hold:menu  B:down  B hold:up"
                                  : "tap:move  hold:open  hold2s:menu";
    }

    /** Resolve {@code am start -e app <name>}. The incoming-call case is
     *  handled by the activity itself, which has the caller's number. */
    static Screen byName(String app, ShellActivity shell) {
        if (app == null) return null;
        String a = app.trim().toLowerCase(Locale.US);
        if (a.equals("music") || a.equals("player")) return new MusicScreen();
        if (a.equals("sports") || a.equals("sport")) return new SportsScreen();
        if (a.equals("bluetooth") || a.equals("bt")) return new BtScreen();
        if (a.equals("camera")) return new CameraScreen();
        if (a.equals("call") || a.equals("phone")) return new CallScreen();
        if (a.equals("terminal") || a.equals("term") || a.equals("shell")) return new TermScreen();
        return null;
    }
}
