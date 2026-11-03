package io.github.jacobhortencio.watchbridge.phone;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Escolher que apps enviam notificações para o relógio. */
public class AppsActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(20);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(32), pad, pad);

        TextView title = new TextView(this);
        title.setText("Apps no relógio");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Desligue as apps cujas notificações não quer no pulso. A lista cresce à medida que as apps enviam notificações.");
        sub.setPadding(0, dp(4), 0, dp(12));
        col.addView(sub);

        PackageManager pm = getPackageManager();
        Set<String> muted = Prefs.muted(this);
        List<String[]> apps = new ArrayList<>();
        for (String pkg : Prefs.seenApps(this)) {
            String label;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception e) {
                continue; // desinstalada
            }
            apps.add(new String[]{label, pkg});
        }
        apps.sort((x, y) -> x[0].compareToIgnoreCase(y[0]));

        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Ainda não chegou nenhuma notificação. Volte aqui mais tarde.");
            col.addView(empty);
        }

        for (String[] a : apps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            ImageView icon = new ImageView(this);
            try {
                Drawable d = pm.getApplicationIcon(a[1]);
                icon.setImageDrawable(d);
            } catch (Exception ignored) {
            }
            row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

            Switch sw = new Switch(this);
            sw.setText(a[0]);
            sw.setTextSize(16);
            sw.setChecked(!muted.contains(a[1]));
            sw.setPadding(dp(12), 0, 0, 0);
            final String pkg = a[1];
            sw.setOnCheckedChangeListener((v, on) -> Prefs.setMuted(this, pkg, !on));
            row.addView(sw, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            col.addView(row);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
