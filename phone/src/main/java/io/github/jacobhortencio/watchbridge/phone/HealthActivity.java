package io.github.jacobhortencio.watchbridge.phone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Gráficos de saúde, sono estimado, treinos e exportação. */
public class HealthActivity extends Activity {
    private static final int RESTORE = 31;
    private LinearLayout col;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        int pad = dp(20);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(32), pad, dp(40));
        title("Saúde");

        section("Frequência cardíaca · últimas 24 horas");
        ChartView hr = new ChartView(this, ChartView.LINE, 0xFFE53950);
        hr.setEmpty("Ainda sem medições. Ative \"Saúde em segundo plano\" no relógio.");
        long now = System.currentTimeMillis();
        hr.setLine(Prefs.hrHistory(this), now - 24 * 3600_000L, now);
        col.addView(hr, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170)));
        text(hrStats(now - 24 * 3600_000L));

        section("Oxigénio no sangue (SpO2) · últimas 24 horas");
        ChartView sp = new ChartView(this, ChartView.LINE, 0xFF2F7FD8);
        sp.setUnit("%");
        sp.setEmpty("Sem medições. No relógio: Saúde → 🩸 Oxigénio (à noite mede sozinho).");
        sp.setLine(Prefs.spo2History(this), now - 24 * 3600_000L, now);
        col.addView(sp, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)));
        text(statsOf(Prefs.spo2History(this), now - 24 * 3600_000L, "%"));

        section("Passos · 7 dias");
        ChartView steps = new ChartView(this, ChartView.BARS, 0xFF2E9E6A);
        TreeMap<String, Long> per = HealthData.stepsPerDay(this, 7);
        List<String> names = new ArrayList<>();
        List<Float> vals = new ArrayList<>();
        String[] wk = {"D", "S", "T", "Q", "Q", "S", "S"};
        java.util.Calendar cal = java.util.Calendar.getInstance();
        long total = 0;
        for (Map.Entry<String, Long> e : per.entrySet()) {
            try {
                cal.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(e.getKey()));
                names.add(wk[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]);
            } catch (Exception ex) {
                names.add("");
            }
            vals.add((float) e.getValue());
            total += e.getValue();
        }
        steps.setBars(names, vals);
        col.addView(steps, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170)));
        text("Média: " + (total / 7) + " passos por dia");

        section("Sono");
        text(HealthData.sleepSummary(this));
        button("Corrigir as horas desta noite (afina o algoritmo)", this::sleepDiary);

        section("Altitude e pressão · últimas 24 horas");
        java.util.List<long[]> pres = new java.util.ArrayList<>();
        for (long[] r : Prefs.pressureHistory(this)) pres.add(new long[]{r[0], r[1] / 10});
        ChartView pc = new ChartView(this, ChartView.LINE, 0xFF8E6BD6);
        pc.setEmpty("Sem dados do barómetro ainda.");
        pc.setLine(pres, now - 24 * 3600_000L, now);
        col.addView(pc, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)));
        android.content.SharedPreferences pp = Prefs.p(this);
        text("🪜 " + pp.getInt("floors", 0) + " pisos hoje"
                + (pp.contains("alt") ? " · ⛰ " + pp.getInt("alt", 0) + " m de altitude" : "")
                + (pres.isEmpty() ? "" : " · " + pres.get(pres.size() - 1)[1] + " hPa")
                + (pp.contains("w_temp") ? "\n🌡 Temperatura interna do relógio: " + pp.getInt("w_temp", 0) + " °C" : "")
                + (pp.getBoolean("worn", true) ? "" : "\n⌚ O relógio está fora do pulso"));

        section("Treinos");
        List<JSONObject> ws = HealthData.workouts(this);
        if (ws.isEmpty()) text("Ainda sem treinos. Inicie um no relógio: 🏃 Treino.");
        SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
        for (JSONObject w : ws) {
            long secs = w.optLong("ms") / 1000;
            double km = w.optLong("m") / 1000.0;
            String pace = km > 0.05 ? String.format(Locale.US, " · %d:%02d /km",
                    (int) ((secs / 60.0) / km), (int) ((((secs / 60.0) / km) % 1) * 60)) : "";
            text(String.format(Locale.US, "%s · %s\n%.2f km · %d:%02d%s%s",
                    f.format(new Date(w.optLong("start"))), w.optString("type"), km, secs / 60, secs % 60, pace,
                    (w.optInt("avgHr") > 0 ? " · ❤ " + w.optInt("avgHr") + "/" + w.optInt("maxHr") : "")
                            + (w.optInt("gain") > 0 ? " · ↑" + w.optInt("gain") + " m" : "")));
        }

        section("Exportar e cópias de segurança");
        button("Exportar saúde (CSV) e treinos (GPX)", () -> {
            int n = HealthData.exportAll(this);
            toast(n > 0 ? n + " ficheiro(s) em Transferências/WatchBridge" : "Nada para exportar");
        });
        button("Guardar cópia das definições", () -> {
            String name = HealthData.backup(this);
            toast(name != null ? "Guardado: Transferências/WatchBridge/" + name : "Falhou");
        });
        button("Restaurar definições de uma cópia", () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
            startActivityForResult(i, RESTORE);
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == RESTORE && res == RESULT_OK && data != null && data.getData() != null) {
            boolean ok = HealthData.restore(this, data.getData());
            toast(ok ? "Definições restauradas" : "Ficheiro inválido");
            BridgeService s = BridgeService.get();
            if (ok && s != null) s.pushConfig();
        }
    }

    /** Pergunta a hora de deitar e de acordar e afina o sono por movimento. */
    private void sleepDiary() {
        java.util.Calendar bed = java.util.Calendar.getInstance();
        new android.app.TimePickerDialog(this, (v1, bh, bm) -> {
            java.util.Calendar b = java.util.Calendar.getInstance();
            b.set(java.util.Calendar.HOUR_OF_DAY, bh);
            b.set(java.util.Calendar.MINUTE, bm);
            if (bh >= 12) b.add(java.util.Calendar.DAY_OF_YEAR, -1); // deitou-se ontem à noite
            new android.app.TimePickerDialog(this, (v2, wh, wm) -> {
                java.util.Calendar w = java.util.Calendar.getInstance();
                w.set(java.util.Calendar.HOUR_OF_DAY, wh);
                w.set(java.util.Calendar.MINUTE, wm);
                String r = HealthData.calibrate(this, b.getTimeInMillis(), w.getTimeInMillis());
                new android.app.AlertDialog.Builder(this).setTitle("Sono").setMessage(r)
                        .setPositiveButton("OK", (d, x) -> build()).show();
            }, 7, 0, true).show();
            toast("Agora, a hora a que acordou");
        }, 23, 0, true).show();
        toast("Primeiro, a hora a que adormeceu");
    }

    private String statsOf(java.util.List<long[]> data, long from, String unit) {
        int n = 0, min = Integer.MAX_VALUE, max = 0;
        long sum = 0;
        for (long[] r : data) {
            if (r[0] < from) continue;
            n++;
            sum += r[1];
            min = Math.min(min, (int) r[1]);
            max = Math.max(max, (int) r[1]);
        }
        if (n == 0) return "";
        return n + " medições · média " + (sum / n) + unit + " · mín " + min + unit + " · máx " + max + unit;
    }

    private String hrStats(long from) {
        int n = 0, min = Integer.MAX_VALUE, max = 0;
        long sum = 0;
        for (long[] r : Prefs.hrHistory(this)) {
            if (r[0] < from) continue;
            n++;
            sum += r[1];
            min = Math.min(min, (int) r[1]);
            max = Math.max(max, (int) r[1]);
        }
        if (n == 0) return "";
        return n + " medições · média " + (sum / n) + " · mín " + min + " · máx " + max + " bpm";
    }

    private void title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);
    }

    private void section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(6));
        col.addView(t);
    }

    private void text(String s) {
        if (s == null || s.isEmpty()) return;
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(4), 0, dp(4));
        col.addView(t);
    }

    private void button(String s, Runnable r) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(v -> r.run());
        col.addView(b);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
