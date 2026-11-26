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
final class MotorLoops {
    private static final int BLOCK = 240;
    // sample rate and number of rpm points come with each engine (engines.json): 24 kHz × 8 or 44.1 kHz × 16
    private int SR = 24000, PTS = 8;

    private final Context app;
    private final SharedPreferences p;
    private volatile String state = "{}";
    private Thread thread;
    private volatile boolean run;
    private volatile float pedalThr, pedalBrake;

    MotorLoops(Context c) {
        app = c;
        p = c.getSharedPreferences("motor", Context.MODE_PRIVATE);
    }

    private JSONObject cfg() { try { return new JSONObject(MotorSesi.get(app).settings()); } catch (Exception e) { return new JSONObject(); } }

    String state() { return state; }

    private String playing = "";

    /** Start, stop or switch engine to match the saved settings (mode "loop"). */
    synchronized void sync() {
        JSONObject c = cfg();
        boolean want = c.optBoolean("on") && "loop".equals(c.optString("mode", "live"));
        String eng = c.optString("engine", "v8flat");
        if (!want) { if (thread != null) stopVoice(); return; }
        if (thread == null || !eng.equals(playing)) { stopVoice(); playing = eng; startVoice(); }
    }

    void pedal(float thr, float brake) { pedalThr = thr; pedalBrake = brake; }

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
            SR = man.optInt("sr", 24000);
            PTS = rp.length();
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
            // overrun crackle (lift off at high revs): short noise bursts through a resonator + a low thump
            java.util.Random rnd = new java.util.Random();
            float thrSlow = 0, crackleLeft = 0, burstLeft = 0, burstAmp = 0, burstDecay = 0, gapLeft = 0;
            float rz1 = 0, rz2 = 0, ra1 = 0, ra2 = 0, thump = 0, thumpPh = 0;
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
                        carThr = h.demand();
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
                // ---- crackle trigger: the demand fell fast while the engine was revving
                float pops = (float) c.optDouble("pops", 0.6);
                thrSlow += (thr - thrSlow) * 0.02f;
                if (pops > 0.01f && crackleLeft <= 0 && thrSlow - thr > 0.3f && rpm > idle * 2.0f) {
                    crackleLeft = SR * (0.6f + 0.9f * pops) * (0.7f + rnd.nextFloat() * 0.6f);
                    gapLeft = 0;
                }
                if (crackleLeft > 0 || burstLeft > 0) {
                    for (int n = 0; n < BLOCK; n++) {
                        if (burstLeft <= 0 && crackleLeft > 0 && --gapLeft <= 0) {
                            // next pop: some loud with a thump, most small crackles
                            boolean big = rnd.nextFloat() < 0.18f;
                            burstLeft = SR * (big ? 0.035f : 0.006f + rnd.nextFloat() * 0.012f);
                            burstAmp = 9000f * pops * (big ? 1.6f : 0.35f + rnd.nextFloat() * 0.65f) * Math.min(1f, crackleLeft / (SR * 0.4f) + 0.3f);
                            burstDecay = (float) Math.exp(-1.0 / (SR * (big ? 0.012f : 0.003f)));
                            gapLeft = SR * (0.015f + rnd.nextFloat() * (big ? 0.12f : 0.07f));
                            if (big) { thump = burstAmp * 0.9f; thumpPh = 0; }
                            // resonator a bit different each pop (900 Hz … 2.6 kHz)
                            double f0 = 900 + rnd.nextFloat() * 1700, r = 0.93;
                            ra1 = (float) (2 * r * Math.cos(2 * Math.PI * f0 / SR)); ra2 = (float) (-r * r);
                        }
                        float x = 0;
                        if (burstLeft > 0) { x = (rnd.nextFloat() * 2 - 1) * burstAmp; burstAmp *= burstDecay; burstLeft--; }
                        float y = x * 0.08f + ra1 * rz1 + ra2 * rz2;   // the resonator rings ≈ ×14 rz2 = rz1; rz1 = y;
                        float th = 0;
                        if (thump > 1) { th = (float) Math.sin(thumpPh) * thump; thumpPh += 2 * Math.PI * 70 / SR; thump *= 0.9985f; }
                        mix[n] += y + th;
                        if (crackleLeft > 0) crackleLeft--;
                    }
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
