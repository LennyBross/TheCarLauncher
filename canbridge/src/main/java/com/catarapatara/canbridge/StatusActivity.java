package com.catarapatara.canbridge;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;

/** Shows what the bridge sees: connection, decoded values and every frame type with its last bytes. */
public final class StatusActivity extends Activity {
    private TextView text;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() { paint(); h.postDelayed(this, 500); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        BridgeService.start(this);
        text = new TextView(this);
        text.setTextColor(0xFFF3F5F7);
        text.setTextSize(17);
        text.setTypeface(Typeface.MONOSPACE);
        text.setPadding(40, 30, 40, 30);
        text.setTextIsSelectable(true);
        ScrollView s = new ScrollView(this);
        s.setBackgroundColor(0xFF0B0E13);
        s.addView(text);
        setContentView(s);
    }

    @Override
    protected void onResume() { super.onResume(); h.post(tick); }

    @Override
    protected void onPause() { h.removeCallbacks(tick); super.onPause(); }

    private void paint() {
        StringBuilder sb = new StringBuilder();
        sb.append("CataraPatara CAN köprüsü\n\n");
        sb.append(BridgeService.status).append('\n');
        long age = System.currentTimeMillis() - BridgeService.lastFrameAt;
        sb.append(BridgeService.lastFrameAt == 0 ? "Henüz çerçeve yok (kontak açık mı?)" : "Son çerçeve: " + age / 1000 + " sn önce").append("\n\n");
        sb.append(String.format(Locale.US, "Anlık yakıt : %s L/100 km\n", BridgeService.instant < 0 ? "—" : String.format(Locale.US, "%.1f", BridgeService.instant)));
        sb.append(String.format(Locale.US, "Menzil      : %s km\n", BridgeService.range < 0 ? "—" : String.valueOf(Math.round(BridgeService.range))));
        sb.append(String.format(Locale.US, "Dış sıcaklık: %s °C\n\n", Float.isNaN(BridgeService.outdoor) ? "—" : String.valueOf(Math.round(BridgeService.outdoor))));
        sb.append("Gelen çerçeveler (tür ×sayı  son bayt):\n");
        synchronized (BridgeService.frames) {
            for (Map.Entry<Integer, String> e : BridgeService.frames.entrySet()) {
                sb.append(String.format(Locale.US, "0x%02X ×%-5d %s\n", e.getKey(), BridgeService.counts.get(e.getKey()), e.getValue()));
            }
        }
        text.setText(sb);
    }
}
