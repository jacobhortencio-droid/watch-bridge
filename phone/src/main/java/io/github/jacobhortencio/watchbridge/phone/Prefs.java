package io.github.jacobhortencio.watchbridge.phone;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private Prefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("wb", Context.MODE_PRIVATE);
    }

    static String watchIp(Context c) {
        return p(c).getString("watch_ip", "");
    }

    static void setWatchIp(Context c, String ip) {
        p(c).edit().putString("watch_ip", ip == null ? "" : ip.trim()).apply();
    }

    static void saveHealth(Context c, int hr, int steps) {
        SharedPreferences.Editor e = p(c).edit();
        long now = System.currentTimeMillis();
        if (hr > 0) e.putInt("hr", hr).putLong("hr_t", now);
        if (steps >= 0) e.putInt("steps", steps).putLong("steps_t", now);
        e.apply();
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
}
