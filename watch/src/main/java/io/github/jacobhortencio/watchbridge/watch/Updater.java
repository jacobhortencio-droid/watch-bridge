package io.github.jacobhortencio.watchbridge.watch;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O relógio atualiza-se sozinho pelo Wi-Fi a partir da página Releases do GitHub,
 * sem voltar a precisar de ADB.
 */
final class Updater {
    private Updater() {}

    private static final String API =
            "https://api.github.com/repos/jacobhortencio-droid/watch-bridge/releases/tags/latest";
    private static final String ASSET = "WatchBridge-relogio.apk";

    static volatile String state = "";
    private static volatile boolean busy;
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void run(Context ctx) {
        if (busy) return;
        busy = true;
        final Context app = ctx.getApplicationContext();
        state = "A procurar atualização...";
        new Thread(() -> {
            try {
                check(app);
            } catch (Exception e) {
                Log.w("WB-Update", "falhou", e);
                state = "Sem acesso à internet pelo Wi-Fi";
            } finally {
                busy = false;
            }
        }, "wb-update").start();
    }

    private static void check(Context ctx) throws Exception {
        JSONObject rel = new JSONObject(new String(get(API), StandardCharsets.UTF_8));
        Matcher mt = Pattern.compile("(\\d+)").matcher(rel.optString("name"));
        long remote = mt.find() ? Long.parseLong(mt.group(1)) : -1;
        long local = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode();
        if (remote > 0 && remote <= local) {
            state = "Já tem a versão mais recente (" + local + ")";
            return;
        }
        String url = null;
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a != null && ASSET.equals(a.optString("name"))) url = a.optString("browser_download_url");
            }
        }
        if (url == null) {
            state = "Ficheiro não encontrado na página Releases";
            return;
        }
        state = "A descarregar a versão " + remote + "...";
        byte[] apk = get(url);
        state = "A instalar...";
        install(ctx, apk);
    }

    private static byte[] get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "watch-bridge");
        c.setRequestProperty("Accept", "application/json, application/octet-stream");
        int code = c.getResponseCode();
        if (code >= 300 && code < 400) { // redirecionamento entre domínios
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (loc != null) return get(loc);
        }
        if (code != 200) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static void install(Context ctx, byte[] apk) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("base.apk", 0, apk.length)) {
                out.write(apk);
                s.fsync(out);
            }
            Intent i = new Intent(ctx, InstallReceiver.class);
            PendingIntent pend = PendingIntent.getBroadcast(ctx, id, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pend.getIntentSender());
        }
    }

    public static class InstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(confirm);
                        state = "Confirme a instalação no ecrã";
                    } catch (Exception e) {
                        state = "O relógio não deixou abrir o instalador";
                    }
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                state = "Atualizado";
            } else {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                state = "Falhou: " + (msg == null ? ("código " + st) : msg);
            }
        }
    }
}
