package io.github.jacobhortencio.watchbridge.phone;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Painel de diagnóstico: o que está realmente a acontecer na ligação. */
public class DiagActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView info, test;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad * 2, pad, pad);
        TextView t = new TextView(this);
        t.setText("Diagnóstico");
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);
        info = new TextView(this);
        info.setTextSize(15);
        info.setPadding(0, pad / 2, 0, pad / 2);
        col.addView(info);
        Button run = new Button(this);
        run.setText("Testar tudo");
        run.setAllCaps(false);
        run.setOnClickListener(v -> runTests());
        col.addView(run);
        test = new TextView(this);
        test.setTextSize(15);
        col.addView(test);
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresh);
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            BridgeService s = BridgeService.get();
            BleCentral ble = s == null ? null : s.ble();
            if (ble != null && ble.isReady()) ble.readRssi();
            StringBuilder sb = new StringBuilder();
            sb.append("Estado: ").append(BridgeService.status()).append('\n');
            sb.append("Ligação: ").append(BridgeService.connected() ? "segura 🔒" : BridgeService.linked() ? "física, sem sessão" : "nenhuma").append('\n');
            if (ble != null) {
                sb.append("Bluetooth: ").append(ble.isReady() ? "ligado" : "desligado")
                        .append(" · MTU ").append(ble.mtu())
                        .append(" · sinal ").append(BleCentral.lastRssi == 0 ? "--" : BleCentral.lastRssi + " dBm")
                        .append(" (").append(signal(BleCentral.lastRssi)).append(")\n");
            }
            sb.append("Latência: ").append(BridgeService.lastRtt < 0 ? "--" : BridgeService.lastRtt + " ms").append('\n');
            sb.append("Mensagens: ").append(BridgeService.sent).append(" enviadas · ").append(BridgeService.received).append(" recebidas\n");
            sb.append("Religações hoje: ").append(BridgeService.reconnects).append('\n');
            sb.append("Serviço ativo desde ").append(BridgeService.startedAt == 0 ? "--"
                    : DateUtils.getRelativeTimeSpanString(BridgeService.startedAt)).append('\n');
            sb.append("Bateria do relógio: ").append(Prefs.watchBattery(DiagActivity.this)).append("%\n");
            sb.append("Envio de WhatsApp: ").append(WaSendService.enabled(DiagActivity.this) ? "ativo" : "desligado").append('\n');
            sb.append("Shizuku: ").append(ShizukuTool.granted() ? "autorizado" : ShizukuTool.running() ? "sem autorização" : "parado");
            info.setText(sb.toString());
            h.postDelayed(this, 1500);
        }
    };

    private static String signal(int rssi) {
        if (rssi == 0) return "?";
        if (rssi > -60) return "excelente";
        if (rssi > -75) return "bom";
        if (rssi > -88) return "fraco";
        return "muito fraco";
    }

    /** Latência (5 pings) e velocidade (eco de 32 KB). */
    private void runTests() {
        if (!BridgeService.linked()) {
            test.setText("O relógio não está ligado.");
            return;
        }
        test.setText("A testar...");
        new Thread(() -> {
            long sum = 0;
            int ok = 0;
            for (int i = 0; i < 5; i++) {
                BridgeService.lastRtt = -1;
                BridgeService.sendPlain(Msg.of(Proto.PING, "t", System.currentTimeMillis()));
                long t0 = System.currentTimeMillis();
                while (BridgeService.lastRtt < 0 && System.currentTimeMillis() - t0 < 4000) sleep(20);
                if (BridgeService.lastRtt >= 0) {
                    sum += BridgeService.lastRtt;
                    ok++;
                }
            }
            String lat = ok == 0 ? "sem resposta" : (sum / ok) + " ms (" + ok + "/5)";
            String speed = "precisa da sessão segura";
            if (BridgeService.connected()) {
                StringBuilder pad = new StringBuilder();
                java.util.Random r = new java.util.Random();
                for (int i = 0; i < 32 * 1024; i++) pad.append((char) ('a' + r.nextInt(26))); // pouco compressível
                BridgeService.diagBack = -1;
                long t0 = System.currentTimeMillis();
                BridgeService.send(Msg.of(Proto.DIAG, "data", pad.toString(), "t", t0));
                while (BridgeService.diagBack < 0 && System.currentTimeMillis() - t0 < 30_000) sleep(50);
                if (BridgeService.diagBack > 0) {
                    double secs = BridgeService.diagBack / 1000.0;
                    speed = String.format(Locale.US, "%.1f KB/s (32 KB em %.1f s, ida)", 32 / secs, secs);
                } else {
                    speed = "sem resposta em 30 s";
                }
            }
            String res = "Latência média: " + lat + "\nVelocidade: " + speed;
            h.post(() -> test.setText(res));
        }, "wb-diag").start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
