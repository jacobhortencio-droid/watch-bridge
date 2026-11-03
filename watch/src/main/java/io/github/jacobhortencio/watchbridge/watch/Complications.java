package io.github.jacobhortencio.watchbridge.watch;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.wear.tiles.TileService;
import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.MonochromaticImage;
import androidx.wear.watchface.complications.data.NoDataComplicationData;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.RangedValueComplicationData;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

import org.json.JSONObject;

/** Complicações para qualquer mostrador: bateria do telemóvel, passos, frequência cardíaca, tempo. */
public final class Complications {
    private Complications() {}

    private static final Class<?>[] ALL = {
            PhoneBattery.class, Steps.class, Heart.class, Weather.class,
    };

    /** Pede aos mostradores e mosaicos que voltem a ler os dados. */
    static void refreshAll(Context c) {
        for (Class<?> cls : ALL) {
            try {
                ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, cls)).requestUpdateAll();
            } catch (Exception ignored) {
            }
        }
        try {
            TileService.getUpdater(c).requestUpdate(MainTileService.class);
            TileService.getUpdater(c).requestUpdate(HealthTileService.class);
        } catch (Exception ignored) {
        }
    }

    static PlainComplicationText t(String s) {
        return new PlainComplicationText.Builder(s).build();
    }

    static MonochromaticImage img(Context c, int res) {
        return new MonochromaticImage.Builder(Icon.createWithResource(c, res)).build();
    }

    /** Base: um valor numérico com texto curto e ícone. */
    public abstract static class Base extends ComplicationDataSourceService {
        /** null = sem dados. */
        abstract Float value();

        abstract float max();

        abstract String label();

        abstract int icon();

        abstract String describe();

        @Override
        public void onComplicationRequest(@NonNull ComplicationRequest request,
                                          @NonNull ComplicationRequestListener listener) {
            try {
                listener.onComplicationData(build(request.getComplicationType(), value()));
            } catch (Exception ignored) {
            }
        }

        @Nullable
        @Override
        public ComplicationData getPreviewData(@NonNull ComplicationType type) {
            return build(type, max() * 0.7f);
        }

        private ComplicationData build(ComplicationType type, Float v) {
            if (v == null) return new NoDataComplicationData();
            String text = label() != null ? label() : String.valueOf(Math.round(v));
            if (type == ComplicationType.RANGED_VALUE) {
                return new RangedValueComplicationData.Builder(Math.min(v, max()), 0f, max(), t(describe()))
                        .setText(t(text))
                        .setMonochromaticImage(img(this, icon()))
                        .build();
            }
            if (type == ComplicationType.SHORT_TEXT) {
                return new ShortTextComplicationData.Builder(t(text), t(describe()))
                        .setMonochromaticImage(img(this, icon()))
                        .build();
            }
            return null;
        }
    }

    public static class PhoneBattery extends Base {
        Float value() {
            int b = WatchService.phoneBattery;
            return b < 0 ? null : (float) b;
        }

        float max() {
            return 100f;
        }

        String label() {
            int b = WatchService.phoneBattery;
            return (b < 0 ? 70 : b) + "%";
        }

        int icon() {
            return R.drawable.ic_phone;
        }

        String describe() {
            return "Bateria do telemóvel";
        }
    }

    public static class Steps extends Base {
        Float value() {
            int s = WatchService.todaySteps;
            return s < 0 ? null : (float) s;
        }

        float max() {
            return 8000f;
        }

        String label() {
            int s = WatchService.todaySteps;
            if (s < 0) return "5600";
            return s >= 10000 ? (s / 1000) + "k" : String.valueOf(s);
        }

        int icon() {
            return R.drawable.ic_steps;
        }

        String describe() {
            return "Passos hoje";
        }
    }

    public static class Heart extends Base {
        Float value() {
            int h = WatchService.lastHr;
            return h <= 0 ? null : (float) h;
        }

        float max() {
            return 200f;
        }

        String label() {
            int h = WatchService.lastHr;
            return String.valueOf(h <= 0 ? 72 : h);
        }

        int icon() {
            return R.drawable.ic_heart;
        }

        String describe() {
            return "Frequência cardíaca";
        }
    }

    public static class Weather extends Base {
        private JSONObject w() {
            return WPrefs.weather(this);
        }

        Float value() {
            JSONObject w = w();
            return w == null ? null : (float) w.optInt("temp");
        }

        float max() {
            return 45f;
        }

        String label() {
            JSONObject w = w();
            return (w == null ? 27 : w.optInt("temp")) + "°";
        }

        int icon() {
            return R.drawable.ic_weather;
        }

        String describe() {
            JSONObject w = w();
            return w == null ? "Tempo" : w.optString("desc", "Tempo");
        }
    }
}
