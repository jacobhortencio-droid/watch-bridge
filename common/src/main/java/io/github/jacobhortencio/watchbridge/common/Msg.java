package io.github.jacobhortencio.watchbridge.common;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

public final class Msg {
    private Msg() {}

    /** Msg.of("notif", "key", k, "title", t, ...) */
    public static JSONObject of(String type, Object... kv) {
        JSONObject o = new JSONObject();
        try {
            o.put("t", type);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                o.put(String.valueOf(kv[i]), kv[i + 1] == null ? JSONObject.NULL : kv[i + 1]);
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return o;
    }

    public static byte[] encode(JSONObject o) {
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static JSONObject decode(byte[] b) {
        try {
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return null;
        }
    }
}
