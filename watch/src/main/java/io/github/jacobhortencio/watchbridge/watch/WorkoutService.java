package io.github.jacobhortencio.watchbridge.watch;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.wear.ongoing.OngoingActivity;
import androidx.wear.ongoing.Status;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import io.github.jacobhortencio.watchbridge.common.WLog;

/** Treino com GPS e frequência cardíaca, mesmo com o ecrã apagado. Grava em GPX. */
public class WorkoutService extends Service implements LocationListener, SensorEventListener {
    static final String START = "start", PAUSE = "pause", RESUME = "resume", STOP = "stop";
    private static final int NID = 40;

    static volatile boolean running, paused;
    static volatile String type = "Corrida";
    static volatile long startWall, activeMs, segStart;
    static volatile double distance, elevGain;
    private float lastBaroAlt = Float.NaN;
    static volatile int hr, hrMax, gpsAccuracy = -1;
    private static long hrSum, hrCount;

    private Location last;
    private StringBuilder gpx;
    private int points;
    private PowerManager.WakeLock wake;

    static long elapsed() {
        if (!running) return activeMs;
        return activeMs + (paused ? 0 : System.currentTimeMillis() - segStart);
    }

    static void send(Context c, String action, String t) {
        Intent i = new Intent(c, WorkoutService.class).setAction(action).putExtra("type", t);
        if (START.equals(action)) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (START.equals(a)) start(intent.getStringExtra("type"));
        else if (PAUSE.equals(a)) pause();
        else if (RESUME.equals(a)) resume();
        else if (STOP.equals(a)) finish();
        return START_NOT_STICKY;
    }

    @SuppressLint("MissingPermission")
    private void start(String t) {
        if (running) return;
        type = t == null ? "Corrida" : t;
        running = true;
        paused = false;
        startWall = System.currentTimeMillis();
        segStart = startWall;
        activeMs = 0;
        distance = 0;
        elevGain = 0;
        lastBaroAlt = Float.NaN;
        hr = 0;
        hrMax = 0;
        hrSum = 0;
        hrCount = 0;
        last = null;
        points = 0;
        gpx = new StringBuilder();
        gpx.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"Watch Bridge\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
                .append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
                .append("<trk><name>").append(type).append(' ').append(iso(startWall)).append("</name><type>")
                .append(type).append("</type><trkseg>\n");

        int fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED) {
            fgsType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
        }
        try {
            startForeground(NID, notif(), fgsType);
        } catch (Exception e) {
            WLog.w("WB-Workout", "startForeground falhou", e);
            try {
                startForeground(NID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } catch (Exception e2) {
                running = false;
                stopSelf();
                return;
            }
        }
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wb:workout");
        wake.acquire(4 * 60 * 60_000L);
        try {
            getSystemService(LocationManager.class).requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 2f,
                    this, Looper.getMainLooper());
        } catch (Exception e) {
            WLog.w("WB-Workout", "GPS indisponível", e);
        }
        SensorManager sm = getSystemService(SensorManager.class);
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
        WLog.i("WB-Workout", "treino iniciado: " + type);
    }

    private void pause() {
        if (!running || paused) return;
        activeMs += System.currentTimeMillis() - segStart;
        paused = true;
        update();
    }

    private void resume() {
        if (!running || !paused) return;
        segStart = System.currentTimeMillis();
        paused = false;
        last = null; // não conta a distância percorrida em pausa
        update();
    }

    private void finish() {
        if (!running) {
            stopSelf();
            return;
        }
        if (!paused) activeMs += System.currentTimeMillis() - segStart;
        running = false;
        try {
            getSystemService(LocationManager.class).removeUpdates(this);
        } catch (Exception ignored) {
        }
        getSystemService(SensorManager.class).unregisterListener(this);
        if (wake != null && wake.isHeld()) wake.release();
        gpx.append("</trkseg></trk>\n</gpx>\n");
        int avg = hrCount > 0 ? (int) (hrSum / hrCount) : 0;
        try {
            File dir = new File(getFilesDir(), "workouts");
            dir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(new File(dir, startWall + ".gpx"))) {
                o.write(gpx.toString().getBytes(StandardCharsets.UTF_8));
            }
            Workouts.saveSummary(this, startWall, type, activeMs, distance, avg, hrMax, points, elevGain);
        } catch (Exception e) {
            WLog.w("WB-Workout", "gravar falhou", e);
        }
        Workouts.flush(this);
        WLog.i("WB-Workout", "treino terminado: " + Math.round(distance) + " m");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onLocationChanged(Location l) {
        gpsAccuracy = Math.round(l.getAccuracy());
        if (!running || paused) return;
        if (l.getAccuracy() > 35) return;
        if (last != null) {
            float d = last.distanceTo(l);
            long dt = Math.max(1, (l.getTime() - last.getTime()) / 1000);
            if (d / dt < 30) distance += d; // ignora saltos impossíveis (> 108 km/h)
        }
        last = l;
        points++;
        // desnível pelo barómetro (mais preciso que o GPS); ignora variações < 1 m
        float ba = WSensors.altitude;
        if (!Float.isNaN(ba)) {
            if (Float.isNaN(lastBaroAlt)) lastBaroAlt = ba;
            else if (ba - lastBaroAlt >= 1f) {
                elevGain += ba - lastBaroAlt;
                lastBaroAlt = ba;
            } else if (ba < lastBaroAlt) lastBaroAlt = ba;
        }
        gpx.append(String.format(Locale.US, "<trkpt lat=\"%.6f\" lon=\"%.6f\">", l.getLatitude(), l.getLongitude()));
        if (!Float.isNaN(WSensors.altitude)) gpx.append(String.format(Locale.US, "<ele>%.1f</ele>", WSensors.altitude));
        else if (l.hasAltitude()) gpx.append(String.format(Locale.US, "<ele>%.1f</ele>", l.getAltitude()));
        gpx.append("<time>").append(iso(l.getTime())).append("</time>");
        if (hr > 0) {
            gpx.append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>").append(hr)
                    .append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>");
        }
        gpx.append("</trkpt>\n");
        if (points % 5 == 0) update();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_HEART_RATE) return;
        int v = Math.round(e.values[0]);
        if (v <= 30 || v > 230 || e.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT) return;
        hr = v;
        if (!paused) {
            hrMax = Math.max(hrMax, v);
            hrSum += v;
            hrCount++;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) {
    }

    private static String iso(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t));
    }

    static String summaryLine() {
        long s = elapsed() / 1000;
        return String.format(Locale.US, "%d:%02d:%02d · %.2f km · ↑%d m", s / 3600, (s / 60) % 60, s % 60,
                distance / 1000, Math.round(elevGain));
    }

    private Notification notif() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("workout", "Treino", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, NID, new Intent(this, WorkoutActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, "workout")
                .setSmallIcon(R.drawable.ic_steps)
                .setContentTitle(type + (paused ? " (pausa)" : ""))
                .setContentText(summaryLine())
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT);
        new OngoingActivity.Builder(this, NID, b)
                .setStaticIcon(R.drawable.ic_steps)
                .setTouchIntent(pi)
                .setStatus(new Status.Builder().addTemplate(type + " " + summaryLine()).build())
                .build()
                .apply(this);
        return b.build();
    }

    private void update() {
        getSystemService(NotificationManager.class).notify(NID, notif());
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
