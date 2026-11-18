package com.catarapatara.engine;

import java.util.Random;

/**
 * Plays a real engine recording at any rpm: the recording comes with a map of which rpm the
 * engine was turning at each moment, in three banks (idle, on load, off load). Short grains
 * are taken from where the recording was closest to the wanted rpm, nudged in pitch, windowed
 * and overlapped, so the sound follows the car without audible loops. On load and off load
 * banks are mixed by the throttle, idle takes over near idle.
 *
 * Pure Java (no Android).
 */
public final class GrainEngine {
    public static final int IDLE = 0, ON = 1, OFF = 2;

    private final short[] src;
    private final float srcRate;       // source samples per output sample at rate 1
    private final float[][] t = new float[3][], r = new float[3][], g = new float[3][]; // per bank: time (s), rpm, level gain
    private final float[] bankGain = {1, 1, 1};
    private final float idleRpm, maxRate, srcSR;
    private final int grain;           // output samples per grain
    private final float[] win;
    private final Random rnd = new Random(3);

    // Active grains (per bank two overlap)
    private static final int MAX = 12;
    private final double[] gPos = new double[MAX];
    private final float[] gRate = new float[MAX], gAmp = new float[MAX];
    private final int[] gAge = new int[MAX];
    private int n;
    private int untilNext;
    private float wIdle, wOn, wOff = 1;

    public GrainEngine(short[] audio, int sampleRate, int outRate, float idleRpm, float maxRate, float grainSec,
                       float[][] idle, float[][] on, float[][] off, float[] gains) {
        src = audio;
        srcRate = sampleRate / (float) outRate;
        srcSR = sampleRate;
        this.idleRpm = idleRpm;
        this.maxRate = Math.max(1.05f, maxRate);
        grain = Math.max(256, Math.round(grainSec * outRate));
        win = new float[grain];
        for (int i = 0; i < grain; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / grain));
        float[][][] banks = {idle, on, off};
        for (int b = 0; b < 3; b++) {
            float[][] p = banks[b];
            if (p == null || p.length == 0) p = banks[b == IDLE ? ON : (b == ON ? OFF : ON)];
            if (p == null) p = new float[][]{{0, idleRpm, 1}};
            t[b] = new float[p.length]; r[b] = new float[p.length]; g[b] = new float[p.length];
            for (int i = 0; i < p.length; i++) { t[b][i] = p[i][0]; r[b][i] = p[i][1]; g[b][i] = p[i].length > 2 ? p[i][2] : 1f; }
        }
        if (gains != null) for (int b = 0; b < 3 && b < gains.length; b++) bankGain[b] = gains[b];
    }

    public void render(float[] out, int off, int len, float rpm, float load) {
        // Bank weights, smoothed
        float idleW = Math.max(0, Math.min(1, (idleRpm * 1.55f - rpm) / (idleRpm * .55f)));
        if (load > .5f) idleW *= .4f;
        float onW = Math.max(0, Math.min(1, (load - .12f) / .35f));
        float tIdle = idleW, tOn = (1 - idleW) * onW, tOff = (1 - idleW) * (1 - onW);
        for (int i = 0; i < len; i++) {
            if (untilNext <= 0) {
                wIdle += (tIdle - wIdle) * .35f; wOn += (tOn - wOn) * .35f; wOff += (tOff - wOff) * .35f;
                spawn(IDLE, wIdle, rpm);
                spawn(ON, wOn, rpm);
                spawn(OFF, wOff, rpm);
                untilNext = grain / 2;
            }
            untilNext--;
            float y = 0;
            for (int k = 0; k < n; k++) {
                double p = gPos[k];
                int ip = (int) p;
                if (ip >= 0 && ip + 1 < src.length) {
                    float fr = (float) (p - ip);
                    y += ((src[ip] * (1 - fr) + src[ip + 1] * fr) / 32768f) * win[gAge[k]] * gAmp[k];
                }
                gPos[k] += gRate[k];
                if (++gAge[k] >= grain) { // retire
                    n--;
                    gPos[k] = gPos[n]; gRate[k] = gRate[n]; gAmp[k] = gAmp[n]; gAge[k] = gAge[n];
                    k--;
                }
            }
            out[off + i] = y;
        }
    }

    private void spawn(int b, float w, float rpm) {
        if (w < .02f || n >= MAX) return;
        float[] rb = r[b];
        // Nearest rpm, then a random pick among close neighbours so grains don't repeat.
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < rb.length; i++) {
            float d = Math.abs(rb[i] - rpm);
            if (d < bd) { bd = d; best = i; }
        }
        int pick = best;
        for (int tries = 0; tries < 4; tries++) {
            int c = best + rnd.nextInt(9) - 4;
            if (c >= 0 && c < rb.length && Math.abs(rb[c] - rpm) <= bd + 60) { pick = c; break; }
        }
        float rate = rpm / Math.max(200f, rb[pick]);
        rate = Math.max(1f / maxRate, Math.min(maxRate, rate));
        double pos = (t[b][pick] + (rnd.nextFloat() - .5f) * .02f) * srcSR;
        // Keep the grain inside the source
        double need = grain * rate * srcRate;
        pos = Math.max(0, Math.min(src.length - need - 2, pos - need / 2));
        gPos[n] = pos;
        gRate[n] = rate * srcRate;
        gAmp[n] = w * g[b][pick] * bankGain[b];
        gAge[n] = 0;
        n++;
    }

}
