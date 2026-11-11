package com.thecarlauncher.engine;

import java.util.Random;

/**
 * A small engine and exhaust synthesizer: no recordings, everything is built from the firing
 * order. Each cylinder firing sends a short pressure pulse into two exhaust "banks" (resonant
 * filters for the pipes), plus combustion noise, saturation for growl, and extras per engine
 * (turbo whistle and blow-off, supercharger whine, a cam change-over). Lifting off at high rpm
 * makes the exhaust crackle and pop, more so with a louder exhaust.
 *
 * Pure Java so it runs the same on the unit and on a desktop (demo renders).
 */
public final class EngineSynth {

    /** One engine character. */
    public static final class Profile {
        final String id, name, sub;
        final float[] fire;      // firing angles in the 720° cycle
        final int[] bank;        // 0 / 1 per firing: which exhaust bank gets the pulse
        final float[] amp;       // per-firing strength (uneven = burble)
        final float idle, redline;
        final float[] resL, resR, resG; // exhaust resonances (Hz) per bank and their gains
        final float q, bright, noise, drive;
        final float turbo, whine, cam; // turbo amount, supercharger whine amount, cam change-over rpm (0 = none)

        Profile(String id, String name, String sub, float[] fire, int[] bank, float[] amp, float idle, float redline,
                float[] resL, float[] resR, float[] resG, float q, float bright, float noise, float drive,
                float turbo, float whine, float cam) {
            this.id = id; this.name = name; this.sub = sub; this.fire = fire; this.bank = bank; this.amp = amp;
            this.idle = idle; this.redline = redline; this.resL = resL; this.resR = resR; this.resG = resG;
            this.q = q; this.bright = bright; this.noise = noise; this.drive = drive;
            this.turbo = turbo; this.whine = whine; this.cam = cam;
        }

        public String id() { return id; }
        public String name() { return name; }
        public String sub() { return sub; }
        public float idle() { return idle; }
        public float redline() { return redline; }
    }

    private static float[] even(int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = 720f * i / n;
        return a;
    }

    private static int[] alt(int n) {
        int[] b = new int[n];
        for (int i = 0; i < n; i++) b[i] = i % 2;
        return b;
    }

    private static float[] ones(int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = 1f;
        return a;
    }

    private static float[] f(float... v) { return v; }

