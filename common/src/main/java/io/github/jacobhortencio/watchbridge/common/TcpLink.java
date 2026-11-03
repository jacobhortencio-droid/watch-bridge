package io.github.jacobhortencio.watchbridge.common;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Ligação alternativa por Wi-Fi: mensagens JSON com prefixo de tamanho. */
public class TcpLink {
    public interface Listener {
        void onMessage(TcpLink link, JSONObject msg);

        void onClosed(TcpLink link);
    }

    private final Socket socket;
    private final Listener listener;
    private final DataOutputStream out;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile boolean closed;

    public TcpLink(Socket socket, Listener listener) throws IOException {
        this.socket = socket;
        this.listener = listener;
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    public void start() {
        Thread t = new Thread(this::readLoop, "wb-tcp-read");
        t.setDaemon(true);
        t.start();
    }

    private void readLoop() {
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            while (!closed) {
                int len = in.readInt();
                if (len < 0 || len > 4_000_000) throw new IOException("tamanho inválido");
                byte[] b = new byte[len];
                in.readFully(b);
                JSONObject m = Msg.decode(b);
                if (m != null) listener.onMessage(this, m);
            }
        } catch (IOException ignored) {
        } finally {
            close();
        }
    }

    public void send(JSONObject msg) {
        if (closed) return;
        final byte[] b = Msg.encode(msg);
        try {
            writer.execute(() -> {
                try {
                    out.writeInt(b.length);
                    out.write(b);
                    out.flush();
                } catch (IOException e) {
                    close();
                }
            });
        } catch (Exception ignored) {
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        writer.shutdownNow();
        listener.onClosed(this);
    }
}
