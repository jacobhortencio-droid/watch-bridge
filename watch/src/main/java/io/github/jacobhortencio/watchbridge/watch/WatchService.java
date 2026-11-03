package io.github.jacobhortencio.watchbridge.watch;

import android.Manifest;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Pairing;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.SecureLink;
import io.github.jacobhortencio.watchbridge.common.TcpLink;
import io.github.jacobhortencio.watchbridge.common.WLog;

/** Serviço permanente do relógio: BLE + Wi-Fi, camada segura, notificações, saúde e lembretes. */
public class WatchService extends Service implements BlePeripheral.Listener, TcpLink.Listener, SensorEventListener {
    private static final String TAG = "WB";

    private static volatile WatchService instance;
    static volatile String status = "Parado";
    static volatile int phoneBattery = -1;
    static volatile boolean phoneCharging;
    static volatile String phoneName = "";
    static volatile JSONObject media;
    static volatile int todaySteps = -1;
    static volatile int lastHr = -1;
    static volatile boolean phoneDnd;
    static volatile Pairing pairing;
    static volatile String pairState = "";

    private final Handler h = new Handler(Looper.getMainLooper());
    private Notifier notifier;
    private BlePeripheral ble;
    private boolean bleUp;
    private volatile TcpLink tcp;
    private volatile ServerSocket server;
    private volatile boolean destroyed;
    private SecureLink link;
    private boolean secureReady;
    private long lastStepsSent;
    private boolean lowWarned;
    private int myBattery = -1;
    private boolean myCharging;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, WatchService.class));
        } catch (Exception e) {
            WLog.w(TAG, "não foi possível iniciar", e);
        }
    }

    static WatchService get() {
        return instance;
    }

    /** Ligado, emparelhado e com sessão segura. */
    static boolean connected() {
        WatchService s = instance;
        return s != null && s.linked() && s.link.ready();
    }

    static boolean linkedPhysically() {
        WatchService s = instance;
        return s != null && s.linked();
    }

    static boolean paired(Context c) {
        return WPrefs.key(c) != null;
    }

    static void sendToPhone(JSONObject m) {
        WatchService s = instance;
        if (s != null) s.send(m);
    }

    Notifier notifier() {
        return notifier;
    }

    String bleError() {
        return ble == null ? "" : ble.lastError;
    }

    private boolean linked() {
        return bleUp || tcp != null;
    }

    private void send(JSONObject m) {
        JSONObject wire = link == null ? null : link.wrap(m);
        if (wire != null) sendRaw(wire);
    }

    private void sendRaw(JSONObject wire) {
        if (bleUp) {
            ble.send(wire);
            return;
        }
        TcpLink t = tcp;
        if (t != null) t.send(wire);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        link = new SecureLink(() -> WPrefs.key(this));
        notifier = new Notifier(this);
        try {
            startForeground(Notifier.ONGOING_ID, notifier.ongoing("À espera do telemóvel"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (Exception e) {
            WLog.w(TAG, "startForeground falhou", e);
            stopSelf();
            return;
        }
        ble = new BlePeripheral(this, this);
        if (!ble.start()) WLog.w(TAG, "BLE: " + ble.lastError);
        startTcpServer();
        registerSteps();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        updateStatus();
        upgradeForeground();
        h.postDelayed(tick, 30_000);
        h.postDelayed(hrTick, 60_000);
        WLog.i(TAG, "serviço do relógio iniciado, versão " + Updater.localVersion(this));
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
            unregisterReceiver(batteryReceiver);
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
            if (linked()) sendRaw(Msg.of(Proto.PING));
            Pairing p = pairing;
            if (p != null && p.expired()) {
                pairing = null;
                pairState = "O emparelhamento expirou.";
            }
            h.postDelayed(this, 30_000);
        }
    };

    private boolean wasConnected;

    /** Avisa (com vibração) se a ligação cair durante mais de 30 segundos. */
    private final Runnable lostAlert = () -> {
        if (!connected() && WPrefs.cfg(this).optBoolean("lostAlertOn", true)) {
            notifier.alert(Notifier.LOST_ID, "Telemóvel desligado",
                    "Perdeu-se a ligação ao telemóvel. Volta sozinha quando estiver perto.");
        }
    };

    private void updateStatus() {
        boolean now = connected();
        if (now) status = bleUp ? "Ligado por Bluetooth 🔒" : "Ligado por Wi-Fi 🔒";
        else if (linked() && !paired(this)) status = "Ligado. Falta emparelhar.";
        else if (linked() && link.keyMismatch()) status = "Emparelhamento diferente. Emparelhe de novo.";
        else if (linked()) status = "A abrir sessão segura...";
        else status = "À espera do telemóvel";
        notifier.updateOngoing(status);
        if (wasConnected && !now) h.postDelayed(lostAlert, 30_000);
        if (now) {
            h.removeCallbacks(lostAlert);
            notifier.cancel(Notifier.LOST_ID);
        }
        wasConnected = now;
    }

    private void onLinkUp() {
        link.reset();
        secureReady = false;
        sendRaw(link.sessionMsg("watch"));
        updateStatus();
    }

    private void onSecureReady() {
        if (secureReady) return;
        secureReady = true;
        WLog.i(TAG, "sessão segura aberta");
        updateStatus();
        refreshSurfaces();
        send(Msg.of(Proto.HELLO, "name", Build.MODEL, "ver", Updater.localVersion(this)));
        sendSteps(true);
        sendSensorList();
        sendMyBattery();
        flushPendingHealth();
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
                            if (!bleUp) onLinkUp();
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
    public void onMessage(TcpLink l, JSONObject msg) {
        h.post(() -> dispatch(msg));
    }

    @Override
    public void onClosed(TcpLink l) {
        h.post(() -> {
            if (tcp == l) tcp = null;
            if (!bleUp) secureReady = false;
            updateStatus();
        });
    }

    // ---------- Bluetooth ----------

    @Override
    public void onConnectionChanged(boolean c) {
        bleUp = c;
        if (c) {
            onLinkUp();
        } else {
            if (tcp == null) secureReady = false;
            else onLinkUp();
            updateStatus();
        }
    }

    @Override
    public void onMessage(JSONObject m) {
        dispatch(m);
    }

    // ---------- emparelhamento ----------

    void confirmPairing() {
        Pairing p = pairing;
        if (p == null || p.key == null) return;
        p.localOk = true;
        sendRaw(Msg.of(Proto.PAIR_OK, "proof", p.proof()));
        pairState = p.peerOk ? "" : "Confirme também no telemóvel.";
        maybeFinishPairing();
    }

    void cancelPairing() {
        if (pairing != null) sendRaw(Msg.of(Proto.PAIR_CANCEL));
        pairing = null;
        pairState = "Cancelado.";
        notifier.cancel(Notifier.PAIR_ID);
    }

    private void maybeFinishPairing() {
        Pairing p = pairing;
        if (p == null || !p.done()) return;
        WPrefs.setKey(this, p.key);
        pairing = null;
        pairState = "Emparelhado 🔒";
        notifier.cancel(Notifier.PAIR_ID);
        WLog.i(TAG, "emparelhamento concluído");
        secureReady = false;
        sendRaw(link.sessionMsg("watch"));
    }

    // ---------- mensagens do telemóvel ----------

    private void dispatch(JSONObject wire) {
        JSONObject m = link.unwrap(wire);
        if (m == null) return;
        switch (m.optString("t")) {
            case Proto.SESSION:
                link.onPeerSession(m);
                if (!m.optBoolean("ack")) sendRaw(link.sessionMsg("watch", true));
                if (link.ready()) onSecureReady();
                else secureReady = false;
                updateStatus();
                break;
            case Proto.PAIR_START:
                try {
                    Pairing p = new Pairing(false);
                    p.onPeerPub(m.optString("pub"));
                    pairing = p;
                    pairState = "Código " + p.code;
                    sendRaw(Msg.of(Proto.PAIR_KEY, "pub", p.pubWatch));
                    notifier.pairPrompt(p.code);
                } catch (Exception e) {
                    WLog.w(TAG, "PAIR_START falhou", e);
                }
                break;
            case Proto.PAIR_OK: {
                Pairing p = pairing;
                if (p == null) break;
                if (p.checkPeerProof(m.optString("proof"))) {
                    p.peerOk = true;
                    maybeFinishPairing();
                } else {
                    pairing = null;
                    pairState = "Os códigos não coincidem.";
                }
                break;
            }
            case Proto.PAIR_CANCEL:
                pairing = null;
                pairState = "Cancelado no telemóvel.";
                notifier.cancel(Notifier.PAIR_ID);
                break;
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
                if (!m.optString("art").isEmpty()) MusicArt.put(m.optString("artKey"), m.optString("art"));
                try {
                    m.put("at", System.currentTimeMillis());
                    m.remove("art");
                } catch (Exception ignored) {
                }
                media = m;
                Ongoing.music(this, m);
                refreshSurfaces();
                break;
            case Proto.CALL:
                CallActivity.onCall(this, m);
                break;
            case Proto.CALLS:
                try {
                    m.put("time", System.currentTimeMillis());
                } catch (Exception ignored) {
                }
                CallsActivity.data = m;
                break;
            case Proto.NAV:
                Ongoing.navigation(this, m, notifier);
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
                refreshSurfaces();
                break;
            case Proto.RING:
                notifier.ring(m.optBoolean("on", true));
                break;
            case Proto.CONFIG:
                WPrefs.setCfg(this, m);
                break;
            case Proto.WEATHER:
                WPrefs.setWeather(this, m);
                refreshSurfaces();
                break;
            case Proto.DND:
                phoneDnd = m.optBoolean("on");
                refreshSurfaces();
                break;
            case Proto.LOG_REQ:
                send(Msg.of(Proto.LOG, "text", WLog.dump()));
                break;
            case Proto.HELLO:
                phoneName = m.optString("name");
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

    private long lastSurfaceRefresh;

    /** Atualiza mosaicos e complicações (no máximo de 20 em 20 segundos). */
    void refreshSurfaces() {
        long now = System.currentTimeMillis();
        if (now - lastSurfaceRefresh < 20_000) return;
        lastSurfaceRefresh = now;
        Complications.refreshAll(this);
    }

    /** Lista de sensores do relógio, para descobrir o que mais dá para ler (SpO2, temperatura...). */
    private void sendSensorList() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            for (Sensor s : getSystemService(SensorManager.class).getSensorList(Sensor.TYPE_ALL)) {
                sb.append(s.getType()).append(" | ").append(s.getStringType()).append(" | ")
                        .append(s.getName()).append(" | ").append(s.getVendor()).append('\n');
            }
            send(Msg.of(Proto.SENSORS, "list", sb.toString()));
        } catch (Exception e) {
            WLog.w(TAG, "lista de sensores falhou", e);
        }
    }

    // ---------- bateria do relógio ----------

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int pct = scale > 0 ? (level * 100 / scale) : level;
            boolean chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            if (pct != myBattery || chg != myCharging) {
                myBattery = pct;
                myCharging = chg;
                sendMyBattery();
            }
        }
    };

    static int watchBattery() {
        WatchService s = instance;
        return s == null ? -1 : s.myBattery;
    }

    private void sendMyBattery() {
        if (myBattery >= 0) send(Msg.of(Proto.WATCH_BATTERY, "level", myBattery, "charging", myCharging));
    }

    // ---------- frequência cardíaca e lembretes (de 15 em 15 minutos) ----------

    private static final long HR_INTERVAL = 15 * 60_000;
    private final JSONArray pendingHealth = new JSONArray();
    private final ArrayDeque<long[]> stepSamples = new ArrayDeque<>(); // {tempo, passos}
    private boolean measuring;
    private long measureStart;
    private long lastMoveAlert, lastWaterAlert = System.currentTimeMillis(), lastHrAlert;

    private final Runnable hrTick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (todaySteps >= 0) {
                stepSamples.addLast(new long[]{now, todaySteps});
                while (stepSamples.size() > 12) stepSamples.removeFirst();
                reportSample(-1);
            }
            checkReminders(now);
            startHr();
            h.postDelayed(this, HR_INTERVAL);
        }
    };

    private final Runnable hrStop = this::stopHr;

    boolean canMeasure() {
        return checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED;
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

    /** Passos nos últimos ~15 minutos (para saber se está em repouso). */
    private long recentSteps(long windowMs) {
        long now = System.currentTimeMillis();
        long[] oldest = null;
        for (long[] s : stepSamples) {
            if (now - s[0] <= windowMs + 60_000) {
                oldest = s;
                break;
            }
        }
        if (oldest == null || todaySteps < 0) return Long.MAX_VALUE;
        return todaySteps - oldest[1];
    }

    void reportHealth(int bpm) {
        lastHr = bpm;
        refreshSurfaces();
        reportSample(bpm);
        JSONObject cfg = WPrefs.cfg(this);
        long now = System.currentTimeMillis();
        if (cfg.optBoolean("hrAlertOn") && bpm > cfg.optInt("hrAlert", 120)
                && recentSteps(15 * 60_000) < 60 && now - lastHrAlert > 30 * 60_000) {
            lastHrAlert = now;
            notifier.alert(Notifier.HEALTH_ID, "Frequência cardíaca alta",
                    bpm + " bpm em repouso. Se não se sentir bem, procure ajuda médica.");
        }
    }

    private void checkReminders(long now) {
        JSONObject cfg = WPrefs.cfg(this);
        if (!WPrefs.daytime() || WPrefs.inQuiet(this)) return;
        if (cfg.optBoolean("moveOn") && recentSteps(60 * 60_000) < 50 && now - lastMoveAlert > 60 * 60_000
                && stepSamples.size() >= 4) {
            lastMoveAlert = now;
            notifier.alert(Notifier.MOVE_ID, "Hora de se mexer", "Esteve parado durante a última hora. Que tal uma volta?");
        }
        if (cfg.optBoolean("waterOn") && now - lastWaterAlert > 2 * 60 * 60_000) {
            lastWaterAlert = now;
            notifier.alert(Notifier.WATER_ID, "Beba água 💧", "Já passaram duas horas.");
        }
    }

    /** Envia ao telemóvel, ou guarda para enviar quando voltar a ligação. */
    private void reportSample(int bpm) {
        JSONObject m = Msg.of(Proto.HEALTH, "hr", bpm, "steps", todaySteps, "time", System.currentTimeMillis());
        if (connected()) {
            send(m);
        } else {
            synchronized (pendingHealth) {
                if (pendingHealth.length() >= 300) pendingHealth.remove(0);
                pendingHealth.put(m);
            }
        }
    }

    private void flushPendingHealth() {
        synchronized (pendingHealth) {
            for (int i = 0; i < pendingHealth.length(); i++) {
                JSONObject m = pendingHealth.optJSONObject(i);
                if (m != null) send(m);
            }
            while (pendingHealth.length() > 0) pendingHealth.remove(0);
        }
    }

    /** Com a permissão de sensores, pede ao sistema o tipo "saúde" para medir com o ecrã apagado. */
    void upgradeForeground() {
        if (Build.VERSION.SDK_INT < 34 || !canMeasure()) return;
        try {
            startForeground(Notifier.ONGOING_ID, notifier.ongoing(status),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        } catch (Exception e) {
            WLog.w(TAG, "tipo saúde indisponível", e);
        }
    }

    // ---------- passos ----------

    private void registerSteps() {
        try {
            SensorManager sm = getSystemService(SensorManager.class);
            Sensor s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 5 * 60 * 1_000_000);
        } catch (Exception e) {
            WLog.w(TAG, "sensor de passos indisponível", e);
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
        send(Msg.of(Proto.HEALTH, "steps", todaySteps, "time", now));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
