package org.watchlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The SMS control plane, held by the launcher instead of the vendor app.
 *
 * <h3>Why this has to exist before the vendor app goes</h3>
 *
 * Disabling {@code com.enqualcomm.support} takes its {@code ICSmsManager} with it, and that is
 * the only path that reaches this watch when the data connection is the thing that is broken.
 * {@link Guard} already documents what that costs: the server's remote reboot is how a stalled
 * sensor gets cleared, and one stall ran 4225 seconds against a 1800 second timeout because
 * nothing was listening. Losing TCP and SMS at the same time would mean a watch that can only
 * be recovered by taking it off and plugging it in.
 *
 * <h3>Receiving without becoming the SMS app</h3>
 *
 * KitKat introduced the default-SMS-app rule, but it only governs {@code SMS_DELIVER}.
 * {@code SMS_RECEIVED} is still broadcast to anything holding {@code RECEIVE_SMS}, so this
 * reads commands without taking over messaging, and a normal text still lands in the inbox.
 *
 * Command messages are aborted so they do not. That is deliberate: a control channel that fills
 * the message list is one nobody leaves enabled.
 *
 * <h3>Authentication, and why it is more than the vendor's</h3>
 *
 * The vendor's scheme is a shared password in the clear over unauthenticated SMS, and the
 * protocol notes call it the most security-relevant surface on the device -- anyone who knows
 * the number and the password gets the camera and the modem. The password is kept, because the
 * server already knows it, but it is no longer sufficient on its own:
 *
 * <ul>
 *   <li>an optional allowlist of sender numbers, and when it is set nothing else is obeyed;
 *   <li>commands that can hand the device to someone else are refused outright (below);
 *   <li>every accepted and every rejected command is logged with its sender.
 * </ul>
 *
 * A short numeric password over SMS is still weak. It is kept only because losing the channel
 * is worse, and it should not be the only way in.
 *
 * <h3>What is deliberately not implemented</h3>
 *
 * <table>
 *   <tr><td>{@code fotaupdate}, {@code setfotasrv}, {@code setlogsrv}</td>
 *       <td>firmware upload -- excluded by request, and the one command whose failure bricks
 *           the watch rather than inconveniencing it</td></tr>
 *   <tr><td>{@code atcmd=}</td>
 *       <td>arbitrary AT to the modem. Nothing here needs it, and it is a way to reach the
 *           baseband from a text message</td></tr>
 *   <tr><td>{@code monitor}, {@code listen}</td>
 *       <td>silent microphone. {@code dumpsys appops} shows the vendor app never once used
 *           RECORD_AUDIO, so nothing is being taken away by leaving it out. It is a
 *           surveillance capability and it should be added deliberately, if at all, not
 *           inherited by default</td></tr>
 * </table>
 *
 * {@code host=} is implemented, because pointing the watch at your own server is the point of
 * the exercise -- but it is exactly the command that would hand the device to someone else, so
 * it is one of the two that ignores a message with no allowlist configured.
 */
public class SmsControl extends BroadcastReceiver {

    private static final String TAG = "SmsControl";

    private static final String ACTION_SMS = "android.provider.Telephony.SMS_RECEIVED";

