package io.github.jacobhortencio.watchbridge.phone;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.WLog;

/** Próximos eventos do calendário do telemóvel (7 dias). */
final class Agenda {
    private Agenda() {}

    static void send(Context c) {
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            BridgeService.send(Msg.of(Proto.AGENDA, "events", new JSONArray(),
                    "error", "Conceda no telemóvel a permissão do calendário."));
            return;
        }
        JSONArray out = new JSONArray();
        long now = System.currentTimeMillis();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, now - 3600_000L);
        ContentUris.appendId(b, now + 7 * 24 * 3600_000L);
        try (Cursor cur = c.getContentResolver().query(b.build(), new String[]{
                        CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                        CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION},
                null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            while (cur != null && cur.moveToNext() && out.length() < 20) {
                if (cur.getLong(2) < now) continue; // já terminou
                out.put(Msg.of("e", "title", nz(cur.getString(0)), "begin", cur.getLong(1), "end", cur.getLong(2),
                        "allDay", cur.getInt(3) == 1, "where", nz(cur.getString(4))));
            }
        } catch (Exception e) {
            WLog.w("WB-Agenda", "falhou", e);
        }
        BridgeService.send(Msg.of(Proto.AGENDA, "events", out, "error", ""));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
