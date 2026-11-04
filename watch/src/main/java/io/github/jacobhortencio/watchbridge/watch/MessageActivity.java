package io.github.jacobhortencio.watchbridge.watch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Mensagem nova (WhatsApp ou SMS) a um contacto favorito ou recente, escrita ou ditada no relógio. */
public class MessageActivity extends Activity {
    private static final int VOICE = 12;
    private final Handler h = new Handler(Looper.getMainLooper());
    private String number = "", name = "", app = "whatsapp";
    private EditText input;
    private long shown = -2;

    @Override
    protected void onResume() {
        super.onResume();
        if (number.isEmpty()) {
            WatchService.sendToPhone(Msg.of(Proto.CALLS_REQ));
            h.post(poll);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            JSONObject d = CallsActivity.data;
            long t = d == null ? -1 : d.optLong("time");
            if (t != shown && number.isEmpty()) {
                shown = t;
                pickContact(d);
            }
            h.postDelayed(this, 1000);
        }
    };

    private void pickContact(JSONObject d) {
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Nova mensagem", 15, true);
        if (d == null) {
            Ui.text(this, col, WatchService.connected() ? "A carregar contactos..." : "Telemóvel desligado", 13, false);
            Ui.show(this, col);
            return;
        }
        java.util.LinkedHashMap<String, String> people = new java.util.LinkedHashMap<>();
        for (String key : new String[]{"favs", "recent"}) {
            JSONArray a = d.optJSONArray(key);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null || c.optString("number").isEmpty()) continue;
                String nm = c.optString("name").isEmpty() ? c.optString("number") : c.optString("name");
                if (!people.containsValue(c.optString("number"))) people.put(nm, c.optString("number"));
            }
        }
        if (people.isEmpty()) Ui.text(this, col, "Sem contactos. Marque favoritos no telemóvel.", 12, false);
        for (java.util.Map.Entry<String, String> e : people.entrySet()) {
            Button b = Ui.button(this, col, e.getKey(), v -> {
                name = e.getKey();
                number = e.getValue();
                h.removeCallbacks(poll);
                compose();
            });
            b.setTextSize(12);
        }
        Ui.show(this, col);
    }

    private void compose() {
        LinearLayout col = Ui.column(this);
        Ui.text(this, col, "Para " + name, 13, true);
        LinearLayout row = Ui.row(this, col);
        Button wa = Ui.rowButton(this, row, "WhatsApp", v -> setApp("whatsapp"));
        Button sms = Ui.rowButton(this, row, "SMS", v -> setApp("sms"));
        wa.setTextSize(12);
        sms.setTextSize(12);
        wa.setTag("whatsapp");
        sms.setTag("sms");
        input = new EditText(this);
        input.setHint("Escreva...");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, id, ev) -> {
            send(input.getText().toString());
            return true;
        });
        col.addView(input);
        Ui.button(this, col, "🎤  Ditar", v -> {
            try {
                startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-PT"), VOICE);
            } catch (Exception e) {
                Toast.makeText(this, "Ditado indisponível neste relógio", Toast.LENGTH_SHORT).show();
            }
        });
        Ui.button(this, col, "Enviar", v -> send(input.getText().toString()));
        Ui.text(this, col, "Respostas rápidas:", 11, false);
        for (CharSequence r : WPrefs.replies(this)) {
            Button b = Ui.button(this, col, r.toString(), v -> send(r.toString()));
            b.setTextSize(12);
        }
        TextView note = Ui.text(this, col, "WhatsApp: o telemóvel tem de estar desbloqueado.", 10, false);
        note.setVisibility(View.VISIBLE);
        Ui.show(this, col);
    }

    private void setApp(String a) {
        app = a;
        Toast.makeText(this, "whatsapp".equals(a) ? "Pelo WhatsApp" : "Por SMS", Toast.LENGTH_SHORT).show();
    }

    private void send(String text) {
        if (text == null || text.trim().isEmpty()) return;
        WatchService.sendToPhone(Msg.of(Proto.MSG_SEND, "app", app, "number", number, "text", text.trim()));
        Toast.makeText(this, "A enviar...", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == VOICE && res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty() && input != null) input.setText(r.get(0));
        }
    }
}
