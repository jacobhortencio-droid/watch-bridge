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
        if (sbn.getKey().equals(navKey)) {
            navKey = null;
            navSig = "";
            BridgeService.send(Msg.of(Proto.NAV, "end", true));
            return;
        }
        if (lastSent.remove(sbn.getKey()) != null) {
            BridgeService.send(Msg.of(Proto.NOTIF_RM, "key", sbn.getKey()));
        }
    }

    // ---------- navegação ----------

    private static final java.util.Set<String> NAV_APPS = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.google.android.apps.maps", "com.waze", "app.organicmaps", "app.comaps", "net.osmand",
            "net.osmand.plus", "com.here.app.maps", "com.sygic.aura", "com.autonavi.minimap", "com.baidu.BaiduMap"));
    private volatile String navKey;
    private volatile String navSig = "";

    private boolean isNavigation(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (Notification.CATEGORY_NAVIGATION.equals(n.category)) return true;
        return sbn.isOngoing() && NAV_APPS.contains(sbn.getPackageName());
    }

    private void forwardNav(StatusBarNotification sbn, boolean force) {
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        String title = str(ex, Notification.EXTRA_TITLE);
        String text = str(ex, Notification.EXTRA_TEXT);
        String sub = str(ex, Notification.EXTRA_SUB_TEXT);
        if (sub.isEmpty()) sub = str(ex, Notification.EXTRA_BIG_TEXT);
        String sig = title + "|" + text + "|" + sub;
        if (!force && sig.equals(navSig)) return;
        navSig = sig;
        navKey = sbn.getKey();
        JSONObject m = Msg.of(Proto.NAV, "title", title, "text", text, "sub", sub, "app", appLabel(sbn.getPackageName()));
        try {
            android.graphics.drawable.Icon li = n.getLargeIcon();
            if (li != null) {
                android.graphics.drawable.Drawable d = li.loadDrawable(this);
                if (d != null) {
                    android.graphics.Bitmap b = toBitmap(d, 96);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos);
                    m.put("icon", android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP));
                }
            }
        } catch (Exception e) {
            io.github.jacobhortencio.watchbridge.common.WLog.w("WB-Nav", "ícone falhou", e);
        }
        BridgeService.send(m);
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
        if (isNavigation(sbn)) {
            forwardNav(sbn, force);
            return;
        }
        boolean call = Notification.CATEGORY_CALL.equals(n.category);
        // com o ecrã de chamada próprio, a notificação do marcador não é reenviada
        if (call && CallBridge.active(this)) return;
        boolean alarm = Notification.CATEGORY_ALARM.equals(n.category)
                || Notification.CATEGORY_REMINDER.equals(n.category)
                || Notification.CATEGORY_EVENT.equals(n.category);
        if (sbn.isOngoing() && !call && !alarm) return;
        Prefs.addSeenApp(this, pkg);
        if (Prefs.muted(this).contains(pkg)) return;

        String title = str(ex, Notification.EXTRA_TITLE);
        String text = messagingText(ex);
        if (text.isEmpty()) text = str(ex, Notification.EXTRA_BIG_TEXT);
        if (text.isEmpty()) text = str(ex, Notification.EXTRA_TEXT);
        if (title.isEmpty() && text.isEmpty()) return;

        // filtros por palavra e contactos importantes
        String lower = (title + " " + text).toLowerCase(java.util.Locale.ROOT);
        for (String w : Prefs.list(this, "mute_words", "")) {
            if (lower.contains(w.toLowerCase(java.util.Locale.ROOT))) return;
        }
        boolean vip = false;
        String lowerTitle = title.toLowerCase(java.util.Locale.ROOT);
        for (String name : Prefs.list(this, "vip", "")) {
            if (lowerTitle.contains(name.toLowerCase(java.util.Locale.ROOT))) vip = true;
        }
        for (String w : Prefs.list(this, "important_words", "")) {
            if (lower.contains(w.toLowerCase(java.util.Locale.ROOT))) vip = true;
        }
        String otp = findCode(title + " " + text);
        int vib = vip ? 3 : Prefs.vibFor(this, pkg);
        boolean silent = !call && !alarm && !vip && Prefs.bool(this, "silence_active", true) && phoneInUse();

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
        JSONObject msg = Msg.of(Proto.NOTIF,
                "key", sbn.getKey(),
                "pkg", pkg,
                "app", appLabel(pkg),
                "title", title,
                "text", text.length() > 3000 ? text.substring(0, 3000) : text,
                "time", sbn.getPostTime(),
                "call", call,
                "alarm", alarm && sbn.isOngoing(),
                "vip", vip,
                "vib", vib,
                "silent", silent,
                "otp", otp,
                "actions", actions);
        if (Prefs.bool(this, "send_images", true)) {
            try {
                if (ex != null) {
                    android.graphics.Bitmap pic = ex.getParcelable(Notification.EXTRA_PICTURE, android.graphics.Bitmap.class);
                    if (pic != null) msg.put("img", jpeg(pic, 320, 70));
                }
                android.graphics.drawable.Icon li = n.getLargeIcon();
                if (li != null) {
                    android.graphics.drawable.Drawable d = li.loadDrawable(this);
                    if (d != null) msg.put("avatar", jpeg(toBitmap(d, 96), 96, 80));
                }
            } catch (Exception e) {
                io.github.jacobhortencio.watchbridge.common.WLog.w("WB-Notif", "imagem falhou", e);
            }
        }
        BridgeService.send(msg);
    }

    /** Códigos de verificação (4 a 8 dígitos) em mensagens que falam de código/OTP/PIN. */
    static String findCode(String s) {
        if (!java.util.regex.Pattern.compile("(?i)(c[oó]digo|code|otp|verifica|senha|pin|token|验证码)")
                .matcher(s).find()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<![\\d-])(\\d{4,8})(?![\\d-])").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private boolean phoneInUse() {
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        return pm.isInteractive() && !km.isKeyguardLocked();
    }

    private static android.graphics.Bitmap toBitmap(android.graphics.drawable.Drawable d, int size) {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    private static String jpeg(android.graphics.Bitmap src, int maxSide, int quality) {
        int w = src.getWidth(), h = src.getHeight();
        float s = Math.min(1f, maxSide / (float) Math.max(w, h));
        android.graphics.Bitmap b = s < 1f
                ? android.graphics.Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true)
                : src;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        b.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, bos);
        return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
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

    private List<MediaController> sessions = new java.util.ArrayList<>();
    private String lastArtKey = "";

    /** Troca para o leitor seguinte (quando há vários ativos). */
    void nextPlayer() {
        List<MediaController> l = sessions;
        if (l == null || l.size() < 2) return;
        int i = 0;
        for (int k = 0; k < l.size(); k++) {
            if (controller != null && l.get(k).getSessionToken().equals(controller.getSessionToken())) i = k;
        }
        setController(l.get((i + 1) % l.size()));
    }

    private void setController(MediaController pick) {
        if (controller != null) {
            try {
                controller.unregisterCallback(mediaCb);
            } catch (Exception ignored) {
            }
        }
        controller = pick;
        if (controller != null) controller.registerCallback(mediaCb, h);
        lastArtKey = "";
        pushMedia();
    }

    private void onSessionsChanged(List<MediaController> list) {
        sessions = list == null ? new java.util.ArrayList<>() : list;
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
        long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long pos = 0;
        if (st != null) {
            pos = st.getPosition();
            if (playing && st.getLastPositionUpdateTime() > 0) {
                pos += (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime())
                        * st.getPlaybackSpeed());
            }
        }
        String artKey = Integer.toHexString((title + "|" + artist + "|" + c.getPackageName()).hashCode());
        JSONObject m = Msg.of(Proto.MEDIA, "has", true, "title", title, "artist", artist,
                "app", appLabel(c.getPackageName()), "playing", playing, "dur", dur, "pos", pos,
                "players", sessions == null ? 1 : sessions.size(), "artKey", artKey);
        // a capa só viaja quando a faixa muda
        if (!artKey.equals(lastArtKey) && md != null) {
            android.graphics.Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (art != null) {
                try {
                    m.put("art", jpeg(art, 128, 75));
                } catch (Exception ignored) {
                }
            }
            lastArtKey = artKey;
        }
        BridgeService.send(m);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
