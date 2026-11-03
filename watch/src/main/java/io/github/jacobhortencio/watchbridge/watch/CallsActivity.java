package io.github.jacobhortencio.watchbridge.watch;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Favoritos e chamadas recentes do telemóvel; tocar liga a partir do telemóvel. */
public class CallsActivity extends Activity {
    static volatile JSONObject data;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long shown = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        WatchService.sendToPhone(Msg.of(Proto.CALLS_REQ));
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
            JSONObject d = data;
            long t = d == null ? -1 : d.optLong("time", 0);
            if (t != shown) build();
            h.postDelayed(this, 1000);
        }
    };

    private void build() {
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Chamadas", 15, true);
        JSONObject d = data;
        shown = d == null ? -1 : d.optLong("time", 0);
        if (d == null) {
            Ui.text(this, col, WatchService.connected() ? "A carregar..." : "Telemóvel desligado", 13, false);
            Ui.show(this, col);
            return;
        }
        if (!d.optString("error").isEmpty()) Ui.text(this, col, d.optString("error"), 12, false);
        JSONArray favs = d.optJSONArray("favs");
        if (favs != null && favs.length() > 0) {
            Ui.text(this, col, "⭐ Favoritos", 12, true);
            for (int i = 0; i < favs.length(); i++) add(col, favs.optJSONObject(i), false);
        }
        JSONArray rec = d.optJSONArray("recent");
        if (rec != null && rec.length() > 0) {
            Ui.text(this, col, "🕘 Recentes", 12, true);
            for (int i = 0; i < rec.length(); i++) add(col, rec.optJSONObject(i), true);
        }
        Ui.show(this, col);
    }

    private void add(LinearLayout col, JSONObject c, boolean recent) {
        if (c == null) return;
        String name = c.optString("name");
        String num = c.optString("number");
        String label = name.isEmpty() ? num : name;
        if (recent) {
            int type = c.optInt("type");
            String icon = type == 3 ? "↙️❗" : (type == 2 ? "↗️" : "↙️");
            label = icon + " " + label + " · " + DateUtils.getRelativeTimeSpanString(c.optLong("date"),
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
        }
        Button b = Ui.button(this, col, label, v -> {
            WatchService.sendToPhone(Msg.of(Proto.DIAL, "number", num));
            Toast.makeText(this, "A ligar pelo telemóvel...", Toast.LENGTH_SHORT).show();
        });
        b.setTextSize(12);
    }
}
