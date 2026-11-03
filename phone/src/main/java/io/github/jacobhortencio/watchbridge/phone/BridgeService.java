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
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.TcpLink;

/** Serviço permanente que mantém a ligação ao relógio. */
public class BridgeService extends Service implements BleCentral.Listener, TcpLink.Listener {
    private static final String TAG = "WB";
    private static final int NID = 1;
    private static final String CH = "bridge";

    private static volatile BridgeService instance;
    private static volatile String status = "Parado";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Set<String> sentIcons = ConcurrentHashMap.newKeySet();
    private BleCentral ble;
    private volatile TcpLink tcp;
    private volatile boolean tcpConnecting;
    private int lastBattery = -1;
    private boolean lastCharging;

    public static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, BridgeService.class));
        } catch (Exception e) {
            Log.w(TAG, "não foi possível iniciar o serviço", e);
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

    static boolean connected() {
        BridgeService b = instance;
        return b != null && b.isConnected();
    }

    static void send(JSONObject m) {
        BridgeService b = instance;
        if (b != null) b.sendNow(m);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Ligação ao relógio", NotificationManager.IMPORTANCE_MIN));
        try {
            startForeground(NID, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (Exception e) {
            Log.w(TAG, "startForeground falhou (faltam permissões?)", e);
            stopSelf();
            return;
        }
        ble = new BleCentral(this, this);
        ble.start();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        h.postDelayed(tick, 2_000);
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
        TcpLink t = tcp;
        if (t != null) t.close();
        try {
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

    // ---------- ligação ----------

    boolean isConnected() {
        return (ble != null && ble.isReady()) || tcp != null;
    }

    void sendNow(JSONObject m) {
        if (ble != null && ble.isReady()) {
            ble.send(m);
            return;
        }
        TcpLink t = tcp;
        if (t != null) t.send(m);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!(ble != null && ble.isReady()) && tcp == null) tryTcp();
            if (isConnected()) sendNow(Msg.of(Proto.PING));
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

    private void onLinkUp(String via) {
        status = "Ligado por " + via;
        updateNotif();
        sentIcons.clear();
        sendNow(Msg.of(Proto.HELLO, "name", Build.MANUFACTURER + " " + Build.MODEL));
        sendBattery(true);
        NotifListener l = NotifListener.get();
        if (l != null) {
            l.syncAll();
            l.pushMedia();
        }
    }

    @Override
    public void onConnectionChanged(boolean connected) {
        if (connected) {
            onLinkUp("Bluetooth");
        } else {
            status = tcp != null ? "Ligado por Wi-Fi" : "Desligado, a tentar de novo...";
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
    public void onClosed(TcpLink link) {
        h.post(() -> {
            if (tcp == link) tcp = null;
            if (!(ble != null && ble.isReady())) {
                status = "Desligado, a tentar de novo...";
                updateNotif();
            }
        });
    }

    // ---------- mensagens vindas do relógio ----------

    private void dispatch(JSONObject m) {
        String t = m.optString("t");
        NotifListener l = NotifListener.get();
        switch (t) {
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
            case Proto.HELLO:
                if (l != null) l.syncAll();
                sendBattery(true);
                break;
            default:
                break;
        }
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
            Log.w(TAG, "ícone falhou para " + pkg, e);
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
                sendBattery(false);
            }
        }
    };

    private void sendBattery(boolean force) {
        if (lastBattery < 0) return;
        if (isConnected()) sendNow(Msg.of(Proto.BATTERY, "level", lastBattery, "charging", lastCharging));
    }

    // ---------- notificação permanente ----------

    private Notification buildNotif() {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Watch Bridge")
                .setContentText(status)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotif() {
        if (instance == null) return;
        getSystemService(NotificationManager.class).notify(NID, buildNotif());
    }
}
