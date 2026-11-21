package com.catarapatara.engine;

import java.util.Random;

/**
 * Physically modelled engine and exhaust: no recordings. The sound is built the way a real one
 * is, as gas pulses travelling through pipes (digital waveguides, after Baldan et al., "Physically
 * informed car engine sound synthesis", 2015):
 *
 *  - every cylinder has its own valve timing; when the exhaust valve opens, the gas left after
 *    combustion blows down into its header runner (a strong, short pulse that depends on load),
 *    then the piston pushes the rest out;
 *  - runners are two-way delay lines: pulses bounce off the closed valve and off the collector,
 *    so the header length gives the engine its tuned "voice";
 *  - runners meet in a collector (a scattering junction), each bank (or the merged system)
 *    continues through a pipe, a muffler (expansion chamber: reflection, chamber resonance and
 *    damping that depend on the exhaust setting) and a tailpipe whose open end radiates;
 *  - turbulent flow noise rides on the pressure, which gives the rasp at high load;
 *  - the intake side breathes through runners into a plenum (induction roar), and the engine
 *    block rings from combustion and valve closing (mechanical body);
 *  - cycle-to-cycle variation, cam lope at idle, overrun burble and real exhaust bangs (an
 *    impulse fired into the tailpipe, so the pipe shapes it).
 *
 * Pure Java so it runs the same on the unit and on a desktop (demo renders).
 */
public final class EngineSynth {

    /** One engine. Lengths in metres, frequencies in Hz. */
    public static final class Profile {
        final String id, name, sub;
        final float[] fire;     // firing angles in the 720° cycle, in firing order
        final int[] bank;       // exhaust bank of each firing
        final float[] runner;   // header runner length per firing (m)
        final float[] amp;      // per-cylinder strength (tiny differences = character)
        final float idle, redline;
        final boolean merge;    // banks join into one muffler (else true dual exhaust)
        final float pipe, tail, chamber; // bank pipe, tailpipe, muffler chamber lengths (m)
        final float blow;       // blowdown time constant (ms): bigger cylinders = longer, deeper pulses
        final float lope;       // idle roughness (cam overlap)
        final float[] block;    // engine block modes (Hz)
        final float plenum, intake; // intake plenum resonance, intake loudness
        final float rasp;       // turbulence amount
        final float gain;
        final float turbo, whine, cam; // turbo, supercharger whine, cam change-over rpm (0 = none)
        float knock;            // diesel combustion clatter (0 = petrol)

        Profile(String id, String name, String sub, float[] fire, int[] bank, float[] runner, float[] amp, float idle, float redline,
                boolean merge, float pipe, float tail, float chamber, float blow, float lope, float[] block, float plenum, float intake,
                float rasp, float gain, float turbo, float whine, float cam) {
            this.id = id; this.name = name; this.sub = sub; this.fire = fire; this.bank = bank; this.runner = runner; this.amp = amp;
            this.idle = idle; this.redline = redline; this.merge = merge; this.pipe = pipe; this.tail = tail; this.chamber = chamber;
            this.blow = blow; this.lope = lope; this.block = block; this.plenum = plenum; this.intake = intake; this.rasp = rasp;
            this.gain = gain; this.turbo = turbo; this.whine = whine; this.cam = cam;
        }

        Profile knock(float k) { knock = k; return this; }

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

    private static int[] same(int n) { return new int[n]; }

    private static float[] fill(int n, float v) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = v;
        return a;
    }

    private static float[] f(float... v) { return v; }

