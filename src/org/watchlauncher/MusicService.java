package org.watchlauncher;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/** Holds the MediaPlayer so audio survives the screen going off, and so that
 *  leaving the music screen for the camera or a call does not stop playback.
 *  It is the one part of the app that outlives the screen showing it. */
public class MusicService extends Service
        implements MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {

    public static final String ACTION_TOGGLE = "org.watchlauncher.TOGGLE";
    public static final String ACTION_PLAY   = "org.watchlauncher.PLAY";
    public static final String ACTION_PAUSE  = "org.watchlauncher.PAUSE";
    public static final String ACTION_NEXT   = "org.watchlauncher.NEXT";
    public static final String ACTION_PREV   = "org.watchlauncher.PREV";

    private static final int NOTE_ID = 1;

    public interface Listener { void onPlayerChanged(); }

    public class LocalBinder extends Binder {
        public MusicService get() { return MusicService.this; }
    }

    private final IBinder binder = new LocalBinder();

    private MediaPlayer mp;
    private List<Library.Track> tracks = new ArrayList<Library.Track>();
    private int index = 0;
    private boolean playing = false;
    private String note = "";
    /** Consecutive tracks that would not open. Bounds the skipping that a
     *  folder emptied out under us would otherwise turn into a run through the
     *  whole library. The first track that plays resets it. */
    private int loadFailures = 0;
    private Listener listener;
    private PowerManager.WakeLock wake;
    private AudioManager audio;
    private ComponentName mediaButtons;

    @Override
    public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchlauncher.music");
        wake.setReferenceCounted(false);
        mediaButtons = new ComponentName(this, MediaButtonReceiver.class);
        rescan();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = (intent == null) ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(a)) toggle();
        else if (ACTION_PLAY.equals(a)) play();
        else if (ACTION_PAUSE.equals(a)) pause();
        else if (ACTION_NEXT.equals(a)) next();
        else if (ACTION_PREV.equals(a)) prev();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    public void setListener(Listener l) { listener = l; }

    private void changed() {
        if (listener != null) listener.onPlayerChanged();
        if (playing) updateNotification();
    }

    // ---- library ----------------------------------------------------------

    public void rescan() {
        int at = reload(current());
        index = (at >= 0) ? at : 0;
        if (index >= tracks.size()) index = 0;
        note = tracks.isEmpty() ? "No music found" : "";
        changed();
    }

    /**
     * Re-read the library and report where {@code stay} ended up, or -1 if it
     * is no longer there.
     *
     * musicsync rewrites /sdcard/Music while this service is running -- that is
     * its whole job -- so an index into the previous scan means nothing once it
     * has. The file is the only thing worth carrying across a scan, and every
     * reload goes through here so the track someone is listening to stays under
     * the cursor even when the list around it moved.
     */
    private int reload(Library.Track stay) {
        String path = (stay == null) ? null : stay.file.getAbsolutePath();
        tracks = Library.scan();
        if (path != null) {
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i).file.getAbsolutePath().equals(path)) return i;
            }
        }
        return -1;
    }

    private Library.Track current() {
        if (index < 0 || index >= tracks.size()) return null;
        return tracks.get(index);
    }

    /** The library emptied out underneath us. */
    private void noMusic() {
        index = 0;
        note = "No music found";
        playing = false;
        release();
        changed();
    }

    /**
     * A track would not open. Re-read the library first, because the usual
     * reason on this watch is that musicsync deleted the file while its name
     * was still on screen, and then move on -- but only when the file really
     * has gone. Skipping on every error would run through the whole folder in
     * a second the first time a decoder simply disliked something, so a track
     * that is still on disk stops here and says so.
     */
    private void onLoadFailed(Library.Track failed) {
        int was = index;
        int at = reload(failed);

        if (tracks.isEmpty()) { noMusic(); return; }

        if (at >= 0) {                 // still on disk: a real decode problem
            index = at;
            changed();
            return;
        }

        if (++loadFailures > tracks.size()) {
            loadFailures = 0;
            note = "Nothing here will play";
            changed();
            return;
        }

        // Gone. The slot it used to hold is the closest thing to "where we
        // were" that survived the scan.
        playIndex(was);
    }

    public List<Library.Track> tracks() { return tracks; }
    public int index() { return index; }
    public boolean isPlaying() { return playing; }
    public String note() { return note; }

    public String title() {
        if (tracks.isEmpty()) return "No music";
        return tracks.get(index).title;
    }

    public int position() { 
        try { return (mp != null) ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    public int duration() {
        try { return (mp != null) ? mp.getDuration() : 0; } catch (Exception e) { return 0; }
    }

    // ---- transport --------------------------------------------------------

    public void toggle() { if (playing) pause(); else play(); }

    public void playIndex(int i) {
        if (tracks.isEmpty()) return;
        index = ((i % tracks.size()) + tracks.size()) % tracks.size();
        openAndStart();
    }

    public void play() {
        if (tracks.isEmpty()) { note = "No music found"; changed(); return; }
        if (mp == null) { openAndStart(); return; }
        try {
            mp.start();
            playing = true;
            wake.acquire();
            audio.registerMediaButtonEventReceiver(mediaButtons);
            updateNotification();
        } catch (Exception e) {
            note = "Play failed";
        }
        changed();
    }

    public void pause() {
        try {
            if (mp != null && mp.isPlaying()) mp.pause();
        } catch (Exception e) { /* player already torn down */ }
        playing = false;
        if (wake.isHeld()) wake.release();
        stopForeground(true);
        changed();
    }

    /**
     * Skipping is the other moment the library gets re-read. musicsync may
     * have added or removed tracks since the last scan, and the entire point
     * of a skip is to land on something that is actually there.
     */
    public void next() {
        Library.Track from = current();
        int was = index;
        int at = reload(from);
        if (tracks.isEmpty()) { noMusic(); return; }
        playIndex(at < 0 ? was : at + 1);
    }

    public void prev() {
        Library.Track from = current();
        int was = index;
        // Restarting the track already playing is not a skip, and scanning the
        // card to do it would be a scan for nothing.
        if (position() > 3000 && from != null) { playIndex(was); return; }
        int at = reload(from);
        if (tracks.isEmpty()) { noMusic(); return; }
        playIndex(at < 0 ? was : at - 1);
    }

    private void openAndStart() {
        release();
        Library.Track t = current();
        if (t == null) { noMusic(); return; }
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(t.file.getAbsolutePath());
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            mp.prepare();
            mp.start();
            playing = true;
            note = "";
            wake.acquire();
            audio.registerMediaButtonEventReceiver(mediaButtons);
            updateNotification();
            loadFailures = 0;
        } catch (Exception e) {
            note = "Cannot play " + t.title;
            playing = false;
            release();
            // Most likely the file is simply not there any more. onLoadFailed
            // re-reads the library and reports the result, so nothing below.
            onLoadFailed(t);
            return;
        }
        changed();
    }

    private void release() {
        if (mp != null) {
            try { mp.reset(); mp.release(); } catch (Exception e) { /* ignore */ }
            mp = null;
        }
    }

    public void onCompletion(MediaPlayer m) { next(); }

    public boolean onError(MediaPlayer m, int what, int extra) {
        Library.Track t = current();
        note = "Decode error";
        release();
        playing = false;
        onLoadFailed(t);
        return true;
    }

    // ---- notification -----------------------------------------------------

    private void updateNotification() {
        Intent i = new Intent(this, ShellActivity.class);
        i.putExtra(ShellActivity.EXTRA_APP, "music");
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title())
                .setContentText("Playing")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTE_ID, n);
    }

    @Override
    public void onDestroy() {
        release();
        if (wake != null && wake.isHeld()) wake.release();
        try { audio.unregisterMediaButtonEventReceiver(mediaButtons); } catch (Exception e) { /* ignore */ }
        super.onDestroy();
    }
}
