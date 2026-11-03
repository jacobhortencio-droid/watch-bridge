package io.github.jacobhortencio.watchbridge.watch;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Contador de copos de água por dia. */
final class Wellness {
    private Wellness() {}

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("wellness", Context.MODE_PRIVATE);
    }

    static int waterToday(Context c) {
        SharedPreferences p = p(c);
        return today().equals(p.getString("water_day", "")) ? p.getInt("water", 0) : 0;
    }

    static void addWater(Context c) {
        int n = waterToday(c) + 1;
        p(c).edit().putString("water_day", today()).putInt("water", n).apply();
    }
}
