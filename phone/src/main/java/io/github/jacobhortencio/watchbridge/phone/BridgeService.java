package io.github.jacobhortencio.watchbridge.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.jacobhortencio.watchbridge.common.Http;
import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Pairing;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.SecureLink;
import io.github.jacobhortencio.watchbridge.common.TcpLink;
import io.github.jacobhortencio.watchbridge.common.WLog;

/** Serviço permanente que mantém a ligação ao relógio. */
public class BridgeService extends Service implements BleCentral.Listener, TcpLink.Listener {
    private static final String TAG = "WB";
    private static final int NID = 1;
    private static final int LOST_NID = 2;
    private static final int WBAT_NID = 3;
    private static final String CH = "bridge";
    private static final String CH_ALERT = "alerts";

    private static volatile BridgeService instance;
    private static volatile String status = "Parado";
    static volatile String watchLog = "";
    static volatile Pairing pairing;
    static volatile String pairState = "";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Set<String> sentIcons = ConcurrentHashMap.newKeySet();
    private BleCentral ble;
    private volatile TcpLink tcp;
    private volatile boolean tcpConnecting;
    private SecureLink link;
    private boolean wasReady;
    private int lastBattery = -1;
    private boolean lastCharging;
    private boolean watchLowWarned;

    public static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, BridgeService.class));
        } catch (Exception e) {
            WLog.w(TAG, "não foi possível iniciar o serviço", e);
        }
    }

    static BridgeService get() {
        return instance;
    }

    static String status() {
        return status;
    }

    static void setStatus(String s) {
        status = s;
        BridgeService b = instance;
        if (b != null) b.h.post(b::updateNotif);
    }

    /** Há ligação física (pode ainda não estar emparelhado). */
    static boolean linked() {
        BridgeService b = instance;
        return b != null && b.isLinked();
    }

    /** Ligado, emparelhado e com sessão segura aberta. */
    static boolean connected() {
        BridgeService b = instance;
        return b != null && b.isLinked() && b.link.ready();
    }

    static boolean paired(Context c) {
        return Prefs.key(c) != null;
    }

    static void send(JSONObject m) {
        BridgeService b = instance;
        if (b != null) b.sendNow(m);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        link = new SecureLink(() -> Prefs.key(this));
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Ligação ao relógio", NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Avisos do relógio", NotificationManager.IMPORTANCE_HIGH));
        try {
            startForeground(NID, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (Exception e) {
            WLog.w(TAG, "startForeground falhou (faltam permissões?)", e);
            stopSelf();
            return;
        }
        ble = new BleCentral(this, this);
        ble.start();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(dndReceiver, new IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                Context.RECEIVER_NOT_EXPORTED);
        CallBridge.register(this);
        h.postDelayed(tick, 2_000);
        h.postDelayed(() -> PhoneUpdater.checkQuietly(this), 60_000);
        WLog.i(TAG, "serviço iniciado, versão " + PhoneUpdater.localVersion(this));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        h.removeCallbacksAndMessages(null);
        if (ble != null) ble.stop();
        CallBridge.unregister(this);
        TcpLink t = tcp;
        if (t != null) t.close();
        try {
            unregisterReceiver(batteryReceiver);
            unregisterReceiver(dndReceiver);
        } catch (Exception ignored) {
        }
        status = "Parado";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    BleCentral ble() {
        return ble;
    }

    // ---------- transporte ----------

    boolean isLinked() {
        return (ble != null && ble.isReady()) || tcp != null;
    }

    /** Envia pela camada segura (as mensagens normais só saem cifradas). */
    void sendNow(JSONObject m) {
        JSONObject wire = link == null ? null : link.wrap(m);
        if (wire != null) sendRaw(wire);
    }

    private void sendRaw(JSONObject wire) {
        if (ble != null && ble.isReady()) {
            ble.send(wire);
            return;
        }
        TcpLink t = tcp;
        if (t != null) t.send(wire);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!(ble != null && ble.isReady()) && tcp == null) tryTcp();
            if (isLinked()) sendRaw(Msg.of(Proto.PING));
            Pairing p = pairing;
            if (p != null && p.expired()) {
                pairing = null;
                pairState = "O emparelhamento expirou. Tente de novo.";
            }
            if (connected()) Weather.refresh(BridgeService.this, false);
            h.postDelayed(this, 15_000);
        }
    };

    /** Ligação alternativa por Wi-Fi quando o Bluetooth não está disponível. */
    private void tryTcp() {
        final String ip = Prefs.watchIp(this);
        if (ip.isEmpty() || tcpConnecting) return;
        tcpConnecting = true;
        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, Proto.TCP_PORT), 4_000);
                s.setSoTimeout(60_000);
                s.setKeepAlive(true);
                TcpLink l = new TcpLink(s, this);
                tcp = l;
                l.start();
                h.post(() -> onLinkUp("Wi-Fi"));
            } catch (IOException ignored) {
            } finally {
                tcpConnecting = false;
            }
        }, "wb-tcp-connect").start();
    }

    private String via = "";

    private void onLinkUp(String via) {
        this.via = via;
        WLog.i(TAG, "ligação física por " + via);
        link.reset();
        sendRaw(link.sessionMsg("phone"));
        status = "A abrir sessão segura...";
        updateNotif();
    }

    /** Sessão segura aberta: sincroniza tudo. */
    private void onSecureReady() {
        if (wasReady) return;
        wasReady = true;
        h.removeCallbacks(lostAlert);
        getSystemService(NotificationManager.class).cancel(LOST_NID);
        status = "Ligado por " + via + " 🔒";
        updateNotif();
        sentIcons.clear();
        WLog.i(TAG, "sessão segura aberta");
        sendNow(Msg.of(Proto.HELLO, "name", Build.MANUFACTURER + " " + Build.MODEL,
                "ver", PhoneUpdater.localVersion(this)));
        pushConfig();
        sendBattery();
        sendDnd();
        Weather.refresh(this, false);
        NotifListener l = NotifListener.get();
        if (l != null) {
            l.syncAll();
            l.pushMedia();
        }
    }

    private void onSecureLost() {
        if (!wasReady) return;
        wasReady = false;
        WLog.i(TAG, "ligação perdida");
        if (Prefs.bool(this, "lost_alert_phone", true)) h.postDelayed(lostAlert, 60_000);
    }

    /** Alarme de separação: avisa no telemóvel se o relógio ficar longe. */
    private final Runnable lostAlert = () -> {
        if (connected()) return;
        alert(LOST_NID, "Relógio desligado", "Perdeu-se a ligação ao relógio há mais de um minuto.");
    };

    @Override
    public void onConnectionChanged(boolean connected) {
        if (connected) {
            onLinkUp("Bluetooth");
        } else {
            if (tcp == null) {
                status = "Desligado, a tentar de novo...";
                onSecureLost();
            } else {
                link.reset();
                sendRaw(link.sessionMsg("phone"));
            }
            updateNotif();
        }
    }

    @Override
    public void onMessage(JSONObject m) {
        dispatch(m);
    }

    @Override
    public void onMessage(TcpLink link, JSONObject msg) {
        h.post(() -> dispatch(msg));
    }

    @Override
    public void onClosed(TcpLink l) {
        h.post(() -> {
            if (tcp == l) tcp = null;
            if (!(ble != null && ble.isReady())) {
                status = "Desligado, a tentar de novo...";
                onSecureLost();
                updateNotif();
            }
        });
    }

    // ---------- emparelhamento ----------

    void startPairing() {
        if (!isLinked()) {
            pairState = "O relógio não está ligado. Abra a app no relógio e aproxime-o.";
            return;
        }
        try {
            Pairing p = new Pairing(true);
            pairing = p;
            pairState = "A falar com o relógio...";
            sendRaw(Msg.of(Proto.PAIR_START, "pub", p.pubPhone));
            WLog.i(TAG, "emparelhamento iniciado");
        } catch (Exception e) {
            pairState = "Erro: " + e.getMessage();
        }
    }

    void confirmPairing() {
        Pairing p = pairing;
        if (p == null || p.key == null) return;
        p.localOk = true;
        sendRaw(Msg.of(Proto.PAIR_OK, "proof", p.proof()));
        pairState = p.peerOk ? "" : "Agora confirme também no relógio.";
        maybeFinishPairing();
    }

    void cancelPairing() {
        if (pairing != null) sendRaw(Msg.of(Proto.PAIR_CANCEL));
        pairing = null;
        pairState = "Emparelhamento cancelado.";
    }

    private void maybeFinishPairing() {
        Pairing p = pairing;
        if (p == null || !p.done()) return;
        Prefs.setKey(this, p.key);
        pairing = null;
        pairState = "Emparelhado com sucesso 🔒";
        WLog.i(TAG, "emparelhamento concluído");
        wasReady = false;
        sendRaw(link.sessionMsg("phone"));
    }

    void unpair() {
        Prefs.setKey(this, null);
        wasReady = false;
        pairState = "Emparelhamento removido.";
        link.reset();
        if (isLinked()) sendRaw(link.sessionMsg("phone"));
    }

    // ---------- mensagens vindas do relógio ----------

    private void dispatch(JSONObject wire) {
        JSONObject m = link.unwrap(wire);
        if (m == null) return;
        String t = m.optString("t");
        NotifListener l = NotifListener.get();
        switch (t) {
            case Proto.SESSION: {
                link.onPeerSession(m);
                if (!m.optBoolean("ack")) sendRaw(link.sessionMsg("phone", true));
                if (link.ready()) {
                    onSecureReady();
                } else {
                    wasReady = false;
                    status = paired(this) ? "O relógio tem outro emparelhamento. Emparelhe de novo."
                            : "Ligado. Falta emparelhar (toque em Emparelhar).";
                    updateNotif();
                }
                break;
            }
            case Proto.PAIR_KEY: {
                Pairing p = pairing;
                if (p == null) break;
                try {
                    p.onPeerPub(m.optString("pub"));
                    pairState = "Confirme que o relógio mostra o código " + p.code;
                } catch (Exception e) {
                    pairState = "Erro no emparelhamento: " + e.getMessage();
                    pairing = null;
                }
                break;
            }
            case Proto.PAIR_OK: {
                Pairing p = pairing;
                if (p == null) break;
                if (p.checkPeerProof(m.optString("proof"))) {
                    p.peerOk = true;
                    if (!p.localOk) pairState = "O relógio confirmou. Confirme o código " + p.code + " aqui.";
                    maybeFinishPairing();
                } else {
                    pairState = "Os códigos não coincidem. Emparelhamento recusado.";
                    pairing = null;
                }
                break;
            }
            case Proto.PAIR_CANCEL:
                if (pairing != null) {
                    pairing = null;
                    pairState = "Cancelado no relógio.";
                }
                break;
            case Proto.DISMISS:
                if (l != null) l.dismiss(m.optString("key"));
                break;
            case Proto.ACTION:
                if (l != null) l.fireAction(m.optString("key"), m.optInt("i", -1));
                break;
            case Proto.REPLY:
                if (l != null) l.reply(m.optString("key"), m.optInt("i", -1), m.optString("text"));
                break;
            case Proto.MEDIA_CMD:
                PhoneActions.media(this, m.optString("cmd"));
                break;
            case Proto.MEDIA_REQ:
                if (l != null) l.pushMedia();
                else sendNow(Msg.of(Proto.MEDIA, "has", false, "noaccess", true));
                break;
            case Proto.FIND_PHONE:
                PhoneActions.ringPhone(this, m.optBoolean("on", true));
                break;
            case Proto.HEALTH:
                Prefs.saveHealth(this, m.optInt("hr", -1), m.optInt("steps", -1), m.optLong("time", 0));
                break;
            case Proto.WORKOUT:
                HealthData.saveWorkout(this, m);
                break;
            case Proto.BUTTON:
                Automation.onWatchButton(this, m.optInt("n", 1));
                break;
            case Proto.PHONE_CTL:
                new Thread(() -> PhoneControl.handle(this, m.optString("cmd")), "wb-ctl").start();
                break;
            case Proto.AGENDA_REQ:
                new Thread(() -> Agenda.send(this), "wb-agenda").start();
                break;
            case Proto.APPS:
                try {
                    m.put("time", System.currentTimeMillis());
                } catch (Exception ignored) {
                }
                WatchAppsActivity.apps = m;
                break;
            case Proto.APK_ACK:
                if (m.optString("id").equals(WatchAppsActivity.lastAckId)) {
                    WatchAppsActivity.lastAck = Math.max(WatchAppsActivity.lastAck, m.optInt("seq", -1));
                }
                break;
            case Proto.WNOTIF:
                showWatchNotif(m);
                break;
            case Proto.RESULT:
                lastResult = m.optString("msg");
                break;
            case Proto.WATCH_BATTERY:
                onWatchBattery(m.optInt("level", -1), m.optBoolean("charging"));
                break;
            case Proto.SET_DND:
                PhoneActions.setDnd(this, m.optBoolean("on"));
                break;
            case Proto.CALL_CMD:
                CallBridge.command(this, m.optString("cmd"), m.optString("text"));
                break;
            case Proto.CALLS_REQ:
                new Thread(() -> CallBridge.sendList(this), "wb-calls").start();
                break;
            case Proto.DIAL:
                CallBridge.dial(this, m.optString("number"));
                break;
            case Proto.SENSORS:
                Prefs.setSensors(this, m.optString("list"));
                break;
            case Proto.LOG:
                watchLog = m.optString("text");
                break;
            case Proto.UPD_REQ:
                relayWatchUpdate(m.optLong("have", 0));
                break;
            case Proto.HELLO:
                if (l != null) l.syncAll();
                sendBattery();
                break;
            default:
                break;
        }
    }

    static volatile String lastResult = "";

    /** Notificações do próprio relógio mostradas no telemóvel. */
    private void showWatchNotif(JSONObject m) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("watch_notifs", "Notificações do relógio",
                NotificationManager.IMPORTANCE_DEFAULT));
        int id = 5000 + (m.optString("key").hashCode() & 0xfff);
        if (m.optBoolean("removed")) {
            nm.cancel(id);
            return;
        }
        nm.notify(id, new Notification.Builder(this, "watch_notifs")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("⌚ " + m.optString("title"))
                .setContentText(m.optString("text"))
                .setSubText(m.optString("app"))
                .setStyle(new Notification.BigTextStyle().bigText(m.optString("text")))
                .setAutoCancel(true)
                .build());
    }

    // ---------- configuração e estado ----------

    void pushConfig() {
        JSONObject c = Prefs.watchConfig(this);
        try {
            c.put("t", Proto.CONFIG);
        } catch (Exception ignored) {
        }
        sendNow(c);
    }

    void requestWatchLog() {
        sendNow(Msg.of(Proto.LOG_REQ));
    }

    private final BroadcastReceiver dndReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            sendDnd();
        }
    };

    private void sendDnd() {
        int f = getSystemService(NotificationManager.class).getCurrentInterruptionFilter();
        sendNow(Msg.of(Proto.DND, "on", f != NotificationManager.INTERRUPTION_FILTER_ALL
                && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN));
    }

    private void onWatchBattery(int level, boolean charging) {
        Prefs.setWatchBattery(this, level, charging);
        updateNotif();
        if (level >= 0 && level <= 15 && !charging && !watchLowWarned) {
            watchLowWarned = true;
            alert(WBAT_NID, "Bateria do relógio fraca", "O relógio está com " + level + "%.");
        } else if (charging || level > 20) {
            watchLowWarned = false;
            getSystemService(NotificationManager.class).cancel(WBAT_NID);
        }
    }

    // ---------- atualização do relógio através do telemóvel ----------

    private void relayWatchUpdate(long have) {
        new Thread(() -> {
            try {
                Http.Release r = Http.latest(null);
                if (r.version > 0 && r.version <= have) {
                    sendNow(Msg.of(Proto.UPD_NONE, "msg", "Já tem a versão mais recente (" + have + ")"));
                    return;
                }
                if (r.watchApkUrl == null) {
                    sendNow(Msg.of(Proto.UPD_NONE, "msg", "APK não encontrado no GitHub"));
                    return;
                }
                byte[] apk = Http.get(r.watchApkUrl, null);
                // a atualização viaja em claro: o Android só a aceita com a nossa assinatura
                sendRaw(Msg.of(Proto.UPD_APK, "ver", r.version, "b64", Base64.encodeToString(apk, Base64.NO_WRAP)));
            } catch (Exception e) {
                WLog.w(TAG, "atualização via telemóvel falhou", e);
                sendRaw(Msg.of(Proto.UPD_NONE, "msg", "O telemóvel não conseguiu descarregar: " + e.getMessage()));
            }
        }, "wb-relay-update").start();
    }

    // ---------- ícones das apps ----------

    void sendIconIfNeeded(String pkg) {
        if (!sentIcons.add(pkg)) return;
        try {
            PackageManager pm = getPackageManager();
            Drawable d = pm.getApplicationIcon(pkg);
            int size = 64;
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            sendNow(Msg.of(Proto.ICON, "pkg", pkg, "png", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)));
        } catch (Exception e) {
            WLog.w(TAG, "ícone falhou para " + pkg, e);
        }
    }

    // ---------- bateria ----------

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int pct = scale > 0 ? (level * 100 / scale) : level;
            boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            if (pct != lastBattery || charging != lastCharging) {
                lastBattery = pct;
                lastCharging = charging;
                sendBattery();
            }
        }
    };

    private void sendBattery() {
        if (lastBattery < 0) return;
        sendNow(Msg.of(Proto.BATTERY, "level", lastBattery, "charging", lastCharging));
    }

    // ---------- notificações do telemóvel ----------

    private void alert(int id, String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, id, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        getSystemService(NotificationManager.class).notify(id, new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    private Notification buildNotif() {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        String text = status;
        int wb = Prefs.watchBattery(this);
        if (connected() && wb >= 0) text += " · Relógio " + wb + "%" + (Prefs.watchCharging(this) ? " ⚡" : "");
        return new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Watch Bridge")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    void updateNotif() {
        if (instance == null) return;
        getSystemService(NotificationManager.class).notify(NID, buildNotif());
    }
}
