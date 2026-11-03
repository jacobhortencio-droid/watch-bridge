package io.github.jacobhortencio.watchbridge.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
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

import org.json.JSONArray;

import io.github.jacobhortencio.watchbridge.common.Installer;
import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Pairing;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.WLog;

public class MainActivity extends Activity {
    static final String[] PERMS = {
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
    };

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status, checks, health, pairInfo, updInfo, accInfo;
    private Button pairBtn;
    private EditText ip;
    private AlertDialog codeDialog;
    private Pairing dialogFor;

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
        sub.setText("Liga o Xiaomi Watch 2 a este telemóvel sem o Mi Fitness. Versão " + PhoneUpdater.localVersion(this));
        sub.setPadding(0, dp(4), 0, dp(16));
        col.addView(sub);

        status = new TextView(this);
        status.setTextSize(18);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(status);

        checks = new TextView(this);
        checks.setPadding(0, dp(8), 0, dp(8));
        col.addView(checks);

        section(col, "1. Emparelhamento seguro");
        pairInfo = new TextView(this);
        col.addView(pairInfo);
        pairBtn = button(col, "Emparelhar relógio", v -> {
            BridgeService s = BridgeService.get();
            if (s == null) {
                toast("O serviço ainda não arrancou. Conceda as permissões primeiro.");
                return;
            }
            if (BridgeService.paired(this) && BridgeService.connected()) {
                new AlertDialog.Builder(this)
                        .setMessage("Remover o emparelhamento? Terá de emparelhar de novo.")
                        .setPositiveButton("Remover", (d, w) -> s.unpair())
                        .setNegativeButton("Cancelar", null)
                        .show();
            } else {
                s.startPairing();
            }
        });

