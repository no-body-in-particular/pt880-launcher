package org.watchlauncher;

import java.util.List;

/**
 * What a hold on button A gets you from the launcher itself. Everything here
 * is about the watch rather than about one app, which is why it is not on the
 * launcher's own list -- five apps and three settings in the same list would
 * mean scrolling past the settings to reach the camera every time.
 */
public class SystemMenuScreen extends ListScreen {

    @Override
    public String title() { return "Watch"; }

    @Override
    protected List<Item> items() {
        List<Item> l = list();
        l.add(new Item("Screen", shell.keepAwake() ? "always on" : "normal", AppIcons.GEAR));
        l.add(new Item("Buttons", shell.twoButtons() ? "two" : "one", AppIcons.GEAR));
        //Order here is the switch below. They were out of step: the Sleep log row was lost to
        //a failed edit while its case survived, so every row under Buttons ran the action
        //belonging to the row above it - Root shell toggled sleep logging, About opened the
        //terminal. Anything added here needs a case, and anything removed needs its case
        //taken out with it.
        l.add(new Item("Sleep log", SleepLog.enabled(shell) ? "on" : "off", AppIcons.GEAR));
        l.add(new Item("Diagnostics", WatchdogReport.enabled(shell) ? "on" : "off", AppIcons.GEAR));
        l.add(new Item("Root shell", shell.root().describe(), AppIcons.TERMINAL));
        l.add(new Item("About", null, AppIcons.DEVICE));
        addBack(l);
        l.add(new Item("Exit app", null, AppIcons.BACK, Ui.WARN));
        return l;
    }

    @Override
    protected void onPick(int index) {
        switch (index) {
            case 0:
                shell.setKeepAwake(!shell.keepAwake());
                render();
                break;
            case 1:
                // Only ever forced back to one-button: two-button mode sets
                // itself the first time a real second key arrives, and there
                // is no way to fake one from here.
                shell.prefs().edit().putBoolean("twoButtons", false).commit();
                shell.toast("One-button mode after restart");
                break;
            case 2:
                boolean on = !SleepLog.enabled(shell);
                SleepLog.setEnabled(shell, on);

                if (on) {
                    SleepService.schedule(shell, 10000);

                } else {
                    // Cancel the alarm as well as clearing the flag. Leaving it
                    // armed means one more burst fires after switching off,
                    // which is the sort of thing that makes an A/B say nothing.
                    SleepService.cancel(shell);
                }

                shell.toast(on ? "Sleep logging on" : "Sleep logging off");
                render();
                break;
            case 3:
                boolean diag = !WatchdogReport.enabled(shell);
                WatchdogReport.setEnabled(shell, diag);
                shell.toast(diag ? "Diagnostics on" : "Diagnostics off");
                render();
                break;
            case 4:
                shell.push(new TermScreen());
                break;
            case 5:
                shell.push(new AboutScreen());
                break;
            case 6:
                shell.pop();
                break;
            default:
                shell.quit();
                break;
        }
    }
}
