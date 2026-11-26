package com.thecarlauncher.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Motor sesi: the engine voice of MYDS. Two ways to make it, chosen on motor.html:
 *  - "live" (default): Engine_Sim (MIT) simulating the engine in a hidden WebView ({@link MotorLive});
 *    the richest sound, with pops, turbo and every transition.
 *  - "loop": pre-recorded Engine_Sim loops mixed natively ({@link MotorLoops}); almost no CPU.
 * Settings live here (prefs "motor" / "cfg"); both voices read them.
 */
public final class MotorSesi {
    private static MotorSesi sInstance;

    private final SharedPreferences p;
    private final MotorLive live;
    private final MotorLoops loops;

    public static synchronized MotorSesi get(Context c) {
        if (sInstance == null) sInstance = new MotorSesi(c.getApplicationContext());
        return sInstance;
    }

    private MotorSesi(Context c) {
        p = c.getSharedPreferences("motor", Context.MODE_PRIVATE);
        live = new MotorLive(c);
        loops = new MotorLoops(c);
    }

    /** Saved settings as JSON (motor.html reads them to draw itself). */
    public String settings() {
        return p.getString("cfg", "{\"on\":false,\"mode\":\"loop\",\"engine\":\"i4\",\"vehicle\":\"supercar\",\"follow\":true,"
                + "\"carIdle\":800,\"carMax\":4500,\"sr\":16000,\"lat\":0.35,\"volume\":0.8,\"interior\":true,\"pops\":0.6,\"lite\":true}");
    }

    private JSONObject cfg() { try { return new JSONObject(settings()); } catch (Exception e) { return new JSONObject(); } }

    public boolean on() { return cfg().optBoolean("on"); }

    private boolean loopMode() { return "loop".equals(cfg().optString("mode", "live")); }

    public String state() {
        String s = loopMode() ? loops.state() : live.state();
        try { return new JSONObject(s).put("mode", loopMode() ? "loop" : "live").toString(); } catch (Exception e) { return s; }
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
