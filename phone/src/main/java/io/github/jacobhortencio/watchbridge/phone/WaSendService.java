package io.github.jacobhortencio.watchbridge.phone;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.WLog;

/**
 * Toca no botão Enviar do WhatsApp, e só quando o relógio pediu um envio nos últimos 20 segundos.
 * Não lê nem guarda conversas: apenas procura o botão de enviar.
 */
public class WaSendService extends AccessibilityService {
    private static volatile long armedUntil;
    private static volatile String expected = "";
    private final Handler h = new Handler(Looper.getMainLooper());

    static void arm(String text) {
        expected = text;
        armedUntil = System.currentTimeMillis() + 20_000;
    }

    static void disarm() {
        armedUntil = 0;
    }

    static boolean enabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(new ComponentName(c, WaSendService.class).flattenToString());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (System.currentTimeMillis() > armedUntil) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!pkg.startsWith("com.whatsapp")) return;
        List<AccessibilityNodeInfo> send = root.findAccessibilityNodeInfosByViewId(pkg + ":id/send");
        if (send == null || send.isEmpty()) return;
        AccessibilityNodeInfo b = send.get(0);
        if (!b.isEnabled()) return;
        if (b.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            disarm();
            WLog.i("WB-WA", "mensagem enviada pelo WhatsApp");
            BridgeService.send(Msg.of(Proto.RESULT, "msg", "WhatsApp enviado ✅"));
            // volta ao ecrã inicial para não deixar a conversa aberta
            h.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 1200);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
