package io.github.jacobhortencio.watchbridge.watch;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TreeMap;

import io.github.jacobhortencio.watchbridge.common.WLog;

/**
 * Sensores extra do Xiaomi Watch 2 (lista enviada pelo utilizador):
 * no pulso (34), acelerómetro à noite para o sono, barómetro (altitude, pisos, tempestade),
 * luz ambiente, temperatura da placa (65589) e SpO2 (65596 / 65588).
 */
final class WSensors implements SensorEventListener {
    static final int TYPE_OFFBODY = 34;
    static final int TYPE_SPO2 = 65596;
    static final int TYPE_MI_SPO2 = 65588;
    static final int TYPE_BOARD_TEMP = 65589;

    static volatile boolean onWrist = true;
    static volatile float pressure = -1, altitude = Float.NaN, lux = -1, boardTemp = Float.NaN;
    static volatile int floorsToday;
    static volatile int lastSpo2 = -1;
    static volatile long lastSpo2Time;
    static volatile String spo2Raw = "";

    private final Context ctx;
    private final SensorManager sm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Notifier notifier;
    private boolean accelOn;
    private final TreeMap<Long, float[]> minutes = new TreeMap<>(); // minuto -> {soma, n}
    private final ArrayDeque<float[]> pressureLog = new ArrayDeque<>(); // {tempo em min, hPa}
    private float floorBase = Float.NaN;
    private long lastStorm, lastHot;
    private int stepsAtLastFloor = -1;

    WSensors(Context c, Notifier n) {
        ctx = c;
        notifier = n;
        sm = c.getSystemService(SensorManager.class);
        loadFloors();
    }

    void start() {
        reg(TYPE_OFFBODY, SensorManager.SENSOR_DELAY_NORMAL, 0);
        reg(Sensor.TYPE_PRESSURE, 60_000_000, 5 * 60_000_000);
        reg(Sensor.TYPE_LIGHT, 60_000_000, 5 * 60_000_000);
        reg(TYPE_BOARD_TEMP, 5 * 60_000_000, 10 * 60_000_000);
        h.post(nightCheck);
    }

    void stop() {
        h.removeCallbacksAndMessages(null);
        sm.unregisterListener(this);
    }

    private boolean reg(int type, int periodUs, int latencyUs) {
        Sensor s = sm.getDefaultSensor(type);
        if (s == null) return false;
        try {
            return sm.registerListener(this, s, periodUs, latencyUs);
        } catch (Exception e) {
            WLog.w("WB-Sens", "registo falhou " + type, e);
            return false;
        }
    }

    // ---------- acelerómetro só à noite (sono por movimento) ----------

    static boolean night() {
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hr >= 20 || hr < 12;
    }

    private final Runnable nightCheck = new Runnable() {
        @Override
        public void run() {
            boolean want = night() && onWrist;
            Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (want && !accelOn && a != null) {
                // 10 amostras por segundo, entregues em lotes de 10 minutos (o processador dorme)
                accelOn = sm.registerListener(WSensors.this, a, 100_000, 10 * 60_000_000);
            } else if (!want && accelOn && a != null) {
                sm.unregisterListener(WSensors.this, a);
                accelOn = false;
            }
            h.postDelayed(this, 10 * 60_000);
        }
    };

