package org.watchlauncher;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Who owns a Bluetooth address.
 *
 * A scan on this watch is mostly rows with no name: earbuds only answer a name
 * request while they are in pairing mode, and plenty of devices never answer
 * at all. IEEE publishes the owner of every address prefix, so the address by
 * itself is enough to say "Sony" rather than "AC:9B:0A:11:22:33".
 *
 * <h3>Format</h3>
 *
 * {@code assets/oui.db} is built by {@code tools/build_oui_db.py} from four
 * IEEE registries -- MA-L (24-bit prefix), MA-M (28), MA-S (36) and the
 * retired IAB (36) -- 57k entries in all. The narrow registries are blocks
 * sold out of an MA-L that IEEE holds itself, so a 24-bit hit alone is often
 * just "IEEE Registration Authority"; lookup tries 36 bits, then 28, then 24,
 * and the file keeps one sorted section per width.
 *
 * <h3>Why it is a file and not a map</h3>
 *
 * 57k entries parsed into objects is several megabytes of heap on a device
 * that has very little, for a lookup that happens a few times per scan. The
 * records are fixed width and sorted, so the file is binary-searched on disk
 * instead: ~16 seeks per lookup, no parse step, no heap.
 *
 * The asset is packaged uncompressed ({@code aapt -0 db}), so it can be read
 * in place through a positional {@link java.nio.channels.FileChannel} on the
 * APK's own descriptor -- no unpacking, no second copy on a device with little
 * room to spare. If a build ever compresses it, {@code openFd} fails and the
 * fallback stages a copy into the app's files directory instead.
 */
public class OuiDb {

    private static final String ASSET = "oui.db";
    private static final String LOCAL = "oui.db";

    private static final int HEADER_LEN = 64;
    private static final int REC_LEN = 32;
    private static final int NAME_LEN = 26;

    private static OuiDb instance;

    private final Context ctx;
    private Source src;
    private int[] secBits, secOff, secCount;
    private boolean tried = false;
    private String problem = null;

    private OuiDb(Context c) { ctx = c.getApplicationContext(); }

    public static synchronized OuiDb get(Context c) {
        if (instance == null) instance = new OuiDb(c);
        return instance;
    }

    /** @return the vendor, or null if the prefix is in no registry. */
    public synchronized String vendor(String address) {
        long mac = macPrefix(address);
        if (mac < 0) return null;
        if (!open()) return null;
        try {
            for (int s = 0; s < secBits.length; s++) {
                int bits = secBits[s];
                long key = mac & (mask(bits) << (40 - bits));
                String hit = search(secOff[s], secCount[s], key);
                if (hit != null) return hit;
            }
        } catch (Exception e) {
            problem = "read failed";
        }
        return null;
    }

    /** Shown on the About screen so a missing database is visible rather than
     *  silently degrading every scan back to bare addresses. */
    public synchronized String describe() {
        if (!open()) return problem == null ? "missing" : problem;
        int n = 0;
        for (int i = 0; i < secCount.length; i++) n += secCount[i];
        return n + " vendors";
    }

    // ---------------------------------------------------------------- file

    /** Random access to the database, wherever it turned out to live. */
    private interface Source {
        void read(long offset, byte[] into) throws Exception;
    }

    private boolean open() {
        if (src != null) return true;
        if (tried) return false;
        tried = true;

        Source s = inApk();
        if (s == null) s = staged();
        if (s == null) return false;

        try {
            byte[] head = new byte[HEADER_LEN];
            s.read(0, head);
            if (head[0] != 'W' || head[1] != 'O' || head[2] != 'U' || head[3] != 'I') {
                problem = "bad header";
                return false;
            }
            int sections = be32(head, 8);
            if (sections <= 0 || sections > 8) { problem = "bad header"; return false; }
            secBits = new int[sections];
            secOff = new int[sections];
            secCount = new int[sections];
            for (int i = 0; i < sections; i++) {
                int o = 12 + i * 12;
                secBits[i] = head[o] & 0xFF;
                secOff[i] = be32(head, o + 4);
                secCount[i] = be32(head, o + 8);
            }
            src = s;
            return true;
        } catch (Exception e) {
            problem = "unreadable";
            return false;
        }
    }

    /** Read the asset where it lies inside the APK. openFd only succeeds for
     *  a stored-uncompressed entry, which is exactly the case this wants. */
    private Source inApk() {
        try {
            AssetFileDescriptor fd = ctx.getAssets().openFd(ASSET);
            final long base = fd.getStartOffset();
            final FileInputStream in = fd.createInputStream();
            final FileChannel ch = in.getChannel();
            return new Source() {
                public void read(long offset, byte[] into) throws Exception {
                    ByteBuffer b = ByteBuffer.wrap(into);
                    long at = base + offset;
                    // Positional reads do not move the channel's own pointer,
                    // so no locking is needed around a binary search.
                    while (b.hasRemaining()) {
                        int n = ch.read(b, at);
                        if (n <= 0) throw new java.io.EOFException();
                        at += n;
                    }
                }
            };
        } catch (Exception e) {
            return null;
        }
    }

    /** Fallback for a build that compressed the asset: unpack it once into the
     *  app's own files directory and seek there. */
    private Source staged() {
        File out = new File(ctx.getFilesDir(), LOCAL);
        if (!out.exists() || out.length() < HEADER_LEN) {
            InputStream in = null;
            FileOutputStream os = null;
            try {
                in = ctx.getAssets().open(ASSET);
                os = new FileOutputStream(out);
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
            } catch (Exception e) {
                problem = "not packaged";
                return null;
            } finally {
                try { if (in != null) in.close(); } catch (Exception e) { /* ignore */ }
                try { if (os != null) os.close(); } catch (Exception e) { /* ignore */ }
            }
        }
        try {
            final RandomAccessFile f = new RandomAccessFile(out, "r");
            return new Source() {
                public void read(long offset, byte[] into) throws Exception {
                    f.seek(offset);
                    f.readFully(into);
                }
            };
        } catch (Exception e) {
            problem = "unreadable";
            return null;
        }
    }

    // ---------------------------------------------------------------- search

    private String search(int off, int count, long key) throws Exception {
        byte[] rec = new byte[REC_LEN];
        int lo = 0, hi = count - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            src.read(off + (long) mid * REC_LEN, rec);
            long k = ((long) (rec[0] & 0xFF) << 32)
                    | ((long) (rec[1] & 0xFF) << 24)
                    | ((long) (rec[2] & 0xFF) << 16)
                    | ((long) (rec[3] & 0xFF) << 8)
                    | (long) (rec[4] & 0xFF);
            if (k < key) lo = mid + 1;
            else if (k > key) hi = mid - 1;
            else return name(rec);
        }
        return null;
    }

    private static String name(byte[] rec) {
        int n = 0;
        while (n < NAME_LEN && rec[6 + n] != 0) n++;
        return n == 0 ? null : new String(rec, 6, n);
    }

    private static long mask(int bits) { return (1L << bits) - 1L; }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    /** The first five bytes of "AA:BB:CC:DD:EE:FF" as a 40-bit value, which is
     *  the widest prefix any registry assigns. */
    private static long macPrefix(String address) {
        if (address == null) return -1;
        long v = 0;
        int digits = 0;
        for (int i = 0; i < address.length() && digits < 10; i++) {
            int d = Character.digit(address.charAt(i), 16);
            if (d < 0) continue;
            v = (v << 4) | d;
            digits++;
        }
        return digits == 10 ? v : -1;
    }
}
