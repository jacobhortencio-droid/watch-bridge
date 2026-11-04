package io.github.jacobhortencio.watchbridge.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.speech.tts.TextToSpeech;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

import io.github.jacobhortencio.watchbridge.common.Installer;
import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.WLog;

/** Ferramentas do relógio: receber e instalar APKs, gerir apps, ler mensagens em voz alta. */
final class WatchTools {
    private WatchTools() {}

    private static final String CH = "tools";
    private static String apkId = "";
    private static ByteArrayOutputStream apk;
    private static int nextSeq;

    // ---------- instalar APK vindo do telemóvel ----------

    static synchronized void apkBegin(JSONObject m) {
        apkId = m.optString("id");
        apk = new ByteArrayOutputStream(Math.max(1024, m.optInt("size", 1024)));
        nextSeq = 0;
        WLog.i("WB-Apk", "a receber APK de " + m.optInt("size") + " bytes");
    }

    static synchronized void apkChunk(Context c, JSONObject m) {
        if (!m.optString("id").equals(apkId) || apk == null) return;
        int seq = m.optInt("seq", -1);
        if (seq == nextSeq) {
            byte[] b = Base64.decode(m.optString("b64"), Base64.DEFAULT);
            apk.write(b, 0, b.length);
            nextSeq++;
        }
        // confirma sempre (um bloco repetido também é confirmado)
        WatchService.sendToPhone(Msg.of(Proto.APK_ACK, "id", apkId, "seq", nextSeq - 1));
        if (m.optBoolean("last") && seq == nextSeq - 1) {
            byte[] all = apk.toByteArray();
            apk = null;
            try {
                Installer.install(c, all, null);
            } catch (Exception e) {
                WLog.w("WB-Apk", "instalação falhou", e);
                WatchService.sendToPhone(Msg.of(Proto.RESULT, "msg", "Instalação falhou: " + e.getMessage()));
            }
        }
    }

    // ---------- apps instaladas ----------

    static void sendApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        JSONArray out = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (!seen.add(pkg)) continue;
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                boolean system = (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                out.put(Msg.of("a", "pkg", pkg, "label", ri.loadLabel(pm).toString(),
                        "ver", pi.versionName == null ? "" : pi.versionName,
                        "removable", !system && !pkg.equals(c.getPackageName())));
            } catch (Exception ignored) {
            }
        }
        WatchService.sendToPhone(Msg.of(Proto.APPS, "apps", out));
    }

    private static void channel(Context c) {
        c.getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CH, "Pedidos do telemóvel", NotificationManager.IMPORTANCE_HIGH));
    }

    /** O Android não deixa abrir apps a partir de segundo plano: fica uma notificação para tocar. */
    static void openApp(Context c, String pkg) {
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        prompt(c, pkg.hashCode(), "Abrir " + label(c, pkg), "Toque para abrir", launch);
    }

    static void uninstallApp(Context c, String pkg) {
        Intent del = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        prompt(c, pkg.hashCode() + 1, "Desinstalar " + label(c, pkg) + "?", "Toque para confirmar", del);
    }

    private static void prompt(Context c, int id, String title, String text, Intent target) {
        channel(c);
        PendingIntent pi = PendingIntent.getActivity(c, id, target, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getSystemService(NotificationManager.class).notify(8000 + (id & 0xfff), new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    private static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---------- ler em voz alta ----------

    private static TextToSpeech tts;
    private static boolean ttsReady;
    private static String pending;

    static void speak(Context c, String text) {
        if (text == null || text.isEmpty()) return;
        if (tts == null) {
            pending = text;
            tts = new TextToSpeech(c.getApplicationContext(), status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    int r = tts.setLanguage(new Locale("pt", "PT"));
                    if (r < 0) tts.setLanguage(new Locale("pt", "BR"));
                    if (pending != null) tts.speak(pending, TextToSpeech.QUEUE_FLUSH, null, "wb");
                    pending = null;
                } else {
                    WLog.w("WB-TTS", "voz indisponível no relógio");
                }
            });
            return;
        }
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wb");
    }
}
