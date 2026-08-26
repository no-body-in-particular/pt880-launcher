package org.watchlauncher;

import android.content.Context;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.HashMap;
import java.util.Locale;

/**
 * Spoken turn instructions.
 *
 * Modelled on the voice code in org.claudewatch, which demonstrably speaks on
 * this hardware. The first version of this class did not, and the differences
 * were all small and all mine:
 *
 * <ul>
 *   <li>it asked for {@code Locale.getDefault()}, which on a watch set to
 *       Dutch is a language Pico does not have. Pico ships en-US, en-GB, de,
 *       es, fr and it, and asking it for anything else fails quietly;
 *   <li>it marked itself ready before finding out whether a voice had loaded,
 *       so a failure to set the language looked exactly like success;
 *   <li>it never set a volume, and Pico's default rate clips words on a
 *       speaker this small.
 * </ul>
 *
 * Output goes to STREAM_MUSIC, which is the stream A2DP carries: the
 * instruction follows the headphones when they are connected and falls back
 * to the case speaker when they are not, the same routing the music player
 * uses, which is why no choice has to be offered.
 */
public class Speech {

    private static final String TAG = "watchvoice";

    /** Pico's default clips words on a small speaker; slower is clearer. */
    private static final float RATE = 0.85f;

    /** The same phrase inside this window is a repeat, not a new instruction. */
    private static final long REPEAT_MS = 20000;

    private TextToSpeech tts;
    private volatile boolean ready = false;
    private volatile String status = "starting";

    private String last = "";
    private long lastAt = 0;

    /** Said as soon as the engine is ready, if something was asked for while
     *  it was still starting. Only the most recent: an instruction that has
     *  been overtaken is not worth catching up on. */
    private volatile String pending = null;

    private AudioManager audio;

    public Speech(Context c) {
        final Context app = c.getApplicationContext();
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        try {
            tts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
                public void onInit(int code) {
                    if (code != TextToSpeech.SUCCESS) {
                        status = "engine failed";
                        Log.e(TAG, "tts init failed, status=" + code);
                        return;
                    }
                    status = pickLanguage();
                    try {
                        tts.setSpeechRate(RATE);
                        tts.setPitch(1.0f);
                    } catch (Exception e) { /* the defaults will do */ }

                    if (audio != null) {
                        Log.i(TAG, "music volume "
                                + audio.getStreamVolume(AudioManager.STREAM_MUSIC) + "/"
                                + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                + " a2dp=" + audio.isBluetoothA2dpOn());
                    }

                    String queued = pending;
                    pending = null;
                    if (ready && queued != null) say(queued);
                }
            });
        } catch (Exception e) {
            status = "no engine";
            Log.e(TAG, "tts unavailable: " + e);
            tts = null;
        }
    }

    /**
     * The first language Pico actually has.
     *
     * Asked for in order of usefulness rather than of preference: the point is
     * that something speaks. Ready is only set once one of them loads, so a
     * silent engine reports itself as silent instead of pretending.
     */
    private String pickLanguage() {
        Locale[] wanted = { Locale.US, Locale.UK, Locale.getDefault(), Locale.ENGLISH };
        for (int i = 0; i < wanted.length; i++) {
            try {
                int r = tts.setLanguage(wanted[i]);
                if (r != TextToSpeech.LANG_MISSING_DATA
                        && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                    ready = true;
                    String engine = "";
                    try { engine = String.valueOf(tts.getDefaultEngine()); } catch (Exception e) { }
                    Log.i(TAG, "tts ready, " + wanted[i] + " on " + engine);
                    return wanted[i].toString() + " ok";
                }
            } catch (Exception e) {
                Log.w(TAG, "setLanguage(" + wanted[i] + ") threw: " + e);
            }
        }
        Log.e(TAG, "tts has no voice data for any language tried");
        return "no voice data";
    }

    public boolean ready() { return ready; }

    /** What the engine is doing, for the About screen and for saying so out
     *  loud when someone asks why it is quiet. */
    public String status() { return status; }

    /** Say it, unless it is the same thing we just said. */
    public void say(String phrase) {
        if (phrase == null || phrase.length() == 0) return;
        if (!ready || tts == null) {
            // Held rather than dropped. The engine takes a second or two to
            // start and the first thing anyone hears is "route found", which
            // was landing in that window every time.
            pending = phrase;
            Log.w(TAG, "tts not ready (" + status + "), holding: " + phrase);
            return;
        }
        long now = System.currentTimeMillis();
        if (phrase.equals(last) && now - lastAt < REPEAT_MS) return;
        last = phrase;
        lastAt = now;
        try {
            HashMap<String, String> params = new HashMap<String, String>();
            params.put(TextToSpeech.Engine.KEY_PARAM_STREAM,
                    String.valueOf(AudioManager.STREAM_MUSIC));
            params.put(TextToSpeech.Engine.KEY_PARAM_VOLUME, "1.0");
            Log.i(TAG, "say: " + phrase);
            // ADD, not FLUSH: two instructions rarely fall together, and when
            // they do, cutting the first one off mid-word is worse than
            // hearing both.
            tts.speak(phrase, TextToSpeech.QUEUE_ADD, params);
        } catch (Exception e) {
            Log.w(TAG, "speak failed: " + e);
        }
    }

    /** Say it even if it was just said - for a test, where silence is the
     *  thing being investigated. */
    public void sayAgain(String phrase) {
        last = "";
        say(phrase);
    }

    public void stop() {
        if (tts == null) return;
        try { tts.stop(); tts.shutdown(); } catch (Exception e) { /* ignore */ }
        tts = null;
        ready = false;
        status = "stopped";
    }
}