    public static final Profile[] PROFILES = {
            // The car itself: DS4 1.6 e-HDi (DV6C, Bosch EDC17): four-cylinder diesel, 1-3-4-2, turbo,
            // catalyst + particle filter (heavily damped exhaust), combustion clatter, 4 800 rpm.
            new Profile("ds4d", "DS4 1.6 e-HDi", "Kendi motorum · dizel turbo", even(4), new int[]{0, 0, 0, 0}, f(.62f, .66f, .6f, .64f), f(1f, .95f, .98f, .94f), 800, 4800,
                    true, 2.4f, .7f, .4f, 1.6f, .03f, f(420, 1250, 2900), 180, .6f, .35f, 1.5f, .9f, 0, 0).knock(1f),
            // Same engine, the way people like to hear it: straight pipe, no particle filter.
            new Profile("ds4ds", "DS4 e-HDi Spor", "Kendi motorum · açık egzoz", even(4), new int[]{0, 1, 1, 0}, f(.5f, .54f, .5f, .52f), f(1f, .95f, .98f, .94f), 800, 5000,
                    true, 1.5f, .45f, .26f, 1.25f, .04f, f(420, 1250, 2900), 200, .65f, .8f, 1.6f, 1f, 0, 0).knock(.6f),
            // Cross-plane V8, true dual exhaust: each bank gets its pulses unevenly (L R R L R L L R)
            // which is the burble. Long headers, big cylinders.
            new Profile("v8us", "Amerikan V8", "Çapraz krank · gurlayan", even(8), new int[]{0, 1, 1, 0, 1, 0, 0, 1},
                    f(.82f, .9f, .78f, .94f, .86f, .8f, .92f, .88f), f(1f, .96f, .93f, 1f, .95f, .99f, .97f, .94f), 720, 6600,
                    false, 1.9f, .8f, .42f, 1.35f, .1f, f(240, 620, 1500), 150, .5f, .9f, 1.28f, 0, 0, 0),
            new Profile("v8sc", "Süperşarjlı V8", "Çapraz krank · kompresör ıslığı", even(8), new int[]{0, 1, 1, 0, 1, 0, 0, 1},
                    f(.7f, .76f, .68f, .8f, .74f, .7f, .78f, .75f), f(1f, .97f, .95f, 1f, .96f, .99f, .98f, .95f), 750, 6800,
                    false, 1.6f, .7f, .36f, 1.25f, .07f, f(260, 680, 1650), 170, .45f, 1f, 1.72f, 0, 1f, 0),
            // Flat-plane V8: each bank fires evenly every 180°, short equal headers: the scream.
            new Profile("v8it", "İtalyan V8", "Düz krank · çığlık", even(8), alt(8), fill(8, .46f), f(1f, .97f, 1f, .96f, .99f, .97f, 1f, .96f), 950, 9000,
                    false, 1.2f, .45f, .26f, .8f, .04f, f(340, 900, 2300), 260, .7f, 1.3f, 2.25f, 0, 0, 0),
            new Profile("v10", "V10 Yarış", "Atmosferik · 10.500 d/d", even(10), alt(10), fill(10, .38f), fill(10, 1f), 1000, 10500,
                    false, 1.0f, .35f, .2f, .65f, .03f, f(380, 1050, 2600), 300, .75f, 1.35f, 2.85f, 0, 0, 0),
            new Profile("v12", "İtalyan V12", "Atmosferik · ipeksi", even(12), alt(12), fill(12, .42f), fill(12, 1f), 1000, 9200,
                    false, 1.1f, .4f, .24f, .7f, .02f, f(360, 980, 2500), 280, .8f, 1.1f, 2.66f, 0, 0, 0),
            // Flat six: two 3-into-1 collectors joined, metallic howl.
            new Profile("b6", "Alman Boxer 6", "Yatay altı · uluma", even(6), alt(6), f(.5f, .56f, .52f, .58f, .54f, .5f), f(1f, .95f, .98f, .94f, 1f, .96f), 900, 9000,
                    true, 1.4f, .5f, .3f, .85f, .04f, f(320, 880, 2100), 230, .65f, 1.1f, 1.8f, 0, 0, 0),
            new Profile("i6", "İngiliz Sıralı 6", "Pürüzsüz · sert egzoz", even(6), new int[]{0, 0, 0, 1, 1, 1}, fill(6, .62f), fill(6, 1f), 800, 7200,
                    true, 1.7f, .65f, .34f, 1.0f, .04f, f(280, 760, 1800), 190, .55f, 1f, 2.0f, 0, 0, 0),
            // Twin-turbo V6: the turbines soak up the sharp edges; whistle and blow-off on top.
            new Profile("v6t", "Biturbo V6", "Turbo ıslığı · blow-off", even(6), alt(6), fill(6, .34f), f(1f, .96f, .98f, .95f, 1f, .97f), 850, 7600,
                    false, 1.5f, .55f, .32f, .95f, .04f, f(300, 820, 2000), 210, .5f, .7f, 1.44f, 1f, 0, 0),
            // Five cylinders, 144° apart, one collector: the warble.
            new Profile("i5", "Ralli 5 Silindir", "Tek sayı · turbo", even(5), same(5), f(.48f, .55f, .5f, .58f, .52f), f(1f, .93f, .97f, .9f, .95f), 850, 7200,
                    true, 1.6f, .55f, .3f, .95f, .05f, f(300, 800, 1900), 210, .55f, .9f, 3.0f, .8f, 0, 0),
            // Inline four, 4-2-1 header, cam change-over at 5 600 rpm.
            new Profile("i4v", "Japon VTEC 4", "8.800 d/d · kam geçişi", even(4), new int[]{0, 1, 1, 0}, fill(4, .45f), f(1f, .97f, .99f, .96f), 850, 8800,
                    true, 1.4f, .5f, .28f, .75f, .04f, f(360, 950, 2400), 280, .8f, 1.1f, 1.0f, 0, 0, 5600),
    };

