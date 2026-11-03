package io.github.jacobhortencio.watchbridge.watch;

import androidx.annotation.NonNull;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Mosaico principal: bateria do telemóvel, tempo e atalhos (encontrar, música, não incomodar). */
public class MainTileService extends TileService {
    private static final String RES = "1";
    private static boolean finding;

    @NonNull
    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest req) {
        String clicked = req.getCurrentState().getLastClickableId();
        handle(clicked);
        return Futures.immediateFuture(new TileBuilders.Tile.Builder()
                .setResourcesVersion(RES)
                .setFreshnessIntervalMillis(5 * 60_000)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout()))
                .build());
    }

    @NonNull
    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            @NonNull RequestBuilders.ResourcesRequest req) {
        return Futures.immediateFuture(new ResourceBuilders.Resources.Builder().setVersion(RES).build());
    }

    private void handle(String id) {
        if (id == null || id.isEmpty()) return;
        switch (id) {
            case "find":
                finding = !finding;
                WatchService.sendToPhone(Msg.of(Proto.FIND_PHONE, "on", finding));
                break;
            case "music":
                WatchService.sendToPhone(Msg.of(Proto.MEDIA_CMD, "cmd", "toggle"));
                break;
            case "dnd":
                WatchService.sendToPhone(Msg.of(Proto.SET_DND, "on", !WatchService.phoneDnd));
                WatchService.phoneDnd = !WatchService.phoneDnd;
                break;
            default:
                break;
        }
    }

    private LayoutElementBuilders.LayoutElement layout() {
        boolean on = WatchService.connected();
        String head;
        if (on) {
            int b = WatchService.phoneBattery;
            head = "📱 " + (b >= 0 ? b + "%" : "--") + (WatchService.phoneCharging ? "⚡" : "");
        } else {
            head = "📱 desligado";
        }
        int wb = WatchService.watchBattery();
        if (wb >= 0) head += "   ⌚ " + wb + "%";

        JSONObject w = WPrefs.weather(this);
        String weather = w == null ? "" : WeatherActivity.icon(w.optInt("code"), w.optBoolean("day", true))
                + " " + w.optInt("temp") + "° " + w.optString("desc");

        JSONObject media = WatchService.media;
        boolean playing = media != null && media.optBoolean("playing");
        String song = media != null && media.optBoolean("has") ? media.optString("title") : "";

        LayoutElementBuilders.Column.Builder col = TileUi.column()
                .addContent(TileUi.text("Watch Bridge", 12, TileUi.GREY, false))
                .addContent(TileUi.text(head, 15, TileUi.WHITE, true));
        if (!weather.isEmpty()) col.addContent(TileUi.text(weather, 12, TileUi.GREY, false));
        col.addContent(TileUi.spacer(8))
                .addContent(TileUi.row()
                        .addContent(TileUi.button("find", finding ? "⏹" : "📳", TileUi.BLUE))
                        .addContent(TileUi.spacer(6))
                        .addContent(TileUi.button("music", playing ? "⏸" : "▶", TileUi.GREEN))
                        .addContent(TileUi.spacer(6))
                        .addContent(TileUi.button("dnd", WatchService.phoneDnd ? "🔕" : "🔔", TileUi.AMBER))
                        .build())
                .addContent(TileUi.spacer(6));
        if (!song.isEmpty()) col.addContent(TileUi.text("♪ " + song, 11, TileUi.GREY, false));
        else col.addContent(TileUi.text(WatchService.status, 11, TileUi.GREY, false));
        return col.build();
    }
}
