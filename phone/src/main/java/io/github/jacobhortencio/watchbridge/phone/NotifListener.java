package io.github.jacobhortencio.watchbridge.phone;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Lê as notificações e o leitor de música do telemóvel e envia-os ao relógio. */
public class NotifListener extends NotificationListenerService {
    private static final String TAG = "WB-Notif";
    private static volatile NotifListener instance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<String, String> lastSent = new ConcurrentHashMap<>();
    private MediaSessionManager msm;
    private MediaController controller;

    static NotifListener get() {
        return instance;
    }

    @Override
    public void onListenerConnected() {
        instance = this;
        BridgeService.start(this);
        try {
            msm = getSystemService(MediaSessionManager.class);
            ComponentName cn = new ComponentName(this, NotifListener.class);
            msm.addOnActiveSessionsChangedListener(this::onSessionsChanged, cn, h);
            onSessionsChanged(msm.getActiveSessions(cn));
        } catch (Exception e) {
            Log.w(TAG, "media sessions indisponíveis", e);
        }
        if (BridgeService.connected()) syncAll();
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        try {
            requestRebind(new ComponentName(this, NotifListener.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        forward(sbn, false);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (lastSent.remove(sbn.getKey()) != null) {
            BridgeService.send(Msg.of(Proto.NOTIF_RM, "key", sbn.getKey()));
        }
    }

    // ---------- reencaminhar notificações ----------

    void syncAll() {
        lastSent.clear();
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all == null) return;
            for (StatusBarNotification s : all) forward(s, true);
        } catch (Exception e) {
            Log.w(TAG, "syncAll falhou", e);
        }
    }

    private void forward(StatusBarNotification sbn, boolean force) {
        if (!BridgeService.connected()) return;
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName())) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle ex = n.extras;
        if (ex != null && ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return; // música vai à parte
        boolean call = Notification.CATEGORY_CALL.equals(n.category);
        if (sbn.isOngoing() && !call) return;

        String title = str(ex, Notification.EXTRA_TITLE);
        String text = messagingText(ex);
        if (text.isEmpty()) text = str(ex, Notification.EXTRA_BIG_TEXT);
        if (text.isEmpty()) text = str(ex, Notification.EXTRA_TEXT);
        if (title.isEmpty() && text.isEmpty()) return;

        JSONArray actions = new JSONArray();
        if (n.actions != null) {
            for (int i = 0; i < n.actions.length && actions.length() < 3; i++) {
                Notification.Action a = n.actions[i];
                if (a == null || a.title == null) continue;
                boolean reply = false;
                RemoteInput[] ris = a.getRemoteInputs();
                if (ris != null) {
                    for (RemoteInput ri : ris) if (ri.getAllowFreeFormInput()) reply = true;
                }
                actions.put(Msg.of("a", "i", i, "title", a.title.toString(), "reply", reply));
            }
        }

        String sig = title + "|" + text + "|" + actions.length();
        if (!force && sig.equals(lastSent.get(sbn.getKey()))) return;
        lastSent.put(sbn.getKey(), sig);

        BridgeService b = BridgeService.get();
        if (b != null) b.sendIconIfNeeded(pkg);
        BridgeService.send(Msg.of(Proto.NOTIF,
                "key", sbn.getKey(),
                "pkg", pkg,
                "app", appLabel(pkg),
                "title", title,
                "text", text.length() > 3000 ? text.substring(0, 3000) : text,
                "time", sbn.getPostTime(),
                "call", call,
                "actions", actions));
    }

    private static String str(Bundle ex, String k) {
        if (ex == null) return "";
        CharSequence cs = ex.getCharSequence(k);
        return cs == null ? "" : cs.toString();
    }

    /** Conversas (WhatsApp, Telegram...): últimas mensagens com o remetente. */
    private static String messagingText(Bundle ex) {
        if (ex == null) return "";
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs == null || msgs.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, msgs.length - 6);
        for (int i = from; i < msgs.length; i++) {
            if (!(msgs[i] instanceof Bundle)) continue;
            Bundle m = (Bundle) msgs[i];
            CharSequence t = m.getCharSequence("text");
            if (t == null) continue;
            CharSequence sender = m.getCharSequence("sender");
            if (sender == null) {
                Person p = m.getParcelable("sender_person", Person.class);
                if (p != null) sender = p.getName();
            }
            if (sb.length() > 0) sb.append('\n');
            if (sender != null) sb.append(sender).append(": ");
            sb.append(t);
        }
        return sb.toString();
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---------- comandos vindos do relógio ----------

    void dismiss(String key) {
        try {
            lastSent.remove(key);
            cancelNotification(key);
        } catch (Exception e) {
            Log.w(TAG, "dismiss falhou", e);
        }
    }

    private Notification find(String key) {
        try {
            StatusBarNotification[] r = getActiveNotifications(new String[]{key});
            if (r != null && r.length > 0) return r[0].getNotification();
        } catch (Exception ignored) {
        }
        return null;
    }

    void fireAction(String key, int i) {
        Notification n = find(key);
        if (n == null || n.actions == null || i < 0 || i >= n.actions.length) return;
        try {
            n.actions[i].actionIntent.send();
        } catch (PendingIntent.CanceledException e) {
            Log.w(TAG, "ação cancelada", e);
        }
    }

    void reply(String key, int i, String text) {
        Notification n = find(key);
        if (n == null || n.actions == null) return;
        Notification.Action a = null;
        if (i >= 0 && i < n.actions.length && n.actions[i].getRemoteInputs() != null) a = n.actions[i];
        if (a == null) {
            for (Notification.Action x : n.actions) {
                if (x.getRemoteInputs() != null) {
                    a = x;
                    break;
                }
            }
        }
        if (a == null) return;
        try {
            RemoteInput[] ris = a.getRemoteInputs();
            Intent fill = new Intent();
            Bundle results = new Bundle();
            for (RemoteInput ri : ris) results.putCharSequence(ri.getResultKey(), text);
            RemoteInput.addResultsToIntent(ris, fill, results);
            a.actionIntent.send(this, 0, fill);
        } catch (Exception e) {
            Log.w(TAG, "resposta falhou", e);
        }
    }

    // ---------- música ----------

    private final MediaController.Callback mediaCb = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            pushMedia();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            pushMedia();
        }

        @Override
        public void onSessionDestroyed() {
            pushMedia();
        }
    };

    private void onSessionsChanged(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    pick = c;
                    break;
                }
            }
            if (pick == null && !list.isEmpty()) pick = list.get(0);
        }
        if (controller != null) {
            try {
                controller.unregisterCallback(mediaCb);
            } catch (Exception ignored) {
            }
        }
        controller = pick;
        if (controller != null) controller.registerCallback(mediaCb, h);
        pushMedia();
    }

    MediaController currentController() {
        return controller;
    }

    void pushMedia() {
        if (!BridgeService.connected()) return;
        MediaController c = controller;
        if (c == null) {
            BridgeService.send(Msg.of(Proto.MEDIA, "has", false));
            return;
        }
        MediaMetadata md = c.getMetadata();
        PlaybackState st = c.getPlaybackState();
        String title = md == null ? "" : nz(md.getString(MediaMetadata.METADATA_KEY_TITLE));
        String artist = md == null ? "" : nz(md.getString(MediaMetadata.METADATA_KEY_ARTIST));
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        BridgeService.send(Msg.of(Proto.MEDIA, "has", true, "title", title, "artist", artist,
                "app", appLabel(c.getPackageName()), "playing", playing));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
