package org.watchlauncher;

import android.content.Context;

/**
 * Starts musicsync -- the daemon that keeps /sdcard/Music holding exactly what
 * is in the server's device_playlist folder, and nothing else.
 *
 * The daemon is not part of the APK. It is a native binary that
 * install-musicsync.sh puts in /data/misc/musicsync, a directory that is
 * root-owned and 0700 because the client certificate it authenticates with
 * lives beside it. This app cannot so much as stat that path, so there is
 * nothing worth checking before trying: the command either runs or it does
 * not, and the daemon's own log is where the answer would be read anyway.
 *
 * Calling this twice is harmless. musicsync keeps a pidfile and refuses to run
 * beside a copy of itself, so every boot and every launcher start can ask for
 * it without any bookkeeping here.
 */
public class MusicSync {

    private static final String BIN = "/data/misc/musicsync/musicsync";

    public static void start(Context c) {
        // Bringing a root shell up takes a second or two, and BootReceiver's
        // onReceive runs on the main thread -- which is exactly how a boot
        // receiver earns an ANR.
        new Thread(new Runnable() {
            public void run() {
                RootShell sh = new RootShell();
                try {
                    if (!sh.open() || !sh.isRoot()) return;
                    sh.runQuiet(BIN);
                } catch (Exception e) {
                    // Nothing here is fixable at runtime and there is no one to
                    // tell: the daemon logs its own startup either way.
                } finally {
                    try { sh.close(); } catch (Exception e) { /* ignore */ }
                }
            }
        }, "musicsync-start").start();
    }
}
