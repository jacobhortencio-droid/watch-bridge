package io.github.jacobhortencio.watchbridge.watch;

import java.util.LinkedHashMap;
import java.util.Map;

/** Capas recentes (só são enviadas quando a faixa muda). */
final class MusicArt {
    private MusicArt() {}

    private static final Map<String, String> cache = new LinkedHashMap<String, String>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > 6;
        }
    };

    static synchronized void put(String key, String b64) {
        if (key != null && b64 != null && !b64.isEmpty()) cache.put(key, b64);
    }

    static synchronized String get(String key) {
        return key == null ? null : cache.get(key);
    }
}
