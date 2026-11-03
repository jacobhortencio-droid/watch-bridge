package io.github.jacobhortencio.watchbridge.watch;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import androidx.core.app.NotificationCompat;
import androidx.wear.ongoing.OngoingActivity;
import androidx.wear.ongoing.Status;

import org.json.JSONObject;

/**
 * Atividades em curso (ícone no mostrador e em "Recentes"): música a tocar e navegação.
 */
final class Ongoing {
    private Ongoing() {}

    static final int MUSIC_ID = 20;
    static final int NAV_ID = 21;
    private static final String CH = "ongoing";
    private static final String CH_NAV = "nav";
    private static String lastNavTitle = "";
    static volatile JSONObject nav;

    private static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Música em curso", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel n = new NotificationChannel(CH_NAV, "Navegação", NotificationManager.IMPORTANCE_HIGH);
        n.enableVibration(false);
        n.setSound(null, null);
        nm.createNotificationChannel(n);
    }

    static void music(Context c, JSONObject m) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (m == null || !m.optBoolean("has") || !m.optBoolean("playing")) {
            nm.cancel(MUSIC_ID);
            return;
        }
        channels(c);
        String title = m.optString("title");
        String artist = m.optString("artist", m.optString("app"));
        PendingIntent pi = PendingIntent.getActivity(c, MUSIC_ID, new Intent(c, MusicActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title.isEmpty() ? "A tocar" : title)
                .setContentText(artist)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT);
        OngoingActivity oa = new OngoingActivity.Builder(c, MUSIC_ID, b)
                .setStaticIcon(R.drawable.ic_stat)
                .setTouchIntent(pi)
                .setStatus(new Status.Builder().addTemplate("♪ " + (title.isEmpty() ? artist : title)).build())
                .build();
        oa.apply(c);
        nm.notify(MUSIC_ID, b.build());
    }

    /** Indicações de navegação (Google Maps e outras) vindas do telemóvel. */
    static void navigation(Context c, JSONObject m, Notifier notifier) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (m == null || m.optBoolean("end")) {
            nav = null;
            lastNavTitle = "";
            nm.cancel(NAV_ID);
            return;
        }
        nav = m;
        channels(c);
        String title = m.optString("title");
        String text = m.optString("text");
        PendingIntent pi = PendingIntent.getActivity(c, NAV_ID, new Intent(c, NavActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_NAV)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text + "\n" + m.optString("sub")))
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION);
        Bitmap arrow = decode(m.optString("icon"));
        if (arrow != null) b.setLargeIcon(arrow);
        OngoingActivity oa = new OngoingActivity.Builder(c, NAV_ID, b)
                .setStaticIcon(R.drawable.ic_stat)
                .setTouchIntent(pi)
                .setStatus(new Status.Builder().addTemplate(title).build())
                .build();
        oa.apply(c);
        nm.notify(NAV_ID, b.build());
        // vibração curta a cada nova manobra
        if (!title.equals(lastNavTitle)) {
            lastNavTitle = title;
            notifier.vibrate(1);
        }
    }

    static Bitmap decode(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] b = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Exception e) {
            return null;
        }
    }
}
