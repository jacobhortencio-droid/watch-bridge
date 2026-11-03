package io.github.jacobhortencio.watchbridge.common;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Protocolo partilhado entre o telemóvel e o relógio. */
public final class Proto {
    private Proto() {}

    /** Versão do protocolo. A v2 introduziu o emparelhamento e a cifra. */
    public static final int VERSION = 2;

    // Serviço BLE próprio: não depende do GMS nem do Mi Fitness.
    public static final UUID SERVICE = UUID.fromString("7d2a0001-6b1e-4c33-9a3f-2b6c1e5f0a01");
    /** Telemóvel escreve aqui (telemóvel -> relógio). */
    public static final UUID RX = UUID.fromString("7d2a0002-6b1e-4c33-9a3f-2b6c1e5f0a01");
    /** Relógio notifica por aqui (relógio -> telemóvel). */
    public static final UUID TX = UUID.fromString("7d2a0003-6b1e-4c33-9a3f-2b6c1e5f0a01");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** Porta TCP da ligação alternativa por Wi-Fi. */
    public static final int TCP_PORT = 48211;

    // ---------- sessão e emparelhamento (sempre em claro) ----------
    public static final String SESSION = "session";
    public static final String PAIR_START = "pair_start"; // telemóvel -> relógio
    public static final String PAIR_KEY = "pair_key";     // relógio -> telemóvel
    public static final String PAIR_OK = "pair_ok";       // ambos
    public static final String PAIR_CANCEL = "pair_cancel";
    /** Envelope cifrado. */
    public static final String ENC = "e";

    // ---------- gerais ----------
    public static final String HELLO = "hello";
    public static final String PING = "ping";
    public static final String LOG_REQ = "log_req";
    public static final String LOG = "log";

    // ---------- telemóvel -> relógio ----------
    public static final String NOTIF = "notif";
    public static final String NOTIF_RM = "notif_rm";
    public static final String ICON = "icon";
    public static final String MEDIA = "media";
    public static final String BATTERY = "battery";
    public static final String RING = "ring";
    public static final String CONFIG = "config";
    public static final String WEATHER = "weather";
    public static final String DND = "dnd";

    // ---------- relógio -> telemóvel ----------
    public static final String DISMISS = "dismiss";
    public static final String ACTION = "action";
    public static final String REPLY = "reply";
    public static final String MEDIA_CMD = "media_cmd";
    public static final String MEDIA_REQ = "media_req";
    public static final String FIND_PHONE = "find_phone";
    public static final String HEALTH = "health";
    public static final String SENSORS = "sensors";
    public static final String WATCH_BATTERY = "w_battery";
    public static final String SET_DND = "set_dnd";

    // ---------- atualização do relógio através do telemóvel ----------
    public static final String UPD_REQ = "upd_req";   // relógio -> telemóvel
    public static final String UPD_APK = "upd_apk";   // telemóvel -> relógio
    public static final String UPD_NONE = "upd_none"; // telemóvel -> relógio

    /**
     * Tipos que viajam em claro. Tudo o resto só passa cifrado depois do emparelhamento.
     * A atualização é segura em claro: o Android só aceita um APK assinado com a mesma chave.
     */
    public static final Set<String> PLAIN = new HashSet<>(Arrays.asList(
            SESSION, PAIR_START, PAIR_KEY, PAIR_OK, PAIR_CANCEL, PING,
            UPD_REQ, UPD_APK, UPD_NONE));

    public static final String RELEASE_API =
            "https://api.github.com/repos/jacobhortencio-droid/watch-bridge/releases/tags/latest";
    public static final String WATCH_ASSET = "WatchBridge-relogio.apk";
    public static final String PHONE_ASSET = "WatchBridge-telemovel.apk";
}
