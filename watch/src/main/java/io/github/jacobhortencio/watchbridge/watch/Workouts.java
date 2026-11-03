package io.github.jacobhortencio.watchbridge.watch;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.WLog;

/** Treinos gravados no relógio, enviados ao telemóvel quando houver ligação. */
final class Workouts {
    private Workouts() {}

    static synchronized void saveSummary(Context c, long start, String type, long ms, double meters,
                                         int avgHr, int maxHr, int points) {
        try {
            JSONArray a = list(c);
            a.put(Msg.of("w", "start", start, "type", type, "ms", ms, "m", Math.round(meters),
                    "avgHr", avgHr, "maxHr", maxHr, "points", points, "sent", false));
            WPrefs.p(c).edit().putString("workouts", a.toString()).apply();
        } catch (Exception e) {
            WLog.w("WB-Workout", "resumo falhou", e);
        }
    }

    static JSONArray list(Context c) {
        try {
            return new JSONArray(WPrefs.p(c).getString("workouts", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Envia ao telemóvel os treinos ainda não enviados. */
    static synchronized void flush(Context c) {
        if (!WatchService.connected()) return;
        JSONArray a = list(c);
        boolean changed = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject w = a.optJSONObject(i);
            if (w == null || w.optBoolean("sent")) continue;
            try {
                File f = new File(new File(c.getFilesDir(), "workouts"), w.optLong("start") + ".gpx");
                String gpx = f.exists() ? read(f) : "";
                JSONObject m = new JSONObject(w.toString());
                m.put("t", Proto.WORKOUT);
                m.put("gpx", gpx);
                WatchService.sendToPhone(m);
                w.put("sent", true);
                changed = true;
            } catch (Exception e) {
                WLog.w("WB-Workout", "envio falhou", e);
            }
        }
        if (changed) WPrefs.p(c).edit().putString("workouts", a.toString()).apply();
    }

    private static String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
