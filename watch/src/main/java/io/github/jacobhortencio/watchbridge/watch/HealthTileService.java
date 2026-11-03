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

/** Mosaico de saúde: frequência cardíaca, passos e atalhos para medir e respirar. */
public class HealthTileService extends TileService {
    private static final String RES = "1";

    @NonNull
    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest req) {
        if ("water".equals(req.getCurrentState().getLastClickableId())) Wellness.addWater(this);
        int hr = WatchService.lastHr;
        int steps = WatchService.todaySteps;
        int goal = 8000;
        int pct = steps > 0 ? Math.min(100, steps * 100 / goal) : 0;
        LayoutElementBuilders.LayoutElement root = TileUi.column()
                .addContent(TileUi.text("Saúde", 12, TileUi.GREY, false))
                .addContent(TileUi.text("❤ " + (hr > 0 ? hr + " bpm" : "--"), 18, TileUi.WHITE, true))
                .addContent(TileUi.text("👣 " + (steps >= 0 ? steps : 0) + " (" + pct + "%)", 14, TileUi.WHITE, false))
                .addContent(TileUi.text("💧 " + Wellness.waterToday(this) + " copos", 12, TileUi.GREY, false))
                .addContent(TileUi.spacer(8))
                .addContent(TileUi.row()
                        .addContent(TileUi.launch(getPackageName(), HealthActivity.class, "❤", TileUi.RED))
                        .addContent(TileUi.spacer(6))
                        .addContent(TileUi.button("water", "💧", TileUi.BLUE))
                        .addContent(TileUi.spacer(6))
                        .addContent(TileUi.launch(getPackageName(), BreatheActivity.class, "🌬", TileUi.GREEN))
                        .build())
                .build();
        return Futures.immediateFuture(new TileBuilders.Tile.Builder()
                .setResourcesVersion(RES)
                .setFreshnessIntervalMillis(15 * 60_000)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .build());
    }

    @NonNull
    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            @NonNull RequestBuilders.ResourcesRequest req) {
        return Futures.immediateFuture(new ResourceBuilders.Resources.Builder().setVersion(RES).build());
    }
}
