package io.github.jacobhortencio.watchbridge.common;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Divide mensagens em pacotes BLE. Cada pacote começa com 1 byte:
 * 1 = há mais pacotes, 0 = último pacote.
 */
public final class Chunker {
    private Chunker() {}

    public static List<byte[]> split(byte[] data, int maxPacket) {
        int body = Math.max(1, maxPacket - 1);
        List<byte[]> out = new ArrayList<>();
        int off = 0;
        do {
            int n = Math.min(body, data.length - off);
            byte[] c = new byte[n + 1];
            c[0] = (byte) ((off + n < data.length) ? 1 : 0);
            System.arraycopy(data, off, c, 1, n);
            out.add(c);
            off += n;
        } while (off < data.length);
        return out;
    }

    public static final class Assembler {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        /** Devolve a mensagem completa quando chega o último pacote; caso contrário null. */
        public synchronized byte[] feed(byte[] chunk) {
            if (chunk == null || chunk.length == 0) return null;
            buf.write(chunk, 1, chunk.length - 1);
            if ((chunk[0] & 1) == 0) {
                byte[] r = buf.toByteArray();
                buf.reset();
                return r;
            }
            if (buf.size() > 2_000_000) buf.reset();
            return null;
        }

        public synchronized void reset() {
            buf.reset();
        }
    }
}
