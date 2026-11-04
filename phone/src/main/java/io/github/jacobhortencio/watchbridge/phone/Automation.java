package io.github.jacobhortencio.watchbridge.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.github.jacobhortencio.watchbridge.common.Http;
import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.WLog;

/**
 * Automações (Tasker, MacroDroid, Home Assistant...).
 * Entrada: intents para enviar notificações ou fazer o relógio vibrar.
 * Saída: os 3 botões de atalho do relógio disparam um intent e, opcionalmente, um webhook.
 */
public class Automation extends BroadcastReceiver {
    static final String ACTION_NOTIFY = "io.github.jacobhortencio.watchbridge.NOTIFY";
    static final String ACTION_FIND_WATCH = "io.github.jacobhortencio.watchbridge.FIND_WATCH";
    static final String ACTION_WATCH_BUTTON = "io.github.jacobhortencio.watchbridge.WATCH_BUTTON";

    @Override
    public void onReceive(Context c, Intent i) {
        if (!Prefs.bool(c, "automation_on", false)) {
            WLog.w("WB-Auto", "automações desligadas; pedido ignorado");
            return;
        }
        String a = i.getAction();
        if (ACTION_NOTIFY.equals(a)) {
            String title = i.getStringExtra("title");
            String text = i.getStringExtra("text");
            BridgeService.send(Msg.of(Proto.NOTIF, "key", "auto-" + System.currentTimeMillis(),
                    "pkg", c.getPackageName(), "app", "Automação",
                    "title", title == null ? "Automação" : title, "text", text == null ? "" : text,
                    "time", System.currentTimeMillis(), "call", false, "vib", 0,
                    "actions", new org.json.JSONArray()));
        } else if (ACTION_FIND_WATCH.equals(a)) {
            BridgeService.send(Msg.of(Proto.RING, "on", true));
        }
    }

    /** Um botão de atalho foi tocado no relógio. */
    static void onWatchButton(Context c, int n) {
        String name = Prefs.str(c, "btn" + n + "_name", "Botão " + n);
        Intent i = new Intent(ACTION_WATCH_BUTTON).putExtra("button", n).putExtra("name", name);
        c.sendBroadcast(i);
        String url = Prefs.str(c, "btn" + n + "_url", "");
        if (url.isEmpty()) {
            BridgeService.send(Msg.of(Proto.RESULT, "msg", name + ": enviado às automações"));
            return;
        }
        new Thread(() -> {
            String r;
            try {
                int code = Http.post(url, Msg.of("button", "button", n, "name", name,
                        "time", System.currentTimeMillis()).toString());
                r = name + ": " + (code >= 200 && code < 300 ? "feito ✅" : "erro HTTP " + code);
            } catch (Exception e) {
                r = name + ": falhou (" + e.getMessage() + ")";
            }
            WLog.i("WB-Auto", r);
            BridgeService.send(Msg.of(Proto.RESULT, "msg", r));
        }, "wb-webhook").start();
    }
}