    /** A profile for a real recording: only idle and redline matter (the synth adds starter and pops). */
    public static Profile forRecording(String id, String name, String sub, float idle, float redline) {
        Profile b = PROFILES[0];
        return new Profile(id, name, sub, b.fire, b.bank, b.runner, b.amp, idle, redline, b.merge, b.pipe, b.tail, b.chamber,
                b.blow, b.lope, b.block, b.plenum, b.intake, b.rasp, b.gain, 0, 0, 0);
    }

    public static Profile profile(String id) {
        for (Profile p : PROFILES) if (p.id.equals(id)) return p;
        return PROFILES[0];
    }

    /** Exhaust: 0 stock, 1 sport, 2 race. */
    private static final float[] EX_MUFF = {-.55f, -.38f, -.18f};   // reflection at the muffler inlet
    private static final float[] EX_CUT = {700f, 1500f, 3600f};     // muffler damping (low-pass)
    private static final float[] EX_RASP = {.45f, .8f, 1.25f};
    private static final float[] EX_GAIN = {.75f, .9f, 1f};
    private static final float[] EX_POP = {.0f, .12f, .32f};

    private static final float C = 520f; // speed of sound in hot exhaust gas, m/s
    private static final int EVO = 128, EVC = 372, IVO = 352, IVC = 590;

    // ---- State -------------------------------------------------------------------------------------

    private final float sr;
    private final Random rnd = new Random(7);
    private int seed = 0x9E3779B9;

    /** White noise -1..1 (xorshift: no locks, no allocation on the audio thread). */
    private float noise() {
        int x = seed;
        x ^= x << 13; x ^= x >>> 17; x ^= x << 5;
        seed = x;
        return x * (1f / 2147483648f);
    }

    private static float tanh(float x) {
        if (x > 3f) return 1f;
        if (x < -3f) return -1f;
        float x2 = x * x;
        return x * (27f + x2) / (27f + 9f * x2);
    }
    private Profile p = PROFILES[0];
    private int exhaust = 1;

    private final float[] exLift = new float[721], inLift = new float[721], push = new float[721], suck = new float[721];

