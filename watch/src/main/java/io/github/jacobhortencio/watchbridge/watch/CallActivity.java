package io.github.jacobhortencio.watchbridge.watch;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Ecrã de chamada completo: quem liga, atender, recusar, silenciar, recusar com mensagem. */
public class CallActivity extends Activity {
    static final int CALL_ID = 30;
    static volatile JSONObject current;
    private static long started;

    private final Handler h = new Handler(Looper.getMainLooper());
    private ImageView photo;
    private TextView name, state;
    private LinearLayout ringing, active, replies;

    /** Chamado pelo serviço quando chega um CALL do telemóvel. */
    static void onCall(Context c, JSONObject m) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        String st = m.optString("state");
        if ("idle".equals(st)) {
            current = null;
            nm.cancel(CALL_ID);
            return;
        }
        if (!"offhook".equals(st) || current == null || !"offhook".equals(current.optString("state"))) {
            started = System.currentTimeMillis();
        }
        current = m;
        PendingIntent full = PendingIntent.getActivity(c, CALL_ID, new Intent(c, CallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String who = who(m);
        boolean ring = "ringing".equals(st);
        Notification.Builder b = new Notification.Builder(c, ring ? Notifier.CH_CALLS : Notifier.CH_SILENT)
                .setSmallIcon(R.drawable.ic_phone)
                .setContentTitle(ring ? "📞 " + who : "Em chamada: " + who)
                .setContentText(ring ? "A ligar..." : "Toque para desligar")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(full);
        if (ring) b.setFullScreenIntent(full, true);
        Bitmap p = Ongoing.decode(m.optString("photo"));
        if (p != null) b.setLargeIcon(p);
        if (ring) {
            b.addAction(action(c, "answer", "Atender"));
            b.addAction(action(c, "decline", "Recusar"));
        } else {
            b.addAction(action(c, "hangup", "Desligar"));
        }
        nm.notify(CALL_ID, b.build());
    }

    private static Notification.Action action(Context c, String cmd, String label) {
        Intent i = new Intent(c, ActionReceiver.class).setAction(ActionReceiver.ACTION_CALL).putExtra("cmd", cmd)
                .setData(android.net.Uri.parse("wb://call/" + cmd));
        PendingIntent pi = PendingIntent.getBroadcast(c, cmd.hashCode(), i, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_phone), label, pi).build();
    }

    static String who(JSONObject m) {
        String n = m.optString("name");
        if (!n.isEmpty()) return n;
        String num = m.optString("number");
        return num.isEmpty() ? "Número privado" : num;
    }

    static void cmd(String c, String text) {
        WatchService.sendToPhone(Msg.of(Proto.CALL_CMD, "cmd", c, "text", text));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout col = Ui.column(this);
        photo = new ImageView(this);
        col.addView(photo, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        name = Ui.text(this, col, "", 17, true);
        state = Ui.text(this, col, "", 12, false);

        ringing = Ui.row(this, col);
        Ui.rowButton(this, ringing, "✅", v -> cmd("answer", ""));
        Ui.rowButton(this, ringing, "❌", v -> {
            cmd("decline", "");
            finish();
        });
        Ui.rowButton(this, ringing, "🔇", v -> cmd("mute", ""));

        active = Ui.row(this, col);
        Ui.rowButton(this, active, "📵 Desligar", v -> {
            cmd("hangup", "");
            finish();
        });

        Ui.text(this, col, "Recusar com mensagem:", 11, false);
        replies = new LinearLayout(this);
        replies.setOrientation(LinearLayout.VERTICAL);
        col.addView(replies);
        for (CharSequence r : WPrefs.replies(this)) {
            String txt = r.toString();
            android.widget.Button btn = new android.widget.Button(this);
            btn.setText(txt);
            btn.setAllCaps(false);
            btn.setTextSize(12);
            btn.setOnClickListener(v -> {
                cmd("sms", txt);
                finish();
            });
            replies.addView(btn);
        }
        Ui.show(this, col);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresh);
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            JSONObject m = current;
            if (m == null) {
                finish();
                return;
            }
            name.setText(who(m));
            boolean ring = "ringing".equals(m.optString("state"));
            long secs = (System.currentTimeMillis() - started) / 1000;
            state.setText(ring ? (m.optString("name").isEmpty() ? "A ligar..." : m.optString("number"))
                    : String.format(java.util.Locale.US, "Em chamada %d:%02d", secs / 60, secs % 60));
            ringing.setVisibility(ring ? View.VISIBLE : View.GONE);
            replies.setVisibility(ring ? View.VISIBLE : View.GONE);
            active.setVisibility(ring ? View.GONE : View.VISIBLE);
            Bitmap p = Ongoing.decode(m.optString("photo"));
            if (p != null) photo.setImageBitmap(p);
            else photo.setImageResource(R.drawable.ic_phone);
            h.postDelayed(this, 1000);
        }
    };
}
