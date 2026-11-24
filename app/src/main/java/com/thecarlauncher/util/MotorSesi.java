package com.thecarlauncher.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Motor sesi: the engine voice of MYDS.
 *
 * How racing games do it: every engine was recorded beforehand (rendered offline with Engine_Sim,
 * MIT) at 8 rpm points from idle to the limiter, on and off throttle, as seamless loops
 * (assets/motor/eng/). Here those loops are only played back: the 2 nearest rpm points × on / off
 * throttle are pitched to the exact rpm and crossfaded. That is a few multiplications per sample,
 * so it runs smoothly on the head unit, where simulating the engine live did not.
 *
 * Follows the car (OBD rpm, engine load as the accelerator), or a test pedal from motor.html.
 */
public final class MotorSesi {
    private static MotorSesi sInstance;
    private static final int SR = 24000, BLOCK = 240, PTS = 8;

    private final Context app;
    private final SharedPreferences p;
    private volatile String state = "{}";
    private Thread thread;
    private volatile boolean run;
    private volatile float pedalThr, pedalBrake;

    public static synchronized MotorSesi get(Context c) {
        if (sInstance == null) sInstance = new MotorSesi(c.getApplicationContext());
        return sInstance;
    }

    private MotorSesi(Context c) {
        app = c;
        p = c.getSharedPreferences("motor", Context.MODE_PRIVATE);
    }

    /** Saved settings as JSON (motor.html reads them to draw itself). */
    public String settings() { return p.getString("cfg", "{\"on\":false,\"engine\":\"v8flat\",\"follow\":true,\"carIdle\":800,\"carMax\":6000,\"volume\":0.8}"); }

    private JSONObject cfg() { try { return new JSONObject(settings()); } catch (Exception e) { return new JSONObject(); } }

    public String state() { return state; }

    public boolean on() { return cfg().optBoolean("on"); }

    /** Merge a partial settings object, save it, start / stop / switch engine as needed. */
    public synchronized void set(String partial) {
        try {
            JSONObject cur = cfg(), o = new JSONObject(partial);
            String oldEngine = cur.optString("engine");
            java.util.Iterator<String> k = o.keys();
            while (k.hasNext()) { String n = k.next(); cur.put(n, o.get(n)); }
            p.edit().putString("cfg", cur.toString()).apply();
            boolean engineChanged = !oldEngine.equals(cur.optString("engine"));
            if (!cur.optBoolean("on")) stopVoice();
            else if (thread == null || engineChanged) { stopVoice(); startVoice(); }
        } catch (Exception ignored) { }
    }

    public void toggle() { set("{\"on\":" + !on() + "}"); }

    /** Test pedal from motor.html (used when not following the car). */
    public void pedal(float thr, float brake) { pedalThr = thr; pedalBrake = brake; }

    /** Called from KeepAlive: start if switched on. */
    public synchronized void start() { if (on() && thread == null) startVoice(); }

    private synchronized void stopVoice() {
        run = false;
        Thread t = thread; thread = null;
        if (t != null) try { t.join(600); } catch (InterruptedException ignored) { }
        state = "{\"on\":false}";
    }

