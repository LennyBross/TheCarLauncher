package com.thecarlauncher.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

import com.catarapatara.Obd;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The OBD-II adapter (Bluetooth ELM327 in the OBD socket), read by MYDS itself and always on:
 * engine data for the screens (coolant, boost, rail pressure, battery, fault codes…) and rpm /
 * pedal / speed for the CataraPatara engine sound. Only one program may talk to the adapter, so
 * CataraPatara no longer opens it; its OBD settings (prefs "cp") are still the ones used here.
 * Read-only: mode 01 / 03 requests, nothing is written to the car.
 */
public final class ObdHub implements Obd.Listener {
    private static ObdHub sInstance;

    public static synchronized void start(Context c) {
        if (sInstance == null) sInstance = new ObdHub(c.getApplicationContext());
        sInstance.apply();
    }

    public static ObdHub get() { return sInstance; }

    private final Context app;
    private Obd obd;
    private String applied = "";
    private final Map<String, Float> val = new ConcurrentHashMap<>();
    private final Map<String, Long> at = new ConcurrentHashMap<>();
    private volatile String status = "", dtc = "";
    private volatile boolean ok;
    private volatile float rpm = -1, speed = -1, pedal = -1;
    private volatile long rpmAt, speedAt, pedalAt, lastSendAt;

    private ObdHub(Context c) {
        app = c;
        IntentFilter f = new IntentFilter("com.thecarlauncher.OBD_APPLY");
        BroadcastReceiver r = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { apply(); }
        };
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED); else app.registerReceiver(r, f);
        // A newly paired adapter is picked up without a restart.
        new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try { Thread.sleep(15000); } catch (InterruptedException e) { return; }
                    if (obd == null) apply();
                }
            }
        }, "obd-watch").start();
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences cp() { return app.getSharedPreferences("cp", Context.MODE_MULTI_PROCESS); }

    synchronized void apply() {
        SharedPreferences p = cp();
        String mode = p.getString("obd", "auto");
        if ("auto".equals(mode)) mode = Obd.pairedAdapter() != null ? Obd.BT : "off";
        String key = mode + "|" + p.getString("obdTarget", "") + "|" + p.getString("obdProto", Obd.STD);
        if (key.equals(applied) && (obd != null || "off".equals(mode))) return;
        applied = key;
        if (obd != null) obd.stop();
        obd = null;
        ok = false;
        status = "off".equals(mode) ? "" : "OBD: hazırlanıyor…";
        if (Obd.WIFI.equals(mode) || Obd.BT.equals(mode)) {
            obd = new Obd(this);
            obd.start(mode, p.getString("obdTarget", ""), p.getString("obdProto", Obd.STD));
        }
    }

    private volatile long phoneAt;

    public void readDtc() {
        Obd o = obd;
        if (o != null) o.readDtc();
        else if (System.currentTimeMillis() - phoneAt < 10000) { try { PhoneLink.send(new JSONObject().put("t", "dtc")); } catch (Exception ignored) { } }
    }

    /** Values from the ELM327 that the phone (MYDS Telefon) reads and passes on. */
    public void fromPhone(JSONObject o) {
        phoneAt = System.currentTimeMillis();
        if (o.has("st")) { onStatus(o.optString("st") + " · telefon", o.optBoolean("ok")); return; }
        if (o.has("dtc")) { onDtc(o.optString("dtc")); return; }
        String k = o.optString("k");
        float v = (float) o.optDouble("v", Double.NaN);
        if (Float.isNaN(v)) return;
        if (!ok) { ok = true; status = "OBD · telefon"; }
        switch (k) {
            case "rpm": onRpm(v); break;
            case "pedal": onPedal(v); break;
            case "speed": onSpeed(v); break;
            default: onPid(k, v);
        }
    }

    // ---- from the adapter ----

    @Override public void onRpm(float r) { rpm = r; rpmAt = System.currentTimeMillis(); send(false); }
    @Override public void onPedal(float p) { pedal = p; pedalAt = System.currentTimeMillis(); }
    @Override public void onSpeed(float kmh) { speed = kmh; speedAt = System.currentTimeMillis(); }
    @Override public void onStatus(String t, boolean good) { status = t; ok = good; send(true); }
    @Override public void onPid(String k, float v) { val.put(k, v); at.put(k, System.currentTimeMillis()); }
    @Override public void onDtc(String codes) { dtc = codes == null ? "" : codes; }

    /** rpm / pedal / speed to the engine sound (same broadcast as the CAN bridge uses). */
    private void send(boolean statusOnly) {
        long now = System.currentTimeMillis();
        try {
            Intent i = new Intent("com.catarapatara.DATA").setPackage(app.getPackageName()).putExtra("obdStatus", status);
            if (!statusOnly) {
                if (now - lastSendAt < 60) return;
                lastSendAt = now;
                i.putExtra("rpm", rpm).putExtra("rpmMax", (float) cp().getInt("carMax", 4500));
                if (now - pedalAt < 2000) i.putExtra("pedal", pedal);
                if (now - speedAt < 3000) i.putExtra("speed", speed);
            }
            app.sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    // ---- for the screens ----

    public boolean connected() { return ok && System.currentTimeMillis() - rpmAt < 4000; }

    public float rpm() { return System.currentTimeMillis() - rpmAt < 3000 ? rpm : -1; }

    public float speed() { return System.currentTimeMillis() - speedAt < 4000 ? speed : -1; }

    /** A value if it came in the last minute, else NaN. */
    public float value(String k) {
        Long t = at.get(k);
        Float v = val.get(k);
        return t == null || v == null || System.currentTimeMillis() - t > 60000 ? Float.NaN : v;
    }

    public JSONObject json() {
        JSONObject o = new JSONObject();
        try {
            o.put("on", obd != null || System.currentTimeMillis() - phoneAt < 10000).put("ok", connected()).put("st", status);
            JSONObject v = new JSONObject();
            float r = rpm();
            if (r >= 0) v.put("rpm", Math.round(r));
            float s = speed();
            if (s >= 0) v.put("speed", Math.round(s));
            if (System.currentTimeMillis() - pedalAt < 3000) v.put("pedal", Math.round(pedal * 100));
            for (String k : val.keySet()) {
                float f = value(k);
                if (!Float.isNaN(f)) v.put(k, Math.round(f * 10) / 10.0);
            }
            o.put("v", v);
            JSONArray d = new JSONArray();
            if (!dtc.isEmpty()) for (String c : dtc.split(",")) d.put(c);
            o.put("dtc", d);
        } catch (Exception ignored) {
        }
        return o;
    }
}
