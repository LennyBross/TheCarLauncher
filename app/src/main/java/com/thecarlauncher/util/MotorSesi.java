package com.thecarlauncher.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Motor sesi: the engine voice of MYDS — pre-recorded Engine_Sim (MIT) loops mixed natively
 * ({@link MotorLoops}); almost no CPU. The live simulation was removed: the head unit (AC8227L)
 * could not run it without breaking up. Settings are kept here (prefs "motor" / "cfg").
 */
public final class MotorSesi {
    private static MotorSesi sInstance;

    private final SharedPreferences p;
    private final MotorLoops loops;

    public static synchronized MotorSesi get(Context c) {
        if (sInstance == null) sInstance = new MotorSesi(c.getApplicationContext());
        return sInstance;
    }

    private MotorSesi(Context c) {
        p = c.getSharedPreferences("motor", Context.MODE_PRIVATE);
        loops = new MotorLoops(c);
    }

    /** Saved settings as JSON (motor.html reads them to draw itself). */
    public String settings() {
        return p.getString("cfg", "{\"on\":false,\"mode\":\"loop\",\"engine\":\"v8flat\",\"follow\":true,"
                + "\"carIdle\":800,\"carMax\":4500,\"volume\":0.9,\"pops\":0.7}");
    }

    private JSONObject cfg() { try { return new JSONObject(settings()); } catch (Exception e) { return new JSONObject(); } }

    public boolean on() { return cfg().optBoolean("on"); }

    public String state() { return loops.state(); }

    /** Merge a partial settings object, save it, and hand it to both voices. */
    public void set(String partial) {
        try {
            JSONObject cur = cfg(), o = new JSONObject(partial);
            java.util.Iterator<String> k = o.keys();
            while (k.hasNext()) { String n = k.next(); cur.put(n, o.get(n)); }
            p.edit().putString("cfg", cur.toString()).commit();
        } catch (Exception ignored) { }
        loops.sync();
    }

    public void toggle() { set("{\"on\":" + !on() + "}"); }

    /** Test pedal from motor.html (used when not following the car). */
    public void pedal(float thr, float brake) { loops.pedal(thr, brake); }

    /** Called from KeepAlive: start the chosen voice if it is switched on. */
    public void start() { if (on()) loops.sync(); }
}
