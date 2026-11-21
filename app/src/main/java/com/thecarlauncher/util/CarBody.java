package com.thecarlauncher.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * The car's body state (doors, boot, bonnet, lights, indicators) for the 3D car. The CAN box
 * frames come raw from the CataraPatara CAN bridge; which bit means what differs per box and car,
 * so the driver teaches it once: "Öğren" snapshots the frames, he opens the driver's door, taps
 * "Sol ön kapı" and the bit that changed is remembered. Indicators blink: a bit that toggled
 * within the last 1.2 s counts as on. Read-only: nothing is ever sent to the car.
 */
public final class CarBody {
    public static final String[] FIELDS = {"doorFL", "doorFR", "doorRL", "doorRR", "trunk", "hood", "park", "low", "high", "turnL", "turnR", "hazard"};
    private static final String P_MAP = "body_map";

    private static final Map<Integer, int[]> raw = new HashMap<>();
    private static final Map<Integer, Long> rawAt = new HashMap<>();
    /** last time each bit flipped: key = cmd<<16 | byte<<8 | bit */
    private static final Map<Integer, Long> flipAt = new HashMap<>();
    private static Map<Integer, int[]> base;
    private static final java.util.Set<Integer> noisy = new java.util.HashSet<>();
    private static boolean registered;
    private static Context app;

    private CarBody() {}

    public static synchronized void start(Context c) {
        if (registered) return;
        registered = true;
        app = c.getApplicationContext();
        BroadcastReceiver r = new BroadcastReceiver() {
            public void onReceive(Context ctx, Intent i) {
                int cmd = i.getIntExtra("cmd", -1);
                String hex = i.getStringExtra("hex");
                if (cmd < 0 || hex == null) return;
                String[] p = hex.trim().split("\\s+");
                int[] b = new int[p.length];
                for (int k = 0; k < p.length; k++) try { b[k] = Integer.parseInt(p[k], 16); } catch (Exception e) { b[k] = 0; }
                synchronized (CarBody.class) {
                    int[] old = raw.get(cmd);
                    long now = System.currentTimeMillis();
                    if (old != null) for (int k = 3; k < Math.min(old.length, b.length) - 1; k++) {
                        int d = old[k] ^ b[k];
                        for (int bit = 0; bit < 8; bit++) if ((d & (1 << bit)) != 0) flipAt.put((cmd << 16) | (k << 8) | bit, now);
                    }
                    raw.put(cmd, b);
                    rawAt.put(cmd, now);
                }
            }
        };
        IntentFilter f = new IntentFilter("com.thecarlauncher.CANRAW");
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED); else app.registerReceiver(r, f);
    }

    // ---- learning

    public static synchronized void learnStart() {
        base = new HashMap<>();
        for (Map.Entry<Integer, int[]> e : raw.entrySet()) base.put(e.getKey(), e.getValue().clone());
        // bits that keep changing by themselves (counters) are never the door
        noisy.clear();
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Long> e : flipAt.entrySet()) if (now - e.getValue() < 3000) noisy.add(e.getKey());
    }

    /** The bit that changed since learnStart() becomes field; returns a description or an error. */
    public static synchronized String learn(String field) {
        if (base == null) return "Önce 'Öğren'e bas";
        int bestCmd = -1, bestByte = -1, bestBit = -1, bestVal = 0;
        boolean blink = field.startsWith("turn") || field.equals("hazard");
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, int[]> e : raw.entrySet()) {
            int cmd = e.getKey();
            if (cmd == 0x33 || cmd == 0x36) continue;          // trip computer / outside temperature
            int[] a = base.get(cmd), b = e.getValue();
            if (a == null) continue;
            for (int k = 3; k < Math.min(a.length, b.length) - 1; k++) {
                int d = a[k] ^ b[k];
                for (int bit = 0; bit < 8; bit++) {
                    int key = (cmd << 16) | (k << 8) | bit;
                    Long fl = flipAt.get(key);
                    boolean changed = (d & (1 << bit)) != 0 || (blink && fl != null && now - fl < 1500);
                    if (!changed || (!blink && noisy.contains(key))) continue;
                    bestCmd = cmd; bestByte = k; bestBit = bit; bestVal = (b[k] >> bit) & 1;
                    break;
                }
                if (bestCmd >= 0) break;
            }
            if (bestCmd >= 0) break;
        }
        if (bestCmd < 0) return "Değişen bir bit bulunamadı (CAN kutusu bunu göndermiyor olabilir)";
        try {
            JSONObject m = map();
            m.put(field, new JSONArray().put(bestCmd).put(bestByte).put(bestBit).put(blink ? -1 : bestVal));
            Prefs.put(app, P_MAP, m.toString());
        } catch (Exception ignored) {
        }
        return String.format(java.util.Locale.US, "Öğrenildi: kod %02X, bayt %d, bit %d", bestCmd, bestByte, bestBit);
    }

    public static synchronized void forget() { Prefs.put(app, P_MAP, "{}"); }

    private static JSONObject map() {
        try { return new JSONObject(Prefs.str(app, P_MAP, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    // ---- state for the screens

    public static synchronized JSONObject json() {
        JSONObject o = new JSONObject();
        try {
            if (app == null) return o;
            JSONObject m = map();
            long now = System.currentTimeMillis();
            for (String f : FIELDS) {
                JSONArray d = m.optJSONArray(f);
                if (d == null) continue;
                int cmd = d.getInt(0), k = d.getInt(1), bit = d.getInt(2), on = d.getInt(3);
                int[] b = raw.get(cmd);
                Long at = rawAt.get(cmd);
                if (b == null || at == null || now - at > 15000 || k >= b.length) continue;
                boolean v;
                if (on < 0) { Long fl = flipAt.get((cmd << 16) | (k << 8) | bit); v = fl != null && now - fl < 1200; }
                else v = ((b[k] >> bit) & 1) == on;
                o.put(f, v);
            }
            o.put("learned", m.length());
            o.put("frames", raw.size());
        } catch (Exception ignored) {
        }
        return o;
    }
}
