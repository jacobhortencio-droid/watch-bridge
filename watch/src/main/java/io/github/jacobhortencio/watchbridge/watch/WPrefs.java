package io.github.jacobhortencio.watchbridge.watch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Secure;

/** Preferências do relógio: chave de emparelhamento, configuração recebida, histórico. */
final class WPrefs {
    private WPrefs() {}

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences("wbw", Context.MODE_PRIVATE);
    }

    // ---------- chave ----------

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

    // ---------- configuração vinda do telemóvel ----------

    private static volatile JSONObject cfg;

    static JSONObject cfg(Context c) {
        JSONObject o = cfg;
        if (o != null) return o;
        try {
            o = new JSONObject(p(c).getString("cfg", "{}"));
        } catch (Exception e) {
            o = new JSONObject();
        }
        cfg = o;
        return o;
    }

    static void setCfg(Context c, JSONObject o) {
        cfg = o;
        p(c).edit().putString("cfg", o.toString()).apply();
    }

    static CharSequence[] replies(Context c) {
        JSONArray a = cfg(c).optJSONArray("replies");
        if (a == null || a.length() == 0) {
            return new CharSequence[]{"Ok", "Já te ligo", "Estou a conduzir", "A caminho"};
        }
        CharSequence[] out = new CharSequence[Math.min(a.length(), 10)];
        for (int i = 0; i < out.length; i++) out[i] = a.optString(i);
        return out;
    }

    /** Dentro do horário de silêncio definido no telemóvel. */
    static boolean inQuiet(Context c) {
        JSONObject o = cfg(c);
        if (!o.optBoolean("quietOn")) return false;
        int s = o.optInt("quietStart", 23), e = o.optInt("quietEnd", 7);
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return s <= e ? (hour >= s && hour < e) : (hour >= s || hour < e);
    }

    static boolean daytime() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 8 && hour < 20;
    }

    // ---------- histórico de notificações ----------

    static synchronized void addHistory(Context c, String app, String title, String text, long time) {
        try {
            JSONArray old = new JSONArray(p(c).getString("hist", "[]"));
            JSONArray n = new JSONArray();
            n.put(Msg.of("h", "app", app, "title", title, "text", text.length() > 400 ? text.substring(0, 400) : text,
                    "time", time));
            for (int i = 0; i < old.length() && n.length() < 50; i++) n.put(old.get(i));
            p(c).edit().putString("hist", n.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static JSONArray history(Context c) {
        try {
            return new JSONArray(p(c).getString("hist", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static void clearHistory(Context c) {
        p(c).edit().putString("hist", "[]").apply();
    }

    // ---------- meteorologia ----------

    static void setWeather(Context c, JSONObject w) {
        p(c).edit().putString("weather", w.toString()).apply();
    }

    static JSONObject weather(Context c) {
        try {
            String s = p(c).getString("weather", "");
            return s.isEmpty() ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }
}
