package io.github.jacobhortencio.watchbridge.watch;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

public class MainActivity extends Activity {
    static final String[] PERMS = {
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
    };

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status, battery, ip, update;
    private Button find;
    private boolean finding;

    static boolean hasBlePermissions(Context c) {
        return c.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Watch Bridge", 16, true);
        status = Ui.text(this, col, "", 13, false);
        battery = Ui.text(this, col, "", 13, false);

        Ui.button(this, col, "🎵  Música", v -> startActivity(new Intent(this, MusicActivity.class)));
        find = Ui.button(this, col, "📱  Encontrar telemóvel", v -> {
            finding = !finding;
            WatchService.sendToPhone(Msg.of(Proto.FIND_PHONE, "on", finding));
            find.setText(finding ? "⏹  Parar toque" : "📱  Encontrar telemóvel");
            if (finding) h.postDelayed(() -> {
                finding = false;
                find.setText("📱  Encontrar telemóvel");
            }, 30_000);
        });
        Ui.button(this, col, "❤  Saúde", v -> startActivity(new Intent(this, HealthActivity.class)));
        Ui.button(this, col, "Permissões", v -> requestPermissions(PERMS, 1));
        Ui.button(this, col, "Saúde em segundo plano", v -> {
            if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 2);
            } else {
                requestPermissions(new String[]{Manifest.permission.BODY_SENSORS_BACKGROUND}, 3);
            }
        });
        Ui.button(this, col, "⬇  Atualizar app", v -> Updater.run(this));
        update = Ui.text(this, col, "", 11, false);
        Ui.button(this, col, "Reiniciar ligação", v -> {
            stopService(new Intent(this, WatchService.class));
            h.postDelayed(() -> WatchService.start(this), 600);
        });
        ip = Ui.text(this, col, "", 11, false);
        Ui.show(this, col);

        if (!hasBlePermissions(this)) requestPermissions(PERMS, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasBlePermissions(this)) WatchService.start(this);
        h.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresh);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (hasBlePermissions(this)) WatchService.start(this);
        WatchService s = WatchService.get();
        if (s != null) s.upgradeForeground();
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            String st = hasBlePermissions(MainActivity.this) ? WatchService.status : "Faltam permissões";
            WatchService s = WatchService.get();
            if (s != null && !WatchService.connected() && !s.bleError().isEmpty()) st += "\n" + s.bleError();
            status.setText(st);
            int bat = WatchService.phoneBattery;
            battery.setText(WatchService.connected() && bat >= 0
                    ? "Telemóvel: " + bat + "%" + (WatchService.phoneCharging ? " ⚡" : "") : "");
            String addr = wifiIp();
            ip.setText(addr == null ? "Wi-Fi desligado" : "IP para o telemóvel:\n" + addr);
            update.setText(Updater.state);
            h.postDelayed(this, 1000);
        }
    };

    private String wifiIp() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            if (n == null) return null;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) return la.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
