package io.github.jacobhortencio.watchbridge.common;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Registo em memória (últimas 500 linhas) que o utilizador pode copiar e partilhar. */
public final class WLog {
    private WLog() {}

    private static final int MAX = 500;
    private static final ArrayDeque<String> buf = new ArrayDeque<>();
    private static final SimpleDateFormat F = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    public static void i(String tag, String msg) {
        add("I", tag, msg, null);
    }

    public static void w(String tag, String msg) {
        add("W", tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable t) {
        add("W", tag, msg, t);
    }

    private static synchronized void add(String lvl, String tag, String msg, Throwable t) {
        String line;
        synchronized (F) {
            line = F.format(new Date()) + " " + lvl + "/" + tag + ": " + msg;
        }
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            String s = sw.toString();
            line += "\n  " + (s.length() > 1500 ? s.substring(0, 1500) : s);
        }
        try {
            if ("W".equals(lvl)) android.util.Log.w(tag, msg, t);
            else android.util.Log.i(tag, msg);
        } catch (Throwable ignored) {
            // testes unitários fora do Android
        }
        buf.addLast(line);
        while (buf.size() > MAX) buf.removeFirst();
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : buf) sb.append(s).append('\n');
        return sb.toString();
    }
}
