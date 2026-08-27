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

    /**
     * How fast it talks.
     *
     * This was 0.85 - slower than Pico's default - because words were coming
     * out clipped on this speaker. That was the wrong fix for the right
     * symptom: what was actually being lost was the beginning of each
     * utterance, while the amplifier woke up, and slowing the whole phrase
     * down only meant more of it arrived after the amplifier was ready.
     *
     * The silence queued ahead of the words deals with that properly, so the
     * rate no longer has to compensate for it. Slightly above default: a turn
     * instruction is three or four words and the useful thing is that it
     * finishes before the junction.
     */
    private static final float RATE = 1.15f;

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

                    // Deprecated on modern Android and the only one that
                    // exists on this one: UtteranceProgressListener arrived in
                    // API 15 but the engine here reports through the old
                    // callback, and the point is to know when the queue is
                    // empty so the audio path can be handed back.
                    try {
                        tts.setOnUtteranceCompletedListener(
                                new TextToSpeech.OnUtteranceCompletedListener() {
                            public void onUtteranceCompleted(String id) {
                                doneSpeaking();
                            }
                        });
                    } catch (Throwable e) {
                        Log.w(TAG, "no utterance callback: " + e);
                    }

                    if (audio != null) {
                        Log.i(TAG, "music volume "
                                + audio.getStreamVolume(AudioManager.STREAM_MUSIC) + "/"
                                + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                + " a2dp=" + audio.isBluetoothA2dpOn());
                        raiseSpeakerVolume();
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

    /** Whether stop() has been called and this instance is spent. */
    public boolean stopped() { return tts == null; }

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
        utter(phrase);
    }

    /**
     * Put a phrase out, having made the path ready to carry it.
     *
     * Three things have to be true for a turn instruction to be heard on the
     * watch's own speaker, and only the first was being done.
     *
     * <h3>The stream</h3>
     *
     * STREAM_MUSIC, which A2DP carries, so the instruction follows the
     * headphones when they are connected and falls back to the case speaker
     * when they are not.
     *
     * <h3>The volume</h3>
     *
     * That stream can be sitting at zero. On this watch the media volume is
     * whatever the music player was last left at, and a watch that has only
     * ever been used with headphones is quite likely to have been turned down
     * to nothing - at which point the engine speaks perfectly and nobody hears
     * it. KEY_PARAM_VOLUME is a scale of the stream volume, so 1.0 of zero is
     * still zero. Nudged up to a third of the range, once, and only when it is
     * actually silent.
     *
     * <h3>The amplifier</h3>
     *
     * A small speaker's amplifier is powered down when nothing is playing and
     * takes a moment to come up. A two second instruction that starts the
     * moment the track opens loses its first word, and a short one - "turn
     * left" - can be over before there is anything to hear. A little silence
     * queued ahead of the words gives it that moment, and costs nothing when
     * the path is already open, which is the case over Bluetooth.
     */
    private void utter(String phrase) {
        try {
            if (audio != null) {
                int vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                if (vol == 0 && max > 0) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, max / 3, 0);
                    Log.i(TAG, "media volume was 0; raised to " + (max / 3) + "/" + max);
                }
                // Ducks the music player rather than talking over it, and on
                // this hardware asking for the path is also what opens it.
                audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }

            HashMap<String, String> params = new HashMap<String, String>();
            params.put(TextToSpeech.Engine.KEY_PARAM_STREAM,
                    String.valueOf(AudioManager.STREAM_MUSIC));
            params.put(TextToSpeech.Engine.KEY_PARAM_VOLUME, "1.0");
            // An id, so the engine tells us when it has finished and the audio
            // path can go back to whatever had it.
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                    String.valueOf(++utterance));
            queued++;

            // Queued on the same stream and immediately before the words, so
            // the amplifier is already up by the time they start.
            tts.playSilence(WARMUP_MS, TextToSpeech.QUEUE_ADD, params);

            Log.i(TAG, "say: " + phrase);
            // ADD, not FLUSH: two instructions rarely fall together, and when
            // they do, cutting the first one off mid-word is worse than
            // hearing both.
            tts.speak(phrase, TextToSpeech.QUEUE_ADD, params);
        } catch (Exception e) {
            Log.w(TAG, "speak failed: " + e);
        }
    }

    /** Long enough for a small amplifier to come up, short enough not to be a
     *  pause anyone notices before an instruction. */
    private static final long WARMUP_MS = 350;

    private int utterance = 0;
    private volatile int queued = 0;

    /**
     * Give the audio path back when there is nothing left to say.
     *
     * Focus is asked for per utterance and used to be handed back only when
     * the engine shut down. That was harmless while the engine was shut down
     * at the end of every screen - and stopped being harmless when navigation
     * started continuing with the screen off, because the engine then lives
     * for the whole drive and the music player would stay ducked for all of
     * it. Handed back when the queue drains instead, which is a second or two
     * after each instruction.
     */
    private void doneSpeaking() {
        if (--queued > 0) return;
        queued = 0;
        try {
            if (audio != null) audio.abandonAudioFocus(null);
        } catch (Exception e) { /* nothing holding it */ }
    }

    /** Say it even if it was just said - for a test, where silence is the
     *  thing being investigated. */
    public void sayAgain(String phrase) {
        last = "";
        say(phrase);
    }

    public void stop() {
        if (tts == null) return;
        // Hand the audio path back, or the music player stays ducked for as
        // long as the process lives.
        try { if (audio != null) audio.abandonAudioFocus(null); } catch (Exception e) { /* ignore */ }
        try { tts.stop(); tts.shutdown(); } catch (Exception e) { /* ignore */ }
        tts = null;
        ready = false;
        status = "stopped";
    }

    /**
     * Put STREAM_MUSIC at maximum when navigation is coming out of the case speaker.
     *
     * The speaker on this watch is quiet enough that anything below full is inaudible
     * outdoors, which is the only place turn instructions matter. There is nothing to lose by
     * running it flat out: it is a watch speaker, and the alternative is an announcement
     * nobody hears.
     *
     * Not when A2DP or a wired headset is connected. Those are already loud, they are inches
     * from an eardrum, and forcing them to maximum for a navigation prompt is unpleasant. The
     * check runs each time rather than once, because buds connect and disconnect while the
     * launcher stays up.
     */
    private void raiseSpeakerVolume() {
        try {
            if (audio.isBluetoothA2dpOn() || audio.isWiredHeadsetOn()) return;
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) >= max) return;
            // Flag 0: no volume panel. A 240px screen has nowhere to put one, and it would
            // cover the map at the moment a turn is being announced.
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
            Log.i(TAG, "speaker output: music volume raised to " + max);
        } catch (Throwable t) {
            // A volume we cannot set is not a reason to stay silent.
            Log.w(TAG, "could not raise the speaker volume", t);
        }
    }

}
