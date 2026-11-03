package io.github.jacobhortencio.watchbridge.watch;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Pequenos utilitários de interface para ecrã redondo. */
final class Ui {
    private Ui() {}

    static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    static LinearLayout column(Activity a) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = dp(a, 26);
        col.setPadding(side, dp(a, 34), side, dp(a, 44));
        return col;
    }

    static void show(Activity a, LinearLayout col) {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.addView(col);
        a.setContentView(sv);
        sv.requestFocus(); // permite rodar a coroa para deslizar
    }

    static TextView text(Activity a, LinearLayout col, String s, int sp, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(a, 2), 0, dp(a, 2));
        col.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    static Button button(Activity a, LinearLayout col, String s, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 4);
        col.addView(b, lp);
        return b;
    }

    static LinearLayout row(Activity a, LinearLayout col) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        col.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    static Button rowButton(Activity a, LinearLayout row, String s, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setOnClickListener(l);
        row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }
}
