package io.github.jacobhortencio.watchbridge.phone;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Gráfico simples: linha (valores no tempo) ou barras (valores por dia). */
class ChartView extends View {
    static final int LINE = 0, BARS = 1;

    private final int mode;
    private final List<float[]> pts = new ArrayList<>(); // {x, y}
    private final List<String> labels = new ArrayList<>();
    private float minY, maxY, minX, maxX;
    private String empty = "Sem dados";
    private String unit = "";
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    ChartView(Context c, int mode, int color) {
        super(c);
        this.mode = mode;
        d = c.getResources().getDisplayMetrics().density;
        boolean dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        line.setColor(color);
        line.setStrokeWidth(2.5f * d);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(color);
        text.setColor(dark ? 0xFFB0B8C4 : 0xFF5F6B7A);
        text.setTextSize(11 * d);
        grid.setColor(dark ? 0x33FFFFFF : 0x22000000);
        grid.setStrokeWidth(1 * d);
        setMinimumHeight(Math.round(160 * d));
    }

    void setEmpty(String s) {
        empty = s;
    }

    void setUnit(String u) {
        unit = u;
    }

    /** Linha: x = tempo (ms), y = valor. */
    void setLine(List<long[]> data, long fromX, long toX) {
        pts.clear();
        labels.clear();
        minX = fromX;
        maxX = toX;
        minY = Float.MAX_VALUE;
        maxY = -Float.MAX_VALUE;
        for (long[] r : data) {
            if (r[0] < fromX || r[0] > toX) continue;
            pts.add(new float[]{r[0], r[1]});
            minY = Math.min(minY, r[1]);
            maxY = Math.max(maxY, r[1]);
        }
        if (!pts.isEmpty()) {
            float pad = Math.max(5, (maxY - minY) * 0.15f);
            minY = Math.max(0, minY - pad);
            maxY = maxY + pad;
        }
        invalidate();
    }

    /** Barras: um valor por etiqueta. */
    void setBars(List<String> names, List<Float> values) {
        pts.clear();
        labels.clear();
        labels.addAll(names);
        minY = 0;
        maxY = 1;
        for (int i = 0; i < values.size(); i++) {
            pts.add(new float[]{i, values.get(i)});
            maxY = Math.max(maxY, values.get(i) * 1.15f);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float left = 34 * d, bottom = h - 20 * d, top = 10 * d, right = w - 6 * d;
        if (pts.isEmpty() || (mode == BARS && maxY <= 1)) {
            c.drawText(empty, left, h / 2, text);
            return;
        }
        // grelha com 3 linhas e valores
        for (int i = 0; i <= 2; i++) {
            float y = bottom - (bottom - top) * i / 2f;
            c.drawLine(left, y, right, y, grid);
            float v = minY + (maxY - minY) * i / 2f;
            c.drawText(fmt(v), 2 * d, y + 4 * d, text);
        }
        if (mode == LINE) {
            Path p = new Path();
            for (int i = 0; i < pts.size(); i++) {
                float x = left + (right - left) * (pts.get(i)[0] - minX) / Math.max(1, maxX - minX);
                float y = bottom - (bottom - top) * (pts.get(i)[1] - minY) / Math.max(1, maxY - minY);
                if (i == 0) p.moveTo(x, y);
                else p.lineTo(x, y);
                c.drawCircle(x, y, 2.5f * d, fill);
            }
            c.drawPath(p, line);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
            for (int i = 0; i <= 3; i++) {
                long t = (long) (minX + (maxX - minX) * i / 3f);
                float x = left + (right - left) * i / 3f;
                String s = f.format(new java.util.Date(t));
                c.drawText(s, Math.min(x, right - text.measureText(s)), h - 4 * d, text);
            }
        } else {
            int n = pts.size();
            float slot = (right - left) / n;
            for (int i = 0; i < n; i++) {
                float v = pts.get(i)[1];
                float x0 = left + slot * i + slot * 0.18f, x1 = left + slot * (i + 1) - slot * 0.18f;
                float y = bottom - (bottom - top) * v / maxY;
                c.drawRoundRect(x0, y, x1, bottom, 3 * d, 3 * d, fill);
                String lb = i < labels.size() ? labels.get(i) : "";
                c.drawText(lb, x0, h - 4 * d, text);
            }
        }
    }

    private String fmt(float v) {
        if (v >= 10000) return Math.round(v / 1000) + "k";
        return Math.round(v) + unit;
    }
}
