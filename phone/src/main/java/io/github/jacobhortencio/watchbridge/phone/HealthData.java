package io.github.jacobhortencio.watchbridge.phone;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.github.jacobhortencio.watchbridge.common.WLog;

/** Análise e exportação: passos por dia, sono estimado, treinos, CSV/GPX e cópia de segurança. */
final class HealthData {
    private HealthData() {}

    // ---------- treinos ----------

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "workouts");
        d.mkdirs();
        return d;
    }

    static void saveWorkout(Context c, JSONObject m) {
        try {
            long start = m.optLong("start");
            String gpx = m.optString("gpx");
            m.remove("gpx");
            m.remove("t");
            try (FileOutputStream o = new FileOutputStream(new File(dir(c), start + ".json"))) {
                o.write(m.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!gpx.isEmpty()) {
                try (FileOutputStream o = new FileOutputStream(new File(dir(c), start + ".gpx"))) {
                    o.write(gpx.getBytes(StandardCharsets.UTF_8));
                }
            }
            WLog.i("WB-Health", "treino recebido: " + m.optLong("m") + " m");
        } catch (Exception e) {
            WLog.w("WB-Health", "gravar treino falhou", e);
        }
    }

    static List<JSONObject> workouts(Context c) {
        List<JSONObject> out = new ArrayList<>();
        File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".json"));
        if (fs == null) return out;
        for (File f : fs) {
            try {
                out.add(new JSONObject(read(f)));
            } catch (Exception ignored) {
            }
        }
        out.sort((a, b) -> Long.compare(b.optLong("start"), a.optLong("start")));
        return out;
    }

    // ---------- passos por dia ----------

    /** Últimos 7 dias: {etiqueta, passos}. Usa o maior valor registado em cada dia. */
    static TreeMap<String, Long> stepsPerDay(Context c, int days) {
        TreeMap<String, Long> m = new TreeMap<>();
        SimpleDateFormat key = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar cal = Calendar.getInstance();
        for (int i = days - 1; i >= 0; i--) {
            Calendar d = (Calendar) cal.clone();
            d.add(Calendar.DAY_OF_YEAR, -i);
            m.put(key.format(d.getTime()), 0L);
        }
        for (long[] r : Prefs.stepsHistory(c)) {
            String k = key.format(new Date(r[0]));
            if (m.containsKey(k) && r[1] > m.get(k)) m.put(k, r[1]);
        }
        return m;
    }

    // ---------- sono estimado ----------

    /**
     * Estimativa: o período mais longo, entre as 20h de ontem e as 12h de hoje, em que os passos
     * quase não mudaram (amostras de 15 em 15 minutos). Não é uma medição clínica.
     */
    static String sleepSummary(Context c) {
        Calendar from = Calendar.getInstance();
        from.add(Calendar.DAY_OF_YEAR, -1);
        from.set(Calendar.HOUR_OF_DAY, 20);
        from.set(Calendar.MINUTE, 0);
        Calendar to = Calendar.getInstance();
        to.set(Calendar.HOUR_OF_DAY, 12);
        to.set(Calendar.MINUTE, 0);
        long a = from.getTimeInMillis(), b = Math.min(to.getTimeInMillis(), System.currentTimeMillis());
        List<long[]> s = new ArrayList<>();
        for (long[] r : Prefs.stepsHistory(c)) if (r[0] >= a && r[0] <= b) s.add(r);
        if (s.size() < 8) return "Sem dados suficientes da noite passada. Durma com o relógio e a app ativa.";

        int bestStart = -1, bestEnd = -1, curStart = 0;
        for (int i = 1; i < s.size(); i++) {
            long dSteps = s.get(i)[1] - s.get(i - 1)[1];
            long gap = s.get(i)[0] - s.get(i - 1)[0];
            boolean still = (dSteps >= 0 && dSteps < 20) || dSteps < 0 && s.get(i)[1] < 20; // < 0: mudou de dia
            if (!still || gap > 45 * 60_000) {
                curStart = i;
                continue;
            }
            if (bestStart < 0 || (s.get(i)[0] - s.get(curStart)[0]) > (s.get(bestEnd)[0] - s.get(bestStart)[0])) {
                bestStart = curStart;
                bestEnd = i;
            }
        }
        if (bestStart < 0) return "Não foi detetado um período de sono.";
        long st = s.get(bestStart)[0], en = s.get(bestEnd)[0];
        long mins = (en - st) / 60_000;
        if (mins < 120) return "Não foi detetado um período de sono com mais de 2 horas.";
        int n = 0;
        long sum = 0;
        int min = Integer.MAX_VALUE;
        for (long[] r : Prefs.hrHistory(c)) {
            if (r[0] >= st && r[0] <= en) {
                n++;
                sum += r[1];
                min = Math.min(min, (int) r[1]);
            }
        }
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String out = "Sono estimado: " + f.format(new Date(st)) + " → " + f.format(new Date(en))
                + " (" + (mins / 60) + "h" + String.format(Locale.US, "%02d", mins % 60) + ")";
        if (n > 0) out += "\nFC média " + (sum / n) + " bpm · mínima " + min + " bpm";
        else out += "\nSem frequência cardíaca nesse período (relógio tirado?)";
        return out + "\nEstimativa pelos movimentos; não é uma medição médica.";
    }

    // ---------- exportação ----------

    /** Grava um ficheiro em Transferências/WatchBridge. Devolve o nome ou null. */
    static String toDownloads(Context c, String name, String mime, byte[] data) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/WatchBridge");
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            try (OutputStream o = c.getContentResolver().openOutputStream(uri)) {
                if (o == null) return null;
                o.write(data);
            }
            return name;
        } catch (Exception e) {
            WLog.w("WB-Health", "exportar falhou", e);
            return null;
        }
    }

    static int exportAll(Context c) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        int n = 0;
        StringBuilder csv = new StringBuilder("data_hora,tipo,valor\n");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (long[] r : Prefs.hrHistory(c)) csv.append(f.format(new Date(r[0]))).append(",fc_bpm,").append(r[1]).append('\n');
        for (long[] r : Prefs.stepsHistory(c)) csv.append(f.format(new Date(r[0]))).append(",passos_dia,").append(r[1]).append('\n');
        if (toDownloads(c, "saude-" + stamp + ".csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8)) != null) n++;
        File[] gpx = dir(c).listFiles((d, name) -> name.endsWith(".gpx"));
        if (gpx != null) {
            for (File g : gpx) {
                try {
                    if (toDownloads(c, "treino-" + g.getName(), "application/gpx+xml", readBytes(g)) != null) n++;
                } catch (Exception ignored) {
                }
            }
        }
        return n;
    }

    // ---------- cópia de segurança das definições ----------

    static String backup(Context c) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, ?> e : Prefs.p(c).getAll().entrySet()) {
                String k = e.getKey();
                if ("pair_key".equals(k)) continue; // a chave de emparelhamento nunca sai do telemóvel
                Object v = e.getValue();
                JSONObject item = new JSONObject();
                if (v instanceof Set) {
                    item.put("type", "set");
                    item.put("v", new JSONArray((Set<?>) v));
                } else if (v instanceof Boolean) {
                    item.put("type", "bool").put("v", v);
                } else if (v instanceof Integer) {
                    item.put("type", "int").put("v", v);
                } else if (v instanceof Long) {
                    item.put("type", "long").put("v", v);
                } else {
                    item.put("type", "str").put("v", String.valueOf(v));
                }
                o.put(k, item);
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
            return toDownloads(c, "watchbridge-backup-" + stamp + ".json", "application/json",
                    o.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            WLog.w("WB-Health", "backup falhou", e);
            return null;
        }
    }

    static boolean restore(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            JSONObject o = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            SharedPreferences.Editor e = Prefs.p(c).edit();
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("pair_key".equals(k)) continue;
                JSONObject item = o.optJSONObject(k);
                if (item == null) continue;
                switch (item.optString("type")) {
                    case "set": {
                        Set<String> s = new HashSet<>();
                        JSONArray a = item.optJSONArray("v");
                        if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
                        e.putStringSet(k, s);
                        break;
                    }
                    case "bool":
                        e.putBoolean(k, item.optBoolean("v"));
                        break;
                    case "int":
                        e.putInt(k, item.optInt("v"));
                        break;
                    case "long":
                        e.putLong(k, item.optLong("v"));
                        break;
                    default:
                        e.putString(k, item.optString("v"));
                        break;
                }
            }
            e.apply();
            return true;
        } catch (Exception ex) {
            WLog.w("WB-Health", "restauro falhou", ex);
            return false;
        }
    }

    private static String read(File f) throws Exception {
        return new String(readBytes(f), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
