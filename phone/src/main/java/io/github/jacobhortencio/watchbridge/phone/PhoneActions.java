package io.github.jacobhortencio.watchbridge.phone;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

final class PhoneActions {
    private PhoneActions() {}

    private static final Handler h = new Handler(Looper.getMainLooper());
    private static Ringtone ringtone;
    private static int savedAlarmVolume = -1;

    static void media(Context c, String cmd) {
        AudioManager am = c.getSystemService(AudioManager.class);
        if ("vol_up".equals(cmd)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
            return;
        }
        if ("vol_down".equals(cmd)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
            return;
        }
        NotifListener l = NotifListener.get();
        if ("player_next".equals(cmd)) {
            if (l != null) l.nextPlayer();
            return;
        }
        MediaController mc = l == null ? null : l.currentController();
        if (mc == null) return;
        MediaController.TransportControls tc = mc.getTransportControls();
        switch (cmd) {
            case "toggle": {
                PlaybackState s = mc.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) tc.pause();
                else tc.play();
                break;
            }
            case "next":
                tc.skipToNext();
                break;
            case "prev":
                tc.skipToPrevious();
                break;
            default:
                break;
        }
        h.postDelayed(() -> {
            NotifListener n = NotifListener.get();
            if (n != null) n.pushMedia();
        }, 500);
    }

    /** Ativa ou desativa o Não incomodar (precisa de acesso à política de notificações). */
    static void setDnd(Context c, boolean on) {
        android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
        if (!nm.isNotificationPolicyAccessGranted()) {
            io.github.jacobhortencio.watchbridge.common.WLog.w("WB", "sem acesso ao Não incomodar");
            return;
        }
        nm.setInterruptionFilter(on ? android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                : android.app.NotificationManager.INTERRUPTION_FILTER_ALL);
    }

    /** Faz o telemóvel tocar no volume máximo, mesmo em silêncio. */
    static synchronized void ringPhone(Context c, boolean on) {
        AudioManager am = c.getSystemService(AudioManager.class);
        if (!on) {
            stopRing(am);
            return;
        }
        if (ringtone != null && ringtone.isPlaying()) return;
        try {
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(c.getApplicationContext(), u);
            if (ringtone == null) return;
            ringtone.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            ringtone.setLooping(true);
            ringtone.play();
            h.postDelayed(() -> stopRing(am), 30_000);
        } catch (Exception e) {
            Log.w("WB", "tocar falhou", e);
        }
    }

    private static synchronized void stopRing(AudioManager am) {
        if (ringtone != null) {
            try {
                ringtone.stop();
            } catch (Exception ignored) {
            }
            ringtone = null;
        }
        if (savedAlarmVolume >= 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
            } catch (Exception ignored) {
            }
            savedAlarmVolume = -1;
        }
    }
}
