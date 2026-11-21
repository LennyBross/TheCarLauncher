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

    /** First engine on a fresh install: the bundled V8 pack (falls back to the synth V8 if missing). */
    static final String DEFAULT_ID = "pack:b-jaguar-v8";

    private static volatile EngineService sInstance;
    /** Output peak 0..1 of the last block, so the screen can show that sound is really produced. */
    private volatile float level;
    private volatile String audioError = "";
    private AudioManager.OnAudioFocusChangeListener focus;

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

    static volatile String canStatus = "";
    static volatile long canStatusAt;

    private final BroadcastReceiver data = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            long now = System.currentTimeMillis();
            if ("com.thecarlauncher.CANSTATUS".equals(i.getAction())) {
                long age = i.getLongExtra("age", -1);
                canStatus = i.getStringExtra("status") + (age >= 0 ? " · " + age / 1000 + " sn" : "");
                canStatusAt = now;
                return;
            }
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
        SoundPacks.syncAssets(this);
        applyEngine();
        new Thread(new Runnable() {
            public void run() {
                if (SoundPacks.syncGitHub(EngineService.this) > 0) main.post(new Runnable() { public void run() { applyEngine(); } });
            }
        }, "packs-sync").start();
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
        sim.gpsAllowed = prefs(this).getBoolean("gps", false);
        sim.matchRpm = prefs(this).getBoolean("matchRpm", true);
        if (sim.gpsAllowed && hasLocation()) {
            try {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250, 0, this, Looper.getMainLooper());
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
        IntentFilter f = new IntentFilter(ACTION_DATA);
        f.addAction("com.thecarlauncher.CANSTATUS");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(data, f, RECEIVER_EXPORTED); else registerReceiver(data, f);
        applyObd();
        // CAN bridge (separate system app, if installed) feeds instant consumption.
        try {
            Intent b = new Intent().setClassName("com.catarapatara.canbridge", "com.catarapatara.canbridge.BridgeService");
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(b); else startService(b);
        } catch (Exception ignored) {
        }
        takeFocus(true);
        final int my = ++gen;
        audio = new Thread(new Runnable() { public void run() { loop(my); } }, "engine-audio");
        audio.start();
    }

    private void end() {
        running = false;
        takeFocus(false);
        watchPlayback(false);
        level = 0;
        publish(false, System.currentTimeMillis());
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
        String id = p.getString("id", DEFAULT_ID);
        SoundPacks.Loaded lp = id.startsWith("pack:") ? SoundPacks.load(this, id, SR) : null;
        synchronized (synth) {
            EngineSynth.Profile prof = lp != null ? EngineSynth.forRecording(id, lp.name, lp.sub, lp.idle, lp.redline) : EngineSynth.profile(id);
            pack = lp;
            synth.setProfile(prof, p.getInt("ex", 1));
            sim.setProfile(prof);
        }
        setBass(p.getInt("bass", 3));
        setEq(p);
    }

    // ---- 5-band cabin EQ (Sub, Low-mid, Mid, Presence, Treble), idea from CAR-SOUND-CHANGER (MIT) ----
    static final int[] EQ_HZ = {50, 160, 600, 2500, 8000};
    private final float[][] eqC = new float[5][5], eqZ = new float[5][2];
    private volatile boolean eqOn;

    private void setEq(SharedPreferences p) {
        boolean any = false;
        for (int k = 0; k < 5; k++) {
            int db = Math.max(-9, Math.min(9, p.getInt("eq" + k, 0)));
            any |= db != 0;
            double A = Math.pow(10, db / 40.0), w = 2 * Math.PI * Math.min(EQ_HZ[k], SR * 0.45) / SR, c = Math.cos(w), sn = Math.sin(w);
            double b0, b1, b2, a0, a1, a2;
            if (k == 0 || k == 4) { // shelves
                double al = sn / 2 * Math.sqrt(2), sq = 2 * Math.sqrt(A) * al, sg = k == 0 ? 1 : -1;
                b0 = A * ((A + 1) - sg * (A - 1) * c + sq);
                b1 = sg * 2 * A * ((A - 1) - sg * (A + 1) * c);
                b2 = A * ((A + 1) - sg * (A - 1) * c - sq);
                a0 = (A + 1) + sg * (A - 1) * c + sq;
                a1 = -sg * 2 * ((A - 1) + sg * (A + 1) * c);
                a2 = (A + 1) + sg * (A - 1) * c - sq;
            } else { // peaking, Q 1
                double al = sn / 2;
                b0 = 1 + al * A; b1 = -2 * c; b2 = 1 - al * A; a0 = 1 + al / A; a1 = -2 * c; a2 = 1 - al / A;
            }
            synchronized (eqC) {
                eqC[k][0] = (float) (b0 / a0); eqC[k][1] = (float) (b1 / a0); eqC[k][2] = (float) (b2 / a0);
                eqC[k][3] = (float) (a1 / a0); eqC[k][4] = (float) (a2 / a0);
            }
        }
        eqOn = any;
    }

    private float eq(float x) {
        for (int k = 0; k < 5; k++) {
            float[] c = eqC[k], z = eqZ[k];
            float y = c[0] * x + z[0];
            z[0] = c[1] * x - c[3] * y + z[1];
            z[1] = c[2] * x - c[4] * y;
            x = y;
        }
        return x;
    }

    // ---- Ducking: engine drops by 18 dB while a navigation prompt or assistant speaks ----
    private volatile boolean duckNav, duckFocus, musicOn;
    private volatile float duckNow = 1f;
    /** 12-band spectrum of what the engine plays (0..100), for the app's visualiser. */
    static final float[] SPEC_HZ = {40, 60, 90, 130, 190, 280, 400, 580, 850, 1250, 1800, 2600};
    final int[] spectrum = new int[SPEC_HZ.length];

    private void analyse(float[] x, int n) {
        for (int k = 0; k < SPEC_HZ.length; k++) {
            double w = 2 * Math.PI * SPEC_HZ[k] / SR, c = 2 * Math.cos(w), s1 = 0, s2 = 0;
            for (int i = 0; i < n; i++) { double s0 = x[i] + c * s1 - s2; s2 = s1; s1 = s0; }
            double mag = Math.sqrt(Math.max(0, s1 * s1 + s2 * s2 - c * s1 * s2)) / n;
            int v = (int) Math.max(0, Math.min(100, 100 + 20 * Math.log10(mag + 1e-6) * 1.6));
            spectrum[k] = spectrum[k] * 2 / 3 + v / 3;
        }
    }
    private Object playbackCb;

    private void watchPlayback(boolean on) {
        if (Build.VERSION.SDK_INT < 26) return;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (on && playbackCb == null) {
                AudioManager.AudioPlaybackCallback cb = new AudioManager.AudioPlaybackCallback() {
                    @Override
                    public void onPlaybackConfigChanged(java.util.List<android.media.AudioPlaybackConfiguration> configs) {
                        boolean nav = false, music = false;
                        for (android.media.AudioPlaybackConfiguration c : configs) {
                            int u = c.getAudioAttributes().getUsage();
                            // our own engine track is USAGE_GAME (media / focus modes), so MEDIA here is somebody's music
                            if (u == AudioAttributes.USAGE_MEDIA) music = true;
                            if (u == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE || u == AudioAttributes.USAGE_ASSISTANT
                                    || u == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) {
                                // our own track uses the nav channel in "nav" mode: ignore it
                                if (!"nav".equals(prefs(EngineService.this).getString("out", "focus"))) nav = true;
                            }
                        }
                        duckNav = nav;
                        musicOn = music;
                    }
                };
                am.registerAudioPlaybackCallback(cb, main);
                playbackCb = cb;
            } else if (!on && playbackCb != null) {
                am.unregisterAudioPlaybackCallback((AudioManager.AudioPlaybackCallback) playbackCb);
                playbackCb = null;
                duckNav = false;
            }
        } catch (Throwable ignored) {
        }
    }

    /** GPS is only a fallback the driver switches on; real data comes from OBD / CAN. */
    void applyGps() {
        sim.gpsAllowed = prefs(this).getBoolean("gps", false);
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try { lm.removeUpdates(this); } catch (Exception ignored) { }
        if (running && sim.gpsAllowed && hasLocation()) {
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250, 0, this, Looper.getMainLooper()); } catch (Exception ignored) { }
        }
    }

    void applyObd() {
        SharedPreferences p = prefs(this);
        String mode = p.getString("obd", "auto");
        // "auto": use a paired OBD adapter if there is one (Bluetooth ELM327 in the OBD socket).
        if ("auto".equals(mode)) mode = Obd.pairedAdapter() != null ? Obd.BT : "off";
        if (obd != null) obd.stop();
        obd = null;
        obdStatus = "";
        if (Obd.WIFI.equals(mode) || Obd.BT.equals(mode)) {
            obd = new Obd(this);
            obd.start(mode, p.getString("obdTarget", ""), p.getString("obdProto", Obd.STD));
        }
    }

    String name() {
        SoundPacks.Loaded lp = pack;
        return lp != null ? lp.name : synth.profile().name();
    }

    void rev(boolean on) { sim.setRev(on); }

    private java.util.List<Integer> spectrumList() {
        java.util.List<Integer> l = new java.util.ArrayList<>();
        for (int v : spectrum) l.add(running ? v : 0);
        return l;
    }

    String live() {
        long now = System.currentTimeMillis();
        try {
            return new JSONObject().put("rpm", Math.round(sim.rpm)).put("gear", sim.gear).put("manual", sim.manualGear > 0).put("kmh", Math.round(sim.speed))
                    .put("red", Math.round(synth.profile().redline())).put("load", Math.round(sim.load * 100))
                    .put("src", sim.source(now)).put("level", Math.round(level * 100)).put("err", audioError).put("gh", SoundPacks.ghStatus).put("can", now - canStatusAt < 6000 ? canStatus : "").put("on", running).put("obd", obdStatus).put("gps", now - lastFixAt < 3000)
                    .put("duck", Math.round(duckNow * 100)).put("music", musicOn).put("nav", duckNav).put("spec", new JSONArray(spectrumList())).toString();
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

    /** "media" (mixed under the music), "focus" (media, asks for audio focus) or "nav" (navigation voice channel). */
    private AudioAttributes attrs() {
        String out = prefs(this).getString("out", "focus");
        AudioAttributes.Builder b = new AudioAttributes.Builder();
        if ("nav".equals(out)) {
            b.setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
        } else {
            // USAGE_GAME plays on the music stream like media, and lets us tell our track from real music.
            b.setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
        }
        return b.build();
    }

    @SuppressWarnings("deprecation")
    private void takeFocus(boolean take) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (take && "focus".equals(prefs(this).getString("out", "focus"))) {
            if (focus == null) focus = new AudioManager.OnAudioFocusChangeListener() {
                public void onAudioFocusChange(int f) {
                    duckFocus = f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
                }
            };
            am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        } else if (focus != null) {
            am.abandonAudioFocus(focus);
        }
    }

    float level() { return level; }

    private void restartAudio() {
        if (!running) return;
        takeFocus(false);
        takeFocus(true);
        watchPlayback(true);
        final int my = ++gen;
        audio = new Thread(new Runnable() { public void run() { loop(my); } }, "engine-audio");
        audio.start();
    }

    private void loop(int my) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(min, BLOCK * 2 * 8);
        AudioTrack track;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(attrs())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(SR)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(bytes).setTransferMode(AudioTrack.MODE_STREAM).build();
            } else {
                track = new AudioTrack(AudioManager.STREAM_MUSIC, SR, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bytes, AudioTrack.MODE_STREAM);
            }
            track.play();
        } catch (Throwable e) {
            audioError = "Ses çıkışı açılamadı: " + e.getClass().getSimpleName();
            return;
        }
        audioError = "";
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        SharedPreferences p = prefs(this);
        float[] buf = new float[BLOCK];
        short[] pcm = new short[BLOCK];
        float gain = 0, fade = 1, dt = BLOCK / (float) SR;
        long checkAt = 0;
        boolean muted = false;
        float vol = .7f, popLevel = .6f, cut = 1f;
        int blocks = 0;
        boolean pops = true;
        int lastGear = -1;
        long shiftUntil = 0;
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
                    if (my == gen) publish(running, now);
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
                // Gear change: a short torque-cut dip (~120 ms), like a real shift.
                if (lastGear >= 0 && sim.gear != lastGear && sim.gear > 0) shiftUntil = now + 120;
                lastGear = sim.gear;
                cut += ((now < shiftUntil ? .45f : 1f) - cut) * .5f;
                float duck = duckNav || duckFocus ? .125f : 1f;
                if (musicOn && p.getBoolean("duckMusic", false)) duck = Math.min(duck, 1f - Math.max(0, Math.min(90, p.getInt("duckAmt", 40))) / 100f);
                duckNow = duck;
                float want = muted ? 0f : vol * cut * duck;
                boolean useEq = eqOn;
                boolean live = running && my == gen;
                if (!live) fade -= dt / .8f;
                float pk = 0;
                for (int i = 0; i < BLOCK; i++) {
                    gain += (want - gain) * .002f;
                    float x = useEq ? eq(buf[i]) : buf[i];
                    float y = bassB0 * x + bz1;
                    bz1 = bassB1 * x - bassA1 * y + bz2;
                    bz2 = bassB2 * x - bassA2 * y;
                    float v = (float) Math.tanh(y * .8f) * gain * Math.max(0, fade);
                    pcm[i] = (short) Math.max(-32767, Math.min(32767, Math.round(v * 30000)));
                    pk = Math.max(pk, Math.abs(v));
                }
                level = level * .8f + pk * .2f;
                if ((++blocks & 3) == 0) analyse(buf, BLOCK);
                track.write(pcm, 0, BLOCK);
                if (!live && fade <= 0) break;
            }
        } catch (Throwable ignored) {
        } finally {
            try { track.stop(); } catch (Throwable ignored) { }
            track.release();
        }
    }

    /** Tells MYDS (and anyone listening) what the engine is doing. */
    private void publish(boolean on, long now) {
        try {
            sendBroadcast(new Intent("com.catarapatara.LIVE").putExtra("on", on).putExtra("rpm", Math.round(sim.rpm))
                    .putExtra("gear", sim.gear).putExtra("red", Math.round(synth.profile().redline()))
                    .putExtra("load", Math.round(sim.load * 100)).putExtra("name", name()).putExtra("src", sim.source(now)));
        } catch (Exception ignored) {
        }
    }

    /** + / − gear keys of the app screen. */
    void shift(int dir) { sim.shift(dir, System.currentTimeMillis()); }

    /** Called when the settings change from the app screen. */
    void settingsChanged(String key) {
        if ("out".equals(key)) main.post(new Runnable() { public void run() { restartAudio(); } });
        if (key.startsWith("eq")) setEq(prefs(this));
        if ("id".equals(key) || "ex".equals(key) || "bass".equals(key)) {
            main.post(new Runnable() { public void run() { applyEngine(); } });
        }
        if ("obd".equals(key) || "obdTarget".equals(key) || "obdProto".equals(key)) applyObd();
        if ("matchRpm".equals(key)) sim.matchRpm = prefs(this).getBoolean("matchRpm", true);
        if ("gps".equals(key)) main.post(new Runnable() { public void run() { applyGps(); } });
        if ("id".equals(key)) foreground();
    }
}
