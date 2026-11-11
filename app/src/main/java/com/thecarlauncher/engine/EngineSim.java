package com.thecarlauncher.engine;

/**
 * Turns the car's real speed into a believable rpm, gear and throttle for the chosen engine:
 * a virtual six-speed box that shifts later the harder you accelerate, blips on downshifts,
 * cuts fuel on lift-off (pops) and bounces off the limiter. Standing still, the "gas" button
 * revs it freely. A real rpm (from CANBUS, when available) is followed instead, scaled into
 * the engine's range.
 *
 * Pure Java (no Android), stepped from the audio thread.
 */
public final class EngineSim {
    private static final float[] KMH_PER_1000 = {7.8f, 13f, 19f, 25f, 31.5f, 38.5f};

    private EngineSynth.Profile p;
    public float rpm = 800, load = .2f, speed;
    public int gear; // 0 = neutral, 1..6
    private float target;     // raw speed from GPS / CAN
    private float accel;      // km/h per second, smoothed
    private float shiftT;     // seconds left in a shift
    private boolean shiftUp;
    private boolean rev;      // gas pressed (standing)
    private float realRpm = -1, realMax = 4500;
    private long realAt;
    private float limiterT;
    private float crank = -1;  // >= 0 while starting
    private boolean off;
    private EngineSynth synth;

    public EngineSim(EngineSynth s) {
        synth = s;
        p = s.profile();
        rpm = p.idle();
    }

    public void setProfile(EngineSynth.Profile prof) { p = prof; }

    public void setSpeed(float kmh) { target = Math.max(0, kmh); }

    public void setRev(boolean on) { rev = on; }

    /** Real engine rpm from the car, with the car's own limit (to stretch into the sound's range). */
    public void setRealRpm(float r, float max, long nowMs) {
        realRpm = r;
        realMax = max > 1000 ? max : 4500;
        realAt = nowMs;
    }

    public void start() { crank = 0; off = false; }

    public void stop() { off = true; }

    public boolean running() { return !off && crank < 0; }

    /** Starter level for the synth (0 when not cranking). */
    public float starter() { return crank >= 0 && crank < .75f ? Math.min(1f, crank * 6) : 0f; }

    public void step(float dt, long nowMs) {
        float prevSpeed = speed;
        speed += (target - speed) * Math.min(1f, dt / 1.1f);
        float a = (speed - prevSpeed) / Math.max(dt, 1e-3f);
        accel += (a - accel) * Math.min(1f, dt / .6f);

        float idle = p.idle(), red = p.redline();
        float wantRpm, wantLoad;

        if (off) {
            wantRpm = 0; wantLoad = 0;
            rpm += (wantRpm - rpm) * Math.min(1f, dt / .5f);
            load = 0;
            return;
        }
        if (crank >= 0) { // starting: crank at ~250 rpm, catch, flare, settle
            crank += dt;
            if (crank < .75f) { rpm = 240 + 40 * (float) Math.sin(crank * 40); load = .25f; }
            else if (crank < 1.05f) { rpm += (idle * 1.9f - rpm) * Math.min(1f, dt / .07f); load = .8f; }
            else { rpm += (idle - rpm) * Math.min(1f, dt / .5f); load = .15f; if (crank > 2.2f) crank = -1; }
            return;
        }

        boolean realFresh = realRpm >= 0 && nowMs - realAt < 2000;
        if (realFresh) {
            // Map the car's range onto the sound's range so a diesel's 4 500 reaches the redline.
            float k = (red - idle) / Math.max(1, realMax - 800);
            wantRpm = idle + Math.max(0, realRpm - 800) * k;
            wantLoad = accel > .4f ? Math.min(1f, .35f + accel / 6f) : accel < -1f ? 0f : .25f;
            gear = speed < 3 ? 0 : gear;
        } else if (speed < 4f && target < 4f) {
            gear = 0;
            if (rev) { wantRpm = red * 1.02f; wantLoad = 1f; }
            else { wantRpm = idle; wantLoad = rpm > idle + 250 ? 0f : .15f; }
        } else {
            if (gear == 0) gear = 1;
            float aggr = Math.max(0, Math.min(1, accel / 9f));
            float upAt = red * (.42f + .5f * aggr);
            float r = speed / KMH_PER_1000[gear - 1] * 1000f;
            if (shiftT <= 0) {
                if (r > upAt && gear < 6) { gear++; shiftT = .2f; shiftUp = true; if (aggr > .3f) synth.pop(.5f + aggr * .5f); }
                else if (r < Math.max(idle * 1.5f, 1350) && gear > 1) { gear--; shiftT = .25f; shiftUp = false; }
            }
            r = Math.max(idle * 1.05f, speed / KMH_PER_1000[gear - 1] * 1000f);
            wantRpm = r;
            if (accel > .35f) wantLoad = Math.min(1f, .35f + accel / 6f);
            else if (accel < -.9f) wantLoad = 0f;
            else wantLoad = .28f;
            if (shiftT > 0) {
                shiftT -= dt;
                wantLoad = shiftUp ? 0f : .75f; // fuel cut on upshift, blip on downshift
                if (!shiftUp) wantRpm *= 1.08f;
            }
        }

        // Limiter: fuel cut bounce
        if (rpm >= red * .995f) {
            limiterT += dt;
            if (limiterT > .06f) { limiterT = 0; wantLoad = 0; rpm = red * .955f; synth.pop(.35f); }
        }
        float tau = gear == 0 ? (wantRpm > rpm ? .09f : .35f) : .14f;
        rpm += (Math.min(wantRpm, red) - rpm) * Math.min(1f, dt / tau);
        load += (wantLoad - load) * Math.min(1f, dt / .08f);
    }
}
