package io.github.jacobhortencio.watchbridge.common;

import java.security.KeyPair;

/**
 * Estado de um emparelhamento em curso. Só fica concluído quando o utilizador confirma
 * o código nos DOIS aparelhos (confirmação local + prova do outro lado).
 */
public final class Pairing {
    public final boolean isPhone;
    public final KeyPair mine;
    public String pubPhone, pubWatch;
    public byte[] key;
    public String code;
    public boolean localOk, peerOk;
    public final long started = System.currentTimeMillis();

    public Pairing(boolean isPhone) throws Exception {
        this.isPhone = isPhone;
        this.mine = Secure.newKeyPair();
        if (isPhone) pubPhone = Secure.pub(mine);
        else pubWatch = Secure.pub(mine);
    }

    /** Recebida a chave pública do outro lado: calcula a chave partilhada e o código. */
    public void onPeerPub(String peer) throws Exception {
        if (isPhone) pubWatch = peer;
        else pubPhone = peer;
        key = Secure.derive(mine, peer, pubPhone, pubWatch);
        code = Secure.sas(pubPhone, pubWatch);
    }

    /** Prova de que este lado confirmou, sem revelar a chave. */
    public String proof() {
        return Secure.hmac(key, (isPhone ? "phone-ok|" : "watch-ok|") + pubPhone + "|" + pubWatch);
    }

    public boolean checkPeerProof(String p) {
        if (key == null || p == null) return false;
        String expected = Secure.hmac(key, (isPhone ? "watch-ok|" : "phone-ok|") + pubPhone + "|" + pubWatch);
        return expected.equals(p);
    }

    public boolean done() {
        return key != null && localOk && peerOk;
    }

    public boolean expired() {
        return System.currentTimeMillis() - started > 3 * 60_000;
    }
}