    public static final Profile[] PROFILES = {
            // Cross-plane V8: the two banks fire unevenly (L R R L R L L R), hence the burble.
            new Profile("v8us", "Amerikan V8", "Çapraz krank · gurlayan", even(8), new int[]{0, 1, 1, 0, 1, 0, 0, 1},
                    f(1f, .78f, .74f, 1f, .8f, 1f, .95f, .76f), 720, 6600,
                    f(68, 210, 520), f(76, 236, 590), f(1f, .55f, .22f), 5.5f, .35f, .22f, 2.2f, 0, 0, 0),
            new Profile("v8sc", "Süperşarjlı V8", "Çapraz krank · kompresör ıslığı", even(8), new int[]{0, 1, 1, 0, 1, 0, 0, 1},
                    f(1f, .82f, .8f, 1f, .84f, 1f, .97f, .8f), 750, 6800,
                    f(80, 260, 640), f(88, 285, 700), f(1f, .6f, .3f), 6f, .45f, .25f, 2.4f, 0, 1f, 0),
            // Flat-plane V8: even, alternating banks; higher and raspier.
            new Profile("v8it", "İtalyan V8", "Düz krank · çığlık", even(8), alt(8), f(1f, .93f, 1f, .9f, 1f, .94f, 1f, .91f), 950, 9000,
                    f(140, 430, 1150), f(150, 465, 1240), f(.8f, .75f, .42f), 7f, .7f, .26f, 2.6f, 0, 0, 0),
            new Profile("v10", "V10 Yarış", "Atmosferik · 10.500 d/d", even(10), alt(10), ones(10), 1000, 10500,
                    f(160, 520, 1500), f(172, 560, 1620), f(.75f, .8f, .5f), 7.5f, .85f, .24f, 2.5f, 0, 0, 0),
            new Profile("v12", "İtalyan V12", "Atmosferik · ipeksi", even(12), alt(12), ones(12), 1000, 9200,
                    f(150, 470, 1350), f(158, 500, 1420), f(.7f, .85f, .55f), 8f, .9f, .2f, 2.1f, 0, 0, 0),
            // Flat six: each bank fires every 240°, a hollow metallic howl.
            new Profile("b6", "Alman Boxer 6", "Yatay altı · uluma", even(6), alt(6), f(1f, .9f, .97f, .88f, 1f, .9f), 900, 9000,
                    f(110, 340, 880), f(124, 372, 940), f(.85f, .9f, .45f), 9f, .65f, .22f, 2.3f, 0, 0, 0),
            new Profile("i6", "İngiliz Sıralı 6", "Pürüzsüz · sert egzoz", even(6), new int[]{0, 0, 0, 1, 1, 1}, ones(6), 800, 7200,
                    f(95, 300, 760), f(102, 320, 800), f(.9f, .7f, .35f), 7f, .5f, .2f, 2.2f, 0, 0, 0),
            new Profile("v6t", "Biturbo V6", "Turbo ıslığı · blow-off", even(6), alt(6), f(1f, .92f, .97f, .9f, 1f, .93f), 850, 7600,
                    f(100, 320, 900), f(108, 345, 960), f(.9f, .7f, .4f), 6.5f, .55f, .3f, 2.2f, 1f, 0, 0),
            new Profile("i5", "Ralli 5 Silindir", "Tek sayı · turbo", even(5), new int[]{0, 0, 0, 0, 0}, f(1f, .9f, .96f, .88f, .93f), 850, 7200,
                    f(105, 315, 840), f(105, 315, 840), f(.9f, .75f, .4f), 6.5f, .55f, .28f, 2.4f, .8f, 0, 0),
            // Inline four with a cam change-over: brighter and louder above 5 600 rpm.
            new Profile("i4v", "Japon VTEC 4", "8.800 d/d · kam geçişi", even(4), new int[]{0, 0, 0, 0}, f(1f, .95f, .98f, .93f), 850, 8800,
                    f(120, 380, 1050), f(120, 380, 1050), f(.8f, .7f, .35f), 6.5f, .5f, .26f, 2.0f, 0, 0, 5600),
    };

    public static Profile profile(String id) {
        for (Profile p : PROFILES) if (p.id.equals(id)) return p;
        return PROFILES[0];
    }

    /** Exhaust: 0 stock, 1 sport, 2 race (louder, more growl, pops and bangs). */
    private static final float[] EX_GAIN = {.6f, .82f, 1f}, EX_DRIVE = {.75f, 1f, 1.35f}, EX_POP = {.0f, .12f, .32f},
            EX_NOISE = {.8f, 1f, 1.3f};

    // ---- State -------------------------------------------------------------------------------------

    private final float sr;
    private final Random rnd = new Random(7);
    private Profile p = PROFILES[0];
    private int exhaust = 1;

    private double phase;              // 0..720
    private int nextFire;              // index of the next firing
    // Pulses in flight
    private final float[] pt = new float[8], pw = new float[8], pa = new float[8];
    private final int[] pb = new int[8];
    private int np;
    // Resonators: [bank][3] biquads (bandpass)
    private final Biquad[][] res = new Biquad[2][3];
    private final Biquad body = new Biquad(), popRes = new Biquad(), bovRes = new Biquad(), air = new Biquad();
    private float dcX, dcY, lp, lp2;
    private float envNoise;
    private float popEnv, popLevel;
    private float bovEnv;
    private float turboPh, whinePh, boost, starterPh;
    private float lastLoad;

    public EngineSynth(float sampleRate) {
        sr = sampleRate;
        for (int b = 0; b < 2; b++) for (int k = 0; k < 3; k++) res[b][k] = new Biquad();
        setProfile(p, exhaust);
    }

