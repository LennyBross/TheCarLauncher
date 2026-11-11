package com.thecarlauncher.engine;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import com.thecarlauncher.nav.LocationTracker;
import com.thecarlauncher.util.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Engine sound through the car's speakers, following the car: speed from GPS (later the real
 * rpm from CANBUS) drives a virtual engine, which is synthesized live. Mixed under the music
 * (no audio focus taken) and muted during phone calls. Keeps running over other apps.
 */
public final class EngineSound {
    public static final String P_ON = "engine_on", P_ID = "engine_id", P_EX = "engine_ex", P_VOL = "engine_vol";
    private static final int SR = 32000, BLOCK = 256;

    private static EngineSound sInstance;

    public static synchronized EngineSound get(Context c) {
        if (sInstance == null) sInstance = new EngineSound(c.getApplicationContext());
        return sInstance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final EngineSynth synth = new EngineSynth(SR);
    private final EngineSim sim = new EngineSim(synth);
    private volatile Thread thread;
    private volatile boolean stopping;
    private volatile float fadeOut = 1f;

    private EngineSound(Context c) {
        app = c;
        applyPrefs();
    }

    public boolean on() { return Prefs.bool(app, P_ON, false); }
    public String id() { return Prefs.str(app, P_ID, "v8us"); }
    public int exhaust() { return Prefs.integer(app, P_EX, 1); }
    public int volume() { return Prefs.integer(app, P_VOL, 70); }

    private void applyPrefs() {
        synchronized (synth) {
            EngineSynth.Profile p = EngineSynth.profile(id());
            synth.setProfile(p, exhaust());
            sim.setProfile(p);
        }
    }

    public void setProfile(String id) { Prefs.put(app, P_ID, id); applyPrefs(); }
    public void setExhaust(int ex) { Prefs.put(app, P_EX, Math.max(0, Math.min(2, ex))); applyPrefs(); }
    public void setVolume(int v) { Prefs.put(app, P_VOL, Math.max(0, Math.min(100, v))); }

    public void setOn(boolean on) {
        Prefs.put(app, P_ON, on);
        if (on) start(); else stop();
    }

    /** Called at launcher start: brings the sound back if it was on. */
    public void resume() { if (on()) start(); }

    public void rev(boolean pressed) { sim.setRev(pressed); }

    /** Real rpm from the car (CANBUS), when the unit reports it. */
    public void realRpm(float rpm, float max) { sim.setRealRpm(rpm, max, System.currentTimeMillis()); }

    public boolean running() { return thread != null && !stopping; }

    public int rpm() { return Math.round(sim.rpm); }
    public int gear() { return sim.gear; }

    /** Live values for the page: rpm, gear, km/h, redline, running. */
    public String live() {
        return Math.round(sim.rpm) + "," + sim.gear + "," + Math.round(sim.speed) + ","
                + Math.round(synth.profile().redline()) + "," + (running() ? 1 : 0);
    }

    public static String profilesJson() {
        JSONArray a = new JSONArray();
        try {
            for (EngineSynth.Profile p : EngineSynth.PROFILES) {
                JSONObject o = new JSONObject();
                o.put("id", p.id()); o.put("name", p.name()); o.put("sub", p.sub()); o.put("red", Math.round(p.redline()));
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        return a.toString();
    }

    // ---- Audio --------------------------------------------------------------------------------------

    private synchronized void start() {
        if (thread != null) { // running, or fading out: carry on
            if (stopping) { stopping = false; fadeOut = 1f; sim.start(); }
            return;
        }
        stopping = false;
        fadeOut = 1f;
        main.post(new Runnable() { public void run() { LocationTracker.get(app).start(); } });
        sim.start();
        Thread t = new Thread(new Runnable() { public void run() { loop(); } }, "engine-sound");
        thread = t;
        t.start();
    }

    private synchronized void stop() {
        if (thread == null) return;
        sim.stop();
        stopping = true; // the loop fades out and ends
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(min, BLOCK * 2 * 8);
        AudioTrack track;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(SR)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(bytes).setTransferMode(AudioTrack.MODE_STREAM).build();
            } else {
                track = new AudioTrack(AudioManager.STREAM_MUSIC, SR, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bytes, AudioTrack.MODE_STREAM);
            }
            track.play();
        } catch (Throwable e) {
            thread = null;
            return;
        }
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        LocationTracker loc = LocationTracker.get(app);
        float[] buf = new float[BLOCK];
        short[] pcm = new short[BLOCK];
        float gain = 0, dt = BLOCK / (float) SR;
        long checkAt = 0;
        boolean muted = false;
        try {
            while (true) {
                long now = System.currentTimeMillis();
                if (now >= checkAt) {
                    checkAt = now + 400;
                    int mode = am == null ? AudioManager.MODE_NORMAL : am.getMode();
                    muted = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                            || mode == AudioManager.MODE_RINGTONE;
                    float kmh = loc.hasFix() ? loc.speedMps() * 3.6f : 0f;
                    sim.setSpeed(kmh < 2.5f ? 0f : kmh);
                }
                synchronized (synth) {
                    sim.step(dt, now);
                    synth.render(buf, 0, BLOCK, sim.rpm, sim.load, sim.starter());
                }
                float want = muted ? 0f : volume() / 100f;
                if (stopping) fadeOut -= dt / .8f;
                for (int i = 0; i < BLOCK; i++) {
                    gain += (want - gain) * .002f;
                    float v = (float) Math.tanh(buf[i] * .85f) * gain * Math.max(0, fadeOut);
                    pcm[i] = (short) Math.max(-32767, Math.min(32767, Math.round(v * 30000)));
                }
                track.write(pcm, 0, BLOCK);
                if (stopping && fadeOut <= 0) {
                    synchronized (this) { if (stopping) break; }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { track.stop(); } catch (Throwable ignored) { }
            track.release();
            synchronized (this) { thread = null; stopping = false; }
        }
    }
}
