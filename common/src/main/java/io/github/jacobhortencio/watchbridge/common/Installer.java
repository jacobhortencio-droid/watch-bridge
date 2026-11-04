package io.github.jacobhortencio.watchbridge.common;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.OutputStream;

/** Instala um APK com o PackageInstaller (atualizações próprias ou outras apps). */
public final class Installer {
    private Installer() {}

    public interface Listener {
        void onState(String state);
    }

    public static volatile String state = "";
    private static volatile Listener listener;

    public static void setListener(Listener l) {
        listener = l;
    }

    static void setState(String s) {
        state = s;
        Listener l = listener;
        if (l != null) l.onState(s);
    }

    /** @param pkg nome do pacote se for uma atualização própria (permite instalar sem perguntar); null para outras apps */
    public static void install(Context ctx, byte[] apk, String pkg) throws Exception {
        setState("A instalar...");
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (pkg != null) p.setAppPackageName(pkg);
        if (Build.VERSION.SDK_INT >= 31) {
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("base.apk", 0, apk.length)) {
                out.write(apk);
                s.fsync(out);
            }
            Intent i = new Intent(ctx, Receiver.class).setPackage(ctx.getPackageName());
            PendingIntent pend = PendingIntent.getBroadcast(ctx, id, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pend.getIntentSender());
        }
    }

    static void notifyConfirm(Context c, Intent confirm) {
        android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
        nm.createNotificationChannel(new android.app.NotificationChannel("install", "Instalações",
                android.app.NotificationManager.IMPORTANCE_HIGH));
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(c, 7001, confirm,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(7001, new android.app.Notification.Builder(c, "install")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Confirmar instalação")
                .setContentText("Toque para continuar")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    /** Declarado no manifesto de cada app. */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    // em segundo plano o Android bloqueia a janela sem avisar: fica também uma notificação
                    notifyConfirm(c, confirm);
                    try {
                        c.startActivity(confirm);
                        setState("Confirme a instalação no ecrã (ou toque na notificação)");
                    } catch (Exception e) {
                        setState("Toque na notificação para confirmar a instalação");
                    }
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                c.getSystemService(android.app.NotificationManager.class).cancel(7001);
                setState("Instalado");
            } else {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                setState("Falhou: " + (msg == null ? ("código " + st) : msg));
            }
        }
    }
}
