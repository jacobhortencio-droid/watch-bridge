package io.github.jacobhortencio.watchbridge.watch;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.jacobhortencio.watchbridge.common.Http;
import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/**
 * Atualização do relógio sem ADB:
 * 1) pede o Wi-Fi ao sistema (no Wear OS pode estar desligado para poupar bateria);
 * 2) se falhar, pede ao telemóvel que descarregue e envie o APK pela ligação Bluetooth.
 */
final class Updater {
    private Updater() {}

    static volatile String state = "";
    private static volatile boolean busy;

    static long localVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    static void run(Context ctx) {
        if (busy) return;
        busy = true;
        final Context app = ctx.getApplicationContext();
        state = "A ligar o Wi-Fi...";
        new Thread(() -> {
            String wifiErr;
            try {
                if (viaWifi(app)) {
                    busy = false;
                    return;
                }
                wifiErr = "Wi-Fi indisponível";
            } catch (Exception e) {
                Log.w("WB-Update", "Wi-Fi falhou", e);
                wifiErr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (WatchService.linkedPhysically()) {
                state = "Sem Wi-Fi (" + wifiErr + "). A descarregar pelo telemóvel...";
                WatchService.sendToPhone(Msg.of(Proto.UPD_REQ, "have", localVersion(app)));
                // a resposta chega em onPhoneApk / onPhoneNone
            } else {
                state = "Sem Wi-Fi (" + wifiErr + ") e telemóvel desligado";
                busy = false;
            }
        }, "wb-update").start();
    }

    /** true se tratou do assunto (atualizou ou já estava atualizado). */
    private static boolean viaWifi(Context ctx) throws Exception {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        CountDownLatch got = new CountDownLatch(1);
        AtomicReference<Network> ref = new AtomicReference<>();
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                ref.set(network);
                got.countDown();
            }
        };
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        cm.requestNetwork(req, cb);
        try {
            if (!got.await(20, TimeUnit.SECONDS) || ref.get() == null) return false;
            Network net = ref.get();
            state = "A procurar atualização...";
            Http.Release r = Http.latest(net);
            long local = localVersion(ctx);
            if (r.version > 0 && r.version <= local) {
                state = "Já tem a versão mais recente (" + local + ")";
                return true;
            }
            if (r.watchApkUrl == null) throw new Exception("APK não encontrado");
            state = "A descarregar a versão " + r.version + "...";
            byte[] apk = Http.get(r.watchApkUrl, net);
            install(ctx, apk);
            return true;
        } finally {
            try {
                cm.unregisterNetworkCallback(cb);
            } catch (Exception ignored) {
            }
        }
    }

    static void onPhoneApk(Context ctx, JSONObject m) {
        busy = false;
        long v = m.optLong("ver", -1);
        if (v > 0 && v <= localVersion(ctx)) {
            state = "Já tem a versão mais recente (" + localVersion(ctx) + ")";
            return;
        }
        try {
            byte[] apk = Base64.decode(m.optString("b64"), Base64.DEFAULT);
            state = "A instalar a versão " + v + "...";
            install(ctx, apk);
        } catch (Exception e) {
            state = "Falhou: " + e.getMessage();
        }
    }

    static void onPhoneNone(JSONObject m) {
        busy = false;
        state = m.optString("msg", "Sem atualização");
    }

    private static void install(Context ctx, byte[] apk) throws Exception {
        state = "A instalar...";
        io.github.jacobhortencio.watchbridge.common.Installer.install(ctx, apk, ctx.getPackageName());
    }
}
