package com.thecarlauncher.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Motor sesi: the engine voice of MYDS. Two ways to make it, chosen on motor.html:
 *  - "live": Engine_Sim (MIT) simulating the engine in a hidden WebView ({@link MotorLive}); the
 *    richest sound. Default on strong units (≥ 6 cores and ≥ 3 GB RAM, e.g. UIS7862 / TS10).
 *  - "loop": pre-recorded loops mixed natively ({@link MotorLoops}); almost no CPU. Default on weak
 *    units (AC8227L, where the live voice broke up).
 * Settings live here (prefs "motor" / "cfg"); both voices read them.
 */
public final class MotorSesi {
    private static MotorSesi sInstance;

    private final SharedPreferences p;
    private final MotorLive live;
    private final MotorLoops loops;
    private final boolean strong;

    public static synchronized MotorSesi get(Context c) {
        if (sInstance == null) sInstance = new MotorSesi(c.getApplicationContext());
        return sInstance;
    }

    private MotorSesi(Context c) {
        p = c.getSharedPreferences("motor", Context.MODE_PRIVATE);
        live = new MotorLive(c);
        loops = new MotorLoops(c);
        strong = strongUnit(c);
    }

    /** A unit that can run the live simulation without breaking up. */
    static boolean strongUnit(Context c) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return Runtime.getRuntime().availableProcessors() >= 6 && mi.totalMem >= 3L * 1024 * 1024 * 1024;
        } catch (Throwable t) { return false; }
    }

    public boolean strong() { return strong; }

    /** Saved settings as JSON (motor.html reads them to draw itself). */
    public String settings() {
        String def = strong
                ? "{\"on\":false,\"mode\":\"live\",\"engine\":\"v8flat\",\"vehicle\":\"supercar\",\"follow\":true,"
                  + "\"carIdle\":800,\"carMax\":4500,\"lat\":0.08,\"volume\":0.9,\"interior\":true,\"pops\":0.7,\"lite\":false}"
                : "{\"on\":false,\"mode\":\"loop\",\"engine\":\"v8flat\",\"follow\":true,"
                  + "\"carIdle\":800,\"carMax\":4500,\"volume\":0.9,\"pops\":0.7}";
        return p.getString("cfg", def);
    }

    private JSONObject cfg() { try { return new JSONObject(settings()); } catch (Exception e) { return new JSONObject(); } }

    public boolean on() { return cfg().optBoolean("on"); }

    private boolean loopMode() { return !"live".equals(cfg().optString("mode", strong ? "live" : "loop")); }

    public String state() {
        String s = loopMode() ? loops.state() : live.state();
        try { return new JSONObject(s).put("mode", loopMode() ? "loop" : "live").put("strong", strong).toString(); } catch (Exception e) { return s; }
    }

    /** Merge a partial settings object, save it, and hand it to both voices. */
    public void set(String partial) {
        try {
            JSONObject cur = cfg(), o = new JSONObject(partial);
            java.util.Iterator<String> k = o.keys();
            while (k.hasNext()) { String n = k.next(); cur.put(n, o.get(n)); }
            p.edit().putString("cfg", cur.toString()).commit();
        } catch (Exception ignored) { }
        live.sync();
        loops.sync();
    }

    public void toggle() { set("{\"on\":" + !on() + "}"); }

    /** Test pedal from motor.html (used when not following the car). */
    public void pedal(float thr, float brake) { if (loopMode()) loops.pedal(thr, brake); else live.pedal(thr, brake); }

    /** Called from KeepAlive: start the chosen voice if it is switched on. */
    public void start() { if (on()) { live.sync(); loops.sync(); } }
}
