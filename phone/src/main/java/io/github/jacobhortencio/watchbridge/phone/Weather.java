package io.github.jacobhortencio.watchbridge.phone;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

import io.github.jacobhortencio.watchbridge.common.Http;
import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.WLog;

/** Previsão do tempo pela Open-Meteo (grátis, sem chave). */
final class Weather {
    private Weather() {}

    static volatile JSONObject last;
    private static volatile long lastFetch;

    static void refresh(Context ctx, boolean force) {
        if (!force && System.currentTimeMillis() - lastFetch < 30 * 60_000 && last != null) {
            BridgeService.send(last);
            return;
        }
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                String url = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,is_day"
                                + "&hourly=temperature_2m,weather_code,precipitation_probability"
                                + "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max"
                                + "&timezone=auto&forecast_days=4&forecast_hours=12",
                        Prefs.lat(app), Prefs.lon(app));
                JSONObject j = new JSONObject(Http.getText(url, null));
                JSONObject cur = j.getJSONObject("current");
                JSONObject daily = j.getJSONObject("daily");
                JSONObject hourly = j.optJSONObject("hourly");

                JSONArray days = new JSONArray();
                JSONArray times = daily.getJSONArray("time");
                for (int i = 0; i < times.length(); i++) {
                    days.put(Msg.of("d", "date", times.getString(i),
                            "max", Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)),
                            "min", Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)),
                            "code", daily.getJSONArray("weather_code").getInt(i),
                            "rain", daily.getJSONArray("precipitation_probability_max").optInt(i, 0)));
                }
                JSONArray hours = new JSONArray();
                if (hourly != null) {
                    JSONArray ht = hourly.getJSONArray("time");
                    for (int i = 0; i < ht.length(); i += 2) {
                        String time = ht.getString(i);
                        hours.put(Msg.of("h", "time", time.length() >= 16 ? time.substring(11, 16) : time,
                                "temp", Math.round(hourly.getJSONArray("temperature_2m").getDouble(i)),
                                "code", hourly.getJSONArray("weather_code").getInt(i),
                                "rain", hourly.getJSONArray("precipitation_probability").optInt(i, 0)));
                    }
                }
                int code = cur.getInt("weather_code");
                JSONObject msg = Msg.of(Proto.WEATHER,
                        "city", Prefs.city(app),
                        "temp", Math.round(cur.getDouble("temperature_2m")),
                        "hum", cur.optInt("relative_humidity_2m"),
                        "wind", Math.round(cur.optDouble("wind_speed_10m", 0)),
                        "code", code,
                        "desc", describe(code),
                        "day", cur.optInt("is_day", 1) == 1,
                        "days", days,
                        "hours", hours,
                        "time", System.currentTimeMillis());
                last = msg;
                lastFetch = System.currentTimeMillis();
                BridgeService.send(msg);
            } catch (Exception e) {
                WLog.w("WB-Weather", "falhou", e);
            }
        }, "wb-weather").start();
    }

    /** Códigos WMO usados pela Open-Meteo. */
    static String describe(int c) {
        if (c == 0) return "Céu limpo";
        if (c <= 2) return "Pouco nublado";
        if (c == 3) return "Nublado";
        if (c == 45 || c == 48) return "Nevoeiro";
        if (c >= 51 && c <= 57) return "Chuvisco";
        if (c >= 61 && c <= 67) return "Chuva";
        if (c >= 71 && c <= 77) return "Neve";
        if (c >= 80 && c <= 82) return "Aguaceiros";
        if (c >= 95) return "Trovoada";
        return "Variável";
    }
}
