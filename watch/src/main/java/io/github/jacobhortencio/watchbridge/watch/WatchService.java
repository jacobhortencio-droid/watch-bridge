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
        h.postDelayed(tick, 30_000);
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

    private void updateStatus() {
        if (bleUp) status = "Ligado por Bluetooth";
        else if (tcp != null) status = "Ligado por Wi-Fi";
        else status = "À espera do telemóvel";
        notifier.updateOngoing(status);
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
                break;
            case Proto.RING:
                notifier.ring(m.optBoolean("on", true));
                break;
            case Proto.HELLO:
                phoneName = m.optString("name");
                sendSteps(true);
                break;
            default:
                break;
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
