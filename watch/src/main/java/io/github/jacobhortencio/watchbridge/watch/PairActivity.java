package io.github.jacobhortencio.watchbridge.watch;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.jacobhortencio.watchbridge.common.Pairing;

/** Confirmar o código de emparelhamento (tem de coincidir com o do telemóvel). */
public class PairActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView code, info;
    private LinearLayout buttons;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Emparelhar", 15, true);
        code = Ui.text(this, col, "", 30, true);
        info = Ui.text(this, col, "", 12, false);
        buttons = Ui.row(this, col);
        Ui.rowButton(this, buttons, "✓", v -> {
            WatchService s = WatchService.get();
            if (s != null) s.confirmPairing();
        });
        Ui.rowButton(this, buttons, "✕", v -> {
            WatchService s = WatchService.get();
            if (s != null) s.cancelPairing();
            finish();
        });
        Ui.show(this, col);
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
            Pairing p = WatchService.pairing;
            if (p == null || p.code == null) {
                code.setText(WatchService.paired(PairActivity.this) ? "🔒" : "—");
                info.setText(WatchService.pairState.isEmpty()
                        ? "No telemóvel, toque em Emparelhar relógio." : WatchService.pairState);
                buttons.setVisibility(View.GONE);
                if (WatchService.connected()) h.postDelayed(PairActivity.this::finish, 2500);
            } else {
                code.setText(p.code.substring(0, 3) + " " + p.code.substring(3));
                info.setText(p.localOk ? "Confirme no telemóvel." : "É o mesmo código do telemóvel?");
                buttons.setVisibility(p.localOk ? View.GONE : View.VISIBLE);
            }
            h.postDelayed(this, 500);
        }
    };
}