    private double theta;          // crank, 0..720
    private int n;                 // cylinders
    private float[] cyc, pc, prevA, vLp, rfo, rbo;
    private final float[] mfo = new float[2], mbo = new float[2];
    private Delay[] rf, rb;        // runner forward (valve→collector) / backward
    private final Delay[] mf = new Delay[2], mb = new Delay[2]; // bank pipe forward / backward
    private final Delay[] tl = new Delay[2], ch = new Delay[2]; // tailpipe, muffler chamber
    private final float[] mLp = new float[2], tLp = new float[2], cutLp = new float[2], cutLp2 = new float[2], radPrev = new float[2], env = new float[2];
    private final Biquad[] blockM = {new Biquad(), new Biquad(), new Biquad()};
    private final Biquad plen = new Biquad(), inRun = new Biquad(), inNoise = new Biquad(), rasp = new Biquad(), rasp2 = new Biquad(),
            bovRes = new Biquad(), cabin = new Biquad();
    private int banks;
    private int[][] members;
    private float dcX, dcY;
    private float popEnv, popLevel, popTone;
    private float bovEnv, flutter;
    private float turboPh, whinePh, boost, starterPh, lastLoad;
    private float inEnv, knockEnv;
    private final Biquad[] knockM = {new Biquad(), new Biquad(), new Biquad()};

    public EngineSynth(float sampleRate) {
        sr = sampleRate;
        for (int a = 0; a <= 720; a++) {
            exLift[a] = Math.min(1f, window(a, EVO, EVC) * 4f); // flow area: the valve curtain opens fast
            inLift[a] = window(a, IVO, IVC);
            // piston speed: positive on the exhaust stroke (180..360), on the intake stroke (360..540)
            float s = (float) Math.abs(Math.sin(Math.toRadians(a)));
            push[a] = a >= 180 && a < 380 ? s : 0f;
            suck[a] = a >= 360 && a < 560 ? s : 0f;
        }
        setProfile(p, exhaust);
    }

    private static float window(int a, int open, int close) {
        if (a < open || a > close) return 0f;
        double x = (a - open) / (double) (close - open);
        double s = Math.sin(Math.PI * x);
        return (float) (s * s);
    }

    public synchronized void setProfile(Profile prof, int ex) {
        p = prof;
        exhaust = Math.max(0, Math.min(2, ex));
        n = p.fire.length;
        cyc = new float[n]; pc = new float[n]; prevA = new float[n]; vLp = new float[n]; rfo = new float[n]; rbo = new float[n];
        rf = new Delay[n]; rb = new Delay[n];
        for (int i = 0; i < n; i++) {
            int d = len(p.runner[i]);
            rf[i] = new Delay(d); rb[i] = new Delay(d);
            prevA[i] = 719;
        }
        banks = 1;
        for (int b : p.bank) if (b == 1) banks = 2;
        int c0 = 0;
        for (int b : p.bank) if (b == 0) c0++;
        members = new int[][]{new int[c0], new int[n - c0]};
        for (int c = 0, i0 = 0, i1 = 0; c < n; c++) if (p.bank[c] == 0) members[0][i0++] = c; else members[1][i1++] = c;
        for (int b = 0; b < 2; b++) {
            float jitter = b == 0 ? 1f : 1.07f; // the two sides are never quite equal
            mf[b] = new Delay(len(p.pipe * jitter)); mb[b] = new Delay(len(p.pipe * jitter));
            tl[b] = new Delay(len(p.tail * jitter) * 2);
            ch[b] = new Delay(len(p.chamber * jitter) * 2);
            mLp[b] = tLp[b] = cutLp[b] = cutLp2[b] = radPrev[b] = env[b] = 0;
        }
        for (int k = 0; k < 3; k++) blockM[k].bandpass(sr, p.block[k], 9f + 4 * k);
        plen.bandpass(sr, p.plenum, 2.2f);
        inRun.bandpass(sr, p.plenum * 3.3f, 1.4f);
        inNoise.bandpass(sr, 1400, .8f);
        rasp.bandpass(sr, 1900 + exhaust * 500, .7f);
        rasp2.bandpass(sr, 4200, 1.1f);
        bovRes.bandpass(sr, 2600, 1.4f);
        knockM[0].bandpass(sr, 1650, 6f); knockM[1].bandpass(sr, 2950, 8f); knockM[2].bandpass(sr, 4300, 5f);
        cabin.lowpass(sr, 4500, .7f);
    }

