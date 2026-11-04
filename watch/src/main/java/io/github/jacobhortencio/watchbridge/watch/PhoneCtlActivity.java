package io.github.jacobhortencio.watchbridge.watch;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Controlo do telemóvel. Wi-Fi, dados, modo de avião, bloquear e captura precisam do Shizuku. */
public class PhoneCtlActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Controlar telemóvel", 14, true);
        btn(col, "🔦  Lanterna", "torch");
        LinearLayout r1 = Ui.row(this, col);
        Ui.rowButton(this, r1, "🔔", v -> send("ring_normal"));
        Ui.rowButton(this, r1, "📳", v -> send("ring_vibrate"));
        Ui.rowButton(this, r1, "🔕", v -> send("ring_silent"));
        LinearLayout r2 = Ui.row(this, col);
        Ui.rowButton(this, r2, "📶 ✓", v -> send("wifi_on"));
        Ui.rowButton(this, r2, "📶 ✕", v -> send("wifi_off"));
        LinearLayout r3 = Ui.row(this, col);
        Ui.rowButton(this, r3, "📡 ✓", v -> send("data_on"));
        Ui.rowButton(this, r3, "📡 ✕", v -> send("data_off"));
        LinearLayout r4 = Ui.row(this, col);
        Ui.rowButton(this, r4, "✈ ✓", v -> send("airplane_on"));
        Ui.rowButton(this, r4, "✈ ✕", v -> send("airplane_off"));
        btn(col, "🔒  Bloquear ecrã", "lock");
        btn(col, "📸  Captura de ecrã", "screenshot");
        Ui.text(this, col, "Wi-Fi, dados, avião, bloquear e captura precisam do Shizuku no telemóvel.", 10, false);
        Ui.show(this, col);
    }

    private void btn(LinearLayout col, String label, String cmd) {
        Ui.button(this, col, label, v -> send(cmd));
    }

    private void send(String cmd) {
        WatchService.sendToPhone(Msg.of(Proto.PHONE_CTL, "cmd", cmd));
    }
}
