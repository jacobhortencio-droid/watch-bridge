package io.github.jacobhortencio.watchbridge.watch;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Controlo da música que toca no telemóvel. */
public class MusicActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView title, artist;
    private Button play;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Música", 14, true);
        title = Ui.text(this, col, "", 15, true);
        artist = Ui.text(this, col, "", 12, false);

        LinearLayout r1 = Ui.row(this, col);
        Ui.rowButton(this, r1, "⏮", v -> cmd("prev"));
        play = Ui.rowButton(this, r1, "⏯", v -> cmd("toggle"));
        Ui.rowButton(this, r1, "⏭", v -> cmd("next"));

        LinearLayout r2 = Ui.row(this, col);
        Ui.rowButton(this, r2, "🔉", v -> cmd("vol_down"));
        Ui.rowButton(this, r2, "🔊", v -> cmd("vol_up"));
        Ui.show(this, col);
    }

    private void cmd(String c) {
        WatchService.sendToPhone(Msg.of(Proto.MEDIA_CMD, "cmd", c));
    }

    @Override
    protected void onResume() {
        super.onResume();
        WatchService.sendToPhone(Msg.of(Proto.MEDIA_REQ));
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
            JSONObject m = WatchService.media;
            if (!WatchService.connected()) {
                title.setText("Telemóvel desligado");
                artist.setText("");
            } else if (m == null || !m.optBoolean("has")) {
                title.setText("Nada a tocar");
                artist.setText("Abra um leitor no telemóvel");
            } else {
                String t = m.optString("title");
                title.setText(t.isEmpty() ? m.optString("app") : t);
                String ar = m.optString("artist");
                artist.setText(ar.isEmpty() ? m.optString("app") : ar);
                play.setText(m.optBoolean("playing") ? "⏸" : "▶");
            }
            h.postDelayed(this, 500);
        }
    };
}
