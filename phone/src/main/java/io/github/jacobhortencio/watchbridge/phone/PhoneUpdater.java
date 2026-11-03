package io.github.jacobhortencio.watchbridge.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import io.github.jacobhortencio.watchbridge.common.Http;
import io.github.jacobhortencio.watchbridge.common.Installer;
import io.github.jacobhortencio.watchbridge.common.WLog;

/** A app do telemóvel atualiza-se a partir da página Releases do GitHub. */
final class PhoneUpdater {
    private PhoneUpdater() {}

    static volatile String state = "";
    static volatile long available = -1;
    private static volatile boolean busy;

    static long localVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Verificação silenciosa: só avisa com uma notificação se houver versão nova. */
    static void checkQuietly(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                Http.Release r = Http.latest(null);
                available = r.version;
                if (r.version > localVersion(app)) notifyAvailable(app, r.version);
            } catch (Exception e) {
                WLog.w("WB-Update", "verificação falhou: " + e.getMessage());
            }
        }, "wb-phone-check").start();
    }

    static void update(Context ctx) {
        if (busy) return;
        busy = true;
        final Context app = ctx.getApplicationContext();
        state = "A procurar atualização...";
        new Thread(() -> {
            try {
                Http.Release r = Http.latest(null);
                available = r.version;
                long local = localVersion(app);
                if (r.version > 0 && r.version <= local) {
                    state = "Já tem a versão mais recente (" + local + ")";
                    return;
                }
                if (r.phoneApkUrl == null) throw new Exception("APK não encontrado");
                state = "A descarregar a versão " + r.version + "...";
                byte[] apk = Http.get(r.phoneApkUrl, null);
                if (!app.getPackageManager().canRequestPackageInstalls()) {
                    state = "Autorize 'Instalar apps desconhecidas' para o Watch Bridge e tente de novo";
                    Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:" + app.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(i);
                    return;
                }
                Installer.install(app, apk, app.getPackageName());
                state = "A instalar...";
            } catch (Exception e) {
                state = "Falhou: " + e.getMessage();
                WLog.w("WB-Update", "atualização falhou", e);
            } finally {
                busy = false;
            }
        }, "wb-phone-update").start();
    }

    private static void notifyAvailable(Context c, long v) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("updates", "Atualizações", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent pi = PendingIntent.getActivity(c, 9, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        nm.notify(9, new Notification.Builder(c, "updates")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Watch Bridge: versão " + v + " disponível")
                .setContentText("Toque para atualizar o telemóvel e o relógio")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }
}