    private void startVoice() {
        run = true;
        final String engine = cfg().optString("engine", "v8flat");
        thread = new Thread(new Runnable() { public void run() { voice(engine); } }, "motor-sesi");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    // ---- the voice --------------------------------------------------------------------------

    private void voice(String engine) {
        AudioTrack at = null;
        String err = "";
        try {
            JSONObject man = new JSONObject(readAsset("motor/eng/engines.json")).getJSONObject(engine);
            JSONArray rp = man.getJSONArray("rpm");
            final float idle = (float) man.optDouble("idle", 800), red = (float) man.optDouble("red", 7000);
            float[] rpmPts = new float[PTS];
            for (int i = 0; i < PTS; i++) rpmPts[i] = (float) rp.getDouble(i);
            // loops: [rpm point][0 = on, 1 = off]
            short[][][] loop = new short[PTS][2][];
            JSONObject lens = man.getJSONObject("len");
            for (int i = 0; i < PTS; i++) for (int t = 0; t < 2; t++) {
                String key = i + (t == 0 ? "n" : "f");
                loop[i][t] = decode("motor/eng/" + engine + "/" + key + ".ogg", lens.optInt(key, 0));
                if (!run) return;
            }
            float[][] phase = new float[PTS][2], gain = new float[PTS][2];
            float lr0 = (float) Math.log(rpmPts[0]), lrN = (float) Math.log(rpmPts[PTS - 1]);

            int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            at = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                    new AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    Math.max(min * 3, SR / 4 * 2), AudioTrack.MODE_STREAM, 0);
            at.play();

            float[] mix = new float[BLOCK];
            short[] out = new short[BLOCK];
            float rpm = idle, thr = 0, vol = 0, testRpm = idle;
            long lastState = 0;
            JSONObject c = cfg();
            int tick = 0;
            while (run) {
                if (++tick % 20 == 0) c = cfg();   // settings changes reach the voice within 0.2 s
                boolean follow = c.optBoolean("follow", true);
                float carIdle = (float) c.optDouble("carIdle", 800), carMax = (float) c.optDouble("carMax", 6000);
                float wantVol = (float) c.optDouble("volume", 0.8);
                float carRpm = 0, carThr = 0, targetRpm, targetThr;
                if (follow) {
                    ObdHub h = ObdHub.get();
                    if (h != null) {
                        carRpm = Math.max(0, h.rpm());
                        float load = h.value("load"), tv = h.value("thr");
                        float a = !Float.isNaN(load) ? (load - 12f) / 70f : !Float.isNaN(tv) ? (tv - 15f) / 70f : 0f;
                        carThr = Math.max(0, Math.min(1, a));
                    }
                    float f = Math.max(0, Math.min(1.02f, (carRpm - carIdle) / Math.max(500, carMax - carIdle)));
                    targetRpm = idle + f * (red * 0.97f - idle);
                    targetThr = carThr;
                    if (carRpm < 300) wantVol = 0;          // engine off: silent
                } else {
                    // test pedal: a simple engine that revs with the pedal and falls back
                    float top = idle + pedalThr * (red * 0.97f - idle);
                    float span = red - idle;
                    if (testRpm < top) testRpm = Math.min(top, testRpm + span * 0.0045f * (0.4f + pedalThr));
                    else testRpm = Math.max(top, testRpm - span * (pedalBrake > 0 ? 0.006f : 0.0032f));
                    targetRpm = testRpm; targetThr = pedalThr;
                }
                rpm += (targetRpm - rpm) * 0.12f;
                thr += (targetThr - thr) * 0.15f;
                float vStart = vol; vol += (wantVol - vol) * 0.1f;

                // where the rpm sits between the recorded points (log scale)
                float x = ((float) Math.log(Math.max(rpmPts[0], Math.min(rpmPts[PTS - 1], rpm))) - lr0) / (lrN - lr0) * (PTS - 1);
                int i0 = Math.min(PTS - 2, (int) x);
                float w = x - i0;
                float gOn = (float) Math.sin(thr * Math.PI / 2), gOff = (float) Math.cos(thr * Math.PI / 2);
                float gLo = (float) Math.cos(w * Math.PI / 2), gHi = (float) Math.sin(w * Math.PI / 2);

                java.util.Arrays.fill(mix, 0f);
                for (int i = 0; i < PTS; i++) for (int t = 0; t < 2; t++) {
                    float want = (i == i0 ? gLo : i == i0 + 1 ? gHi : 0f) * (t == 0 ? gOn : gOff);
                    float g = gain[i][t];
                    if (g < 1e-4f && want < 1e-4f) { gain[i][t] = 0; continue; }
                    short[] a = loop[i][t];
                    int L = a.length;
                    if (L < 2) continue;
                    float rate = rpm / rpmPts[i], ph = phase[i][t], dg = (want - g) / BLOCK;
                    for (int n = 0; n < BLOCK; n++) {
                        int k = (int) ph; float fr = ph - k;
                        int k1 = k + 1 == L ? 0 : k + 1;
                        mix[n] += (a[k] + (a[k1] - a[k]) * fr) * g;
                        g += dg;
                        ph += rate; if (ph >= L) ph -= L;
                    }
                    phase[i][t] = ph; gain[i][t] = want;
                }
                float dv = (vol - vStart) / BLOCK, v = vStart;
                for (int n = 0; n < BLOCK; n++) {
                    float s = mix[n] * v; v += dv;
                    out[n] = (short) (s > 32767 ? 32767 : s < -32768 ? -32768 : s);
                }
                at.write(out, 0, BLOCK);   // blocks until there is room: paces the loop

                long now = System.currentTimeMillis();
                if (now - lastState > 400) {
                    lastState = now;
                    state = new JSONObject().put("on", true).put("engine", engine).put("follow", follow)
                            .put("rpm", Math.round(rpm)).put("carRpm", Math.round(carRpm)).put("carThr", carThr).put("err", "").toString();
                }
            }
        } catch (Throwable t) {
            err = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            state = "{\"on\":false,\"err\":" + JSONObject.quote(err) + "}";
        } finally {
            if (at != null) { try { at.pause(); at.flush(); at.stop(); } catch (Throwable ignored) { } at.release(); }
        }
    }

    private String readAsset(String path) throws Exception {
        InputStream in = app.getAssets().open(path);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int k;
        while ((k = in.read(b)) > 0) bo.write(b, 0, k);
        in.close();
        return bo.toString("UTF-8");
    }

    /** Decodes one Vorbis loop from the assets to 16-bit mono samples (cut to its loop length). */
    private short[] decode(String path, int len) throws Exception {
        AssetFileDescriptor afd = app.getAssets().openFd(path);
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        short[] acc = new short[Math.max(len, SR) + SR / 2];
        int n = 0;
        try {
            ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            MediaFormat f = ex.getTrackFormat(0);
            ex.selectTrack(0);
            codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
            codec.configure(f, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            int channels = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            while (!outDone) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(5000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { codec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 5000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size);
                        java.nio.ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                        int cnt = sb.remaining() / Math.max(1, channels);
                        if (n + cnt > acc.length) acc = java.util.Arrays.copyOf(acc, (n + cnt) * 2);
                        for (int q = 0; q < cnt; q++) acc[n++] = sb.get(q * channels);
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Throwable ignored) { } codec.release(); }
            ex.release();
            afd.close();
        }
        int keep = len > 0 && len <= n ? len : n;
        return java.util.Arrays.copyOf(acc, keep);
    }
}
