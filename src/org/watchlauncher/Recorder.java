package org.watchlauncher;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Record from the microphone with a gain stage, for the remote-listen command.
 *
 * <h3>Why not MediaRecorder</h3>
 *
 * It is the obvious choice and it gives AMR straight out, which would be a tenth of the size to
 * upload. But it exposes no gain control on this API, and the microphone on this watch is the
 * problem being solved: a recording at the stock level is close to inaudible. {@link AudioRecord}
 * hands over raw PCM, which can be amplified before it is written, so that is what this uses and
 * the size is the price.
 *
 * <h3>VOICE_RECOGNITION, not MIC</h3>
 *
 * Both are the same physical microphone. {@code MIC} runs the platform's automatic gain control
 * and noise suppression, which on a quiet room does the opposite of what is wanted here -- it
 * decides there is nothing to hear and pulls the level down, and then a software gain stage is
 * amplifying what AGC already discarded. {@code VOICE_RECOGNITION} is specified to leave that
 * processing off, so the gain below is applied to the untouched signal.
 *
 * <h3>The gain stage</h3>
 *
 * A plain multiply with a hard limit. It is deliberately not a compressor or an AGC: this
 * records a room, not a voice at a known distance, and something that adapts would spend the
 * first seconds of every recording deciding what to do. {@link #DEFAULT_GAIN} is a starting
 * point rather than a measured figure -- the right value depends on the room, and it is a
 * preference so it can be changed without a rebuild.
 *
 * Clipping is counted and logged. A recording that clipped is still useful, but if most of it
 * clipped the gain is too high and the log is where that shows up rather than in the audio.
 */
public final class Recorder {

    private static final String TAG = "Recorder";

    /** 8 kHz mono is what a voice channel is, and a quarter the bytes of 16 kHz to upload. */
    private static final int RATE = 8000;

    /** Starting gain. Loud enough to be useful indoors without turning hiss into a roar. */
    public static final float DEFAULT_GAIN = 6.0f;

    private Recorder() {
    }

    /**
     * Record for {@code seconds} and return a WAV, or null.
     *
     * WAV rather than raw PCM because the server decides what a completed upload is from its
     * leading bytes, and "RIFF" identifies it without anything having to be told out of band.
     *
     * Blocking. At 8 kHz mono 16-bit this is 16 kB a second, so a 15 second clip is about 240 kB
     * and 235 packets of upload -- worth knowing before asking for a long one over cellular.
     */
    public static byte[] record(int seconds, float gain) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Log.w(TAG, "no usable buffer size; microphone unavailable");
            return null;
        }
        int bufBytes = Math.max(min, RATE);          // about half a second of slack

        AudioRecord rec = null;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);

            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "recorder did not initialise");
                return null;
            }

            rec.startRecording();

            int wanted = RATE * 2 * Math.max(1, seconds);   // 16-bit mono
            ByteArrayOutputStream pcm = new ByteArrayOutputStream(wanted);
            byte[] chunk = new byte[bufBytes];
            long clipped = 0, samples = 0;

            while (pcm.size() < wanted) {
                int n = rec.read(chunk, 0, chunk.length);
                if (n <= 0) break;
                // Amplify in place, little-endian 16-bit signed, saturating rather than
                // wrapping: an overflow that wraps turns a loud moment into white noise, which
                // is far worse than the same moment flattened.
                for (int i = 0; i + 1 < n; i += 2) {
                    int s = (short) ((chunk[i] & 0xFF) | (chunk[i + 1] << 8));
                    int v = (int) (s * gain);
                    if (v > 32767) { v = 32767; clipped++; }
                    else if (v < -32768) { v = -32768; clipped++; }
                    chunk[i] = (byte) (v & 0xFF);
                    chunk[i + 1] = (byte) ((v >> 8) & 0xFF);
                    samples++;
                }
                pcm.write(chunk, 0, n);
            }

            rec.stop();
            byte[] body = pcm.toByteArray();
            Log.i(TAG, "recorded " + (body.length / 2) + " samples at gain " + gain
                    + ", clipped " + clipped + "/" + samples);
            if (clipped > samples / 4) {
                Log.w(TAG, "over a quarter of the recording clipped; gain is too high");
            }
            return wav(body);

        } catch (Throwable t) {
            Log.w(TAG, "recording failed", t);
            return null;
        } finally {
            // Release always: an AudioRecord left open holds the microphone against every other
            // user of it until this process dies, which on the home activity means until reboot.
            if (rec != null) {
                try { rec.release(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Minimal 44-byte RIFF/WAVE header around 16-bit mono PCM. */
    private static byte[] wav(byte[] pcm) {
        int dataLen = pcm.length;
        int byteRate = RATE * 2;
        byte[] out = new byte[44 + dataLen];
        put(out, 0, "RIFF");
        le32(out, 4, 36 + dataLen);
        put(out, 8, "WAVE");
        put(out, 12, "fmt ");
        le32(out, 16, 16);                 // PCM header size
        le16(out, 20, 1);                  // format: PCM
        le16(out, 22, 1);                  // channels
        le32(out, 24, RATE);
        le32(out, 28, byteRate);
        le16(out, 32, 2);                  // block align
        le16(out, 34, 16);                 // bits per sample
        put(out, 36, "data");
        le32(out, 40, dataLen);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    private static void put(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void le16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void le32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }
}
