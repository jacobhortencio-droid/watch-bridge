package io.github.jacobhortencio.watchbridge.phone;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.regex.Pattern;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.WLog;

/** Chamadas: estado com nome e foto, atender/recusar/silenciar/SMS, recentes, favoritos e ligar. */
@SuppressLint("MissingPermission")
final class CallBridge {
    private CallBridge() {}

    private static final String TAG = "WB-Call";
    private static final Pattern ANSWER = Pattern.compile("(?i)(atender|answer|aceitar|accept|接听|接受)");
    private static final Pattern DECLINE = Pattern.compile("(?i)(recusar|rejeitar|decline|reject|desligar|hang ?up|end|terminar|挂断|拒绝|结束)");

    static volatile String lastNumber = "";
    static volatile String lastState = TelephonyManager.EXTRA_STATE_IDLE;
    private static BroadcastReceiver receiver;

    static boolean hasPerm(Context c, String p) {
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    /** O ecrã de chamada próprio está ativo (então não reenviamos a notificação do marcador). */
    static boolean active(Context c) {
        return Prefs.bool(c, "call_screen", true) && hasPerm(c, Manifest.permission.READ_PHONE_STATE);
    }

    static void register(Context c) {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                String st = i.getStringExtra(TelephonyManager.EXTRA_STATE);
                String num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                if (num != null && !num.isEmpty()) lastNumber = num;
                if (st == null) return;
                // o sistema envia o evento duas vezes (com e sem número); só mudanças contam
                boolean changed = !st.equals(lastState) || (num != null && !num.isEmpty());
                lastState = st;
                if (changed) onState(ctx, st);
            }
        };
        c.registerReceiver(receiver, new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                Context.RECEIVER_EXPORTED);
    }

    static void unregister(Context c) {
        if (receiver == null) return;
        try {
            c.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        receiver = null;
    }

    private static void onState(Context c, String st) {
        String state = TelephonyManager.EXTRA_STATE_RINGING.equals(st) ? "ringing"
                : TelephonyManager.EXTRA_STATE_OFFHOOK.equals(st) ? "offhook" : "idle";
        if ("idle".equals(state)) {
            BridgeService.send(Msg.of(Proto.CALL, "state", "idle"));
            lastNumber = "";
            return;
        }
        String[] contact = lookup(c, lastNumber);
        BridgeService.send(Msg.of(Proto.CALL, "state", state, "number", lastNumber,
                "name", contact[0], "photo", contact[1]));
    }

    /** {nome, foto em base64} do contacto, ou vazios. */
    static String[] lookup(Context c, String number) {
        String[] out = {"", ""};
        if (number == null || number.isEmpty() || !hasPerm(c, Manifest.permission.READ_CONTACTS)) return out;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{
                ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                out[0] = cur.getString(0) == null ? "" : cur.getString(0);
                String photo = cur.getString(1);
                if (photo != null) {
                    try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(photo))) {
                        Bitmap b = BitmapFactory.decodeStream(in);
                        if (b != null) {
                            Bitmap s = Bitmap.createScaledBitmap(b, 96, 96, true);
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            s.compress(Bitmap.CompressFormat.JPEG, 80, bos);
                            out[1] = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                        }
                    }
                }
            }
        } catch (Exception e) {
            WLog.w(TAG, "contacto falhou", e);
        }
        return out;
    }

    // ---------- comandos do relógio ----------

    static void command(Context c, String cmd, String text) {
        WLog.i(TAG, "comando " + cmd);
        TelecomManager tm = c.getSystemService(TelecomManager.class);
        switch (cmd) {
            case "answer":
                if (!fireDialerAction(ANSWER)) {
                    try {
                        tm.acceptRingingCall();
                    } catch (Exception e) {
                        WLog.w(TAG, "atender falhou", e);
                    }
                }
                break;
            case "decline":
            case "hangup":
                if (!fireDialerAction(DECLINE)) endCall(tm);
                break;
            case "mute":
                try {
                    tm.silenceRinger();
                } catch (Exception e) {
                    AudioManager am = c.getSystemService(AudioManager.class);
                    am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0);
                }
                break;
            case "sms":
                String num = lastNumber;
                if (!fireDialerAction(DECLINE)) endCall(tm);
                if (!num.isEmpty() && text != null && !text.isEmpty()) sendSms(c, num, text);
                break;
            default:
                break;
        }
    }

    @SuppressWarnings("deprecation")
    private static void endCall(TelecomManager tm) {
        try {
            tm.endCall();
        } catch (Exception e) {
            WLog.w(TAG, "desligar falhou", e);
        }
    }

    static void sendSms(Context c, String number, String text) {
        if (!hasPerm(c, Manifest.permission.SEND_SMS)) {
            WLog.w(TAG, "sem permissão de SMS");
            return;
        }
        try {
            SmsManager sm = c.getSystemService(SmsManager.class);
            sm.sendMultipartTextMessage(number, null, sm.divideMessage(text), null, null);
        } catch (Exception e) {
            WLog.w(TAG, "SMS falhou", e);
        }
    }

    /** Usa os botões da notificação do marcador (funciona melhor no HyperOS). */
    private static boolean fireDialerAction(Pattern p) {
        NotifListener l = NotifListener.get();
        if (l == null) return false;
        try {
            StatusBarNotification[] all = l.getActiveNotifications();
            if (all == null) return false;
            for (StatusBarNotification s : all) {
                Notification n = s.getNotification();
                if (!Notification.CATEGORY_CALL.equals(n.category) || n.actions == null) continue;
                for (Notification.Action a : n.actions) {
                    if (a.title != null && p.matcher(a.title).find()) {
                        a.actionIntent.send();
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            WLog.w(TAG, "ação do marcador falhou", e);
        }
        return false;
    }

    static void dial(Context c, String number) {
        if (number == null || number.isEmpty()) return;
        if (!hasPerm(c, Manifest.permission.CALL_PHONE)) {
            WLog.w(TAG, "sem permissão para ligar");
            return;
        }
        try {
            c.getSystemService(TelecomManager.class).placeCall(Uri.fromParts("tel", number, null), new Bundle());
        } catch (Exception e) {
            WLog.w(TAG, "ligar falhou", e);
        }
    }

    // ---------- recentes e favoritos ----------

    static void sendList(Context c) {
        JSONArray recent = new JSONArray(), favs = new JSONArray();
        String error = "";
        if (hasPerm(c, Manifest.permission.READ_CALL_LOG)) {
            try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE},
                    null, null, CallLog.Calls.DATE + " DESC")) {
                while (cur != null && cur.moveToNext() && recent.length() < 20) {
                    recent.put(Msg.of("c", "name", nz(cur.getString(0)), "number", nz(cur.getString(1)),
                            "type", cur.getInt(2), "date", cur.getLong(3)));
                }
            } catch (Exception e) {
                WLog.w(TAG, "registo de chamadas falhou", e);
            }
        } else {
            error = "Conceda no telemóvel a permissão de registo de chamadas.";
        }
        if (hasPerm(c, Manifest.permission.READ_CONTACTS)) {
            try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.STARRED + "=1", null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
                while (cur != null && cur.moveToNext() && favs.length() < 15) {
                    favs.put(Msg.of("c", "name", nz(cur.getString(0)), "number", nz(cur.getString(1))));
                }
            } catch (Exception e) {
                WLog.w(TAG, "favoritos falharam", e);
            }
        }
        BridgeService.send(Msg.of(Proto.CALLS, "recent", recent, "favs", favs, "error", error));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static JSONObject unused() {
        return null;
    }
}