    /** Commands that could give the watch away, and so need the allowlist, not just a password. */
    private static final String[] PRIVILEGED = {"host", "ip"};

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SMS.equals(intent.getAction())) return;

        String sender = null;
        StringBuilder body = new StringBuilder();
        try {
            Bundle b = intent.getExtras();
            if (b == null) return;
            Object[] pdus = (Object[]) b.get("pdus");
            if (pdus == null) return;

            // Concatenate rather than handle only pdus[0]: a command with an argument can cross
            // the 160 character boundary, and half a command is worse than none.
            for (Object p : pdus) {
                SmsMessage m = SmsMessage.createFromPdu((byte[]) p);
                if (m == null) continue;
                if (sender == null) sender = m.getOriginatingAddress();
                String part = m.getMessageBody();
                if (part != null) body.append(part);
            }
        } catch (Throwable t) {
            // A malformed PDU is not worth crashing the home screen for.
            Log.w(TAG, "could not read incoming sms", t);
            return;
        }

        String text = body.toString().trim();
        if (text.length() == 0) return;

        Cmd cmd = parse(text);
        if (cmd == null) return;                       // not addressed to us; leave it alone

        // The password is checked here rather than in parse(), so that a wrong one is treated
        // exactly like a message that was never a command: swallowed silently, no reply, not
        // aborted. Replying "wrong password" would confirm to anyone texting the watch that it
        // is a tracker and that guessing is worth continuing.
        String want = password(context);
        String got = passwordOf(text);
        if (want.length() == 0 || got == null || !want.equals(got)) {
            Log.w(TAG, "ignoring command with bad or unset password from " + sender);
            return;
        }

        // From here on it is ours whether or not we obey it, so it does not reach the inbox.
        if (isOrderedBroadcast()) abortBroadcast();

        if (!authorised(context, cmd, sender)) {
            Log.w(TAG, "refused " + cmd.name + " from " + sender);
            return;
        }

        Log.i(TAG, "accepted " + cmd.name + " from " + sender);
        String reply = run(context, cmd);
        if (reply != null) send(sender, reply);
    }

    // ------------------------------------------------------------------ parsing

    static final class Cmd {
        final String name;
        final String arg;

        Cmd(String name, String arg) {
            this.name = name;
            this.arg = arg;
        }
    }

    /**
     * {@code <password>#<command>#} and {@code <password>#<command>=<arg>#}, which is the
     * vendor's shape -- kept so the server and anything already scripted against it still work.
     *
     * Returns null for anything that is not that shape, including a wrong password, so an
     * ordinary text is never swallowed. Telling "wrong password" apart from "not a command"
     * would leak that this number is a tracker.
     */
    static Cmd parse(String text) {
        int first = text.indexOf('#');
        if (first <= 0) return null;
        if (!text.endsWith("#")) return null;

        String rest = text.substring(first + 1, text.length() - 1).trim();
        if (rest.length() == 0) return null;

        String name = rest, arg = null;
        int eq = rest.indexOf('=');
        if (eq >= 0) {
            name = rest.substring(0, eq).trim();
            arg = rest.substring(eq + 1).trim();
        }
        return new Cmd(name.toLowerCase(Locale.US), arg);
    }

    /** The password half, checked separately so a bad one is indistinguishable from a stray text. */
    private static String passwordOf(String text) {
        int first = text.indexOf('#');
        return first <= 0 ? null : text.substring(0, first).trim();
    }

    // ------------------------------------------------------------------ authorisation

    private boolean authorised(Context c, Cmd cmd, String sender) {
        List<String> allow = allowlist(c);

        for (String p : PRIVILEGED) {
            if (p.equals(cmd.name) && allow.isEmpty()) {
                Log.w(TAG, cmd.name + " needs an allowlist configured; ignoring");
                return false;
            }
        }
        if (allow.isEmpty()) return true;              // password-only, as the vendor had it

        String from = digits(sender);
        for (String a : allow) {
            String want = digits(a);
            // Compare on the tail: the same phone arrives as +316..., 06... or 0031 6...
            // depending on who is sending, and a prefix mismatch is not a different sender.
            if (want.length() >= 6 && from.endsWith(want.substring(want.length() - 6))) return true;
        }
        return false;
    }

    private static String digits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) b.append(s.charAt(i));
        }
        return b.toString();
    }

    private static List<String> allowlist(Context c) {
        List<String> out = new ArrayList<String>();
        try {
            String raw = c.getSharedPreferences("tracker", Context.MODE_PRIVATE)
                    .getString("sms_allow", "");
            for (String s : raw.split("[,;\\s]+")) {
                if (s.trim().length() > 0) out.add(s.trim());
            }
        } catch (Throwable t) {
            // No preference is the same as no allowlist.
        }
        return out;
    }

    static String password(Context c) {
        try {
            return c.getSharedPreferences("tracker", Context.MODE_PRIVATE)
                    .getString("sms_password", "");
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ commands

    private String run(Context c, Cmd cmd) {
        if ("reboot".equals(cmd.name))    return shell(c, "reboot", "rebooting");
        if ("poweroff".equals(cmd.name))  return shell(c, "reboot -p", "powering off");
        if ("status".equals(cmd.name))    return status(c);

        if ("host".equals(cmd.name) || "ip".equals(cmd.name)) {
            if (cmd.arg == null || cmd.arg.length() == 0) return "host: no address";
            TrackerService.setEndpoint(c, cmd.arg);
            return "server set to " + cmd.arg;
        }

        if ("capture".equals(cmd.name)) {
            TrackerService.requestPhoto(c);
            return "capturing";
        }
        if ("locate".equals(cmd.name) || "location".equals(cmd.name)) {
            // Says which of the two happened rather than a bare "ok": with the client off, the
            // vendor app is still the thing reporting and this did nothing at all.
            return TrackerService.requestFix(c) ? "locating"
                    : "locate: tracker client is not connected";
        }
        // Named, so an operator gets told rather than met with silence -- and so the refusal is
        // visibly a decision rather than a gap.
        if ("atcmd".equals(cmd.name) || "monitor".equals(cmd.name) || "listen".equals(cmd.name)
                || cmd.name.startsWith("fota") || cmd.name.startsWith("setfota")
                || cmd.name.startsWith("setlog")) {
            return cmd.name + ": not implemented on this build";
        }
        return "unknown command: " + cmd.name;
    }

    private String shell(Context c, String command, String said) {
        RootShell sh = new RootShell();
        try {
            if (!sh.open() || !sh.isRoot()) return "no root: " + sh.failure();
            sh.runQuiet(command);
            return said;
        } catch (Throwable t) {
            return "failed: " + t;
        } finally {
            try { sh.close(); } catch (Throwable ignored) { }
        }
    }

    private String status(Context c) {
        StringBuilder b = new StringBuilder();
        b.append("up ").append(android.os.SystemClock.elapsedRealtime() / 60000).append("m");

        // The battery level comes off the sticky broadcast rather than a helper, so status
        // works even when nothing else in the launcher is running.
        try {
            Intent i = c.registerReceiver(null,
                    new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) {
                int level = i.getIntExtra("level", -1);
                int scale = i.getIntExtra("scale", 100);
                if (level >= 0 && scale > 0) {
                    b.append(", batt ").append(level * 100 / scale).append("%");
                }
            }
        } catch (Throwable ignored) {
        }
        b.append(", ").append(TrackerService.describe(c));
        return b.toString();
    }

    // ------------------------------------------------------------------ reply

    private void send(String to, String text) {
        if (to == null || text == null) return;
        try {
            SmsManager sms = SmsManager.getDefault();
            // Long replies have to be split or the send silently fails.
            List<String> parts = sms.divideMessage(text);
            if (parts.size() == 1) {
                sms.sendTextMessage(to, null, text, null, null);
            } else {
                sms.sendMultipartTextMessage(to, null, new ArrayList<String>(parts), null, null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not reply to " + to, t);
        }
    }
}
