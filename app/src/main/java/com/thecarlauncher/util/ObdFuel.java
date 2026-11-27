package com.thecarlauncher.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Trip computer from OBD for the DS4 1.6 e-HDi: instant consumption, fuel used, and averages over
 * the current trip, the last 100 km and the last 1000 km (kept across restarts).
 *
 * Fuel flow: PID 5E (engine fuel rate, L/h) when the ECU gives it; otherwise from the air mass
 * (PID 10, g/s): a diesel runs lean, the air/fuel ratio falls from ~55:1 at idle to ~19:1 at full
 * load, so fuel g/s = MAF / AFR(load), and 835 g per litre of diesel.
 * Speed: PID 0D, else GPS.
 */
public final class ObdFuel {
    private static final float DIESEL_G_PER_L = 835f;
    private static final int RING = 1000;
    private static ObdFuel sInstance;

    private final SharedPreferences p;
    private final float[] perKm = new float[RING];   // litres used in each of the last 1000 km
    private int head, count;
    private float kmAcc, fuelAcc;                      // the km being filled now
    private double tripKm, tripL;
    private long lastAt, lastMoveAt = System.currentTimeMillis();
    private volatile float lph = -1, instL100 = -1, speedNow = -1;
    private volatile long fuelAt;
    private int sinceSave;
    // Calibration: the estimate (MAF / air-fuel model) times k. k is learnt from the real tank:
    // the Citroën BSI's measured litres while driving, and the litres entered at a full fill-up.
    private float k = 1f;
    private double rawSinceFill;            // estimated litres (before k) since the last full fill-up
    private double rawSinceAnchor = 0;      // … since the BSI level anchor
    private float anchorL = -1, levelL = -1;
    private int kLearnt;

    public static synchronized ObdFuel get(Context c) {
        if (sInstance == null) sInstance = new ObdFuel(c.getApplicationContext());
        return sInstance;
    }

    private ObdFuel(Context c) {
        p = c.getSharedPreferences("obdfuel", Context.MODE_PRIVATE);
        try {
            String r = p.getString("ring", "");
            if (!r.isEmpty()) for (String v : r.split(",")) { perKm[head] = Float.parseFloat(v); head = (head + 1) % RING; count = Math.min(RING, count + 1); }
        } catch (Exception ignored) { }
        k = p.getFloat("k", 1f);
        kLearnt = p.getInt("kN", 0);
        rawSinceFill = p.getFloat("sinceFill", 0f);
    }

    private static float afr(float loadPct) {
        float l = Float.isNaN(loadPct) ? 30 : Math.max(0, Math.min(100, loadPct)) / 100f;
        return 55f - 36f * l;   // 55:1 idle … 19:1 full load
    }

    /** Called about once a second by ObdHub while the adapter answers. */
    public synchronized void tick(ObdHub h, float gpsKmh) {
        long now = System.currentTimeMillis();
        float dt = lastAt == 0 ? 0 : Math.min(3f, (now - lastAt) / 1000f);
        lastAt = now;
        float rpm = h.rpm();
        float rate = h.value("lph");
        if (Float.isNaN(rate) || rate <= 0) {
            float maf = h.value("maf");
            rate = Float.isNaN(maf) || rpm <= 0 ? (rpm > 0 ? 0.55f : 0f) : maf / afr(h.value("load")) * 3600f / DIESEL_G_PER_L;
        }
        if (rpm <= 0) rate = 0;   // engine off (Stop & Start): no fuel
        float raw = rate;
        if (Float.isNaN(h.value("lph")) || h.value("lph") <= 0) rate = raw * k;   // the ECU's own fuel rate needs no correction
        float v = h.speed();
        if (v < 0) v = gpsKmh;
        if (v < 0) v = 0;
        lph = rate; speedNow = v; fuelAt = now;
        instL100 = v > 3 ? rate / v * 100f : -1;
        if (dt <= 0) return;
        // a new trip after 10 minutes parked
        if (v > 1 || rpm > 0) { if (now - lastMoveAt > 10 * 60000) { tripKm = 0; tripL = 0; } lastMoveAt = now; }
        float dKm = v * dt / 3600f, dL = rate * dt / 3600f;
        rawSinceFill += raw * dt / 3600f;
        rawSinceAnchor += raw * dt / 3600f;
        learnFromTank(h.value("psaFuelL"));
        tripKm += dKm; tripL += dL;
        kmAcc += dKm; fuelAcc += dL;
        if (kmAcc >= 1f) {
            perKm[head] = fuelAcc / kmAcc; head = (head + 1) % RING; count = Math.min(RING, count + 1);
            kmAcc = 0; fuelAcc = 0;
            if (++sinceSave >= 2) save();
        }
    }

