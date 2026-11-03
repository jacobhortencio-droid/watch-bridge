package io.github.jacobhortencio.watchbridge.watch;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** Ecrã de navegação: seta da manobra, distância e rua, a partir do telemóvel. */
public class NavActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private ImageView arrow;
    private TextView title, text, sub;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout col = Ui.column(this);
        arrow = new ImageView(this);
        col.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
        title = Ui.text(this, col, "", 18, true);
        text = Ui.text(this, col, "", 13, false);
        sub = Ui.text(this, col, "", 11, false);
        Ui.show(this, col);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresh);
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            JSONObject m = Ongoing.nav;
            if (m == null) {
                title.setText("Sem navegação");
                text.setText("Inicie uma rota no Google Maps do telemóvel.");
                sub.setText("");
                arrow.setImageDrawable(null);
            } else {
                title.setText(m.optString("title"));
                text.setText(m.optString("text"));
                sub.setText(m.optString("sub"));
                Bitmap bmp = Ongoing.decode(m.optString("icon"));
                if (bmp != null) arrow.setImageBitmap(bmp);
            }
            h.postDelayed(this, 1000);
        }
    };
}
