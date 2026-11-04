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

import org.json.JSONArray;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Mosaico de atalhos: 3 botões que disparam automações ou webhooks no telemóvel. */
public class ShortcutsTileService extends TileService {
    private static final String RES = "1";
    static volatile String last = "";

    @NonNull
    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest req) {
        String id = req.getCurrentState().getLastClickableId();
        if (id != null && id.startsWith("btn")) {
            int n = id.charAt(3) - '0';
            WatchService.sendToPhone(Msg.of(Proto.BUTTON, "n", n));
            last = "Enviado: " + name(n);
        } else if ("torch".equals(id)) {
            WatchService.sendToPhone(Msg.of(Proto.PHONE_CTL, "cmd", "torch"));
            last = "Lanterna do telemóvel";
        }
        LayoutElementBuilders.LayoutElement root = TileUi.column()
                .addContent(TileUi.text("Atalhos", 12, TileUi.GREY, false))
                .addContent(TileUi.spacer(6))
                .addContent(TileUi.row()
                        .addContent(TileUi.button("btn1", "1", TileUi.BLUE))
                        .addContent(TileUi.spacer(6))
                        .addContent(TileUi.button("btn2", "2", TileUi.GREEN))
                        .addContent(TileUi.spacer(6))
                        .addContent(TileUi.button("btn3", "3", TileUi.AMBER))
                        .build())
                .addContent(TileUi.spacer(6))
                .addContent(TileUi.row()
                        .addContent(TileUi.button("torch", "🔦", TileUi.RED))
                        .addContent(TileUi.spacer(6))
                        .addContent(TileUi.launch(getPackageName(), PhoneCtlActivity.class, "📱", TileUi.BLUE))
                        .build())
                .addContent(TileUi.spacer(4))
                .addContent(TileUi.text(last.isEmpty() ? "1 " + name(1) + " · 2 " + name(2) + " · 3 " + name(3) : last,
                        10, TileUi.GREY, false))
                .build();
        return Futures.immediateFuture(new TileBuilders.Tile.Builder()
                .setResourcesVersion(RES)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .build());
    }

    private String name(int n) {
        JSONArray a = WPrefs.cfg(this).optJSONArray("buttons");
        String s = a == null ? "" : a.optString(n - 1, "");
        return s.isEmpty() ? "Botão " + n : s;
    }

    @NonNull
    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            @NonNull RequestBuilders.ResourcesRequest req) {
        return Futures.immediateFuture(new ResourceBuilders.Resources.Builder().setVersion(RES).build());
    }
}
