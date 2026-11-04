package io.github.jacobhortencio.watchbridge.watch;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Medição de oxigénio no sangue (SpO2) com o sensor da Xiaomi. */
public class Spo2Activity extends Activity {
    private TextView value, info, raw;
    private boolean measuring;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Oxigénio (SpO2)", 14, true);
        value = Ui.text(this, col, WSensors.lastSpo2 > 0 ? WSensors.lastSpo2 + "%" : "--", 32, true);
        info = Ui.text(this, col, "Fique quieto, com o braço apoiado.", 12, false);
        Ui.button(this, col, "Medir (40 s)", v -> start());
        raw = Ui.text(this, col, "", 9, false);
        Ui.text(this, col, "Indicativo, não é um aparelho médico.", 9, false);
        Ui.show(this, col);
    }

    private void start() {
        if (measuring) return;
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 4);
            return;
        }
        measuring = true;
        value.setText("…");
        WSensors.measureSpo2(this, new WSensors.Spo2Listener() {
            @Override
            public void onProgress(String text) {
                info.setText(text);
                raw.setText("Bruto: " + WSensors.spo2Raw);
            }

            @Override
            public void onDone(int v) {
                measuring = false;
                if (v > 0) {
                    value.setText(v + "%");
                    info.setText("Medição concluída");
                    WatchService s = WatchService.get();
                    if (s != null) s.reportSpo2(v);
                } else if (v == -2) {
                    value.setText("--");
                    info.setText("Sensor de SpO2 não encontrado");
                } else if (v == -3) {
                    value.setText("🔒");
                    info.setText("A Xiaomi bloqueia este sensor a apps externas");
                } else {
                    value.setText("--");
                    info.setText("Sem leitura válida. Aperte a bracelete e tente de novo.");
                }
                raw.setText("Bruto: " + WSensors.spo2Raw);
            }
        });
    }
}
