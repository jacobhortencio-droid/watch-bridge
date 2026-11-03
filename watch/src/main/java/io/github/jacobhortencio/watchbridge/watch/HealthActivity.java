package io.github.jacobhortencio.watchbridge.watch;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Mede a frequência cardíaca enquanto o ecrã está aberto e envia-a ao telemóvel. */
public class HealthActivity extends Activity implements SensorEventListener {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView hrText, stepsText, hint;
    private SensorManager sm;
    private long lastSent;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Saúde", 14, true);
        hrText = Ui.text(this, col, "❤ --", 30, true);
        hint = Ui.text(this, col, "A medir...", 12, false);
        stepsText = Ui.text(this, col, "", 15, false);
        Ui.show(this, col);
        sm = getSystemService(SensorManager.class);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 2);
            hint.setText("Autorize os sensores");
        } else {
            Sensor s = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            if (s == null) hint.setText("Sensor não disponível");
            else sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
        }
        h.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sm.unregisterListener(this);
        h.removeCallbacks(refresh);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        recreate();
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            int st = WatchService.todaySteps;
            stepsText.setText(st >= 0 ? "👣 " + st + " passos hoje" : "👣 a contar passos");
            h.postDelayed(this, 2000);
        }
    };

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_HEART_RATE) return;
        int bpm = Math.round(e.values[0]);
        if (bpm <= 0 || e.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT
                || e.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            hint.setText("Ajuste o relógio no pulso");
            return;
        }
        hrText.setText("❤ " + bpm);
        hint.setText("bpm");
        long now = System.currentTimeMillis();
        if (now - lastSent > 10_000) {
            lastSent = now;
            WatchService s = WatchService.get();
            if (s != null) s.reportHealth(bpm);
            else WatchService.sendToPhone(Msg.of(Proto.HEALTH, "hr", bpm, "steps", WatchService.todaySteps));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