        section(col, "2. Configuração");
        button(col, "Conceder permissões", v -> requestPermissions(PERMS, 1));
        button(col, "Ativar acesso às notificações", v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        button(col, "Bateria sem restrições", v -> askBattery());
        button(col, "Arranque automático (HyperOS)", v -> openAutostart());
        button(col, "Modo acessório (o HyperOS deixa de fechar a app)", v -> Accessory.enable(this));
        accInfo = new TextView(this);
        col.addView(accInfo);
        button(col, "Permitir controlar o Não incomodar", v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
        button(col, "Definições (respostas, silêncio, alertas, tempo)", v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        button(col, "Escolher apps e vibração por app", v -> startActivity(new Intent(this, AppsActivity.class)));

        section(col, "3. Ligação por Wi-Fi (alternativa)");
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

        section(col, "4. Testes");
        button(col, "Enviar notificação de teste", v -> {
            if (!BridgeService.connected()) {
                toast("O relógio ainda não está ligado e emparelhado");
                return;
            }
            BridgeService.send(Msg.of(Proto.NOTIF, "key", "wb-test-" + System.currentTimeMillis(),
                    "pkg", getPackageName(), "app", "Watch Bridge", "title", "Teste",
                    "text", "Se está a ler isto no relógio, a ligação segura funciona. Código de teste 482913.",
                    "otp", "482913", "time", System.currentTimeMillis(), "call", false,
                    "actions", new JSONArray()));
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

        section(col, "5. Saúde (do relógio)");
        health = new TextView(this);
        col.addView(health);
        button(col, "📈 Gráficos, sono, treinos e exportação", v -> startActivity(new Intent(this, HealthActivity.class)));

        section(col, "6. Atualizações");
        button(col, "Atualizar a app do telemóvel", v -> PhoneUpdater.update(this));
        updInfo = new TextView(this);
        col.addView(updInfo);
        TextView updHelp = new TextView(this);
        updHelp.setText("O relógio atualiza-se no próprio relógio: botão ⬇ Atualizar app.");
        col.addView(updHelp);

        section(col, "7. Diagnóstico");
        button(col, "Copiar registo (telemóvel + relógio)", v -> copyLogs());
        button(col, "Copiar lista de sensores do relógio", v -> {
            String s = Prefs.sensors(this);
            if (s.isEmpty()) {
                toast("Ainda não chegou. Ligue o relógio e tente de novo.");
                return;
            }
            copy("sensores", s);
        });

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
            int auto = Accessory.autostart(MainActivity.this);
            boolean dnd = getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();
            int wb = Prefs.watchBattery(MainActivity.this);
            checks.setText(mark(hasRuntimePermissions(MainActivity.this)) + " Permissões Bluetooth\n"
                    + mark(hasNotificationAccess(MainActivity.this)) + " Acesso às notificações\n"
                    + mark(bat) + " Bateria sem restrições\n"
                    + (auto < 0 ? "❔" : mark(auto == 1)) + " Arranque automático\n"
                    + mark(Accessory.associated(MainActivity.this)) + " Modo acessório\n"
                    + mark(BridgeService.paired(MainActivity.this)) + " Emparelhado 🔒\n"
                    + mark(dnd) + " Controlo do Não incomodar"
                    + (BridgeService.connected() && wb >= 0
                    ? "\n\n⌚ Bateria do relógio: " + wb + "%" + (Prefs.watchCharging(MainActivity.this) ? " ⚡" : "") : ""));
            pairInfo.setText(BridgeService.pairState.isEmpty()
                    ? (BridgeService.paired(MainActivity.this)
                    ? "Emparelhado. As mensagens viajam cifradas." : "Ainda não emparelhado. Abra a app no relógio e toque em Emparelhar.")
                    : BridgeService.pairState);
            pairBtn.setText(BridgeService.paired(MainActivity.this) && BridgeService.connected()
                    ? "Remover emparelhamento" : "Emparelhar relógio");
            accInfo.setText(Accessory.state);
            String up = PhoneUpdater.state;
            if (!Installer.state.isEmpty()) up = (up.isEmpty() ? "" : up + "\n") + Installer.state;
            if (PhoneUpdater.available > PhoneUpdater.localVersion(MainActivity.this)) {
                up = "Versão " + PhoneUpdater.available + " disponível. " + up;
            }
            updInfo.setText(up);
            health.setText(healthSummary());
            maybeShowCode();
            h.postDelayed(this, 1000);
        }
    };

    /** Janela de confirmação do código de emparelhamento. */
    private void maybeShowCode() {
        Pairing p = BridgeService.pairing;
        if (p == null || p.code == null || p.localOk) {
            if (codeDialog != null && (p == null || p.localOk)) {
                codeDialog.dismiss();
                codeDialog = null;
            }
            return;
        }
        if (dialogFor == p && codeDialog != null) return;
        dialogFor = p;
        TextView big = new TextView(this);
        big.setText(p.code.substring(0, 3) + " " + p.code.substring(3));
        big.setTextSize(40);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        big.setPadding(0, dp(16), 0, dp(16));
        codeDialog = new AlertDialog.Builder(this)
                .setTitle("O relógio mostra este código?")
                .setView(big)
                .setCancelable(false)
                .setPositiveButton("Sim, coincide", (d, w) -> {
                    BridgeService s = BridgeService.get();
                    if (s != null) s.confirmPairing();
                    codeDialog = null;
                })
                .setNegativeButton("Não", (d, w) -> {
                    BridgeService s = BridgeService.get();
                    if (s != null) s.cancelPairing();
                    codeDialog = null;
                })
                .show();
    }

    private void copyLogs() {
        BridgeService s = BridgeService.get();
        if (s != null && BridgeService.connected()) s.requestWatchLog();
        toast("A juntar os registos...");
        h.postDelayed(() -> {
            String all = "=== Telemóvel (versão " + PhoneUpdater.localVersion(this) + ") ===\n" + WLog.dump()
                    + "\n=== Relógio ===\n" + (BridgeService.watchLog.isEmpty() ? "(sem registo)" : BridgeService.watchLog);
            copy("registo", all);
        }, 2500);
    }

    private void copy(String label, String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        toast("Copiado. Cole na conversa com o Claude.");
    }

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
        java.util.List<long[]> today = new java.util.ArrayList<>();
        for (long[] r : Prefs.hrHistory(this)) {
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

    private Button button(LinearLayout col, String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setGravity(Gravity.CENTER);
        btn.setOnClickListener(l);
        col.addView(btn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return btn;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
