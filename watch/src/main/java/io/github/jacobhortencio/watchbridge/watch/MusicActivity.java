package io.github.jacobhortencio.watchbridge.watch;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Controlo da música do telemóvel: capa, progresso, volume pela coroa, escolher leitor. */
public class MusicActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private ImageView art;
    private TextView title, artist, progress, player;
    private Button play;
    private String artKey = "";
    private long lastScroll;
    private float scrollAcc;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = Ui.column(this);
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        col.addView(art, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        title = Ui.text(this, col, "", 15, true);
        artist = Ui.text(this, col, "", 12, false);
        progress = Ui.text(this, col, "", 11, false);

        LinearLayout r1 = Ui.row(this, col);
        Ui.rowButton(this, r1, "⏮", v -> cmd("prev"));
        play = Ui.rowButton(this, r1, "⏯", v -> cmd("toggle"));
        Ui.rowButton(this, r1, "⏭", v -> cmd("next"));

        LinearLayout r2 = Ui.row(this, col);
        Ui.rowButton(this, r2, "🔉", v -> cmd("vol_down"));
        Ui.rowButton(this, r2, "🔊", v -> cmd("vol_up"));
        player = Ui.text(this, col, "", 11, false);
        player.setOnClickListener(v -> cmd("player_next"));
        Ui.text(this, col, "Rode a coroa para o volume", 10, false);
        Ui.show(this, col);
    }

    private void cmd(String c) {
        WatchService.sendToPhone(Msg.of(Proto.MEDIA_CMD, "cmd", c));
    }

    /** Coroa rotativa: volume do telemóvel. */
    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            scrollAcc += -ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            long now = System.currentTimeMillis();
            if (Math.abs(scrollAcc) >= 1f && now - lastScroll > 120) {
                cmd(scrollAcc > 0 ? "vol_up" : "vol_down");
                scrollAcc = 0;
                lastScroll = now;
            }
            return true;
        }
        return super.onGenericMotionEvent(ev);
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

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            JSONObject m = WatchService.media;
            if (!WatchService.connected()) {
                title.setText("Telemóvel desligado");
                artist.setText("");
                progress.setText("");
            } else if (m == null || !m.optBoolean("has")) {
                title.setText("Nada a tocar");
                artist.setText("Abra um leitor no telemóvel");
                progress.setText("");
            } else {
                String t = m.optString("title");
                title.setText(t.isEmpty() ? m.optString("app") : t);
                String ar = m.optString("artist");
                artist.setText(ar.isEmpty() ? m.optString("app") : ar);
                boolean playing = m.optBoolean("playing");
                play.setText(playing ? "⏸" : "▶");
                long dur = m.optLong("dur", 0);
                long pos = m.optLong("pos", 0);
                if (playing && m.optLong("at", 0) > 0) pos += System.currentTimeMillis() - m.optLong("at");
                progress.setText(dur > 0 ? time(Math.min(pos, dur)) + " / " + time(dur) : "");
                int n = m.optInt("players", 1);
                player.setText(n > 1 ? "🔀 " + m.optString("app") + " (toque para trocar)" : m.optString("app"));
                String key = m.optString("artKey");
                if (!key.equals(artKey)) {
                    artKey = key;
                    Bitmap bmp = Ongoing.decode(MusicArt.get(key));
                    if (bmp != null) art.setImageBitmap(bmp);
                    else art.setImageResource(R.drawable.ic_stat);
                }
            }
            h.postDelayed(this, 1000);
        }
    };
}
