package io.github.jacobhortencio.watchbridge.watch;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Respiração guiada 4-4-6 durante 1 minuto, com vibrações a marcar as fases. */
public class BreatheActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView phase, count;
    private int step, cycles;
    private boolean running;

    private static final String[] NAMES = {"Inspire", "Segure", "Expire"};
    private static final int[] SECONDS = {4, 4, 6};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Respirar", 14, true);
        phase = Ui.text(this, col, "Toque em Começar", 22, true);
        count = Ui.text(this, col, "", 30, true);
        Ui.button(this, col, "Começar", v -> start());
        Ui.show(this, col);
    }

    private void start() {
        if (running) return;
        running = true;
        step = 0;
        cycles = 0;
        next();
    }

    private void next() {
        if (!running) return;
        if (cycles >= 4) {
            phase.setText("Muito bem 🌿");
            count.setText("");
            running = false;
            return;
        }
        int s = step % 3;
        phase.setText(NAMES[s]);
        buzz(s == 2 ? 150 : 60);
        countdown(SECONDS[s]);
    }

    private void countdown(int left) {
        if (!running) return;
        count.setText(String.valueOf(left));
        if (left <= 1) {
            h.postDelayed(() -> {
                step++;
                if (step % 3 == 0) cycles++;
                next();
            }, 1000);
        } else {
            h.postDelayed(() -> countdown(left - 1), 1000);
        }
    }

    private void buzz(long ms) {
        try {
            Vibrator v = getSystemService(VibratorManager.class).getDefaultVibrator();
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        h.removeCallbacksAndMessages(null);
    }
}
