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

    /** Declarado no manifesto de cada app. */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(confirm);
                        setState("Confirme a instalação no ecrã");
                    } catch (Exception e) {
                        setState("Não foi possível abrir o instalador");
                    }
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                setState("Instalado");
            } else {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                setState("Falhou: " + (msg == null ? ("código " + st) : msg));
            }
        }
    }
}
