package io.github.jacobhortencio.watchbridge.common;

import android.net.Network;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pedidos HTTP simples, opcionalmente presos a uma rede específica (Wi-Fi do relógio). */
public final class Http {
    private Http() {}

    public static byte[] get(String u, Network net) throws IOException {
        return get(u, net, 0);
    }

    private static byte[] get(String u, Network net, int depth) throws IOException {
        if (depth > 5) throw new IOException("demasiados redirecionamentos");
        URL url = new URL(u);
        HttpURLConnection c = (HttpURLConnection) (net != null ? net.openConnection(url) : url.openConnection());
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "watch-bridge");
        try {
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                if (loc == null) throw new IOException("redirecionamento sem destino");
                return get(new URL(url, loc).toString(), net, depth + 1);
            }
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Informação da última versão publicada: {versão, url do APK do relógio}. */
    public static final class Release {
        public final long version;
        public final String watchApkUrl;
        public String phoneApkUrl;

        Release(long version, String watchApkUrl) {
            this.version = version;
            this.watchApkUrl = watchApkUrl;
        }
    }

    /** POST com corpo JSON (webhooks, por exemplo do Home Assistant). Devolve o código HTTP. */
    public static int post(String u, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "watch-bridge");
        try {
            c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    public static String getText(String u, Network net) throws IOException {
        return new String(get(u, net), StandardCharsets.UTF_8);
    }

    public static Release latest(Network net) throws Exception {
        JSONObject rel = new JSONObject(new String(get(Proto.RELEASE_API, net), StandardCharsets.UTF_8));
        Matcher mt = Pattern.compile("(\\d+)").matcher(rel.optString("name"));
        long v = mt.find() ? Long.parseLong(mt.group(1)) : -1;
        String url = null, phone = null;
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                if (Proto.WATCH_ASSET.equals(a.optString("name"))) url = a.optString("browser_download_url");
                if (Proto.PHONE_ASSET.equals(a.optString("name"))) phone = a.optString("browser_download_url");
            }
        }
        Release r = new Release(v, url);
        r.phoneApkUrl = phone;
        return r;
    }
}
