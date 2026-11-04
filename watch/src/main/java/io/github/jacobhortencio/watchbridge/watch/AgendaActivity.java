package io.github.jacobhortencio.watchbridge.watch;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Próximos eventos do calendário do telemóvel. */
public class AgendaActivity extends Activity {
    static volatile JSONObject data;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long shown = -1;

    @Override
    protected void onResume() {
        super.onResume();
        WatchService.sendToPhone(Msg.of(Proto.AGENDA_REQ));
        build();
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
            long t = d == null ? -1 : d.optLong("time");
            if (t != shown) build();
            h.postDelayed(this, 1000);
        }
    };

    private void build() {
        JSONObject d = data;
        shown = d == null ? -1 : d.optLong("time");
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Agenda", 15, true);
        if (d == null) {
            Ui.text(this, col, WatchService.connected() ? "A carregar..." : "Telemóvel desligado", 13, false);
        } else {
            if (!d.optString("error").isEmpty()) Ui.text(this, col, d.optString("error"), 12, false);
            JSONArray ev = d.optJSONArray("events");
            if (ev == null || ev.length() == 0) Ui.text(this, col, "Sem eventos nos próximos 7 dias.", 13, false);
            for (int i = 0; ev != null && i < ev.length(); i++) {
                JSONObject e = ev.optJSONObject(i);
                if (e == null) continue;
                int flags = e.optBoolean("allDay") ? DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY
                        | DateUtils.FORMAT_ABBREV_ALL
                        : DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY
                        | DateUtils.FORMAT_ABBREV_ALL;
                Ui.text(this, col, DateUtils.formatDateTime(this, e.optLong("begin"), flags), 11, false);
                Ui.text(this, col, e.optString("title"), 14, true);
                if (!e.optString("where").isEmpty()) Ui.text(this, col, "📍 " + e.optString("where"), 11, false);
            }
        }
        Ui.show(this, col);
    }
}
