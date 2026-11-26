package com.thecarlauncher.util;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Warnings worked out from the OBD values, for every theme (common.js shows them as a strip).
 * Only what the car tells: when a value is not there, its warning simply never comes.
 *
 *  - DPF cleaning (diesel): filter temperature (PID 7C) above 450 °C, or — without it — idle that
 *    stays raised with high load for 20 s while standing: "don't switch off now".
 *  - Charging: engine running and voltage under 12.8 V for 20 s, or over 15.2 V.
 *  - Battery: charge under 60 % (BSI), or under 12.2 V at rest before start.
 *  - Cold engine: coolant under 60 °C and over 3000 rpm.
 *  - Engine lamp (MIL) on; oil level under 25 % (BSI); a tyre under 1.8 bar or 0.3 bar below the others.
 * Each warning has a level: 2 = act now, 1 = good to know.
 */
public final class CarAlerts {
    private static final CarAlerts I = new CarAlerts();
    public static CarAlerts get() { return I; }

    private long dpfSince, lowVSince, idleBase;
    private float idleRpm = -1;
    private long lastRun;

    private CarAlerts() { }

    private static float v(ObdHub h, String k) { return h == null ? Float.NaN : h.value(k); }

    /** Current warnings, most important first: [{k, t, lvl}]. */
    public synchronized JSONArray list() {
        JSONArray out = new JSONArray();
        ObdHub h = ObdHub.get();
        if (h == null || !h.connected()) { dpfSince = lowVSince = 0; return out; }
        long now = System.currentTimeMillis();
        float rpm = h.rpm(), spd = h.speed();
        boolean running = rpm > 450;
        try {
            // --- engine lamp
            if (v(h, "mil") >= 1) add(out, "mil", "Motor arıza lambası yanıyor — arıza kodlarına bak", 2);

            // --- DPF cleaning
            float dpfT = v(h, "dpfT"), load = v(h, "load");
            boolean regen = false;
            if (!Float.isNaN(dpfT)) regen = dpfT > 450;
            else if (running && spd >= 0 && spd < 2) {
                // learn the normal warm idle, then look for a raised idle with load
                float cool = v(h, "cool");
                if (!Float.isNaN(cool) && cool > 75 && !Float.isNaN(load) && load < 25) idleRpm = idleRpm < 0 ? rpm : idleRpm * 0.98f + rpm * 0.02f;
                boolean raised = idleRpm > 0 && rpm > idleRpm + 150 && !Float.isNaN(load) && load > 30;
                if (raised) { if (dpfSince == 0) dpfSince = now; } else dpfSince = 0;
                regen = dpfSince > 0 && now - dpfSince > 20000;
            } else dpfSince = 0;
            if (regen) add(out, "dpf", "Partikül filtresi temizleniyor — motoru şimdi kapatma", 2);

            // --- charging / battery
            float volt = v(h, "psaBatV");
            if (Float.isNaN(volt)) volt = v(h, "batt");
            if (Float.isNaN(volt)) volt = v(h, "volt");
            if (!Float.isNaN(volt) && running) {
                if (volt < 12.8f) { if (lowVSince == 0) lowVSince = now; } else lowVSince = 0;
                if (lowVSince > 0 && now - lowVSince > 20000) add(out, "charge", String.format(java.util.Locale.ROOT, "Şarj düşük (%.1f V) — alternatör/kayış kontrol", volt), 2);
                if (volt > 15.2f) add(out, "chargeHi", String.format(java.util.Locale.ROOT, "Şarj voltajı yüksek (%.1f V)", volt), 2);
            } else lowVSince = 0;
            if (!Float.isNaN(volt) && !running && volt < 12.2f && volt > 5) add(out, "batRest", String.format(java.util.Locale.ROOT, "Akü zayıf (%.1f V)", volt), 1);
            float soc = v(h, "psaBatSoc");
            if (!Float.isNaN(soc) && soc > 0 && soc < 60) add(out, "batSoc", "Akü şarjı %" + Math.round(soc), soc < 45 ? 2 : 1);

            // --- cold engine
            float cool = v(h, "cool");
            if (!Float.isNaN(cool) && cool < 60 && rpm > 3000) add(out, "cold", "Motor soğuk (" + Math.round(cool) + " °C) — devri 3000'in altında tut", 1);

            // --- oil level (BSI)
            float oil = v(h, "psaOilLvl");
            if (!Float.isNaN(oil) && oil > 0 && oil < 25) add(out, "oil", "Yağ seviyesi düşük (%" + Math.round(oil) + ")", 2);

            // --- tyres
            String[] ks = {"tyreFL", "tyreFR", "tyreRL", "tyreRR"}, nm = {"sol ön", "sağ ön", "sol arka", "sağ arka"};
            float[] p = new float[4];
            int have = 0; float sum = 0;
            for (int i = 0; i < 4; i++) { p[i] = v(h, ks[i]); if (!Float.isNaN(p[i]) && p[i] > 0.5f) { have++; sum += p[i]; } }
            for (int i = 0; i < 4; i++) {
                if (Float.isNaN(p[i]) || p[i] <= 0.5f) continue;
                float others = have > 1 ? (sum - p[i]) / (have - 1) : p[i];
                if (p[i] < 1.8f || others - p[i] > 0.3f)
                    add(out, "tyre" + i, String.format(java.util.Locale.ROOT, "Lastik basıncı düşük: %s %.1f bar", nm[i], p[i]), p[i] < 1.6f ? 2 : 1);
            }
        } catch (Exception ignored) { }
        lastRun = now;
        // most important first
        JSONArray sorted = new JSONArray();
        for (int lvl = 2; lvl >= 1; lvl--) for (int i = 0; i < out.length(); i++) if (out.optJSONObject(i).optInt("lvl") == lvl) sorted.put(out.optJSONObject(i));
        return sorted;
    }

    private static void add(JSONArray a, String k, String t, int lvl) throws Exception {
        a.put(new JSONObject().put("k", k).put("t", t).put("lvl", lvl));
    }
}
