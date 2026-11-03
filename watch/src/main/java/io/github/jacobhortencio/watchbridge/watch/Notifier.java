package io.github.jacobhortencio.watchbridge.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Mostra no relógio, com a interface nativa do Wear OS, o que chega do telemóvel. */
class Notifier {
    static final int ONGOING_ID = 1;
    static final int RING_ID = 2;
    static final String CH_STATUS = "status";
    static final String CH_NOTIFS = "notifs";
    static final String CH_CALLS = "calls";
    static final String CH_FIND = "find";

    private final Context ctx;
    private final NotificationManager nm;
    private final Map<String, Bitmap> icons = new HashMap<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private Ringtone ringtone;

    Notifier(Context ctx) {
        this.ctx = ctx;
        nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "Estado da ligação", NotificationManager.IMPORTANCE_MIN));
        NotificationChannel n = new NotificationChannel(CH_NOTIFS, "Notificações do telemóvel", NotificationManager.IMPORTANCE_HIGH);
        n.enableVibration(true);
        nm.createNotificationChannel(n);
        NotificationChannel c = new NotificationChannel(CH_CALLS, "Chamadas", NotificationManager.IMPORTANCE_HIGH);
        c.enableVibration(true);
        c.setVibrationPattern(new long[]{0, 700, 500, 700, 500, 700, 500, 700});
        nm.createNotificationChannel(c);
        NotificationChannel f = new NotificationChannel(CH_FIND, "Encontrar relógio", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(f);
    }

    static int idFor(String key) {
        int id = key.hashCode() & 0x7fffffff;
        return id < 10 ? id + 10 : id;
    }

    Notification ongoing(String text) {
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(ctx, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Watch Bridge")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    void updateOngoing(String text) {
        nm.notify(ONGOING_ID, ongoing(text));
    }

    void cacheIcon(String pkg, String b64) {
        try {
            byte[] b = Base64.decode(b64, Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
            if (bmp != null) icons.put(pkg, bmp);
        } catch (Exception ignored) {
        }
    }

    void post(JSONObject m) {
        String key = m.optString("key");
        if (key.isEmpty()) return;
        int id = idFor(key);
        String app = m.optString("app");
        String title = m.optString("title");
        String text = m.optString("text");
        boolean call = m.optBoolean("call");

        Notification.Builder b = new Notification.Builder(ctx, call ? CH_CALLS : CH_NOTIFS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title.isEmpty() ? app : title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSubText(app)
                .setWhen(m.optLong("time", System.currentTimeMillis()))
                .setShowWhen(true)
                .setOnlyAlertOnce(!call)
                .setAutoCancel(true)
                .setCategory(call ? Notification.CATEGORY_CALL : Notification.CATEGORY_MESSAGE)
                .setDeleteIntent(pi(ActionReceiver.ACTION_DISMISS, key, -1, id, false));
        Bitmap icon = icons.get(m.optString("pkg"));
        if (icon != null) b.setLargeIcon(icon);
        if (call) b.setOngoing(true);

        JSONArray actions = m.optJSONArray("actions");
        if (actions != null) {
            for (int k = 0; k < actions.length(); k++) {
                JSONObject a = actions.optJSONObject(k);
                if (a == null) continue;
                int i = a.optInt("i", -1);
                String at = a.optString("title", "Ação");
                if (a.optBoolean("reply")) {
                    RemoteInput ri = new RemoteInput.Builder(ActionReceiver.KEY_TEXT).setLabel(at).build();
                    b.addAction(new Notification.Action.Builder(
                            Icon.createWithResource(ctx, R.drawable.ic_reply), at,
                            pi(ActionReceiver.ACTION_REPLY, key, i, id, true))
                            .addRemoteInput(ri)
                            .build());
                } else {
                    b.addAction(new Notification.Action.Builder(
                            Icon.createWithResource(ctx, R.drawable.ic_stat), at,
                            pi(ActionReceiver.ACTION_ACTION, key, i, id, false)).build());
                }
            }
        }
        nm.notify(id, b.build());
    }

    void remove(String key) {
        nm.cancel(idFor(key));
    }

    private PendingIntent pi(String action, String key, int i, int nid, boolean mutable) {
        Intent it = new Intent(ctx, ActionReceiver.class)
                .setAction(action)
                .setData(Uri.parse("wb://" + action + "/" + nid + "/" + i))
                .putExtra("key", key)
                .putExtra("i", i)
                .putExtra("nid", nid);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (mutable ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE);
        return PendingIntent.getBroadcast(ctx, nid ^ (i * 31) ^ action.hashCode(), it, flags);
    }

    // ---------- encontrar o relógio ----------

    void ring(boolean on) {
        Vibrator v = ctx.getSystemService(VibratorManager.class).getDefaultVibrator();
        if (!on) {
            v.cancel();
            if (ringtone != null) {
                try {
                    ringtone.stop();
                } catch (Exception ignored) {
                }
                ringtone = null;
            }
            nm.cancel(RING_ID);
            return;
        }
        v.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 400}, 0));
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            ringtone = RingtoneManager.getRingtone(ctx, u);
            if (ringtone != null) {
                ringtone.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM).build());
                ringtone.setLooping(true);
                ringtone.play();
            }
        } catch (Exception ignored) {
        }
        Notification n = new Notification.Builder(ctx, CH_FIND)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("O telemóvel está à sua procura")
                .setContentText("Toque em Parar")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(ctx, R.drawable.ic_stat), "Parar",
                        pi(ActionReceiver.ACTION_STOP_RING, "ring", 0, RING_ID, false)).build())
                .build();
        nm.notify(RING_ID, n);
        h.postDelayed(() -> ring(false), 30_000);
    }
}
