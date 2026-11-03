package io.github.jacobhortencio.watchbridge.watch;

import android.app.Activity;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

/** As últimas 50 notificações, mesmo depois de apagadas. */
public class HistoryActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
    }

    private void build() {
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Histórico", 15, true);
        JSONArray a = WPrefs.history(this);
        if (a.length() == 0) Ui.text(this, col, "Ainda sem notificações.", 13, false);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String when = DateUtils.getRelativeTimeSpanString(o.optLong("time"), System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
            Ui.text(this, col, o.optString("app") + " · " + when, 11, false);
            String t = o.optString("title");
            if (!t.isEmpty()) Ui.text(this, col, t, 14, true);
            Ui.text(this, col, o.optString("text"), 13, false);
            Ui.text(this, col, " ", 6, false);
        }
        if (a.length() > 0) {
            Ui.button(this, col, "Limpar histórico", v -> {
                WPrefs.clearHistory(this);
                build();
            });
        }
        Ui.show(this, col);
    }
}
