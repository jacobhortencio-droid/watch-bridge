package io.github.jacobhortencio.watchbridge.phone;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import io.github.jacobhortencio.watchbridge.common.WLog;
import rikka.shizuku.Shizuku;

/**
 * Comandos com privilégios de ADB através do Shizuku, que já tem instalado:
 * configurar tudo de uma vez e controlar o telemóvel a partir do relógio.
 */
final class ShizukuTool {
    private ShizukuTool() {}

    static final int REQ = 55;

    static boolean running() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean granted() {
        try {
            return running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static void request() {
        try {
            Shizuku.requestPermission(REQ);
        } catch (Throwable t) {
            WLog.w("WB-Shizuku", "pedido falhou", t);
        }
    }

    /** Executa um comando de shell com o utilizador do Shizuku. */
    static String run(String cmd) {
        if (!granted()) return "Shizuku sem autorização";
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
            String out = read(p.getInputStream()) + read(p.getErrorStream());
            p.waitFor();
            return out.trim();
        } catch (Throwable t) {
            WLog.w("WB-Shizuku", "comando falhou: " + cmd, t);
            return "erro: " + t.getClass().getSimpleName();
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Configura tudo o que normalmente é manual no HyperOS. */
    static String setupAll(Context c) {
        String pkg = c.getPackageName();
        String listener = pkg + "/" + NotifListener.class.getName();
        String[][] steps = {
                {"Definições restritas", "appops set " + pkg + " ACCESS_RESTRICTED_SETTINGS allow"},
                {"Acesso às notificações", "cmd notification allow_listener " + listener},
                {"Não incomodar", "cmd notification allow_dnd " + pkg},
                {"Bateria sem restrições", "dumpsys deviceidle whitelist +" + pkg},
                {"Segundo plano", "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow"},
                {"Arranque automático (HyperOS)", "appops set " + pkg + " 10008 allow"},
                {"Permissões de chamadas", "pm grant " + pkg + " android.permission.READ_CALL_LOG; pm grant " + pkg
                        + " android.permission.READ_CONTACTS; pm grant " + pkg + " android.permission.READ_PHONE_STATE"},
                {"Agenda", "pm grant " + pkg + " android.permission.READ_CALENDAR"},
                {"Instalar atualizações", "appops set " + pkg + " REQUEST_INSTALL_PACKAGES allow"},
        };
        StringBuilder sb = new StringBuilder();
        for (String[] s : steps) {
            String r = run(s[1]);
            boolean ok = r.isEmpty() || r.contains("Success") || r.startsWith("Added") || r.contains("already");
            sb.append(ok ? "✅ " : "⚠️ ").append(s[0]);
            if (!ok) sb.append(" (").append(r.length() > 80 ? r.substring(0, 80) : r).append(')');
            sb.append('\n');
        }
        WLog.i("WB-Shizuku", "configuração:\n" + sb);
        return sb.toString().trim();
    }
}
