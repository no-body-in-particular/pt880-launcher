package org.watchlauncher;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.HashMap;
import java.util.Locale;

/**
 * Spoken turn instructions.
 *
 * The watch ships com.svox.pico, so this needs no extra install. Output goes
 * to STREAM_MUSIC, which means it follows the headphones when they are
 * connected and falls back to the case speaker when they are not -- the same
 * routing the music player uses, and the reason no choice has to be offered.
 *
 * Engine startup is asynchronous and takes a second or two, which is fine for
 * navigation: nothing needs saying until the first turn is approaching. A
 * phrase asked for before the engine is ready is dropped rather than queued,
 * because a turn instruction that arrives late is worse than one that never
 * arrives at all.
 */
public class Speech {

    private TextToSpeech tts;
    private boolean ready = false;
    private String last = "";
    private long lastAt = 0;

    /** The same phrase inside this window is a repeat, not a new instruction. */
    private static final long REPEAT_MS = 20000;

    public Speech(Context c) {
        try {
            tts = new TextToSpeech(c.getApplicationContext(),
                    new TextToSpeech.OnInitListener() {
                public void onInit(int status) {
                    if (status != TextToSpeech.SUCCESS) return;
                    ready = true;
                    try {
                        // The watch's own locale, so "two hundred metres" is
                        // said the way its owner reads it.
                        int r = tts.setLanguage(Locale.getDefault());
                        if (r == TextToSpeech.LANG_MISSING_DATA
                                || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.setLanguage(Locale.UK);
                        }
                    } catch (Exception e) { /* the default will do */ }
                }
            });
        } catch (Exception e) {
            tts = null;
        }
    }

    public boolean ready() { return ready; }

    /** Say it, unless it is the same thing we just said. */
    public void say(String phrase) {
        if (!ready || tts == null || phrase == null || phrase.length() == 0) return;
        long now = System.currentTimeMillis();
        if (phrase.equals(last) && now - lastAt < REPEAT_MS) return;
        last = phrase;
        lastAt = now;
        try {
            HashMap<String, String> params = new HashMap<String, String>();
            // STREAM_MUSIC rather than STREAM_NOTIFICATION: it is the stream
            // A2DP carries, so the instruction reaches the headphones.
            params.put(TextToSpeech.Engine.KEY_PARAM_STREAM,
                    String.valueOf(android.media.AudioManager.STREAM_MUSIC));
            // FLUSH, not ADD: if two instructions are due at once the newer one
            // is the one that matters, and a queue would say them in the wrong
            // order relative to the junction.
            tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, params);
        } catch (Exception e) { /* nothing to be done about it mid-drive */ }
    }

    public void stop() {
        if (tts == null) return;
        try { tts.stop(); tts.shutdown(); } catch (Exception e) { /* ignore */ }
        tts = null;
        ready = false;
    }
}
