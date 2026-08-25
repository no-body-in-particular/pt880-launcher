package org.watchlauncher;

import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A call, in both directions.
 *
 * The stock in-call screen is unusable here -- it wants a swipe to answer and
 * there is no touchscreen -- so this one covers it and puts the two things you
 * need on the two buttons that exist.
 *
 *   ringing    A tap answers, B tap rejects
 *   in a call  A tap hangs up, B tap moves the audio between the earpiece,
 *              the speaker and connected headphones
 *   any state  A hold leaves this screen; the call carries on, and the log
 *              gets you back to it
 *
 * The system dialler still starts its own activity on top when a call is
 * placed. Rather than fight it for the foreground continuously, this screen
 * asks for itself back once, shortly after dialling -- enough to win, and not
 * so persistent that it loops if the user deliberately leaves.
 */
public class InCallScreen extends Screen {

    private final String name;
    private final String number;
    private final boolean incoming;

    private TextView vWho, vNumber, vState, vRoute;

    private final Handler ui = new Handler();

    private long startedAt = 0;
    private boolean dialled = false;
    private boolean reclaimed = false;
    private int lastState = -1;
    private String note = "";

    public InCallScreen(String name, String number, boolean incoming) {
        this.name = name;
        this.number = number;
        this.incoming = incoming;
    }

    @Override
    public String title() { return incoming ? "Incoming" : "Call"; }

    @Override
    protected View build() {
        LinearLayout col = Ui.column(shell);
        col.setGravity(Gravity.CENTER_VERTICAL);

        vState = Ui.text(shell, Ui.SMALL_PX, Ui.ACCENT, false);
        vWho = Ui.text(shell, Ui.TITLE_PX, Ui.FG, true);
        vWho.setMaxLines(2);
        vWho.setEllipsize(android.text.TextUtils.TruncateAt.END);
        vNumber = Ui.text(shell, Ui.BODY_PX, Ui.DIM, false);
        vRoute = Ui.text(shell, Ui.SMALL_PX, Ui.FAINT, false);

        int mp = ViewGroup.LayoutParams.MATCH_PARENT;
        col.addView(vState, Ui.lp(mp, 0, 0));
        col.addView(Ui.spacer(shell, 8));
        col.addView(vWho, Ui.lp(mp, 0, 0));
        col.addView(Ui.spacer(shell, 6));
        col.addView(vNumber, Ui.lp(mp, 0, 0));
        col.addView(Ui.spacer(shell, 10));
        col.addView(vRoute, Ui.lp(mp, 0, 0));
        return col;
    }

    @Override
    public void onShow() {
        if (!incoming && !dialled) {
            dialled = true;
            if (!Telephony.dial(shell, number)) note = "Cannot dial";
            else startedAt = System.currentTimeMillis();
        }
        render();
    }

    @Override
    public void onHide() {
        // Deliberately does not hang up. Leaving the screen during a call is a
        // normal thing to want; ending it is what button A is for.
    }

    @Override
    public void tick() {
        // The dialler puts its own activity in front when the call connects.
        // One reclaim, a beat after dialling, is enough to get back on top.
        if (!incoming && !reclaimed && startedAt > 0
                && System.currentTimeMillis() - startedAt > 1200) {
            reclaimed = true;
            reclaim();
        }
        render();
    }

    private void reclaim() {
        try {
            Intent i = new Intent(shell, ShellActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            shell.startActivity(i);
        } catch (Exception e) { /* the stock screen stays in front */ }
    }

    private void render() {
        if (vWho == null) return;

        int state = Telephony.callState(shell);
        if (state != lastState) {
            lastState = state;
            if (state == TelephonyManager.CALL_STATE_OFFHOOK && startedAt == 0) {
                startedAt = System.currentTimeMillis();
            }
        }

        vWho.setText(name != null && name.length() > 0 ? name
                : (number != null && number.length() > 0 ? number : "Unknown"));
        vNumber.setText(number == null ? "" : number);

        StringBuilder s = new StringBuilder();
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                s.append("ringing");
                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                s.append("in call");
                if (startedAt > 0) {
                    s.append("   ").append(
                            Ui.mmss((int) (System.currentTimeMillis() - startedAt)));
                }
                break;
            default:
                s.append(dialled || incoming ? "ended" : "calling");
                break;
        }
        if (note.length() > 0) s.append("  ").append(note);
        vState.setText(s.toString());

        vRoute.setText(routeLabel());
        shell.renderHint();
    }

    private String routeLabel() {
        AudioManager a = shell.audio();
        if (a.isBluetoothA2dpOn() || a.isBluetoothScoOn()) return "audio: headphones";
        return a.isSpeakerphoneOn() ? "audio: speaker" : "audio: earpiece";
    }

    /** Earpiece and speaker only; a connected headset takes the call on its
     *  own and neither flag applies. */
    private void toggleSpeaker() {
        AudioManager a = shell.audio();
        boolean on = !a.isSpeakerphoneOn();
        a.setSpeakerphoneOn(on);
        render();
    }

    @Override
    public boolean onGesture(int button, int kind) {
        int state = Telephony.callState(shell);

        if (button == ShellActivity.BTN_A) {
            if (kind != ShellActivity.TAP) return false;    // hold leaves
            boolean ringing = (state == TelephonyManager.CALL_STATE_RINGING);
            act(ringing, ringing ? "Answer failed" : "Hang up failed");
            return true;
        }

        if (kind == ShellActivity.TAP) {
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                act(false, "Reject failed");
            } else {
                toggleSpeaker();
            }
        }
        return true;
    }

    /**
     * Answering and hanging up can fall back to `input keyevent` through the
     * root shell, which forks a process and waits for it. A second of that on
     * the UI thread stops the clock and risks an ANR, so it runs on a thread
     * and reports back.
     */
    private void act(final boolean answer, final String failure) {
        note = answer ? "answering..." : "ending...";
        render();
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = answer ? Telephony.answer(shell)
                                          : Telephony.hangUp(shell);
                ui.post(new Runnable() {
                    public void run() {
                        note = ok ? "" : failure;
                        render();
                    }
                });
            }
        }).start();
    }

    @Override
    public String hint() {
        int state = Telephony.callState(shell);
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            return shell.twoButtons() ? "A:answer  B:reject  hold:back"
                                      : "tap:answer  hold:back";
        }
        return shell.twoButtons() ? "A:hang up  B:audio  hold:back"
                                  : "tap:hang up  hold:back";
    }
}