    private int len(float metres) { return Math.max(2, Math.round(metres / C * sr)); }

    public Profile profile() { return p; }

    /** Bang now (fuel cut on a shift or at the limiter); size 0..1. */
    public void pop(float size) {
        if (EX_POP[exhaust] <= 0) return;
        popEnv = 1f;
        popLevel = Math.max(popLevel, size * (exhaust == 2 ? 1.25f : .75f));
        popTone = .3f + rnd.nextFloat() * .6f;
    }

    private float prevRpm = 800, prevLoad = .2f;

    /** Fills out[off..off+len) for the given rpm, load 0..1 and starter 0..1 (ramped from the last call). */
    public synchronized void render(float[] out, int off, int count, float rpm, float load, float starter) {
        float r0 = prevRpm, l0 = prevLoad;
        prevRpm = rpm;
        prevLoad = load;
        float muffR = EX_MUFF[exhaust], exG = EX_GAIN[exhaust];
        float cutK = (float) (1 - Math.exp(-2 * Math.PI * EX_CUT[exhaust] / sr));
        float raspK = EX_RASP[exhaust] * p.rasp;
        float drain = 1f / (p.blow * .0006f * sr);
        float ym = 2.2f; // bank pipe is wider than a runner
        for (int i = 0; i < count; i++) {
            float t = (i + 1f) / count;
            float r = r0 + (rpm - r0) * t, ld = l0 + (load - l0) * t;
            float camK = p.cam > 0 && r > p.cam ? 1f : 0f;
            float rn = Math.min(1.2f, r / p.redline);

            theta += r / 60.0 * 360.0 / sr;
            if (theta >= 720.0) theta -= 720.0;

            // ---- read every delay line once (what arrives this tick)
            for (int c = 0; c < n; c++) { rfo[c] = rf[c].read(); rbo[c] = rb[c].read(); }
            for (int b = 0; b < banks; b++) { mfo[b] = mf[b].read(); mbo[b] = mb[b].read(); }

            // ---- cylinders and their runners
            float intakeSum = 0, blockEx = 0, click = 0;
            for (int c = 0; c < n; c++) {
                float a = (float) (theta - p.fire[c]);
                if (a < 0) a += 720;
                float pa = prevA[c];
                prevA[c] = a;
                boolean wrapped = a < pa;
                if (r > 40) {
                    if (wrapped) { // combustion
                        float var = 1f + (float) rnd.nextGaussian() * (.025f + p.lope * Math.max(0, 1.6f - r / p.idle));
                        float fuel = ld < .04f ? .16f : .38f + .62f * ld;
                        // overrun: no fuel, only the air pumped through, sometimes a late burn
                        if (ld < .04f && r > p.idle * 1.4f && rnd.nextFloat() < EX_POP[exhaust] * .12f) {
                            fuel = .5f + rnd.nextFloat() * .6f;
                            pop(.15f + rnd.nextFloat() * .35f);
                        }
                        cyc[c] = p.amp[c] * fuel * Math.max(.2f, var);
                        blockEx += cyc[c];
                        if (p.knock > 0 && ld >= .04f) knockEnv = Math.max(knockEnv, p.knock * (.55f + .45f * ld) * (.8f + .4f * rnd.nextFloat()));
                    }
                    if (pa < EVO && a >= EVO) pc[c] = cyc[c];
                    if (pa < EVC && a >= EVC) click += 1f;
                    if (pa < IVC && a >= IVC) click += .6f;
                }
                int ai = (int) a;
                float lift = exLift[ai];
                float q = 0;
                if (lift > 0) {
                    float o = lift * pc[c] * drain;
                    pc[c] -= o;
                    q = o / drain + lift * push[ai] * (.04f + .08f * ld);
                }
                // valve end: mostly closed = hard reflection, open = it leaks into the cylinder
                vLp[c] += (rbo[c] - vLp[c]) * .6f;
                rf[c].write(q + (.92f - .55f * lift) * vLp[c]);
                // intake: the piston pulls air through the open intake valve
                float il = inLift[ai];
                if (il > 0) intakeSum -= il * suck[ai] * (.3f + .7f * ld) * (cyc[c] + .3f);
            }

            // ---- collector junctions: runners + bank pipe
            float y = 0;
            for (int b = 0; b < banks; b++) {
                int cnt = 0;
                float sumIn = 0;
                int[] mem = members[b];
                cnt = mem.length;
                for (int c : mem) sumIn += rfo[c];
                float pj = 2f * (sumIn + ym * mbo[b]) / (cnt + ym);
                for (int c : mem) rb[c].write(pj - rfo[c]);
                mLp[b] += ((pj - mbo[b]) - mLp[b]) * .7f; // pipe wall losses
                mf[b].write(mLp[b] * .985f);
            }

            // ---- muffler + tailpipe, per side (or merged)
            int sides = p.merge || banks == 1 ? 1 : 2;
            for (int s = 0; s < sides; s++) {
                float x;
                if (sides == 1 && banks == 2) {
                    mb[0].write(muffR * mfo[0]); mb[1].write(muffR * mfo[1]);
                    x = (mfo[0] + mfo[1]) * .75f;
                } else {
                    mb[s].write(muffR * mfo[s]);
                    x = mfo[s];
                }
                x *= 1 + muffR;
                float crack = 0;
                if (popEnv > .001f) {
                    float bang = (noise() * .8f + .6f) * popEnv * popLevel;
                    x += bang * .14f;
                    crack = bang * .02f;
                    if (s == sides - 1) popEnv *= .9975f - popTone * .0025f;
                }
                // expansion chamber: a resonance and the damping of the packing
                float cx = x + .45f * ch[s].read();
                ch[s].write(-cx * .8f);
                cutLp[s] += (cx - cutLp[s]) * cutK;
                cutLp2[s] += (cutLp[s] - cutLp2[s]) * Math.min(1f, cutK * 1.6f);
                float m = cutLp2[s] * .7f + cutLp[s] * .3f;
                m += crack;
                // tailpipe with an open end: standing waves
                tLp[s] += (tl[s].read() - tLp[s]) * .45f;
                float tp = m - .5f * tLp[s];
                tl[s].write(tp);
                // radiation (high-pass) + turbulent rasp riding on the flow
                float rad = tp * .55f + (tp - radPrev[s]) * 1.8f;
                radPrev[s] = tp;
                env[s] += (Math.abs(tp) - env[s]) * .02f;
                float nz = noise() * env[s];
                rad += (rasp.run(nz) * .45f + rasp2.run(nz) * .2f * (.3f + camK)) * raspK * (.15f + ld) * (.5f + rn);
                y += rad;
            }
            y *= exG * (sides == 2 ? .8f : 1f) * 12f;

            // ---- intake roar and induction hiss
            inEnv += (Math.abs(intakeSum) - inEnv) * .01f;
            float hiss = inNoise.run(noise() * inEnv) * (.4f + rn);
            float intake = (plen.run(intakeSum) * 1.2f + inRun.run(intakeSum) * .5f + hiss * .8f) * p.intake * (.5f + .5f * ld) * (1 + camK * .9f) * .4f;
            y += intake;

            // ---- engine block: combustion knock-on and valvetrain
            float ex = blockEx * (.5f + ld) + click * .05f * rn * rn;
            y += (blockM[0].run(ex) * .7f + blockM[1].run(ex) * .45f + blockM[2].run(ex + click * .05f * rn) * .3f) * .5f;

            // ---- diesel clatter: the sharp pressure rise of compression ignition rings the block
            if (knockEnv > .001f) {
                float kn = noise() * knockEnv;
                y += (knockM[0].run(kn) * 1.1f + knockM[1].run(kn) * .9f + knockM[2].run(kn) * .5f) * 1.1f;
                knockEnv *= .965f;
            }

            // ---- turbo: whistle with boost, blow-off and flutter when the throttle shuts on boost
            if (p.turbo > 0) {
                float target = ld > .35f && r > 2200 ? Math.min(1f, (r - 2200) / 2500f) * ld : 0f;
                boost += (target - boost) * (target > boost ? .00012f : .0006f);
                if (lastLoad > .5f && ld < .15f && boost > .35f && bovEnv < .1f) { bovEnv = boost; flutter = 1f; }
                turboPh += (float) (2 * Math.PI * (2400 + 5600 * boost) / sr);
                if (turboPh > 6.2832f) turboPh -= 6.2832f;
                y += ((float) Math.sin(turboPh) * .04f + inNoise.run(noise()) * .02f) * boost * p.turbo;
                if (bovEnv > .001f) {
                    float fl = flutter > .01f ? .55f + .45f * (float) Math.sin(theta * .09) : 1f;
                    y += bovRes.run(noise() * bovEnv) * .8f * p.turbo * fl;
                    bovEnv *= .99984f;
                    flutter *= .9998f;
                }
            }
            if (p.whine > 0) {
                whinePh += (float) (2 * Math.PI * (r / 60f * 7.3f) / sr);
                if (whinePh > 6.2832f) whinePh -= 6.2832f;
                y += (float) (Math.sin(whinePh) + .3 * Math.sin(2 * whinePh)) * Math.min(1f, r / 6000f) * (.3f + .7f * ld) * .035f * p.whine;
            }
            if (starter > 0) {
                // starter: a geared electric motor, labouring on each compression
                starterPh += 150f / sr;
                if (starterPh >= 1f) starterPh -= 1f;
                float saw = starterPh * 2 - 1;
                float strain = .6f + .4f * (float) Math.cos(Math.toRadians(theta * n / 2f));
                y += inNoise.run(saw * .7f + noise() * .5f) * starter * strain * .5f + saw * starter * strain * .03f;
            }
            lastLoad = ld;

            // ---- level, gentle saturation, DC block
            y *= p.gain * (1.9f - 1.1f * Math.min(1f, rn));
            y = cabin.run(y);
            y = tanh(y * 1.3f) / 1.3f * 1.25f;
            float dc = y - dcX + .993f * dcY;
            dcX = y; dcY = dc;
            out[off + i] = dc;
        }
    }

    /** Only the exhaust bangs (for real recordings): overrun crackle and shift / limiter pops. */
    public synchronized void renderPops(float[] out, int off, int count, float rpm, float load, float level) {
        float firingsPerSample = rpm / 60f * 4f / sr;
        for (int i = 0; i < count; i++) {
            if (load < .08f && rpm > 2400 && rnd.nextFloat() < EX_POP[exhaust] * .09f * firingsPerSample) pop(.35f + rnd.nextFloat() * .55f);
            if (popEnv > .001f) {
                float burst = noise() * popEnv * popLevel;
                out[off + i] += (rasp.run(burst) * 3f + burst * .6f) * level;
                popEnv *= .9975f;
            }
        }
    }

    /** Fixed delay line: read() gives the sample written len ticks ago, then write() the new one. */
    static final class Delay {
        final float[] b;
        int w;

        Delay(int len) { b = new float[Math.max(2, len)]; }

        float read() { return b[w]; }

        void write(float x) {
            b[w] = x;
            if (++w == b.length) w = 0;
        }
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
            double w = 2 * Math.PI * Math.min(f, sr * .45) / sr, al = Math.sin(w) / (2 * q), c = Math.cos(w), a0 = 1 + al;
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
