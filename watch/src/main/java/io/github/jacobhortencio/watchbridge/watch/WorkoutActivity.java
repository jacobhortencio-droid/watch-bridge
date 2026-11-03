package io.github.jacobhortencio.watchbridge.watch;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Iniciar, pausar e terminar treinos com GPS. */
public class WorkoutActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout choose, live;
    private TextView time, dist, pace, heart, gps, lastTxt;
    private Button pauseBtn, stopBtn;
    private long stopArmed;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Treino", 15, true);

        choose = new LinearLayout(this);
        choose.setOrientation(LinearLayout.VERTICAL);
        col.addView(choose);
        for (String t : new String[]{"🏃 Corrida", "🚶 Caminhada", "🚴 Bicicleta"}) {
            Button btn = new Button(this);
            btn.setText(t);
            btn.setAllCaps(false);
            btn.setOnClickListener(v -> start(t.substring(t.indexOf(' ') + 1)));
            choose.addView(btn);
        }
        lastTxt = new TextView(this);
        lastTxt.setTextSize(11);
        lastTxt.setGravity(android.view.Gravity.CENTER);
        choose.addView(lastTxt);

        live = new LinearLayout(this);
        live.setOrientation(LinearLayout.VERTICAL);
        col.addView(live);
        time = Ui.text(this, live, "", 26, true);
        dist = Ui.text(this, live, "", 18, true);
        pace = Ui.text(this, live, "", 13, false);
        heart = Ui.text(this, live, "", 13, false);
        gps = Ui.text(this, live, "", 10, false);
        LinearLayout row = Ui.row(this, live);
        pauseBtn = Ui.rowButton(this, row, "⏸", v -> WorkoutService.send(this,
                WorkoutService.paused ? WorkoutService.RESUME : WorkoutService.PAUSE, null));
        stopBtn = Ui.rowButton(this, row, "⏹", v -> {
            long now = System.currentTimeMillis();
            if (now - stopArmed < 3000) {
                WorkoutService.send(this, WorkoutService.STOP, null);
            } else {
                stopArmed = now;
                stopBtn.setText("⏹?");
            }
        });
        Ui.show(this, col);
    }

    private void start(String type) {
        String[] need = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BODY_SENSORS};
        if (checkSelfPermission(need[0]) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(need, 8);
            return;
        }
        WorkoutService.send(this, WorkoutService.START, type);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresh);
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            boolean r = WorkoutService.running;
            choose.setVisibility(r ? View.GONE : View.VISIBLE);
            live.setVisibility(r ? View.VISIBLE : View.GONE);
            if (r) {
                long s = WorkoutService.elapsed() / 1000;
                time.setText(String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60));
                double km = WorkoutService.distance / 1000;
                dist.setText(String.format(Locale.US, "%.2f km", km));
                if (km > 0.05) {
                    double minPerKm = (s / 60.0) / km;
                    pace.setText(String.format(Locale.US, "%d:%02d /km · %.1f km/h",
                            (int) minPerKm, (int) ((minPerKm % 1) * 60), km / (s / 3600.0)));
                } else {
                    pace.setText(WorkoutService.type);
                }
                heart.setText(WorkoutService.hr > 0 ? "❤ " + WorkoutService.hr + " bpm" : "❤ --");
                int acc = WorkoutService.gpsAccuracy;
                gps.setText(acc < 0 ? "🛰 à procura de GPS..." : "🛰 GPS ±" + acc + " m");
                pauseBtn.setText(WorkoutService.paused ? "▶" : "⏸");
                if (System.currentTimeMillis() - stopArmed > 3000) stopBtn.setText("⏹");
            } else {
                JSONArray a = Workouts.list(WorkoutActivity.this);
                if (a.length() > 0) {
                    JSONObject w = a.optJSONObject(a.length() - 1);
                    long secs = w.optLong("ms") / 1000;
                    lastTxt.setText(String.format(Locale.US, "Último: %s %.2f km em %d min%s",
                            w.optString("type"), w.optLong("m") / 1000.0, secs / 60,
                            w.optBoolean("sent") ? " ✓" : " (por enviar)"));
                }
            }
            h.postDelayed(this, 1000);
        }
    };
}
