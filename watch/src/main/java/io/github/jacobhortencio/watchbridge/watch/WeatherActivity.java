package io.github.jacobhortencio.watchbridge.watch;

import android.app.Activity;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

/** Previsão do tempo enviada pelo telemóvel (Open-Meteo). */
public class WeatherActivity extends Activity {
    static String icon(int c, boolean day) {
        if (c == 0) return day ? "☀️" : "🌙";
        if (c <= 2) return day ? "🌤" : "☁️";
        if (c == 3) return "☁️";
        if (c == 45 || c == 48) return "🌫";
        if (c >= 51 && c <= 67) return "🌧";
        if (c >= 71 && c <= 77) return "❄️";
        if (c >= 80 && c <= 82) return "🌦";
        if (c >= 95) return "⛈";
        return "🌡";
    }

    private static final String[] WEEK = {"Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = Ui.column(this);
        JSONObject w = WPrefs.weather(this);
        if (w == null) {
            Ui.text(this, col, "Tempo", 15, true);
            Ui.text(this, col, "Ainda sem previsão. Ligue o telemóvel.", 13, false);
            Ui.show(this, col);
            return;
        }
        boolean day = w.optBoolean("day", true);
        Ui.text(this, col, w.optString("city"), 13, false);
        Ui.text(this, col, icon(w.optInt("code"), day) + " " + w.optInt("temp") + "°", 34, true);
        Ui.text(this, col, w.optString("desc"), 14, false);
        Ui.text(this, col, "💧 " + w.optInt("hum") + "%   💨 " + w.optInt("wind") + " km/h", 12, false);

        JSONArray hours = w.optJSONArray("hours");
        if (hours != null && hours.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(5, hours.length()); i++) {
                JSONObject hr = hours.optJSONObject(i);
                if (hr == null) continue;
                sb.append(hr.optString("time")).append("  ").append(icon(hr.optInt("code"), true)).append(' ')
                        .append(hr.optInt("temp")).append("°");
                if (hr.optInt("rain") >= 30) sb.append("  ☔").append(hr.optInt("rain")).append('%');
                sb.append('\n');
            }
            Ui.text(this, col, sb.toString().trim(), 12, false);
        }

        JSONArray days = w.optJSONArray("days");
        if (days != null) {
            StringBuilder sb = new StringBuilder();
            java.util.Calendar cal = java.util.Calendar.getInstance();
            for (int i = 0; i < days.length(); i++) {
                JSONObject d = days.optJSONObject(i);
                if (d == null) continue;
                String name = i == 0 ? "Hoje" : WEEK[(cal.get(java.util.Calendar.DAY_OF_WEEK) - 1 + i) % 7];
                sb.append(name).append("  ").append(icon(d.optInt("code"), true)).append(' ')
                        .append(d.optInt("max")).append("° / ").append(d.optInt("min")).append("°\n");
            }
            Ui.text(this, col, sb.toString().trim(), 13, false);
        }
        Ui.text(this, col, "Atualizado " + DateUtils.getRelativeTimeSpanString(w.optLong("time")), 10, false);
        Ui.show(this, col);
    }
}
