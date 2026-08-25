package org.watchlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/** Play/pause/next/prev sent over AVRCP by the headphones themselves. */
public class MediaButtonReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        KeyEvent ev = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (ev == null || ev.getAction() != KeyEvent.ACTION_DOWN) return;

        String action;
        switch (ev.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                action = MusicService.ACTION_NEXT;
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                action = MusicService.ACTION_PREV;
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                action = MusicService.ACTION_PLAY;
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                action = MusicService.ACTION_PAUSE;
                break;
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            default:
                action = MusicService.ACTION_TOGGLE;
                break;
        }

        Intent svc = new Intent(context, MusicService.class);
        svc.setAction(action);
        context.startService(svc);

        // There is no touchscreen, so play/pause on the headset doubles as a
        // way back into the UI from wherever the watch has ended up -- it
        // opens the launcher on the music screen. Skip and previous
        // deliberately do not, or the screen would jump up on every track
        // change.
        if (MusicService.ACTION_TOGGLE.equals(action) || MusicService.ACTION_PLAY.equals(action)) {
            Intent ui = new Intent(context, ShellActivity.class);
            ui.putExtra(ShellActivity.EXTRA_APP, "music");
            ui.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try { context.startActivity(ui); } catch (Exception e) { /* nothing to show */ }
        }
        if (isOrderedBroadcast()) abortBroadcast();
    }
}
