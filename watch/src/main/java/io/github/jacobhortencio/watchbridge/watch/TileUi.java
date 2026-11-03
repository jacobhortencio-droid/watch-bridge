package io.github.jacobhortencio.watchbridge.watch;

import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ModifiersBuilders;

/** Pequenos construtores para os mosaicos (Tiles). */
final class TileUi {
    private TileUi() {}

    static final int WHITE = 0xFFFFFFFF;
    static final int GREY = 0xFFB0B8C4;
    static final int BLUE = 0xFF1E3A5F;
    static final int GREEN = 0xFF1B4D3E;
    static final int RED = 0xFF5A1F2B;
    static final int AMBER = 0xFF5A4614;

    static LayoutElementBuilders.LayoutElement text(String s, float sp, int color, boolean bold) {
        LayoutElementBuilders.FontStyle.Builder f = new LayoutElementBuilders.FontStyle.Builder()
                .setSize(DimensionBuilders.sp(sp))
                .setColor(ColorBuilders.argb(color));
        if (bold) f.setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD);
        return new LayoutElementBuilders.Text.Builder()
                .setText(s)
                .setMaxLines(2)
                .setFontStyle(f.build())
                .build();
    }

    static LayoutElementBuilders.LayoutElement spacer(float dp) {
        return new LayoutElementBuilders.Spacer.Builder()
                .setHeight(DimensionBuilders.dp(dp))
                .setWidth(DimensionBuilders.dp(dp))
                .build();
    }

    /** Botão redondo que recarrega o mosaico com o seu id (a ação é tratada no serviço). */
    static LayoutElementBuilders.LayoutElement button(String id, String label, int bg) {
        ModifiersBuilders.Clickable click = new ModifiersBuilders.Clickable.Builder()
                .setId(id)
                .setOnClick(new ActionBuilders.LoadAction.Builder().build())
                .build();
        return circle(label, bg, click);
    }

    /** Botão que abre um ecrã da app. */
    static LayoutElementBuilders.LayoutElement launch(String pkg, Class<?> activity, String label, int bg) {
        ModifiersBuilders.Clickable click = new ModifiersBuilders.Clickable.Builder()
                .setId("open_" + activity.getSimpleName())
                .setOnClick(new ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(pkg)
                                .setClassName(activity.getName())
                                .build())
                        .build())
                .build();
        return circle(label, bg, click);
    }

    private static LayoutElementBuilders.LayoutElement circle(String label, int bg, ModifiersBuilders.Clickable click) {
        ModifiersBuilders.Modifiers mods = new ModifiersBuilders.Modifiers.Builder()
                .setClickable(click)
                .setBackground(new ModifiersBuilders.Background.Builder()
                        .setColor(ColorBuilders.argb(bg))
                        .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(24)).build())
                        .build())
                .build();
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(48))
                .setHeight(DimensionBuilders.dp(48))
                .setModifiers(mods)
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(text(label, 18, WHITE, false))
                .build();
    }

    static LayoutElementBuilders.Row.Builder row() {
        return new LayoutElementBuilders.Row.Builder()
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER);
    }

    static LayoutElementBuilders.Column.Builder column() {
        return new LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER);
    }
}
