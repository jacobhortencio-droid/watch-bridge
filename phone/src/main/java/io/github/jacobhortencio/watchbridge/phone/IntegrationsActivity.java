package io.github.jacobhortencio.watchbridge.phone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Ligar as apps oficiais do relógio (Spotify, WhatsApp) e as integrações próprias. */
public class IntegrationsActivity extends Activity {
    private LinearLayout col;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(20);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(32), pad, dp(40));
        title("Integrações");

        section("Spotify no relógio");
        text("A app oficial do Spotify para Wear OS funciona sozinha pelo Wi-Fi do relógio e controla o Spotify "
                + "do telemóvel pela internet (Spotify Connect). Não precisa do Mi Fitness.\n"
                + "1. Instale-a no relógio (botão abaixo).\n2. Inicie sessão no relógio.\n"
                + "3. No relógio, toque no ícone de dispositivos e escolha o telemóvel.");
        button("Abrir o Spotify na Play Store do relógio", () -> store("com.spotify.music"));
        text("Sem a app oficial, o Watch Bridge já controla o Spotify do telemóvel (Música no relógio) e "
                + "abre as suas playlists com as Ações de apps.");

        section("WhatsApp no relógio");
        text("A app oficial do WhatsApp para Wear OS liga-se com um código de 8 dígitos que aparece no relógio "
                + "e se escreve no WhatsApp do telemóvel (quando o telemóvel pedir). Depois funciona pelo Wi-Fi.\n"
                + "Se o telemóvel nunca pedir o código, use as integrações do Watch Bridge abaixo.");
        button("Abrir o WhatsApp na Play Store do relógio", () -> store("com.whatsapp"));

        section("WhatsApp pelo Watch Bridge");
        text("Já funciona: ler e responder às mensagens. Novo: escrever uma mensagem nova a um contacto a partir "
                + "do relógio (✉️ Nova mensagem). O telemóvel abre a conversa e um serviço de acessibilidade toca em "
                + "Enviar. O telemóvel tem de estar desbloqueado.\n"
                + "Estado: " + (WaSendService.enabled(this) ? "ativo ✅" : "desligado ❌"));
        button("Ativar (com o Shizuku)", () -> {
            if (!ShizukuTool.granted()) {
                toast("Autorize primeiro o Shizuku no ecrã principal");
                return;
            }
            new Thread(() -> {
                String cmd = ShizukuTool.enableAccessibility(this);
                if (!cmd.equals("true")) ShizukuTool.run(cmd);
                runOnUiThread(() -> {
                    toast(WaSendService.enabled(this) ? "Ativado" : "Não foi possível; ative à mão");
                    recreate();
                });
            }).start();
        });
        button("Ativar à mão (Acessibilidade)", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        section("Ações de apps");
        text("Links que aparecem no relógio em 🔗 Ações e abrem no telemóvel: playlists do Spotify, navegação, "
                + "sites, qualquer app com links. Edite-os em Definições → Ações de apps.");
        for (String[] a : Integrations.actions(this)) text("• " + a[0] + "\n   " + a[1]);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    private void store(String pkg) {
        if (!BridgeService.connected()) {
            toast("O relógio não está ligado");
            return;
        }
        BridgeService.send(Msg.of(Proto.STORE_OPEN, "pkg", pkg));
        toast("Toque na notificação que aparece no relógio");
    }

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
        t.setPadding(0, dp(20), 0, dp(6));
        col.addView(t);
    }

    private void text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(4), 0, dp(4));
        col.addView(t);
    }

    private void button(String s, Runnable r) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(v -> r.run());
        col.addView(b);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
