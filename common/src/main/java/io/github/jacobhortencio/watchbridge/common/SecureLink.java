package io.github.jacobhortencio.watchbridge.common;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Camada de segurança por cima do Bluetooth e do Wi-Fi.
 * Cada ligação começa com SESSION (identificador aleatório). Depois, cada mensagem vai cifrada
 * com AES-GCM, leva o identificador de sessão do destinatário e um contador, o que impede
 * mensagens falsas e repetidas. Mensagens grandes são comprimidas antes de cifrar.
 */
public class SecureLink {
    public interface KeyStore {
        /** Chave partilhada, ou null se ainda não houve emparelhamento. */
        byte[] key();
    }

    private final KeyStore store;
    private volatile String mySid = Secure.randomHex(8);
    private volatile String peerSid;
    private volatile String peerKeyId = "";
    private long sendSeq;
    private long recvSeq = -1;

    public SecureLink(KeyStore store) {
        this.store = store;
    }

    /** Nova ligação física: novo identificador, contadores a zero. */
    public synchronized void reset() {
        mySid = Secure.randomHex(8);
        peerSid = null;
        peerKeyId = "";
        sendSeq = 0;
        recvSeq = -1;
    }

    public JSONObject sessionMsg(String role) {
        return Msg.of(Proto.SESSION, "sid", mySid, "kid", Secure.keyId(store.key()),
                "role", role, "proto", Proto.VERSION);
    }

    public synchronized void onPeerSession(JSONObject m) {
        peerSid = m.optString("sid", null);
        peerKeyId = m.optString("kid", "");
        sendSeq = 0;
        recvSeq = -1;
    }

    public boolean paired() {
        return store.key() != null;
    }

    /** Os dois lados estão emparelhados com a mesma chave e a sessão está aberta. */
    public boolean ready() {
        byte[] k = store.key();
        return k != null && peerSid != null && Secure.keyId(k).equals(peerKeyId);
    }

    public boolean peerSeen() {
        return peerSid != null;
    }

    /** O outro lado tem uma chave diferente (ou nenhuma). */
    public boolean keyMismatch() {
        return peerSid != null && !Secure.keyId(store.key()).equals(peerKeyId);
    }

    /** Mensagem pronta a enviar, ou null se não pode sair (ainda sem emparelhamento). */
    public synchronized JSONObject wrap(JSONObject m) {
        String t = m.optString("t");
        if (Proto.PLAIN.contains(t)) return m;
        byte[] k = store.key();
        if (k == null || peerSid == null) return null;
        try {
            JSONObject inner = new JSONObject(m.toString());
            inner.put("_sid", peerSid);
            inner.put("_n", ++sendSeq);
            byte[] raw = Msg.encode(inner);
            boolean z = raw.length > 200;
            if (z) raw = Secure.gzip(raw);
            byte[] framed = new byte[raw.length + 1];
            framed[0] = (byte) (z ? 1 : 0);
            System.arraycopy(raw, 0, framed, 1, raw.length);
            return Msg.of(Proto.ENC, "d", Secure.encrypt(k, framed));
        } catch (Exception e) {
            WLog.w("WB-Sec", "cifrar falhou", e);
            return null;
        }
    }

    /** Mensagem recebida já decifrada e validada, ou null se deve ser ignorada. */
    public synchronized JSONObject unwrap(JSONObject wire) {
        String t = wire.optString("t");
        if (Proto.ENC.equals(t)) {
            byte[] k = store.key();
            if (k == null) return null;
            try {
                byte[] framed = Secure.decrypt(k, wire.optString("d"));
                byte[] raw = new byte[framed.length - 1];
                System.arraycopy(framed, 1, raw, 0, raw.length);
                if (framed[0] == 1) raw = Secure.gunzip(raw);
                JSONObject in = Msg.decode(raw);
                if (in == null) return null;
                if (!mySid.equals(in.optString("_sid"))) return null; // de outra sessão
                long n = in.optLong("_n", -1);
                if (n <= recvSeq) return null; // repetida
                recvSeq = n;
                in.remove("_sid");
                in.remove("_n");
                return in;
            } catch (Exception e) {
                WLog.w("WB-Sec", "mensagem rejeitada: " + e.getClass().getSimpleName());
                return null;
            }
        }
        if (Proto.PLAIN.contains(t)) return wire;
        return null;
    }

    /** Cópia simples, útil nos testes. */
    static JSONObject copy(JSONObject o) throws JSONException {
        JSONObject c = new JSONObject();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            c.put(k, o.get(k));
        }
        return c;
    }
}
