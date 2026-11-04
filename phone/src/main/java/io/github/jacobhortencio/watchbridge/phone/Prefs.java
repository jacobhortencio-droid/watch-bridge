package io.github.jacobhortencio.watchbridge.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.jacobhortencio.watchbridge.common.Secure;

final class Prefs {
    private Prefs() {}

    private static final int HR_MAX = 2000;

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences("wb", Context.MODE_PRIVATE);
    }

    // ---------- ligação ----------

    static String watchIp(Context c) {
        return p(c).getString("watch_ip", "");
    }

    static void setWatchIp(Context c, String ip) {
        p(c).edit().putString("watch_ip", ip == null ? "" : ip.trim()).apply();
    }

    static String bondedAddr(Context c) {
        return p(c).getString("bonded", "");
    }

    static void setBondedAddr(Context c, String a) {
        p(c).edit().putString("bonded", a == null ? "" : a).apply();
    }

    // ---------- emparelhamento ----------

    private static volatile byte[] keyCache;

    static byte[] key(Context c) {
        byte[] k = keyCache;
        if (k != null) return k;
        String s = p(c).getString("pair_key", "");
        if (s.isEmpty()) return null;
        keyCache = Secure.unb64(s);
        return keyCache;
    }

    static void setKey(Context c, byte[] key) {
        keyCache = key;
        p(c).edit().putString("pair_key", key == null ? "" : Secure.b64(key)).apply();
    }

    // ---------- saúde ----------

    static synchronized void saveHealth(Context c, int hr, int steps, long time) {
        SharedPreferences.Editor e = p(c).edit();
        long t = time > 0 ? time : System.currentTimeMillis();
        if (hr > 0) {
            if (t >= p(c).getLong("hr_t", 0)) e.putInt("hr", hr).putLong("hr_t", t);
            e.putString("hr_hist", appendCapped(p(c).getString("hr_hist", ""), t + ":" + hr, HR_MAX));
        }
        if (steps >= 0) {
            e.putInt("steps", steps).putLong("steps_t", t);
            e.putString("steps_hist", appendCapped(p(c).getString("steps_hist", ""), t + ":" + steps, HR_MAX));
        }
        e.apply();
    }

    private static String appendCapped(String hist, String item, int max) {
        StringBuilder sb = new StringBuilder(hist);
        if (sb.length() > 0) sb.append(';');
        sb.append(item);
        String[] parts = sb.toString().split(";");
        if (parts.length <= max) return sb.toString();
        sb.setLength(0);
        for (int i = parts.length - max; i < parts.length; i++) {
            if (sb.length() > 0) sb.append(';');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    static List<long[]> history(Context c, String key) {
        List<long[]> out = new ArrayList<>();
        String h = p(c).getString(key, "");
        if (h.isEmpty()) return out;
        for (String s : h.split(";")) {
            int i = s.indexOf(':');
            if (i <= 0) continue;
            try {
                out.add(new long[]{Long.parseLong(s.substring(0, i)), Long.parseLong(s.substring(i + 1))});
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a[0], b[0]));
        return out;
    }

    /** Sensores extra do relógio: SpO2, movimento por minuto, pressão, pisos, no pulso. */
    static synchronized void saveExtras(Context c, org.json.JSONObject m) {
        SharedPreferences.Editor e = p(c).edit();
        long t = m.optLong("time", System.currentTimeMillis());
        int spo2 = m.optInt("spo2", -1);
        if (spo2 > 0) e.putString("spo2_hist", appendCapped(p(c).getString("spo2_hist", ""), t + ":" + spo2, 1000));
        org.json.JSONArray act = m.optJSONArray("act");
        if (act != null && act.length() > 0) {
            StringBuilder sb = new StringBuilder(p(c).getString("act_hist", ""));
            for (int i = 0; i < act.length(); i++) {
                org.json.JSONArray a = act.optJSONArray(i);
                if (a == null) continue;
                if (sb.length() > 0) sb.append(';');
                sb.append(a.optLong(0) * 60_000L).append(':').append(a.optLong(1));
            }
            e.putString("act_hist", capList(sb.toString(), 3000));
        }
        double pres = m.optDouble("pres", -1);
        if (pres > 0) e.putString("pres_hist", appendCapped(p(c).getString("pres_hist", ""), t + ":" + Math.round(pres * 10), 600));
        if (m.has("floors")) e.putInt("floors", m.optInt("floors"));
        if (m.has("alt")) e.putInt("alt", m.optInt("alt"));
        if (m.has("worn")) e.putBoolean("worn", m.optBoolean("worn", true));
        if (m.has("temp")) e.putInt("w_temp", m.optInt("temp"));
        e.apply();
    }

    private static String capList(String s, int max) {
        String[] parts = s.split(";");
        if (parts.length <= max) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = parts.length - max; i < parts.length; i++) {
            if (sb.length() > 0) sb.append(';');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    static List<long[]> spo2History(Context c) {
        return history(c, "spo2_hist");
    }

    static List<long[]> activityHistory(Context c) {
        return history(c, "act_hist");
    }

    static List<long[]> pressureHistory(Context c) {
        return history(c, "pres_hist");
    }

    static List<long[]> hrHistory(Context c) {
        return history(c, "hr_hist");
    }

    static List<long[]> stepsHistory(Context c) {
        return history(c, "steps_hist");
    }

    static int lastHr(Context c) {
        return p(c).getInt("hr", -1);
    }

    static long lastHrTime(Context c) {
        return p(c).getLong("hr_t", 0);
    }

    static int lastSteps(Context c) {
        return p(c).getInt("steps", -1);
    }

    // ---------- relógio ----------

    static void setWatchBattery(Context c, int level, boolean charging) {
        p(c).edit().putInt("w_bat", level).putBoolean("w_chg", charging).apply();
    }

    static int watchBattery(Context c) {
        return p(c).getInt("w_bat", -1);
    }

    static boolean watchCharging(Context c) {
        return p(c).getBoolean("w_chg", false);
    }

    static void setSensors(Context c, String list) {
        p(c).edit().putString("sensors", list == null ? "" : list).apply();
    }

    static String sensors(Context c) {
        return p(c).getString("sensors", "");
    }

    // ---------- apps ----------

    static Set<String> muted(Context c) {
        return new HashSet<>(p(c).getStringSet("muted", Collections.emptySet()));
    }

    static void setMuted(Context c, String pkg, boolean muted) {
        Set<String> s = muted(c);
        if (muted) s.add(pkg);
        else s.remove(pkg);
        p(c).edit().putStringSet("muted", s).apply();
    }

    static Set<String> seenApps(Context c) {
        return new HashSet<>(p(c).getStringSet("seen", Collections.emptySet()));
    }

    static void addSeenApp(Context c, String pkg) {
        Set<String> s = seenApps(c);
        if (s.add(pkg)) p(c).edit().putStringSet("seen", s).apply();
    }

    /** Padrão de vibração por app (0 normal, 1 curto, 2 longo, 3 forte, 4 sem vibração). */
    static int vibFor(Context c, String pkg) {
        return p(c).getInt("vib_" + pkg, 0);
    }

    static void setVibFor(Context c, String pkg, int v) {
        p(c).edit().putInt("vib_" + pkg, v).apply();
    }

    static final String[] VIB_NAMES = {"Normal", "Curta", "Longa", "Forte", "Sem vibração"};

    // ---------- definições (ecrã Definições) ----------

    static String str(Context c, String k, String def) {
        return p(c).getString(k, def);
    }

    static boolean bool(Context c, String k, boolean def) {
        return p(c).getBoolean(k, def);
    }

    static int integer(Context c, String k, int def) {
        return p(c).getInt(k, def);
    }

    static List<String> list(Context c, String k, String def) {
        List<String> out = new ArrayList<>();
        for (String s : str(c, k, def).split("\n|,")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static final String DEFAULT_REPLIES = "Ok\nJá te ligo\nEstou a conduzir\nEstou numa reunião\nA caminho\nObrigado!";

    /** Configuração enviada ao relógio. */
    static JSONObject watchConfig(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("replies", new JSONArray(list(c, "replies", DEFAULT_REPLIES)));
            o.put("quietOn", bool(c, "quiet_on", false));
            o.put("quietStart", integer(c, "quiet_start", 23));
            o.put("quietEnd", integer(c, "quiet_end", 7));
            o.put("hrAlert", integer(c, "hr_alert", 120));
            o.put("hrAlertOn", bool(c, "hr_alert_on", false));
            o.put("moveOn", bool(c, "move_on", false));
            o.put("waterOn", bool(c, "water_on", false));
            o.put("lostAlertOn", bool(c, "lost_alert_watch", true));
            JSONArray btns = new JSONArray();
            for (int i = 1; i <= 3; i++) btns.put(str(c, "btn" + i + "_name", "Botão " + i));
            o.put("buttons", btns);
            o.put("forwardWatch", bool(c, "forward_watch", false));
            JSONArray acts = new JSONArray();
            for (String[] a : Integrations.actions(c)) acts.put(a[0]);
            o.put("actions", acts);
        } catch (Exception ignored) {
        }
        return o;
    }

    // ---------- meteorologia ----------

    static double lat(Context c) {
        return Double.longBitsToDouble(p(c).getLong("lat", Double.doubleToLongBits(-12.5763)));
    }

    static double lon(Context c) {
        return Double.longBitsToDouble(p(c).getLong("lon", Double.doubleToLongBits(13.4055)));
    }

    static String city(Context c) {
        return p(c).getString("city", "Benguela");
    }

    static void setPlace(Context c, double lat, double lon, String city) {
        p(c).edit().putLong("lat", Double.doubleToLongBits(lat)).putLong("lon", Double.doubleToLongBits(lon))
                .putString("city", city).apply();
    }
}