    /**
     * The BSI's measured litres, smoothed. After 8 L used (measured) against at least 3 L estimated,
     * the ratio moves k a third of the way; a rising level (fill-up) starts again.
     */
    private void learnFromTank(float bsiL) {
        if (Float.isNaN(bsiL) || bsiL <= 0 || bsiL > 120) return;
        levelL = levelL < 0 ? bsiL : levelL * 0.97f + bsiL * 0.03f;
        if (anchorL < 0 || levelL > anchorL + 3) { anchorL = levelL; rawSinceAnchor = 0; return; }
        float used = anchorL - levelL;
        if (used >= 8 && rawSinceAnchor >= 3) {
            float obs = (float) (used / rawSinceAnchor);
            if (obs > 0.5f && obs < 2f) { k = k * (2f / 3f) + obs / 3f; kLearnt++; }
            anchorL = levelL; rawSinceAnchor = 0;
            p.edit().putFloat("k", k).putInt("kN", kLearnt).apply();
        }
    }

    /**
     * Full tank: the litres it took. Compared with what was estimated since the previous full tank
     * (at least 10 L), k moves half of the way. Returns the new k, or -1 if it was the first fill.
     */
    public synchronized float fillUp(float litres) {
        float out = -1;
        if (litres > 3 && rawSinceFill >= 10) {
            float obs = (float) (litres / rawSinceFill);
            if (obs > 0.5f && obs < 2f) { k = k * 0.5f + obs * 0.5f; kLearnt++; out = k; }
        }
        rawSinceFill = 0;
        p.edit().putFloat("k", k).putInt("kN", kLearnt).putFloat("sinceFill", 0f).putLong("fillAt", System.currentTimeMillis()).apply();
        return out;
    }

    private void save() {
        sinceSave = 0;
        p.edit().putFloat("sinceFill", (float) rawSinceFill).apply();
        StringBuilder sb = new StringBuilder();
        int start = (head - count + RING) % RING;
        for (int i = 0; i < count; i++) { if (i > 0) sb.append(','); sb.append(Math.round(perKm[(start + i) % RING] * 1000) / 1000f); }
        p.edit().putString("ring", sb.toString()).apply();
    }

    /** L/100 km over the last n km (0 if fewer than min km recorded). */
    public synchronized float avg(int n, int min) {
        int k = Math.min(n, count);
        if (k < min) return 0;
        float s = 0;
        for (int i = 1; i <= k; i++) s += perKm[(head - i + RING) % RING];
        return s / k * 100f;
    }

    public boolean fresh() { return System.currentTimeMillis() - fuelAt < 5000; }

    public synchronized JSONObject json() {
        JSONObject o = new JSONObject();
        try {
            if (fresh()) {
                o.put("lph", Math.round(lph * 10) / 10f);
                if (instL100 >= 0) o.put("inst", Math.round(Math.min(instL100, 50) * 10) / 10f);
            }
            if (tripKm >= .5) o.put("tripKm", Math.round(tripKm * 10) / 10f).put("tripL", Math.round(tripL * 100) / 100f).put("tripAvg", Math.round(tripL / tripKm * 1000) / 10f);
            float a100 = avg(100, 5), a1000 = avg(1000, 50);
            if (a100 > 0) o.put("avg100", Math.round(a100 * 10) / 10f);
            if (a1000 > 0) o.put("avg1000", Math.round(a1000 * 10) / 10f);
            o.put("kmLogged", count);
            o.put("fuelK", Math.round(k * 1000) / 1000f).put("fuelKn", kLearnt).put("sinceFill", Math.round(rawSinceFill * k * 10) / 10f);
        } catch (Exception ignored) { }
        return o;
    }

    public synchronized void resetTrip() { tripKm = 0; tripL = 0; }

    // ---- Range set from the car's own gauge ("320 km" on the cluster): OBD cannot read the BSI's range on
    // this car, so the owner types it once; litres burnt since then (MAF estimate × k) count it down.
    public synchronized void syncRange(double km) {
        float a = avg(1000, 50); if (a <= 0) a = avg(100, 5); if (a <= 2 || a > 20) a = 5.6f;
        p.edit().putFloat("syncL", (float) (km * a / 100.0)).putFloat("syncRaw", (float) rawSinceFill).putFloat("syncAvg", a)
                .putLong("syncAt", System.currentTimeMillis()).apply();
    }

    /** Litres left counted down from the synced range, or -1. */
    public synchronized double syncedFuelLeft() {
        float l = p.getFloat("syncL", -1f), r0 = p.getFloat("syncRaw", 0f);
        if (l < 0 || rawSinceFill + 0.01 < r0) return -1;   // a full fill-up since: the tank logic takes over
        return Math.max(0, l - (rawSinceFill - r0) * k);
    }

    public synchronized float syncAvg() { return p.getFloat("syncAvg", 5.6f); }
}
