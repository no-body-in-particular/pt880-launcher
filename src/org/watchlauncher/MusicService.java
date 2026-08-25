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
        int keep = index;
        tracks = Library.scan();
        if (keep >= tracks.size()) keep = 0;
        index = keep;
        note = tracks.isEmpty() ? "No music found" : "";
        changed();
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

    public void next() { if (!tracks.isEmpty()) playIndex(index + 1); }
    public void prev() {
        if (tracks.isEmpty()) return;
        if (position() > 3000) { playIndex(index); return; }   // restart current first
        playIndex(index - 1);
    }

    private void openAndStart() {
        release();
        Library.Track t = tracks.get(index);
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
        } catch (Exception e) {
            note = "Cannot play " + t.title;
            playing = false;
            release();
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
        note = "Decode error";
        release();
        playing = false;
        changed();
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