    public void setProfile(Profile prof, int ex) {
        p = prof;
        exhaust = Math.max(0, Math.min(2, ex));
        for (int k = 0; k < 3; k++) {
            res[0][k].bandpass(sr, p.resL[k], p.q * (k == 0 ? .7f : 1f));
            res[1][k].bandpass(sr, p.resR[k], p.q * (k == 0 ? .7f : 1f));
        }
        body.lowpass(sr, 180, .9f);
        popRes.bandpass(sr, 340, 2.2f);
        bovRes.bandpass(sr, 2600, 1.4f);
        air.bandpass(sr, 1800 + 900 * p.bright, .8f);
    }

    public Profile profile() { return p; }

    /** Pop now (fuel cut on a shift or at the limiter); size 0..1. */
    public void pop(float size) {
        if (EX_POP[exhaust] <= 0) return;
        popEnv = 1f;
        popLevel = Math.max(popLevel, size * (exhaust == 2 ? 1.2f : .7f));
        popRes.bandpass(sr, 220 + rnd.nextFloat() * 380, 1.6f + rnd.nextFloat() * 1.5f);
    }

    /**
     * Fills out[off..off+n) with samples for the given rpm and load (0..1), starter 0..1 while
     * cranking. Values may be ramped by the caller between buffers; here rpm and load are linearly
     * interpolated from the previous call.
     */
    private float prevRpm = 800, prevLoad = .2f;

