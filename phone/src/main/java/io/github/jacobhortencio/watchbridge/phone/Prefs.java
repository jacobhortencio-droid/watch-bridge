package io.github.jacobhortencio.watchbridge.phone;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Prefs {
    private Prefs() {}

    private static final int HR_MAX = 500;

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("wb", Context.MODE_PRIVATE);
    }

    static String watchIp(Context c) {
        return p(c).getString("watch_ip", "");
    }

    static void setWatchIp(Context c, String ip) {
        p(c).edit().putString("watch_ip", ip == null ? "" : ip.trim()).apply();
    }

    // ---------- saúde ----------

    static synchronized void saveHealth(Context c, int hr, int steps, long time) {
        SharedPreferences.Editor e = p(c).edit();
        long t = time > 0 ? time : System.currentTimeMillis();
        if (hr > 0) {
            if (t >= p(c).getLong("hr_t", 0)) e.putInt("hr", hr).putLong("hr_t", t);
            String hist = p(c).getString("hr_hist", "");
            StringBuilder sb = new StringBuilder(hist);
            if (sb.length() > 0) sb.append(';');
            sb.append(t).append(':').append(hr);
            String[] parts = sb.toString().split(";");
            if (parts.length > HR_MAX) {
                sb.setLength(0);
                for (int i = parts.length - HR_MAX; i < parts.length; i++) {
                    if (sb.length() > 0) sb.append(';');
                    sb.append(parts[i]);
                }
            }
            e.putString("hr_hist", sb.toString());
        }
        if (steps >= 0) e.putInt("steps", steps).putLong("steps_t", t);
        e.apply();
    }

    /** Pares {tempo, bpm}, ordenados por tempo. */
    static List<long[]> hrHistory(Context c) {
        List<long[]> out = new ArrayList<>();
        String h = p(c).getString("hr_hist", "");
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

    static int lastHr(Context c) {
        return p(c).getInt("hr", -1);
    }

    static long lastHrTime(Context c) {
        return p(c).getLong("hr_t", 0);
    }

    static int lastSteps(Context c) {
        return p(c).getInt("steps", -1);
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
}
