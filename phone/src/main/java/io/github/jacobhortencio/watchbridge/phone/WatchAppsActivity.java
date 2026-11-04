package io.github.jacobhortencio.watchbridge.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.WLog;

/** Instalar APKs no relógio e gerir as apps do relógio, sem ADB. */
public class WatchAppsActivity extends Activity {
    private static final int PICK = 41;
    private static final int CHUNK = 32 * 1024;

    static volatile JSONObject apps;
    static volatile int lastAck = -1;
    static volatile String lastAckId = "";

    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout col, list;
    private TextView progress;
    private long shown = -1;
    private volatile boolean sending;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(20);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(32), pad, dp(40));
        TextView t = new TextView(this);
        t.setText("Apps do relógio");
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);

        Button install = new Button(this);
        install.setText("Instalar um APK no relógio");
        install.setAllCaps(false);
        install.setOnClickListener(v -> {
            if (!BridgeService.connected()) {
                toast("O relógio não está ligado");
                return;
            }
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(i, PICK);
        });
        col.addView(install);
        progress = new TextView(this);
        col.addView(progress);
        TextView help = new TextView(this);
        help.setText("Pelo Bluetooth, 1 MB demora cerca de 1 minuto. O relógio pede confirmação no fim.");
        col.addView(help);

        Button refresh = new Button(this);
        refresh.setText("Atualizar lista");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(v -> BridgeService.send(Msg.of(Proto.APPS_REQ)));
        col.addView(refresh);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        BridgeService.send(Msg.of(Proto.APPS_REQ));
        h.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            JSONObject a = apps;
            long t = a == null ? -1 : a.optLong("time");
            if (t != shown) {
                shown = t;
                showList(a);
            }
            h.postDelayed(this, 1000);
        }
    };

    private void showList(JSONObject a) {
        list.removeAllViews();
        if (a == null) {
            text(BridgeService.connected() ? "A carregar..." : "Relógio desligado");
            return;
        }
        JSONArray arr = a.optJSONArray("apps");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String pkg = o.optString("pkg");
            TextView name = text(o.optString("label") + "  ·  " + o.optString("ver"));
            name.setTypeface(Typeface.DEFAULT_BOLD);
            text(pkg);
            LinearLayout row = new LinearLayout(this);
            Button open = new Button(this);
            open.setText("Abrir");
            open.setAllCaps(false);
            open.setOnClickListener(v -> {
                BridgeService.send(Msg.of(Proto.APP_OPEN, "pkg", pkg));
                toast("Toque na notificação que aparece no relógio");
            });
            row.addView(open);
            if (o.optBoolean("removable")) {
                Button del = new Button(this);
                del.setText("Desinstalar");
                del.setAllCaps(false);
                del.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setMessage("Desinstalar " + o.optString("label") + " do relógio?")
                        .setPositiveButton("Desinstalar", (d, w) -> {
                            BridgeService.send(Msg.of(Proto.APP_UNINSTALL, "pkg", pkg));
                            toast("Confirme no relógio");
                        })
                        .setNegativeButton("Cancelar", null)
                        .show());
                row.addView(del);
            }
            list.addView(row);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> sendApk(uri), "wb-apk-send").start();
    }

    /** Envia o APK em blocos de 32 KB, esperando pela confirmação de cada um. */
    private void sendApk(Uri uri) {
        if (sending) return;
        sending = true;
        try {
            byte[] apk;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                apk = bos.toByteArray();
            }
            if (apk.length < 4 || apk[0] != 'P' || apk[1] != 'K') {
                ui("Isto não parece um APK.");
                return;
            }
            String id = Long.toHexString(System.currentTimeMillis());
            int total = (apk.length + CHUNK - 1) / CHUNK;
            lastAck = -1;
            lastAckId = id;
            BridgeService.send(Msg.of(Proto.APK_BEGIN, "id", id, "size", apk.length, "chunks", total));
            for (int seq = 0; seq < total; seq++) {
                int from = seq * CHUNK, len = Math.min(CHUNK, apk.length - from);
                byte[] part = new byte[len];
                System.arraycopy(apk, from, part, 0, len);
                int tries = 0;
                while (true) {
                    BridgeService.send(Msg.of(Proto.APK_CHUNK, "id", id, "seq", seq, "last", seq == total - 1,
                            "b64", Base64.encodeToString(part, Base64.NO_WRAP)));
                    long t0 = System.currentTimeMillis();
                    while (lastAck < seq && System.currentTimeMillis() - t0 < 30_000) Thread.sleep(100);
                    if (lastAck >= seq) break;
                    if (++tries >= 3) throw new Exception("o relógio deixou de responder");
                }
                int pct = (seq + 1) * 100 / total;
                ui("A enviar... " + pct + "% (" + (apk.length / 1024) + " KB)");
            }
            ui("Enviado. Confirme a instalação no relógio.");
        } catch (Exception e) {
            WLog.w("WB-Apk", "envio falhou", e);
            ui("Falhou: " + e.getMessage());
        } finally {
            sending = false;
        }
    }

    private void ui(String s) {
        h.post(() -> progress.setText(s));
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(6), 0, 0);
        list.addView(t);
        return t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
