package io.github.jacobhortencio.watchbridge.common;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** Guarda as falhas inesperadas num ficheiro e junta-as ao registo no arranque seguinte. */
public final class Crash {
    private Crash() {}

    private static volatile boolean installed;

    public static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        final File f = new File(c.getApplicationContext().getFilesDir(), "crash.txt");
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) Math.min(f.length(), 8000)];
                int n = in.read(b);
                WLog.w("WB-Crash", "falha anterior:\n" + new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
            f.delete();
        }
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (FileOutputStream o = new FileOutputStream(f)) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                o.write(("thread " + t.getName() + "\n" + sw).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
