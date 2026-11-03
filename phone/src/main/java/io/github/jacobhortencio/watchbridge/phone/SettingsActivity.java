package io.github.jacobhortencio.watchbridge.phone;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Definições que o telemóvel aplica e envia ao relógio. */
public class SettingsActivity extends Activity {
    private interface Saver {
        void save(SharedPreferences.Editor e);
    }

    private final List<Saver> savers = new ArrayList<>();
    private LinearLayout col;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(20);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(32), pad, dp(40));

        title("Definições");

        section("Mensagens");
        multiline("replies", "Respostas rápidas (uma por linha)", Prefs.DEFAULT_REPLIES);
        toggle("send_images", "Enviar fotos e avatares para o relógio", true);
        toggle("silence_active", "Não vibrar quando estou a usar o telemóvel", true);
        text("mute_words", "Ignorar mensagens com estas palavras (vírgulas)", "");
        text("important_words", "Palavras importantes: vibração forte (vírgulas)", "");
        text("vip", "Contactos importantes: vibração forte e ignoram o silêncio (vírgulas)", "");

        section("Horário de silêncio (no relógio)");
        toggle("quiet_on", "Ativo (chamadas, alarmes e contactos importantes passam)", false);
        number("quiet_start", "Começa às (hora, 0 a 23)", 23);
        number("quiet_end", "Termina às (hora, 0 a 23)", 7);

        section("Avisos");
        toggle("lost_alert_phone", "Avisar no telemóvel quando o relógio fica longe", true);
        toggle("lost_alert_watch", "Avisar no relógio quando o telemóvel fica longe", true);

        section("Saúde");
        toggle("hr_alert_on", "Alerta de frequência cardíaca alta em repouso", false);
        number("hr_alert", "Limite (bpm)", 120);
        toggle("move_on", "Lembrete para me mexer (1 hora parado, das 8h às 20h)", false);
        toggle("water_on", "Lembrete para beber água (a cada 2 horas, das 8h às 20h)", false);

        section("Meteorologia");
        text("city", "Nome do local", "Benguela");
        Button loc = new Button(this);
        loc.setText("Usar a localização atual");
        loc.setAllCaps(false);
        loc.setOnClickListener(v -> useLocation());
        col.addView(loc);

        Button save = new Button(this);
        save.setText("Guardar e enviar ao relógio");
        save.setAllCaps(false);
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        col.addView(save, lp);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    private void save() {
        SharedPreferences.Editor e = Prefs.p(this).edit();
        for (Saver s : savers) s.save(e);
        e.apply();
        BridgeService s = BridgeService.get();
        if (s != null) s.pushConfig();
        Weather.refresh(this, true);
        Toast.makeText(this, BridgeService.connected() ? "Guardado e enviado" : "Guardado (vai quando o relógio ligar)",
                Toast.LENGTH_SHORT).show();
        finish();
    }

    private void useLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 5);
            return;
        }
        LocationManager lm = getSystemService(LocationManager.class);
        Location best = null;
        for (String p : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException ignored) {
            }
        }
        if (best == null) {
            Toast.makeText(this, "Sem localização conhecida. Abra um mapa uma vez e tente de novo.", Toast.LENGTH_LONG).show();
            return;
        }
        Prefs.setPlace(this, best.getLatitude(), best.getLongitude(), Prefs.city(this));
        Toast.makeText(this, String.format(Locale.US, "Localização: %.3f, %.3f", best.getLatitude(), best.getLongitude()),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == 5 && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) useLocation();
    }

    // ---------- construtores de campos ----------

    private void title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);
    }

    private void section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(4));
        col.addView(t);
    }

    private void label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(8), 0, 0);
        col.addView(t);
    }

    private void toggle(String key, String label, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setChecked(Prefs.bool(this, key, def));
        sw.setPadding(0, dp(8), 0, dp(8));
        col.addView(sw);
        savers.add(e -> e.putBoolean(key, sw.isChecked()));
    }

    private void text(String key, String label, String def) {
        label(label);
        EditText et = new EditText(this);
        et.setText(Prefs.str(this, key, def));
        et.setSingleLine(true);
        col.addView(et);
        savers.add(e -> e.putString(key, et.getText().toString().trim()));
    }

    private void multiline(String key, String label, String def) {
        label(label);
        EditText et = new EditText(this);
        et.setText(Prefs.str(this, key, def));
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(3);
        col.addView(et);
        savers.add(e -> e.putString(key, et.getText().toString().trim()));
    }

    private void number(String key, String label, int def) {
        label(label);
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(Prefs.integer(this, key, def)));
        col.addView(et);
        savers.add(e -> {
            try {
                e.putInt(key, Integer.parseInt(et.getText().toString().trim()));
            } catch (NumberFormatException ignored) {
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
