package io.github.jacobhortencio.watchbridge.phone;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

public class MainActivity extends Activity {
    static final String[] PERMS = {
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_STATE,
    };

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status, checks, health;
    private EditText ip;

    static boolean hasRuntimePermissions(Context c) {
        return c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasNotificationAccess(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(new ComponentName(c, NotifListener.class).flattenToString());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(20);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(32), pad, pad);

        TextView title = new TextView(this);
        title.setText("Watch Bridge");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Liga o Xiaomi Watch 2 a este telemóvel sem o Mi Fitness.");
        sub.setPadding(0, dp(4), 0, dp(16));
        col.addView(sub);

        status = new TextView(this);
        status.setTextSize(18);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(status);

        checks = new TextView(this);
        checks.setPadding(0, dp(8), 0, dp(8));
        col.addView(checks);

        section(col, "1. Configuração");
        button(col, "Conceder permissões", v -> requestPermissions(PERMS, 1));
        button(col, "Ativar acesso às notificações", v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        button(col, "Bateria sem restrições", v -> askBattery());
        button(col, "Arranque automático (HyperOS)", v -> openAutostart());
        button(col, "Escolher apps que vão ao relógio", v -> startActivity(new Intent(this, AppsActivity.class)));

        section(col, "2. Ligação por Wi-Fi (alternativa)");
        TextView ipHelp = new TextView(this);
        ipHelp.setText("Opcional. Escreva o IP que aparece na app do relógio. É usado quando o Bluetooth falha e os dois estão na mesma rede.");
        col.addView(ipHelp);
        ip = new EditText(this);
        ip.setHint("ex.: 192.168.0.158");
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ip.setText(Prefs.watchIp(this));
        col.addView(ip);
        button(col, "Guardar IP", v -> {
            Prefs.setWatchIp(this, ip.getText().toString());
            toast("IP guardado");
        });

        section(col, "3. Testes");
        button(col, "Enviar notificação de teste", v -> {
            if (!BridgeService.connected()) {
                toast("O relógio ainda não está ligado");
                return;
            }
            BridgeService.send(Msg.of(Proto.NOTIF, "key", "wb-test-" + System.currentTimeMillis(),
                    "pkg", getPackageName(), "app", "Watch Bridge", "title", "Teste",
                    "text", "Se está a ler isto no relógio, a ligação funciona.",
                    "time", System.currentTimeMillis(), "call", false,
                    "actions", new org.json.JSONArray()));
            toast("Enviada");
        });
        button(col, "Fazer o relógio tocar", v -> {
            BridgeService.send(Msg.of(Proto.RING, "on", true));
            toast(BridgeService.connected() ? "A tocar no relógio" : "O relógio ainda não está ligado");
        });
        button(col, "Reiniciar ligação", v -> {
            stopService(new Intent(this, BridgeService.class));
            h.postDelayed(() -> BridgeService.start(this), 600);
        });

        section(col, "4. Saúde (do relógio)");
        health = new TextView(this);
        col.addView(health);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasRuntimePermissions(this)) BridgeService.start(this);
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
        if (hasRuntimePermissions(this)) BridgeService.start(this);
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            status.setText("Estado: " + BridgeService.status());
            PowerManager pm = getSystemService(PowerManager.class);
            boolean bat = pm.isIgnoringBatteryOptimizations(getPackageName());
            checks.setText(mark(hasRuntimePermissions(MainActivity.this)) + " Permissões Bluetooth\n"
                    + mark(hasNotificationAccess(MainActivity.this)) + " Acesso às notificações\n"
                    + mark(bat) + " Bateria sem restrições");
            health.setText(healthSummary());
            h.postDelayed(this, 1000);
        }
    };

    private String healthSummary() {
        int hr = Prefs.lastHr(this);
        int steps = Prefs.lastSteps(this);
        StringBuilder sb = new StringBuilder();
        sb.append("Frequência cardíaca: ");
        if (hr > 0) {
            sb.append(hr).append(" bpm (")
                    .append(DateUtils.getRelativeTimeSpanString(Prefs.lastHrTime(this))).append(")");
        } else {
            sb.append("sem dados");
        }
        sb.append("\nPassos hoje: ").append(steps >= 0 ? String.valueOf(steps) : "sem dados");

        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        long midnight = cal.getTimeInMillis();
        int n = 0, min = Integer.MAX_VALUE, max = 0;
        long sum = 0;
        java.util.List<long[]> all = Prefs.hrHistory(this);
        java.util.List<long[]> today = new java.util.ArrayList<>();
        for (long[] r : all) {
            if (r[0] < midnight) continue;
            today.add(r);
            n++;
            sum += r[1];
            min = Math.min(min, (int) r[1]);
            max = Math.max(max, (int) r[1]);
        }
        if (n > 0) {
            sb.append("\n\nHoje: ").append(n).append(" medições · média ").append(sum / n)
                    .append(" · mín ").append(min).append(" · máx ").append(max).append(" bpm");
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
            sb.append("\nÚltimas:");
            for (int i = Math.max(0, today.size() - 8); i < today.size(); i++) {
                long[] r = today.get(i);
                sb.append("\n  ").append(f.format(new java.util.Date(r[0]))).append("  ").append(r[1]).append(" bpm");
            }
        }
        return sb.toString();
    }

    private static String mark(boolean ok) {
        return ok ? "✅" : "❌";
    }

    private void askBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            openAppDetails();
        }
    }

    private void openAutostart() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            startActivity(i);
        } catch (Exception e) {
            toast("Ative 'Arranque automático' nas definições da app");
            openAppDetails();
        }
    }

    private void openAppDetails() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void section(LinearLayout col, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(6));
        col.addView(t);
    }

    private void button(LinearLayout col, String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setGravity(Gravity.CENTER);
        btn.setOnClickListener(l);
        col.addView(btn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
