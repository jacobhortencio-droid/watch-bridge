package io.github.jacobhortencio.watchbridge.watch;

import android.app.Notification;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/**
 * Notificações das apps do próprio relógio, enviadas ao telemóvel.
 * No Wear OS ativa-se por ADB (uma vez):
 * adb shell cmd notification allow_listener io.github.jacobhortencio.watchbridge.watch/io.github.jacobhortencio.watchbridge.watch.WatchNotifListener
 */
public class WatchNotifListener extends NotificationListenerService {
    private boolean enabled() {
        return WPrefs.cfg(this).optBoolean("forwardWatch") && WatchService.connected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!enabled()) return;
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName()) || sbn.isOngoing()) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle ex = n.extras;
        CharSequence title = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null && text == null) return;
        WatchService.sendToPhone(Msg.of(Proto.WNOTIF, "key", sbn.getKey(), "app", label(pkg),
                "title", title == null ? "" : title.toString(), "text", text == null ? "" : text.toString()));
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!enabled() || sbn.getPackageName().equals(getPackageName())) return;
        WatchService.sendToPhone(Msg.of(Proto.WNOTIF, "key", sbn.getKey(), "removed", true));
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