    private static long wallMs(SensorEvent e) {
        return System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000L;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int t = e.sensor.getType();
        if (t == Sensor.TYPE_ACCELEROMETER) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            float dev = Math.abs((float) Math.sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH);
            long minute = wallMs(e) / 60_000L;
            synchronized (minutes) {
                float[] acc = minutes.get(minute);
                if (acc == null) minutes.put(minute, acc = new float[2]);
                acc[0] += dev;
                acc[1]++;
            }
        } else if (t == TYPE_OFFBODY) {
            boolean was = onWrist;
            onWrist = e.values[0] > 0.5f;
            if (was != onWrist) WLog.i("WB-Sens", onWrist ? "relógio no pulso" : "relógio fora do pulso");
        } else if (t == Sensor.TYPE_PRESSURE) {
            onPressure(e.values[0]);
        } else if (t == Sensor.TYPE_LIGHT) {
            lux = e.values[0];
        } else if (t == TYPE_BOARD_TEMP) {
            boardTemp = e.values[0];
            long now = System.currentTimeMillis();
            if (boardTemp > 50 && now - lastHot > 30 * 60_000) {
                lastHot = now;
                notifier.alert(Notifier.HEALTH_ID + 10, "Relógio quente",
                        String.format(Locale.US, "Temperatura interna %.0f °C. Tire-o do sol ou do carregador.", boardTemp));
            }
        }
    }

    // ---------- barómetro: altitude, pisos e tempestade ----------

    private void onPressure(float hpa) {
        if (hpa < 300 || hpa > 1100) return;
        pressure = hpa;
        altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hpa);
        // pisos: subida de 3 m enquanto caminha (passos a aumentar)
        int steps = WatchService.todaySteps;
        if (Float.isNaN(floorBase) || altitude < floorBase) floorBase = altitude;
        if (altitude - floorBase >= 3.0f) {
            if (steps > stepsAtLastFloor && stepsAtLastFloor >= 0) {
                floorsToday += Math.max(1, (int) ((altitude - floorBase) / 3.0f));
                saveFloors();
            }
            floorBase = altitude;
        }
        stepsAtLastFloor = steps;
        // tempestade: queda de 3 hPa em 3 horas
        long nowMin = System.currentTimeMillis() / 60_000L;
        if (pressureLog.isEmpty() || nowMin - (long) pressureLog.peekLast()[0] >= 15) {
            pressureLog.addLast(new float[]{nowMin, hpa});
            while (pressureLog.size() > 32) pressureLog.removeFirst();
        }
        for (float[] p : pressureLog) {
            if (nowMin - (long) p[0] <= 190 && nowMin - (long) p[0] >= 150) {
                long now = System.currentTimeMillis();
                if (p[1] - hpa >= 3f && now - lastStorm > 6 * 3600_000L) {
                    lastStorm = now;
                    notifier.alert(Notifier.HEALTH_ID + 11, "Pressão a cair depressa",
                            String.format(Locale.US, "Desceu %.1f hPa em 3 horas: possível mau tempo.", p[1] - hpa));
                }
                break;
            }
        }
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private void loadFloors() {
        SharedPreferences p = ctx.getSharedPreferences("floors", Context.MODE_PRIVATE);
        floorsToday = today().equals(p.getString("day", "")) ? p.getInt("n", 0) : 0;
    }

    private void saveFloors() {
        SharedPreferences p = ctx.getSharedPreferences("floors", Context.MODE_PRIVATE);
        if (!today().equals(p.getString("day", ""))) floorsToday = Math.min(floorsToday, 1);
        p.edit().putString("day", today()).putInt("n", floorsToday).apply();
    }

    /** Junta à amostra de saúde os valores dos sensores e os minutos de movimento já fechados. */
    void fill(JSONObject m, boolean withActivity) {
        try {
            m.put("worn", onWrist);
            if (pressure > 0) m.put("pres", Math.round(pressure * 10) / 10.0);
            if (!Float.isNaN(altitude)) m.put("alt", Math.round(altitude));
            if (lux >= 0) m.put("lux", Math.round(lux));
            if (!Float.isNaN(boardTemp)) m.put("temp", Math.round(boardTemp));
            m.put("floors", floorsToday);
            if (withActivity) {
                JSONArray act = new JSONArray();
                long cur = System.currentTimeMillis() / 60_000L;
                synchronized (minutes) {
                    while (!minutes.isEmpty() && minutes.firstKey() < cur) {
                        java.util.Map.Entry<Long, float[]> en = minutes.pollFirstEntry();
                        float[] v = en.getValue();
                        if (v[1] > 0) act.put(new JSONArray().put(en.getKey()).put(Math.round(v[0] / v[1] * 1000)));
                    }
                }
                if (act.length() > 0) m.put("act", act);
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- SpO2 ----------

    interface Spo2Listener {
        void onProgress(String text);

        void onDone(int value);
    }

    static Sensor spo2Sensor(SensorManager sm) {
        Sensor s = sm.getDefaultSensor(TYPE_SPO2);
        if (s == null) s = sm.getDefaultSensor(TYPE_MI_SPO2);
        return s;
    }

    /** Medição de SpO2 de ~40 s. O formato do sensor da Xiaomi não é público: guarda os valores brutos. */
    static void measureSpo2(Context c, Spo2Listener l) {
        SensorManager sm = c.getSystemService(SensorManager.class);
        Sensor s = spo2Sensor(sm);
        if (s == null) {
            l.onDone(-2);
            return;
        }
        Handler hh = new Handler(Looper.getMainLooper());
        long start = System.currentTimeMillis();
        final int[] best = {-1};
        SensorEventListener sl = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                StringBuilder raw = new StringBuilder();
                for (int i = 0; i < Math.min(6, e.values.length); i++) {
                    if (i > 0) raw.append(", ");
                    raw.append(String.format(Locale.US, "%.1f", e.values[i]));
                }
                spo2Raw = raw.toString();
                for (float v : e.values) {
                    int iv = Math.round(v);
                    if (iv >= 70 && iv <= 100) best[0] = iv;
                }
                long secs = (System.currentTimeMillis() - start) / 1000;
                l.onProgress("A medir... " + secs + " s" + (best[0] > 0 ? "  (" + best[0] + "%)" : ""));
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        boolean ok;
        try {
            ok = sm.registerListener(sl, s, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            WLog.w("WB-SpO2", "o sistema recusou o sensor " + s.getStringType());
            l.onDone(-3);
            return;
        }
        WLog.i("WB-SpO2", "a medir com " + s.getName());
        hh.postDelayed(() -> {
            sm.unregisterListener(sl);
            WLog.i("WB-SpO2", "resultado " + best[0] + " · bruto: " + spo2Raw);
            if (best[0] > 0) {
                lastSpo2 = best[0];
                lastSpo2Time = System.currentTimeMillis();
            }
            l.onDone(best[0]);
        }, 40_000);
    }
}
