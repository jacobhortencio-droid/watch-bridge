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
    static final int LOST_ID = 3;
    static final int LOWBAT_ID = 4;
    static final int UPDATE_ID = 5;
    static final int PAIR_ID = 6;
    static final int HEALTH_ID = 7;
    static final int MOVE_ID = 8;
    static final int WATER_ID = 9;

    static final String CH_STATUS = "status";
    static final String CH_MSG = "msg2";       // vibração feita à mão (padrão por app)
    static final String CH_SILENT = "silent";  // horário de silêncio / telemóvel em uso
    static final String CH_CALLS = "calls";
    static final String CH_FIND = "find";
    static final String CH_ALERT = "alert";

    /** Padrões: 0 normal, 1 curto, 2 longo, 3 forte, 4 sem vibração. */
    private static final long[][] PATTERNS = {
            {0, 200, 120, 200},
            {0, 90},
            {0, 650},
            {0, 350, 150, 350, 150, 350},
            {},
    };

    private final Context ctx;
    private final NotificationManager nm;
    private final Map<String, Bitmap> icons = new HashMap<>();
    private final Map<String, String> lastText = new HashMap<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private Ringtone ringtone;

    Notifier(Context ctx) {
        this.ctx = ctx;
        nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "Estado da ligação", NotificationManager.IMPORTANCE_MIN));
        NotificationChannel msg = new NotificationChannel(CH_MSG, "Notificações do telemóvel", NotificationManager.IMPORTANCE_HIGH);
        msg.enableVibration(false);
        msg.setSound(null, null);
        nm.createNotificationChannel(msg);
        NotificationChannel silent = new NotificationChannel(CH_SILENT, "Notificações silenciosas", NotificationManager.IMPORTANCE_LOW);
        silent.enableVibration(false);
        nm.createNotificationChannel(silent);
        NotificationChannel c = new NotificationChannel(CH_CALLS, "Chamadas e alarmes", NotificationManager.IMPORTANCE_HIGH);
        c.enableVibration(true);
        c.setVibrationPattern(new long[]{0, 700, 500, 700, 500, 700, 500, 700});
        nm.createNotificationChannel(c);
        nm.createNotificationChannel(new NotificationChannel(CH_FIND, "Encontrar relógio", NotificationManager.IMPORTANCE_HIGH));
        NotificationChannel al = new NotificationChannel(CH_ALERT, "Avisos (ligação, bateria, saúde)", NotificationManager.IMPORTANCE_HIGH);
        al.enableVibration(true);
        al.setVibrationPattern(new long[]{0, 400, 200, 400});
        nm.createNotificationChannel(al);
        // canal antigo (v1-v4) já não é usado
        try {
            nm.deleteNotificationChannel("notifs");
        } catch (Exception ignored) {
        }
    }

    static int idFor(String key) {
        int id = key.hashCode() & 0x7fffffff;
        return id < 100 ? id + 100 : id;
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

    void alert(int id, String title, String text) {
        alert(id, title, text, null);
    }

    void alert(int id, String title, String text, PendingIntent tap) {
        Notification.Builder b = new Notification.Builder(ctx, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        if (tap != null) b.setContentIntent(tap);
        nm.notify(id, b.build());
    }

    void cancel(int id) {
        nm.cancel(id);
    }

    void cacheIcon(String pkg, String b64) {
        Bitmap bmp = decode(b64);
        if (bmp != null) icons.put(pkg, bmp);
    }

    private static Bitmap decode(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] b = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Exception e) {
            return null;
        }
    }

    void post(JSONObject m) {
        String key = m.optString("key");
        if (key.isEmpty()) return;
        int id = idFor(key);
        String app = m.optString("app");
        String title = m.optString("title");
        String text = m.optString("text");
        String otp = m.optString("otp");
        boolean call = m.optBoolean("call");
        boolean alarm = m.optBoolean("alarm");
        boolean vip = m.optBoolean("vip");
        boolean loud = call || alarm;
        boolean quiet = WPrefs.inQuiet(ctx) || WatchService.phoneDnd;
        boolean silent = !loud && (m.optBoolean("silent") || (quiet && !vip));
        String channel = loud ? CH_CALLS : (silent ? CH_SILENT : CH_MSG);

        String shownTitle = title.isEmpty() ? app : title;
        String shownText = text;
        if (!otp.isEmpty()) {
            shownText = shownTitle + ": " + text;
            shownTitle = "🔑 " + otp;
        }

        Notification.Builder b = new Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(shownTitle)
                .setContentText(shownText)
                .setSubText(app)
                .setWhen(m.optLong("time", System.currentTimeMillis()))
                .setShowWhen(true)
                .setOnlyAlertOnce(!loud)
                .setAutoCancel(true)
                .setCategory(call ? Notification.CATEGORY_CALL : (alarm ? Notification.CATEGORY_ALARM : Notification.CATEGORY_MESSAGE))
                .setDeleteIntent(pi(ActionReceiver.ACTION_DISMISS, key, -1, id, false));

        Bitmap img = decode(m.optString("img"));
        if (img != null) {
            b.setStyle(new Notification.BigPictureStyle().bigPicture(img).setSummaryText(shownText));
        } else {
            b.setStyle(new Notification.BigTextStyle().bigText(shownText));
        }
        Bitmap avatar = decode(m.optString("avatar"));
        Bitmap icon = avatar != null ? avatar : icons.get(m.optString("pkg"));
        if (icon != null) b.setLargeIcon(icon);
        if (loud) b.setOngoing(true);

        JSONArray actions = m.optJSONArray("actions");
        if (actions != null) {
            for (int k = 0; k < actions.length(); k++) {
                JSONObject a = actions.optJSONObject(k);
                if (a == null) continue;
                int i = a.optInt("i", -1);
                String at = a.optString("title", "Ação");
                if (a.optBoolean("reply")) {
                    RemoteInput ri = new RemoteInput.Builder(ActionReceiver.KEY_TEXT)
                            .setLabel(at)
                            .setChoices(WPrefs.replies(ctx))
                            .build();
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

        // vibração própria só quando a mensagem é nova ou mudou
        String sig = shownTitle + "|" + shownText;
        boolean changed = !sig.equals(lastText.put(key, sig));
        if (changed && !loud && !silent) vibrate(vip ? 3 : m.optInt("vib", 0));
        if (changed && !key.startsWith("wb-")) {
            WPrefs.addHistory(ctx, app, title, text, m.optLong("time", System.currentTimeMillis()));
        }
    }

    void vibrate(int pattern) {
        if (pattern < 0 || pattern >= PATTERNS.length || PATTERNS[pattern].length == 0) return;
        try {
            Vibrator v = ctx.getSystemService(VibratorManager.class).getDefaultVibrator();
            v.vibrate(VibrationEffect.createWaveform(PATTERNS[pattern], -1));
        } catch (Exception ignored) {
        }
    }

    void remove(String key) {
        lastText.remove(key);
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

    // ---------- emparelhamento ----------

    void pairPrompt(String code) {
        PendingIntent pi = PendingIntent.getActivity(ctx, PAIR_ID, new Intent(ctx, PairActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Emparelhar com o telemóvel?")
                .setContentText("Código " + code + ". Toque para confirmar.")
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build();
        nm.notify(PAIR_ID, n);
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
