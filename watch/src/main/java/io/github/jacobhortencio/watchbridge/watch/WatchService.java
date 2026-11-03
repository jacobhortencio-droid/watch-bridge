package io.github.jacobhortencio.watchbridge.watch;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.TcpLink;

/** Serviço permanente do relógio: BLE + Wi-Fi, notificações e passos. */
public class WatchService extends Service implements BlePeripheral.Listener, TcpLink.Listener, SensorEventListener {
    private static final String TAG = "WB";

    private static volatile WatchService instance;
    static volatile String status = "Parado";
    static volatile int phoneBattery = -1;
    static volatile boolean phoneCharging;
    static volatile String phoneName = "";
    static volatile JSONObject media;
    static volatile int todaySteps = -1;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Notifier notifier;
    private BlePeripheral ble;
    private boolean bleUp;
    private volatile TcpLink tcp;
    private volatile ServerSocket server;
    private volatile boolean destroyed;
    private long lastStepsSent;
    private boolean lowWarned;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, WatchService.class));
        } catch (Exception e) {
            Log.w(TAG, "não foi possível iniciar", e);
        }
    }

    static WatchService get() {
        return instance;
    }

    static boolean connected() {
        WatchService s = instance;
        return s != null && (s.bleUp || s.tcp != null);
    }

    static void sendToPhone(JSONObject m) {
        WatchService s = instance;
        if (s == null) return;
        if (s.bleUp) {
            s.ble.send(m);
            return;
        }
        TcpLink t = s.tcp;
        if (t != null) t.send(m);
    }

    Notifier notifier() {
        return notifier;
    }

    String bleError() {
        return ble == null ? "" : ble.lastError;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        notifier = new Notifier(this);
        try {
            startForeground(Notifier.ONGOING_ID, notifier.ongoing("À espera do telemóvel"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (Exception e) {
            Log.w(TAG, "startForeground falhou", e);
            stopSelf();
            return;
        }
        ble = new BlePeripheral(this, this);
        if (!ble.start()) Log.w(TAG, "BLE: " + ble.lastError);
        startTcpServer();
        registerSteps();
        updateStatus();
        upgradeForeground();
        h.postDelayed(tick, 30_000);
        h.postDelayed(hrTick, 60_000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        instance = null;
        h.removeCallbacksAndMessages(null);
        if (ble != null) ble.stop();
        TcpLink t = tcp;
        if (t != null) t.close();
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        try {
            getSystemService(SensorManager.class).unregisterListener(this);
        } catch (Exception ignored) {
        }
        status = "Parado";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (connected()) sendToPhone(Msg.of(Proto.PING));
            h.postDelayed(this, 30_000);
        }
    };

    private boolean wasConnected;

    /** Avisa (com vibração) se a ligação cair durante mais de 30 segundos. */
    private final Runnable lostAlert = () -> {
        if (!connected()) {
            notifier.alert(Notifier.LOST_ID, "Telemóvel desligado",
                    "Perdeu-se a ligação ao telemóvel. Volta sozinha quando estiver perto.");
        }
    };

    private void updateStatus() {
        boolean now = bleUp || tcp != null;
        if (bleUp) status = "Ligado por Bluetooth";
        else if (tcp != null) status = "Ligado por Wi-Fi";
        else status = "À espera do telemóvel";
        notifier.updateOngoing(status);
        if (wasConnected && !now) h.postDelayed(lostAlert, 30_000);
        if (now) {
            h.removeCallbacks(lostAlert);
            notifier.cancel(Notifier.LOST_ID);
            if (!wasConnected) flushPendingHealth();
        }
        wasConnected = now;
    }

    // ---------- frequência cardíaca em segundo plano ----------

    private static final long HR_INTERVAL = 15 * 60_000;
    private final org.json.JSONArray pendingHealth = new org.json.JSONArray();
    private boolean measuring;
    private long measureStart;

    private final Runnable hrTick = new Runnable() {
        @Override
        public void run() {
            startHr();
            h.postDelayed(this, HR_INTERVAL);
        }
    };

    private final Runnable hrStop = this::stopHr;

    boolean canMeasure() {
        return checkSelfPermission(android.Manifest.permission.BODY_SENSORS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    void startHr() {
        if (measuring || !canMeasure()) return;
        SensorManager sm = getSystemService(SensorManager.class);
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        if (s == null) return;
        try {
            measuring = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Exception e) {
            measuring = false;
        }
        if (measuring) {
            measureStart = System.currentTimeMillis();
            h.postDelayed(hrStop, 45_000);
        }
    }

    private void stopHr() {
        h.removeCallbacks(hrStop);
        if (measuring) {
            SensorManager sm = getSystemService(SensorManager.class);
            Sensor s = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            if (s != null) sm.unregisterListener(this, s);
        }
        measuring = false;
    }

    private void onHeartRate(SensorEvent e) {
        if (!measuring) return;
        int bpm = Math.round(e.values[0]);
        if (bpm <= 30 || bpm > 230) return;
        if (e.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT
                || e.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return;
        if (System.currentTimeMillis() - measureStart < 8_000) return; // deixa o sensor estabilizar
        stopHr();
        reportHealth(bpm);
    }

    /** Envia ao telemóvel, ou guarda para enviar quando voltar a ligação. */
    void reportHealth(int bpm) {
        JSONObject m = Msg.of(Proto.HEALTH, "hr", bpm, "steps", todaySteps, "time", System.currentTimeMillis());
        if (connected()) {
            sendToPhone(m);
        } else {
            synchronized (pendingHealth) {
                if (pendingHealth.length() >= 200) pendingHealth.remove(0);
                pendingHealth.put(m);
            }
        }
    }

    private void flushPendingHealth() {
        synchronized (pendingHealth) {
            for (int i = 0; i < pendingHealth.length(); i++) {
                JSONObject m = pendingHealth.optJSONObject(i);
                if (m != null) sendToPhone(m);
            }
            while (pendingHealth.length() > 0) pendingHealth.remove(0);
        }
    }

    /** Com a permissão de sensores, pede ao sistema o tipo "saúde" para medir com o ecrã apagado. */
    void upgradeForeground() {
        if (android.os.Build.VERSION.SDK_INT < 34 || !canMeasure()) return;
        try {
            startForeground(Notifier.ONGOING_ID, notifier.ongoing(status),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        } catch (Exception e) {
            Log.w(TAG, "tipo saúde indisponível", e);
        }
    }

    // ---------- Wi-Fi ----------

    private void startTcpServer() {
        Thread t = new Thread(() -> {
            while (!destroyed) {
                try {
                    ServerSocket ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(Proto.TCP_PORT));
                    server = ss;
                    while (!destroyed) {
                        Socket s = ss.accept();
                        s.setSoTimeout(60_000);
                        s.setKeepAlive(true);
                        TcpLink old = tcp;
                        if (old != null) old.close();
                        TcpLink l = new TcpLink(s, this);
                        tcp = l;
                        l.start();
                        h.post(() -> {
                            updateStatus();
                            sendToPhone(Msg.of(Proto.HELLO, "name", "Xiaomi Watch 2"));
                        });
                    }
                } catch (IOException e) {
                    if (destroyed) return;
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "wb-tcp-server");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onMessage(TcpLink link, JSONObject msg) {
        h.post(() -> dispatch(msg));
    }

    @Override
    public void onClosed(TcpLink link) {
        h.post(() -> {
            if (tcp == link) tcp = null;
            updateStatus();
        });
    }

    // ---------- Bluetooth ----------

    @Override
    public void onConnectionChanged(boolean connected) {
        bleUp = connected;
        updateStatus();
        if (connected) sendToPhone(Msg.of(Proto.HELLO, "name", "Xiaomi Watch 2"));
    }

    @Override
    public void onMessage(JSONObject m) {
        dispatch(m);
    }

    // ---------- mensagens do telemóvel ----------

    private void dispatch(JSONObject m) {
        switch (m.optString("t")) {
            case Proto.NOTIF:
                notifier.post(m);
                break;
            case Proto.NOTIF_RM:
                notifier.remove(m.optString("key"));
                break;
            case Proto.ICON:
                notifier.cacheIcon(m.optString("pkg"), m.optString("png"));
                break;
            case Proto.MEDIA:
                media = m;
                break;
            case Proto.BATTERY:
                phoneBattery = m.optInt("level", -1);
                phoneCharging = m.optBoolean("charging");
                if (phoneBattery >= 0 && phoneBattery <= 15 && !phoneCharging && !lowWarned) {
                    lowWarned = true;
                    notifier.alert(Notifier.LOWBAT_ID, "Bateria do telemóvel fraca",
                            "O telemóvel está com " + phoneBattery + "%.");
                } else if (phoneCharging || phoneBattery > 20) {
                    lowWarned = false;
                    notifier.cancel(Notifier.LOWBAT_ID);
                }
                break;
            case Proto.RING:
                notifier.ring(m.optBoolean("on", true));
                break;
            case Proto.HELLO:
                phoneName = m.optString("name");
                sendSteps(true);
                sendSensorList();
                break;
            case Proto.UPD_APK:
                Updater.onPhoneApk(this, m);
                break;
            case Proto.UPD_NONE:
                Updater.onPhoneNone(m);
                break;
            default:
                break;
        }
    }

    /** Lista de sensores do relógio, para descobrir o que mais dá para ler (SpO2, temperatura...). */
    private void sendSensorList() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(android.os.Build.MODEL).append(" · Android ").append(android.os.Build.VERSION.RELEASE)
                    .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
            for (Sensor s : getSystemService(SensorManager.class).getSensorList(Sensor.TYPE_ALL)) {
                sb.append(s.getType()).append(" | ").append(s.getStringType()).append(" | ")
                        .append(s.getName()).append(" | ").append(s.getVendor()).append('\n');
            }
            sendToPhone(Msg.of(Proto.SENSORS, "list", sb.toString()));
        } catch (Exception e) {
            Log.w(TAG, "lista de sensores falhou", e);
        }
    }

    // ---------- passos ----------

    private void registerSteps() {
        try {
            SensorManager sm = getSystemService(SensorManager.class);
            Sensor s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 5 * 60 * 1_000_000);
        } catch (Exception e) {
            Log.w(TAG, "sensor de passos indisponível", e);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_HEART_RATE) {
            onHeartRate(e);
            return;
        }
        if (e.sensor.getType() != Sensor.TYPE_STEP_COUNTER) return;
        long total = (long) e.values[0];
        SharedPreferences p = getSharedPreferences("steps", MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String day = p.getString("day", "");
        long base = p.getLong("base", total);
        long acc = p.getLong("acc", 0);
        long last = p.getLong("last", total);
        if (!today.equals(day)) {
            day = today;
            base = total;
            acc = 0;
        } else if (total < last) { // o relógio reiniciou
            acc += last - base;
            base = 0;
        }
        p.edit().putString("day", day).putLong("base", base).putLong("acc", acc).putLong("last", total).apply();
        todaySteps = (int) (acc + (total - base));
        sendSteps(false);
    }

    private void sendSteps(boolean force) {
        long now = System.currentTimeMillis();
        if (todaySteps < 0) return;
        if (!force && now - lastStepsSent < 10 * 60_000) return;
        lastStepsSent = now;
        sendToPhone(Msg.of(Proto.HEALTH, "steps", todaySteps));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