    public void render(float[] out, int off, int n, float rpm, float load, float starter) {
        float r0 = prevRpm, l0 = prevLoad;
        prevRpm = rpm;
        prevLoad = load;
        float exG = EX_GAIN[exhaust], exD = EX_DRIVE[exhaust], exN = EX_NOISE[exhaust];
        int cyl = p.fire.length;
        for (int i = 0; i < n; i++) {
            float t = (i + 1f) / n;
            float r = r0 + (rpm - r0) * t, ld = l0 + (load - l0) * t;
            float camK = p.cam > 0 && r > p.cam ? 1f : 0f;

            // Crank advance and firings
            double prev = phase;
            phase += r / 60.0 * 360.0 / sr;
            float fireAt = p.fire[nextFire];
            boolean wrapped = phase >= 720.0;
            if (wrapped) phase -= 720.0;
            boolean cross = wrapped ? (fireAt >= prev || fireAt < phase) : (fireAt >= prev && fireAt < phase);
            if (r > 40 && cross) {
                float a = p.amp[nextFire] * (.92f + rnd.nextFloat() * .16f);
                // Idle roughness: a little more jitter when slow
                if (r < p.idle * 1.3f) a *= .85f + rnd.nextFloat() * .3f;
                float period = 60f / r * 2f / cyl; // seconds between firings
                float w = Math.max(.0012f, Math.min(.011f, period * .55f));
                startPulse(w * sr, a * (.35f + .65f * ld), p.bank[nextFire]);
                // Overrun crackle: fuel burning in the hot exhaust when the throttle is shut
                if (ld < .08f && r > 2600 && rnd.nextFloat() < EX_POP[exhaust] * .09f) pop(.35f + rnd.nextFloat() * .5f);
                nextFire = (nextFire + 1) % cyl;
                envNoise = 1f;
            }

            // Pulses
            float x0 = 0, x1 = 0;
            for (int k = 0; k < np; k++) {
                float u = pt[k] / pw[k];
                float v = .5f - .5f * (float) Math.cos(2 * Math.PI * u);
                if (pb[k] == 0) x0 += v * pa[k]; else x1 += v * pa[k];
                pt[k] += 1;
                if (pt[k] >= pw[k]) { // remove
                    np--;
                    pt[k] = pt[np]; pw[k] = pw[np]; pa[k] = pa[np]; pb[k] = pb[np];
                    k--;
                }
            }

            // Combustion noise, shaped by the firing envelope
            envNoise *= .9965f;
            float nz = (rnd.nextFloat() * 2 - 1) * envNoise * p.noise * exN * (.4f + .9f * ld) * (1 + camK * .5f);
            float in0 = x0 + nz * .5f, in1 = x1 + nz * .5f;

            float y = 0;
            for (int k = 0; k < 3; k++) {
                float g = p.resG[k] * (k == 2 ? (p.bright + camK * .5f) : 1f) * (k > 0 ? (.6f + .5f * ld) : 1f);
                y += g * (res[0][k].run(in0) + res[1][k].run(in1));
            }
            y += body.run(x0 + x1) * 1.4f;
            y += air.run(nz) * .5f * (.3f + ld);

            // Pops / bangs
            if (popEnv > .001f) {
                float burst = (rnd.nextFloat() * 2 - 1) * popEnv * popLevel;
                y += popRes.run(burst) * 6f + burst * .6f;
                popEnv *= .9975f;
            }

            // Turbo: whistle grows with boost; blow-off when the throttle shuts on boost
            if (p.turbo > 0) {
                float target = ld > .35f && r > 2200 ? Math.min(1f, (r - 2200) / 2500f) * ld : 0f;
                boost += (target - boost) * (target > boost ? .00012f : .0006f);
                if (lastLoad > .5f && ld < .15f && boost > .35f && bovEnv < .1f) bovEnv = boost;
                turboPh += (float) (2 * Math.PI * (2200 + 5200 * boost) / sr);
                if (turboPh > 6.2832f) turboPh -= 6.2832f;
                y += (float) Math.sin(turboPh) * boost * .05f * p.turbo;
                if (bovEnv > .001f) {
                    y += bovRes.run((rnd.nextFloat() * 2 - 1) * bovEnv) * .9f * p.turbo;
                    bovEnv *= .99984f;
                }
            }
            // Supercharger whine follows the crank directly
            if (p.whine > 0) {
                whinePh += (float) (2 * Math.PI * (r / 60f * 7.3f) / sr);
                if (whinePh > 6.2832f) whinePh -= 6.2832f;
                y += (float) Math.sin(whinePh) * Math.min(1f, r / 6000f) * (.3f + .7f * ld) * .045f * p.whine;
            }
            // Starter motor
            if (starter > 0) {
                starterPh += (float) (2 * Math.PI * 165 / sr);
                if (starterPh > 6.2832f) starterPh -= 6.2832f;
                float s = (float) Math.sin(starterPh) + .35f * (float) Math.sin(3 * starterPh);
                y += s * starter * .16f;
            }
            lastLoad = ld;

            // Growl, DC block, gentle top cut
            float drive = p.drive * exD * (.6f + .7f * ld) * (1 + camK * .25f);
            y = (float) Math.tanh(y * drive) / (float) Math.tanh(drive);
            float dc = y - dcX + .995f * dcY;
            dcX = y; dcY = dc;
            float cut = .55f + .3f * p.bright;
            lp += (dc - lp) * cut;
            lp2 += (lp - lp2) * cut;
            out[off + i] = lp2 * exG * (.55f + .45f * ld) * (.6f + .4f * Math.min(1f, r / p.redline * 1.4f));
        }
    }

    private void startPulse(float width, float amp, int bank) {
        if (np >= pt.length) return;
        pt[np] = 0; pw[np] = Math.max(2, width); pa[np] = amp; pb[np] = bank;
        np++;
    }

    /** RBJ biquad. */
    static final class Biquad {
        float b0, b1, b2, a1, a2, z1, z2;

        void bandpass(float sr, float f, float q) {
            double w = 2 * Math.PI * Math.min(f, sr * .45) / sr, al = Math.sin(w) / (2 * q), a0 = 1 + al;
            b0 = (float) (al / a0); b1 = 0; b2 = (float) (-al / a0);
            a1 = (float) (-2 * Math.cos(w) / a0); a2 = (float) ((1 - al) / a0);
        }

        void lowpass(float sr, float f, float q) {
            double w = 2 * Math.PI * f / sr, al = Math.sin(w) / (2 * q), c = Math.cos(w), a0 = 1 + al;
            b0 = (float) ((1 - c) / 2 / a0); b1 = (float) ((1 - c) / a0); b2 = b0;
            a1 = (float) (-2 * c / a0); a2 = (float) ((1 - al) / a0);
        }

        float run(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    }
}
