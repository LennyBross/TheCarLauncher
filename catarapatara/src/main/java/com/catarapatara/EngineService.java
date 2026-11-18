package com.catarapatara;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import com.catarapatara.engine.EngineSim;
import com.catarapatara.engine.EngineSynth;
import com.catarapatara.engine.SoundPacks;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Plays the engine sound in the background (over Android Auto / ZLink and any other app),
 * following the car: OBD rpm + pedal when an adapter is connected, CANBUS consumption when the
 * unit shares it, otherwise GPS speed. Mixed under the music, muted in phone calls.
 */
public final class EngineService extends Service implements LocationListener, Obd.Listener {
    static final String ACTION_STOP = "com.catarapatara.STOP", ACTION_TOGGLE = "com.catarapatara.TOGGLE";
    /** Other apps (e.g. MYDS launcher) can feed car data: extras rpm, rpmMax, pedal (0..1), l100, speed. */
    static final String ACTION_DATA = "com.catarapatara.DATA";
    private static final int SR = 32000, BLOCK = 256, NOTE = 7;

    private static volatile EngineService sInstance;

    static EngineService get() { return sInstance; }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("cp", MODE_PRIVATE); }

    static void start(Context c) {
        Intent i = new Intent(c, EngineService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) { c.stopService(new Intent(c, EngineService.class)); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final EngineSynth synth = new EngineSynth(SR);
    private final EngineSim sim = new EngineSim(synth);
    private volatile SoundPacks.Loaded pack;
    private volatile boolean running;
    private volatile int gen;
    private Thread audio;
    private Obd obd;
    private volatile String obdStatus = "";
    private volatile float gpsKmh, obdKmh = -1;
    private volatile long obdKmhAt, lastFixAt;
    private Location lastLoc;

    private final BroadcastReceiver data = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            long now = System.currentTimeMillis();
            if (i.hasExtra("rpm")) sim.setRealRpm(i.getFloatExtra("rpm", 0), i.getFloatExtra("rpmMax", 4500), now);
            if (i.hasExtra("pedal")) sim.setPedal(i.getFloatExtra("pedal", 0), now);
            if (i.hasExtra("l100")) sim.setConsumption(i.getFloatExtra("l100", 0), now);
            if (i.hasExtra("speed")) { obdKmh = i.getFloatExtra("speed", 0); obdKmhAt = now; }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        applyEngine();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        SharedPreferences p = prefs(this);
        if (ACTION_STOP.equals(a)) p.edit().putBoolean("on", false).apply();
        if (ACTION_TOGGLE.equals(a)) p.edit().putBoolean("on", !p.getBoolean("on", false)).apply();
        foreground();
        boolean on = p.getBoolean("on", false);
        if (on && !running) begin();
        if (!on && running) end();
        Bubble.refresh(this);
        if (!on && !Bubble.wanted(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void foreground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("engine", "Motor sesi", NotificationManager.IMPORTANCE_LOW));
        }
        int pf = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), pf);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "engine") : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat).setContentTitle("CataraPatara").setContentText(prefs(this).getBoolean("on", false) ? name() + " çalışıyor" : "Hazır · egzoza dokun")
                .setContentIntent(open).setOngoing(true).addAction(0, prefs(this).getBoolean("on", false) ? "Sustur" : "Çalıştır",
                        PendingIntent.getService(this, 2, new Intent(this, EngineService.class).setAction(ACTION_TOGGLE), pf));
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (hasLocation()) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            startForeground(NOTE, n, type);
        } else {
            startForeground(NOTE, n);
        }
    }

    private boolean hasLocation() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void begin() {
        running = true;
        sim.start();
        if (hasLocation()) {
            try {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250, 0, this, Looper.getMainLooper());
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
        IntentFilter f = new IntentFilter(ACTION_DATA);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(data, f, RECEIVER_EXPORTED); else registerReceiver(data, f);
        applyObd();
        // CAN bridge (separate system app, if installed) feeds instant consumption.
        try {
            Intent b = new Intent().setClassName("com.catarapatara.canbridge", "com.catarapatara.canbridge.BridgeService");
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(b); else startService(b);
        } catch (Exception ignored) {
        }
        final int my = ++gen;
        audio = new Thread(new Runnable() { public void run() { loop(my); } }, "engine-audio");
        audio.start();
    }

    private void end() {
        running = false;
        try { ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(this); } catch (Exception ignored) { }
        try { unregisterReceiver(data); } catch (Exception ignored) { }
        if (obd != null) obd.stop();
        obd = null;
    }

    boolean playing() { return running; }

    /** Turns the sound on or off (the service itself stays for the floating button). */
    static void toggle(Context c) {
        Intent i = new Intent(c, EngineService.class).setAction(ACTION_TOGGLE);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override
    public void onDestroy() {
        if (running) end();
        sInstance = null;
        Bubble.refresh(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---- Settings -------------------------------------------------------------------------------

    void applyEngine() {
        SharedPreferences p = prefs(this);
        String id = p.getString("id", "v8us");
        SoundPacks.Loaded lp = id.startsWith("pack:") ? SoundPacks.load(this, id, SR) : null;
        synchronized (synth) {
            EngineSynth.Profile prof = lp != null ? EngineSynth.forRecording(id, lp.name, lp.sub, lp.idle, lp.redline) : EngineSynth.profile(id);
            pack = lp;
            synth.setProfile(prof, p.getInt("ex", 1));
            sim.setProfile(prof);
        }
        setBass(p.getInt("bass", 3));
    }

    void applyObd() {
        SharedPreferences p = prefs(this);
        String mode = p.getString("obd", "off");
        if (obd != null) obd.stop();
        obd = null;
        obdStatus = "";
        if (Obd.WIFI.equals(mode) || Obd.BT.equals(mode)) {
            obd = new Obd(this);
            obd.start(mode, p.getString("obdTarget", ""));
        }
    }

    String name() {
        SoundPacks.Loaded lp = pack;
        return lp != null ? lp.name : synth.profile().name();
    }

    void rev(boolean on) { sim.setRev(on); }

    String live() {
        long now = System.currentTimeMillis();
        try {
            return new JSONObject().put("rpm", Math.round(sim.rpm)).put("gear", sim.gear).put("kmh", Math.round(sim.speed))
                    .put("red", Math.round(synth.profile().redline())).put("load", Math.round(sim.load * 100))
                    .put("src", sim.source(now)).put("obd", obdStatus).put("gps", now - lastFixAt < 3000).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    static String profiles(Context c) {
        JSONArray a = new JSONArray();
        try {
            for (SoundPacks.Info i : SoundPacks.list(c)) {
                a.put(new JSONObject().put("id", i.id).put("name", i.name).put("sub", i.sub).put("red", i.redline).put("pack", true));
            }
            for (EngineSynth.Profile p : EngineSynth.PROFILES) {
                a.put(new JSONObject().put("id", p.id()).put("name", p.name()).put("sub", p.sub()).put("red", Math.round(p.redline())));
            }
        } catch (Exception ignored) {
        }
        return a.toString();
    }

    // ---- Car data ---------------------------------------------------------------------------------

    @Override
    public void onLocationChanged(Location l) {
        long now = System.currentTimeMillis();
        float kmh;
        if (l.hasSpeed()) {
            kmh = l.getSpeed() * 3.6f;
        } else if (lastLoc != null) {
            float dt = (l.getTime() - lastLoc.getTime()) / 1000f;
            kmh = dt > 0 ? l.distanceTo(lastLoc) / dt * 3.6f : gpsKmh;
        } else {
            kmh = 0;
        }
        lastLoc = l;
        lastFixAt = now;
        gpsKmh = kmh < 2.5f ? 0 : kmh;
    }

    @Override public void onStatusChanged(String p, int s, Bundle b) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    @Override public void onRpm(float rpm) { sim.setRealRpm(rpm, prefs(this).getInt("carMax", 4500), System.currentTimeMillis()); }
    @Override public void onPedal(float p) { sim.setPedal(p, System.currentTimeMillis()); }
    @Override public void onSpeed(float kmh) { obdKmh = kmh; obdKmhAt = System.currentTimeMillis(); }
    @Override public void onStatus(String t, boolean ok) { obdStatus = t; }

    // ---- Audio --------------------------------------------------------------------------------------

    private float bassB0, bassB1, bassB2, bassA1, bassA2, bz1, bz2;

    private void setBass(int db) {
        // Low shelf at 120 Hz (RBJ)
        double A = Math.pow(10, Math.max(0, Math.min(9, db)) / 40.0), w = 2 * Math.PI * 120 / SR, c = Math.cos(w), s = Math.sin(w);
        double al = s / 2 * Math.sqrt(2), sq = 2 * Math.sqrt(A) * al;
        double a0 = (A + 1) + (A - 1) * c + sq;
        bassB0 = (float) (A * ((A + 1) - (A - 1) * c + sq) / a0);
        bassB1 = (float) (2 * A * ((A - 1) - (A + 1) * c) / a0);
        bassB2 = (float) (A * ((A + 1) - (A - 1) * c - sq) / a0);
        bassA1 = (float) (-2 * ((A - 1) + (A + 1) * c) / a0);
        bassA2 = (float) (((A + 1) + (A - 1) * c - sq) / a0);
    }

    private void loop(int my) {
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
            return;
        }
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        SharedPreferences p = prefs(this);
        float[] buf = new float[BLOCK];
        short[] pcm = new short[BLOCK];
        float gain = 0, fade = 1, dt = BLOCK / (float) SR;
        long checkAt = 0;
        boolean muted = false;
        float vol = .7f, popLevel = .6f;
        boolean pops = true;
        try {
            while (true) {
                long now = System.currentTimeMillis();
                if (now >= checkAt) {
                    checkAt = now + 300;
                    int mode = am == null ? AudioManager.MODE_NORMAL : am.getMode();
                    muted = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE;
                    vol = p.getInt("vol", 70) / 100f;
                    pops = p.getBoolean("pops", true);
                    popLevel = .3f + .25f * p.getInt("ex", 1);
                    boolean obdFresh = obdKmh >= 0 && now - obdKmhAt < 3000;
                    sim.setSpeed(obdFresh ? obdKmh : gpsKmh);
                }
                synchronized (synth) {
                    sim.step(dt, now);
                    SoundPacks.Loaded lp = pack;
                    if (lp != null && sim.starter() <= 0 && sim.rpm > 250) {
                        lp.engine.render(buf, 0, BLOCK, sim.rpm, sim.load);
                        if (pops) synth.renderPops(buf, 0, BLOCK, sim.rpm, sim.load, popLevel);
                    } else {
                        synth.render(buf, 0, BLOCK, sim.rpm, sim.load, sim.starter());
                    }
                }
                float want = muted ? 0f : vol;
                boolean live = running && my == gen;
                if (!live) fade -= dt / .8f;
                for (int i = 0; i < BLOCK; i++) {
                    gain += (want - gain) * .002f;
                    float x = buf[i];
                    float y = bassB0 * x + bz1;
                    bz1 = bassB1 * x - bassA1 * y + bz2;
                    bz2 = bassB2 * x - bassA2 * y;
                    float v = (float) Math.tanh(y * .8f) * gain * Math.max(0, fade);
                    pcm[i] = (short) Math.max(-32767, Math.min(32767, Math.round(v * 30000)));
                }
                track.write(pcm, 0, BLOCK);
                if (!live && fade <= 0) break;
            }
        } catch (Throwable ignored) {
        } finally {
            try { track.stop(); } catch (Throwable ignored) { }
            track.release();
        }
    }

    /** Called when the settings change from the app screen. */
    void settingsChanged(String key) {
        if ("id".equals(key) || "ex".equals(key) || "bass".equals(key)) {
            main.post(new Runnable() { public void run() { applyEngine(); } });
        }
        if ("obd".equals(key) || "obdTarget".equals(key)) applyObd();
        if ("id".equals(key)) foreground();
    }
}
