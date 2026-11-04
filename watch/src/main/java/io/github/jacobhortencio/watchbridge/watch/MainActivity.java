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

import org.json.JSONObject;

import java.net.Inet4Address;

import io.github.jacobhortencio.watchbridge.common.Installer;
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
    private TextView status, battery, weather, ip, update;
    private Button find, pair, dnd;
    private boolean finding;

    static boolean hasBlePermissions(Context c) {
        return c.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        io.github.jacobhortencio.watchbridge.common.Crash.install(this);
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Watch Bridge", 16, true);
        status = Ui.text(this, col, "", 13, false);
        battery = Ui.text(this, col, "", 13, false);
        weather = Ui.text(this, col, "", 13, false);
        weather.setOnClickListener(v -> startActivity(new Intent(this, WeatherActivity.class)));

        pair = Ui.button(this, col, "🔒  Emparelhar", v -> startActivity(new Intent(this, PairActivity.class)));
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
        Ui.button(this, col, "🔔  Histórico", v -> startActivity(new Intent(this, HistoryActivity.class)));
        Ui.button(this, col, "🌤  Tempo", v -> startActivity(new Intent(this, WeatherActivity.class)));
        Ui.button(this, col, "✉️  Nova mensagem", v -> startActivity(new Intent(this, MessageActivity.class)));
        Ui.button(this, col, "🔗  Ações", v -> startActivity(new Intent(this, ActionsActivity.class)));
        Ui.button(this, col, "📞  Chamadas", v -> startActivity(new Intent(this, CallsActivity.class)));
        Ui.button(this, col, "🧭  Navegação", v -> startActivity(new Intent(this, NavActivity.class)));
        Ui.button(this, col, "📅  Agenda", v -> startActivity(new Intent(this, AgendaActivity.class)));
        Ui.button(this, col, "🎛  Controlar telemóvel", v -> startActivity(new Intent(this, PhoneCtlActivity.class)));
        Ui.button(this, col, "🏃  Treino", v -> startActivity(new Intent(this, WorkoutActivity.class)));
        Ui.button(this, col, "❤  Saúde", v -> startActivity(new Intent(this, HealthActivity.class)));
        Ui.button(this, col, "🌬  Respirar", v -> startActivity(new Intent(this, BreatheActivity.class)));
        dnd = Ui.button(this, col, "", v -> WatchService.sendToPhone(Msg.of(Proto.SET_DND, "on", !WatchService.phoneDnd)));
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
        Ui.text(this, col, "Versão " + Updater.localVersion(this), 10, false);
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
            if (s != null && !WatchService.linkedPhysically() && !s.bleError().isEmpty()) st += "\n" + s.bleError();
            status.setText(st);
            int bat = WatchService.phoneBattery;
            battery.setText(WatchService.connected() && bat >= 0
                    ? "📱 " + bat + "%" + (WatchService.phoneCharging ? " ⚡" : "") : "");
            JSONObject w = WPrefs.weather(MainActivity.this);
            weather.setText(w == null ? "" : WeatherActivity.icon(w.optInt("code"), w.optBoolean("day", true))
                    + " " + w.optInt("temp") + "° " + w.optString("city"));
            pair.setText(WatchService.paired(MainActivity.this) ? "🔒  Emparelhado" : "🔓  Emparelhar");
            dnd.setText(WatchService.phoneDnd ? "🔕  Não incomodar: ligado" : "🔔  Não incomodar: desligado");
            String addr = wifiIp();
            ip.setText(addr == null ? "Wi-Fi desligado" : "IP para o telemóvel:\n" + addr);
            String up = Updater.state;
            if (!Installer.state.isEmpty()) up = (up.isEmpty() ? "" : up + "\n") + Installer.state;
            update.setText(up);
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
