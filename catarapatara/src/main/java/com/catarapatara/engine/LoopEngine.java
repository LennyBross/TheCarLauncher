package com.catarapatara.engine;

/**
 * Plays an engine from four looped recordings, the technique of markeasting/engine-audio
 * (MIT, github.com/markeasting/engine-audio), rewritten in Java for CataraPatara:
 *
 *   on_low  · off_low  · on_high · off_high   (+ optional limiter loop)
 *
 * "on" = recorded under throttle, "off" = coasting; "low" / "high" = recorded at a low or a high
 * rpm. Each loop is pitched from the rpm it was recorded at to the engine's rpm
 * (cents = (rpm − loopRpm) × pitchFactor), the low/high pair is blended by rpm and the on/off
 * pair by throttle, both with equal-power crossfades, and the limiter loop fades in at the
 * redline. Fed with the car's real rpm and pedal (OBD) it follows the rev counter exactly.
 *
 * Pure Java (no Android).
 */
public final class LoopEngine implements PackEngine {

    /** One looped recording. */
    public static final class Layer {
        final short[] pcm;
        final float srcPerOut;   // source samples per output sample at pitch 1
        final float rpm, volume;
        double pos;
        float gain;              // smoothed

        public Layer(short[] pcm, int sampleRate, int outRate, float rpm, float volume) {
            this.pcm = pcm;
            this.srcPerOut = sampleRate / (float) outRate;
            this.rpm = rpm;
            this.volume = volume;
        }
    }

    private final Layer onLow, offLow, onHigh, offHigh, limiter;
    private final float fadeFrom, fadeTo, pitchFactor, redline;
    private float load = 0;

    public LoopEngine(Layer onLow, Layer offLow, Layer onHigh, Layer offHigh, Layer limiter,
                      float fadeFrom, float fadeTo, float pitchFactor, float redline) {
        this.onLow = onLow;
        this.offLow = offLow != null ? offLow : onLow;
        this.onHigh = onHigh != null ? onHigh : onLow;
        this.offHigh = offHigh != null ? offHigh : this.offLow;
        this.limiter = limiter;
        this.fadeFrom = fadeFrom;
        this.fadeTo = Math.max(fadeFrom + 100, fadeTo);
        this.pitchFactor = pitchFactor;
        this.redline = redline;
    }

    /** Equal-power crossfade: returns the gain of the "to" side; the "from" side is the cosine. */
    private static float ep(float x) { return (float) Math.sin(Math.max(0, Math.min(1, x)) * Math.PI / 2); }

    @Override
    public void render(float[] out, int off, int len, float rpm, float wantLoad) {
        // throttle smoothing (~30 ms) so on/off changes don't click
        load += (wantLoad - load) * Math.min(1f, len / 1500f);
        float hi = ep((rpm - fadeFrom) / (fadeTo - fadeFrom)), lo = (float) Math.sqrt(Math.max(0, 1 - hi * hi));
        float on = ep(load), offG = (float) Math.sqrt(Math.max(0, 1 - on * on));
        float lim = limiter == null ? 0 : Math.max(0, Math.min(1, (rpm - redline * .93f) / (redline * .07f)));

        for (int i = 0; i < len; i++) out[off + i] = 0;
        // A missing loop stands in for another: add the gains per distinct loop, play each once.
        Layer[] ls = {onLow, offLow, onHigh, offHigh};
        float[] gs = {on * lo, offG * lo, on * hi, offG * hi};
        for (int k = 0; k < 4; k++) {
            if (ls[k] == null) continue;
            float g = 0;
            boolean first = true;
            for (int m = 0; m < 4; m++) {
                if (ls[m] != ls[k]) continue;
                if (m < k) { first = false; break; }
                g += gs[m];
            }
            if (first) mix(out, off, len, ls[k], Math.min(1f, g), rpm);
        }
        if (limiter != null) mix(out, off, len, limiter, lim, -1);
    }

    private void mix(float[] out, int off, int len, Layer l, float target, float rpm) {
        if (l == null) return;
        float g0 = l.gain, g1 = target * l.volume;
        l.gain = g1;
        if (g0 < 1e-4f && g1 < 1e-4f) { // silent: keep the loop running so it stays in step
            l.pos = (l.pos + len * l.srcPerOut) % l.pcm.length;
            return;
        }
        double rate = l.srcPerOut * (rpm < 0 ? 1 : Math.pow(2, (rpm - l.rpm) * pitchFactor / 1200.0));
        short[] p = l.pcm;
        int n = p.length;
        double pos = l.pos;
        for (int i = 0; i < len; i++) {
            int a = (int) pos;
            float f = (float) (pos - a);
            int b = a + 1 >= n ? 0 : a + 1;
            float s = (p[a] + (p[b] - p[a]) * f) / 32768f;
            out[off + i] += s * (g0 + (g1 - g0) * i / len);
            pos += rate;
            if (pos >= n) pos -= n;
        }
        l.pos = pos;
    }
}
