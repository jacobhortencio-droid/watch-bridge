package io.github.jacobhortencio.watchbridge.watch;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Ações de apps definidas no telemóvel (playlists do Spotify, navegar para casa, sites...). */
public class ActionsActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Ações", 15, true);
        JSONArray a = WPrefs.cfg(this).optJSONArray("actions");
        if (a == null || a.length() == 0) {
            Ui.text(this, col, "Defina ações no telemóvel: Definições → Ações de apps.", 12, false);
        }
        for (int i = 0; a != null && i < a.length(); i++) {
            final int n = i;
            Ui.button(this, col, a.optString(i), v -> {
                WatchService.sendToPhone(Msg.of(Proto.APP_ACTION, "n", n));
                Toast.makeText(this, "A abrir no telemóvel...", Toast.LENGTH_SHORT).show();
            }).setTextSize(12);
        }
        Ui.show(this, col);
    }
}
