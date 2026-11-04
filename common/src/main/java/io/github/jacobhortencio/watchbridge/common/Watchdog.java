package io.github.jacobhortencio.watchbridge.common;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** Alarme de 15 em 15 minutos que volta a ligar o serviço se o sistema o tiver fechado. */
public final class Watchdog {
    private Watchdog() {}

    public static void schedule(Context c, Class<?> receiver) {
        Intent i = new Intent(c, receiver).setAction("io.github.jacobhortencio.watchbridge.WATCHDOG");
        PendingIntent pi = PendingIntent.getBroadcast(c, 4242, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi);
    }
}
