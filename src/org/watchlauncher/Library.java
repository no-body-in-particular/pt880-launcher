package org.watchlauncher;

import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Walks local storage looking for playable audio. No MediaStore: the media
 *  scanner is unreliable on this build, and a direct walk finds files that were
 *  pushed over adb straight away. */
public class Library {

    private static final String[] EXTS = {
        ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".oga", ".flac", ".mid", ".mkv", ".mp4", ".3gp"
    };

    /** Directories that only ever hold app data, logs or firmware blobs. */
    private static final String[] SKIP_DIRS = {
        "Android", "carota_core", "EqcLog", "LOST.DIR", "data", "obb"
    };

    private static final int MAX_DEPTH = 6;
    private static final int MAX_TRACKS = 2000;

    public static class Track {
        public final File file;
        public final String title;

        Track(File f) {
            file = f;
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            title = (dot > 0) ? n.substring(0, dot) : n;
        }
    }

    public static List<Track> scan() {
        List<File> roots = new ArrayList<File>();
        roots.add(Environment.getExternalStorageDirectory());
        addIfDir(roots, "/storage/sdcard1");
        addIfDir(roots, "/storage/usbdisk");
        addIfDir(roots, "/data/media/0");

        List<Track> out = new ArrayList<Track>();
        List<String> seen = new ArrayList<String>();
        for (int i = 0; i < roots.size(); i++) {
            File r = roots.get(i);
            String key = canon(r);
            if (seen.contains(key)) continue;
            seen.add(key);
            walk(r, out, 0);
        }

        Collections.sort(out, new Comparator<Track>() {
            public int compare(Track a, Track b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        return out;
    }

    private static void addIfDir(List<File> list, String path) {
        File f = new File(path);
        if (f.isDirectory() && f.canRead()) list.add(f);
    }

    private static String canon(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }

    private static void walk(File dir, List<Track> out, int depth) {
        if (depth > MAX_DEPTH || out.size() >= MAX_TRACKS) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (int i = 0; i < kids.length; i++) {
            File f = kids[i];
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (isSkipped(name)) continue;
                walk(f, out, depth + 1);
            } else if (isAudio(name) && f.length() > 8192 && f.canRead()) {
                out.add(new Track(f));
                if (out.size() >= MAX_TRACKS) return;
            }
        }
    }

    private static boolean isSkipped(String name) {
        for (int i = 0; i < SKIP_DIRS.length; i++) {
            if (SKIP_DIRS[i].equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static boolean isAudio(String name) {
        String l = name.toLowerCase();
        for (int i = 0; i < EXTS.length; i++) {
            if (l.endsWith(EXTS[i])) return true;
        }
        return false;
    }
}
