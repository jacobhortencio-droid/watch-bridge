package io.github.jacobhortencio.watchbridge.common;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Random;

public class ProtocolTest {

    @Test
    public void chunkerRoundTrip() {
        Random r = new Random(1);
        for (int size : new int[]{0, 1, 19, 20, 21, 500, 100_000}) {
            byte[] data = new byte[size];
            r.nextBytes(data);
            for (int mtu : new int[]{20, 185, 514}) {
                List<byte[]> parts = Chunker.split(data, mtu);
                Chunker.Assembler a = new Chunker.Assembler();
                byte[] out = null;
                for (int i = 0; i < parts.size(); i++) {
                    assertTrue(parts.get(i).length <= mtu);
                    out = a.feed(parts.get(i));
                    if (i < parts.size() - 1) assertNull(out);
                }
                assertArrayEquals(data, out);
            }
        }
    }

    @Test
    public void pairingProducesSameKeyAndCode() throws Exception {
        Pairing phone = new Pairing(true);
        Pairing watch = new Pairing(false);
        watch.onPeerPub(phone.pubPhone);
        phone.onPeerPub(watch.pubWatch);
        assertArrayEquals(phone.key, watch.key);
        assertEquals(phone.code, watch.code);
        assertEquals(6, phone.code.length());
        assertTrue(watch.checkPeerProof(phone.proof()));
        assertTrue(phone.checkPeerProof(watch.proof()));
        assertFalse(phone.checkPeerProof(phone.proof()));
    }

    @Test
    public void secureLinkEncryptsAndRejectsReplays() throws Exception {
        Pairing p = new Pairing(true);
        Pairing w = new Pairing(false);
        w.onPeerPub(p.pubPhone);
        p.onPeerPub(w.pubWatch);
        final byte[] key = p.key;
        SecureLink phone = new SecureLink(() -> key);
        SecureLink watch = new SecureLink(() -> key);
        phone.onPeerSession(watch.sessionMsg("watch"));
        watch.onPeerSession(phone.sessionMsg("phone"));
        assertTrue(phone.ready());
        assertTrue(watch.ready());

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300; i++) big.append("olá ");
        JSONObject wire = phone.wrap(Msg.of(Proto.NOTIF, "title", "Teste", "text", big.toString()));
        assertNotNull(wire);
        assertEquals(Proto.ENC, wire.getString("t"));
        assertFalse(wire.toString().contains("Teste"));

        JSONObject got = watch.unwrap(wire);
        assertNotNull(got);
        assertEquals("Teste", got.getString("title"));
        assertEquals(big.toString(), got.getString("text"));
        assertNull("repetição tem de ser rejeitada", watch.unwrap(wire));

        // chave errada
        final byte[] other = Secure.sha256("x".getBytes());
        SecureLink intruder = new SecureLink(() -> other);
        intruder.onPeerSession(watch.sessionMsg("watch"));
        assertNull(watch.unwrap(intruder.wrap(Msg.of(Proto.NOTIF, "title", "falsa"))));

        // sem emparelhamento, mensagens normais não saem e não entram
        SecureLink none = new SecureLink(() -> null);
        assertNull(none.wrap(Msg.of(Proto.NOTIF, "title", "x")));
        assertNull(none.unwrap(Msg.of(Proto.NOTIF, "title", "x")));
        assertNotNull(none.unwrap(Msg.of(Proto.PAIR_START, "pub", "x")));
    }
}
