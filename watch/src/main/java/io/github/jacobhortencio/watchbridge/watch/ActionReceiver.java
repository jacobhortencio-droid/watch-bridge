package io.github.jacobhortencio.watchbridge.watch;

import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Recebe os toques nas notificações do relógio e envia-os ao telemóvel. */
public class ActionReceiver extends BroadcastReceiver {
    static final String ACTION_DISMISS = "wb.DISMISS";
    static final String ACTION_ACTION = "wb.ACTION";
    static final String ACTION_REPLY = "wb.REPLY";
    static final String ACTION_STOP_RING = "wb.STOP_RING";
    static final String KEY_TEXT = "wb_text";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        String key = intent.getStringExtra("key");
        int i = intent.getIntExtra("i", -1);
        int nid = intent.getIntExtra("nid", 0);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        switch (a) {
            case ACTION_DISMISS:
                WatchService.sendToPhone(Msg.of(Proto.DISMISS, "key", key));
                break;
            case ACTION_ACTION:
                WatchService.sendToPhone(Msg.of(Proto.ACTION, "key", key, "i", i));
                nm.cancel(nid);
                break;
            case ACTION_REPLY: {
                Bundle r = RemoteInput.getResultsFromIntent(intent);
                CharSequence text = r == null ? null : r.getCharSequence(KEY_TEXT);
                if (text != null && text.length() > 0) {
                    WatchService.sendToPhone(Msg.of(Proto.REPLY, "key", key, "i", i, "text", text.toString()));
                }
                nm.cancel(nid);
                break;
            }
            case ACTION_STOP_RING: {
                WatchService s = WatchService.get();
                if (s != null) s.notifier().ring(false);
                else nm.cancel(Notifier.RING_ID);
                break;
            }
            default:
                break;
        }
    }
}
