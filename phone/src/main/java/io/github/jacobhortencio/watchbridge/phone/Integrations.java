package io.github.jacobhortencio.watchbridge.phone;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.WLog;

/**
 * Integrações com outras apps do telemóvel:
 * - WhatsApp: mensagem nova a partir do relógio (abre a conversa com o texto e o serviço de
 *   acessibilidade toca em Enviar);
 * - ações de apps: links que o utilizador define (playlist do Spotify, navegar para casa, ...).
 */
final class Integrations {
    private Integrations() {}

    static final String DEFAULT_ACTIONS = "Spotify: os meus gostos | spotify:collection:tracks\n"
            + "Navegar para casa | google.navigation:q=Casa\n"
            + "YouTube Music | https://music.youtube.com";

    /** Linhas "Nome | link" das definições. */
    static List<String[]> actions(Context c) {
        List<String[]> out = new ArrayList<>();
        for (String line : Prefs.str(c, "app_actions", DEFAULT_ACTIONS).split("\n")) {
            int i = line.indexOf('|');
            if (i <= 0) continue;
            String name = line.substring(0, i).trim(), url = line.substring(i + 1).trim();
            if (!name.isEmpty() && !url.isEmpty()) out.add(new String[]{name, url});
        }
        return out;
    }

    static void runAction(Context c, int n) {
        List<String[]> a = actions(c);
        if (n < 0 || n >= a.size()) return;
        String[] act = a.get(n);
        String r = openLink(c, act[1], null) ? act[0] + ": aberto no telemóvel" : act[0] + ": não foi possível abrir";
        BridgeService.send(Msg.of(Proto.RESULT, "msg", r));
        // links do Spotify: começa logo a tocar
        if (act[1].startsWith("spotify:")) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> PhoneActions.media(c, "toggle"), 3500);
        }
    }

    /** Abre um link no telemóvel. Com o Shizuku funciona mesmo com a app em segundo plano. */
    static boolean openLink(Context c, String url, String pkg) {
        if (ShizukuTool.granted()) {
            String cmd = "am start -a android.intent.action.VIEW -d '" + url.replace("'", "'\\''") + "'"
                    + (pkg != null ? " -p " + pkg : "");
            String out = ShizukuTool.run(cmd);
            WLog.i("WB-Int", "am start: " + out);
            return !out.contains("Error") && !out.startsWith("erro");
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pkg != null) i.setPackage(pkg);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            WLog.w("WB-Int", "abrir falhou", e);
            return false;
        }
    }

    // ---------- WhatsApp e SMS ----------

    static void sendMessage(Context c, String app, String number, String text) {
        if (number == null || number.isEmpty() || text == null || text.isEmpty()) return;
        if ("sms".equals(app)) {
            CallBridge.sendSms(c, number, text);
            BridgeService.send(Msg.of(Proto.RESULT, "msg", "SMS enviado"));
            return;
        }
        KeyguardManager km = c.getSystemService(KeyguardManager.class);
        if (km.isKeyguardLocked()) {
            BridgeService.send(Msg.of(Proto.RESULT, "msg", "Desbloqueie o telemóvel para enviar pelo WhatsApp (ou use SMS)"));
            return;
        }
        if (!WaSendService.enabled(c)) {
            BridgeService.send(Msg.of(Proto.RESULT, "msg", "Ative o serviço de envio do WhatsApp (Shizuku ou Acessibilidade)"));
            return;
        }
        String digits = number.replaceAll("[^0-9+]", "");
        if (digits.startsWith("00")) digits = digits.substring(2);
        if (digits.startsWith("+")) digits = digits.substring(1);
        if (digits.length() <= 9) digits = Prefs.str(c, "country_code", "244") + digits; // Angola por omissão
        String url = "https://api.whatsapp.com/send?phone=" + digits + "&text="
                + URLEncoder.encode(text, StandardCharsets.UTF_8);
        String pkg = isInstalled(c, "com.whatsapp") ? "com.whatsapp"
                : isInstalled(c, "com.whatsapp.w4b") ? "com.whatsapp.w4b" : null;
        if (pkg == null) {
            BridgeService.send(Msg.of(Proto.RESULT, "msg", "WhatsApp não instalado"));
            return;
        }
        WaSendService.arm(text);
        if (!openLink(c, url, pkg)) {
            WaSendService.disarm();
            BridgeService.send(Msg.of(Proto.RESULT, "msg", "Não foi possível abrir o WhatsApp"));
        }
    }

    static boolean isInstalled(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
