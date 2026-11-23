package com.thecarlauncher.media;

import android.content.Context;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;

import com.thecarlauncher.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * Bass / mid / treble like the factory "Duboki - Srednji - Visoki" page, each -9…+9.
 * Mapped onto Android's band equalizer for the built-in player and, where the head unit
 * allows it, the global output mix (session 0) so other apps and Bluetooth audio follow too.
 */
public final class Tone {
    public static final int MIN = -9, MAX = 9;
    /** One step = 1 dB, in millibels. */
    private static final int STEP_MB = 100;
    private static final String P_BASS = "tone_bass", P_MID = "tone_mid", P_TREBLE = "tone_treble";
    private static Tone sInstance;

    private final Context app;
    private final List<Equalizer> eqs = new ArrayList<>();
    private final List<Integer> sessions = new ArrayList<>();
    private int bass, mid, treble;

    // ---- HiFi: a 10-point curve on top of bass/mid/treble, plus bass boost, stage width, loudness ----
    public static final int[] F = {32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    public static final int CMIN = -12, CMAX = 12;
    private final float[] curve = new float[10];
    private int boost, width, loud;        // 0..100 each
    private String preset = "flat";
    private final List<BassBoost> bbs = new ArrayList<>();
    private final List<Virtualizer> vzs = new ArrayList<>();
    private final List<LoudnessEnhancer> lds = new ArrayList<>();

    /**
     * Presets tuned for the DS4's factory Denon system (door woofers, dash mids/tweeters, sub in the boot),
     * dB at F[] = 32 Hz … 16 kHz. Shapes follow common graphic-EQ practice (e.g. the Equalizer APO /
     * AutoEq community curves), softened for a car cabin that already lifts the low end.
     */
    public static final String[][] PRESET_NAMES = {
            {"flat", "Düz"}, {"denon", "Denon Hi-Fi"}, {"live", "Canlı performans"}, {"electronic", "Elektronik"},
            {"rock", "Rock"}, {"blues", "Blues"}, {"jazz", "Caz"}, {"pop", "Pop"}, {"hiphop", "Hip-hop"},
            {"classic", "Klasik"}, {"acoustic", "Akustik"}, {"turkish", "Türk müziği"}, {"vocal", "Vokal / Podcast"},
            {"bass", "Bas"}, {"night", "Gece (kısık seste dolgun)"}, {"road", "Otoyol"}};
    private static float[] presetCurve(String id) {
        switch (id) {
            //                         32    64   125   250   500    1k    2k    4k    8k   16k
            case "denon":      return new float[]{3.5f, 3f, 2f, .5f, -.5f, 0f, -1f, .5f, 1.5f, 2.5f};
            case "live":       return new float[]{2f, 2.5f, 1.5f, 0f, -1f, .5f, 2f, 2.5f, 2.5f, 2f};     // room, open stage, crowd air
            case "electronic": return new float[]{5f, 4.5f, 2f, 0f, -2f, -.5f, 1f, 2f, 3.5f, 4f};     // deep sub, scooped mids, crisp top
            case "rock":       return new float[]{3.5f, 3f, 1.5f, -1f, -1.5f, .5f, 2.5f, 3f, 2.5f, 2f};  // kick, guitars forward, cymbals
            case "blues":      return new float[]{2f, 2.5f, 2f, 1f, .5f, 1.5f, 2f, 1f, 0f, -.5f};      // warm low-mids, guitar & voice body
            case "jazz":       return new float[]{2f, 1.5f, 1f, 1.5f, -1f, -1f, 0f, 1f, 2f, 2.5f};     // upright bass, brushes
            case "pop":        return new float[]{1f, 2f, 2.5f, 1f, -.5f, -.5f, .5f, 1.5f, 2f, 2f};
            case "hiphop":     return new float[]{5.5f, 5f, 2.5f, 1f, -1f, -.5f, 1f, .5f, 1.5f, 2f};
            case "classic":    return new float[]{2f, 1.5f, 1f, 0f, 0f, 0f, -.5f, 0f, 1f, 1.5f};
            case "acoustic":   return new float[]{1f, 1.5f, 1.5f, 1f, .5f, 1f, 1.5f, 2f, 1.5f, 1f};
            case "turkish":    return new float[]{2f, 2f, 1.5f, .5f, 0f, 1f, 2f, 1.5f, 1f, .5f};      // darbuka / bağlama attack, vocals
            case "vocal":      return new float[]{-3f, -2f, -1f, .5f, 1.5f, 2.5f, 3f, 2f, .5f, -1f};
            case "bass":       return new float[]{6f, 5.5f, 4f, 1.5f, 0f, 0f, 0f, .5f, 1f, 1f};
            case "night":      return new float[]{5f, 4.5f, 3f, 1f, 0f, 0f, .5f, 1.5f, 3f, 3.5f};
            case "road":       return new float[]{4f, 4f, 3f, 1f, 0f, .5f, 1.5f, 2f, 2f, 1.5f};     // tyre/wind noise masks lows and presence
            default:           return new float[10];
        }
    }
    private static int[] presetFx(String id) {   // boost, width, loudness
        switch (id) {
            case "denon": return new int[]{15, 20, 0};
            case "live": return new int[]{10, 55, 0};
            case "electronic": return new int[]{35, 30, 0};
            case "rock": return new int[]{20, 20, 0};
            case "blues": return new int[]{10, 15, 0};
            case "jazz": return new int[]{5, 25, 0};
            case "hiphop": return new int[]{45, 15, 0};
            case "acoustic": return new int[]{0, 20, 0};
            case "bass": return new int[]{45, 10, 0};
            case "night": return new int[]{25, 15, 25};
            case "road": return new int[]{20, 15, 15};
            default: return new int[]{0, 0, 0};
        }
    }

    // ---- sound focus (Sürücü / Tüm yolcular) through the head unit's own channel attenuation ----
    private String focus = "all";
    public synchronized boolean focus(String f) {
        focus = "driver".equals(f) ? "driver" : "all";
        Prefs.of(app).edit().putString("hifi_focus", focus).apply();
        return applyFocus();
    }
    private boolean applyFocus() {
        try {
            // Same call the factory CAN app makes: attenuation per channel (FL, FR, RL, RR), 0 = full.
            Class<?> c = Class.forName("android.audio.AudioEffect");
            Object ae = c.getMethod("getInstance").invoke(null);
            java.lang.reflect.Method m = c.getMethod("setBalance", int.class, int.class, int.class, int.class);
            if ("driver".equals(focus)) m.invoke(ae, 0, 3, 4, 6); else m.invoke(ae, 0, 0, 0, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    public static boolean hasFocus() {
        try { Class.forName("android.audio.AudioEffect").getMethod("setBalance", int.class, int.class, int.class, int.class); return true; }
        catch (Throwable t) { return false; }
    }

    // ---- speed-dependent volume (Hıza bağlı ses): +1 step per N km/h above 40 ----
    private int svc;          // 0 off, 1..3
    private int added = 0, lastSet = -1;
    public synchronized void svc(int level) { svc = Math.max(0, Math.min(3, level)); Prefs.of(app).edit().putInt("hifi_svc", svc).apply(); }
    public synchronized void speed(float kmh) {
        android.media.AudioManager am = (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        if (lastSet >= 0 && cur != lastSet) added = 0;          // the driver turned the knob: that is the new base
        int want = 0;
        if (svc > 0 && kmh > 40) want = Math.min(svc + 1, (int) ((kmh - 40) / (svc == 3 ? 20 : svc == 2 ? 28 : 40)) + (kmh > 50 ? 1 : 0));
        if (want == added) { lastSet = cur; return; }
        int base = cur - added;
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        int v = Math.max(0, Math.min(max, base + want));
        try { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0); } catch (Throwable ignored) { }
        added = v - base; lastSet = v;
    }
    }
    private boolean globalTried;
    /** Player sessions waiting for an equalizer until a tone is actually set. */
    private final List<Integer> pending = new ArrayList<>();

    public static synchronized Tone get(Context c) {
        if (sInstance == null) sInstance = new Tone(c.getApplicationContext());
        return sInstance;
    }

    private Tone(Context c) {
        app = c;
        bass = Prefs.integer(c, P_BASS, 0);
        mid = Prefs.integer(c, P_MID, 0);
        treble = Prefs.integer(c, P_TREBLE, 0);
        try {
            String cv = Prefs.str(c, "hifi_curve", null);
            if (cv != null) { String[] p = cv.split(","); for (int i = 0; i < 10 && i < p.length; i++) curve[i] = Float.parseFloat(p[i]); }
        } catch (Exception ignored) { }
        boost = Prefs.integer(c, "hifi_boost", 0); width = Prefs.integer(c, "hifi_width", 0); loud = Prefs.integer(c, "hifi_loud", 0);
        preset = Prefs.str(c, "hifi_preset", "flat");
        focus = Prefs.str(c, "hifi_focus", "all");
        svc = Prefs.integer(c, "hifi_svc", 0);
        if ("driver".equals(focus)) applyFocus();
        // Other players (the unit's Bluetooth music app, Spotify…) announce their audio sessions:
        // the tone follows them too, not only the launcher's own player.
        android.content.IntentFilter f = new android.content.IntentFilter(
                android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) c.registerReceiver(sessions(), f, Context.RECEIVER_EXPORTED);
            else c.registerReceiver(sessions(), f);
        } catch (Exception ignored) {
        }
        if (!flat()) attachGlobal();
    }

    private android.content.BroadcastReceiver sessions() {
        return new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, android.content.Intent i) {
                int id = i.getIntExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, -1);
                if (id <= 0) return;
                if (android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) detach(id);
                else if (!sessions.contains(id) && !pending.contains(id)) attach(id);
            }
        };
    }

    /** Called at launcher start: with a tone set, the whole output (Bluetooth music too) gets it. */
    public void init() {
        if (!flat()) attachGlobal();
    }

    public int bass() { return bass; }
    public int mid() { return mid; }
    public int treble() { return treble; }

    /** True once at least one equalizer accepted the settings. */
    public boolean available() {
        attachGlobal();
        return !eqs.isEmpty();
    }

    public void set(int bass, int mid, int treble) {
        this.bass = clamp(bass);
        this.mid = clamp(mid);
        this.treble = clamp(treble);
        Prefs.of(app).edit().putInt(P_BASS, this.bass).putInt(P_MID, this.mid).putInt(P_TREBLE, this.treble).apply();
        if (!flat()) {
            for (Integer id : new ArrayList<>(pending)) create(id);
            pending.clear();
            attachGlobal();
        }
        apply();
    }

    public void reset() { set(0, 0, 0); }

    private static int clamp(int v) { return Math.max(MIN, Math.min(MAX, v)); }

    private boolean flat() {
        if (bass != 0 || mid != 0 || treble != 0 || boost != 0 || width != 0 || loud != 0) return false;
        for (float v : curve) if (Math.abs(v) > .05f) return false;
        return true;
    }

    // ---------------------------------------------------------------- HiFi API (ses.html)
    public synchronized org.json.JSONObject json() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            attachGlobal();
            org.json.JSONArray c = new org.json.JSONArray(), f = new org.json.JSONArray(), dev = new org.json.JSONArray(), pr = new org.json.JSONArray();
            for (int i = 0; i < 10; i++) { c.put(curve[i]); f.put(F[i]); }
            Equalizer e = eqs.isEmpty() ? null : eqs.get(0);
            if (e != null) {
                for (short b = 0; b < e.getNumberOfBands(); b++) dev.put(e.getCenterFreq(b) / 1000);
                short[] r = e.getBandLevelRange();
                o.put("devMin", r[0] / 100).put("devMax", r[1] / 100);
            }
            for (String[] p : PRESET_NAMES) pr.put(new org.json.JSONArray().put(p[0]).put(p[1]));
            o.put("ok", e != null).put("curve", c).put("freqs", f).put("devBands", dev).put("presets", pr).put("preset", preset)
                    .put("boost", boost).put("width", width).put("loud", loud)
                    .put("bass", bass).put("mid", mid).put("treble", treble)
                    .put("hasBoost", !bbs.isEmpty()).put("hasWidth", !vzs.isEmpty()).put("hasLoud", !lds.isEmpty())
                    .put("sessions", sessions.size()).put("focus", focus).put("hasFocus", hasFocus()).put("svc", svc);
        } catch (Exception ignored) { }
        return o;
    }

    public synchronized void preset(String id) {
        float[] c = presetCurve(id); int[] fx = presetFx(id);
        System.arraycopy(c, 0, curve, 0, 10);
        boost = fx[0]; width = fx[1]; loud = fx[2]; preset = id;   // bass / treble knobs stay as set
        save();
    }

    public synchronized void bassTreble(int b, int t) {
        bass = clamp(b); treble = clamp(t); save();
    }

    public synchronized void band(int i, float db) {
        if (i < 0 || i >= 10) return;
        curve[i] = Math.max(CMIN, Math.min(CMAX, Math.round(db * 2) / 2f));
        preset = "custom"; save();
    }

    public synchronized void fx(String which, int v) {
        v = Math.max(0, Math.min(100, v));
        if ("boost".equals(which)) boost = v; else if ("width".equals(which)) width = v; else if ("loud".equals(which)) loud = v;
        save();
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) { if (i > 0) sb.append(','); sb.append(curve[i]); }
        Prefs.of(app).edit().putString("hifi_curve", sb.toString()).putInt("hifi_boost", boost).putInt("hifi_width", width)
                .putInt("hifi_loud", loud).putString("hifi_preset", preset)
                .putInt(P_BASS, bass).putInt(P_MID, mid).putInt(P_TREBLE, treble).apply();
        if (!flat()) {
            for (Integer id : new ArrayList<>(pending)) create(id);
            pending.clear();
            attachGlobal();
        }
        apply();
    }

    /** dB of the 10-point curve at frequency f (log-linear between points). */
    private float curveAt(double f) {
        double lf = Math.log(Math.max(F[0], Math.min(F[9], f)));
        for (int i = 0; i < 9; i++) {
            double a = Math.log(F[i]), b = Math.log(F[i + 1]);
            if (lf <= b) return (float) (curve[i] + (curve[i + 1] - curve[i]) * (lf - a) / (b - a));
        }
        return curve[9];
    }

    /**
     * Hooks the tone control onto an audio session (the built-in player's). With all tones at 0 no
     * equalizer is created: some head-unit audio HALs fail or stall on one, and a flat one does nothing.
     */
    public void attach(int sessionId) {
        if (flat()) {
            if (!pending.contains(sessionId)) pending.add(sessionId);
            return;
        }
        create(sessionId);
    }

    private void create(int sessionId) {
        try {
            Equalizer e = new Equalizer(0, sessionId);
            eqs.add(e);
            sessions.add(sessionId);
            apply(e);   // bass boost / width / loudness live on the global mix only (no per-session leftovers)
        } catch (Throwable ignored) {
            // No equalizer effect in this audio HAL.
        }
    }

    public void detach(int sessionId) {
        pending.remove(Integer.valueOf(sessionId));
        for (int i = eqs.size() - 1; i >= 0; i--) {
            if (sessions.get(i) != sessionId) continue;
            try {
                eqs.get(i).release();
            } catch (Throwable ignored) {
            }
            eqs.remove(i);
            sessions.remove(i);
        }
    }

    /** The global output mix: deprecated by Android but still honoured by most head-unit ROMs. */
    @SuppressWarnings("deprecation")
    public void attachGlobal() {
        if (globalTried) return;
        globalTried = true;
        try {
            Equalizer e = new Equalizer(0, 0);
            eqs.add(e);
            sessions.add(0);
            apply(e);
        } catch (Throwable ignored) {
        }
        try {
            extras(0);
        } catch (Throwable ignored) {
        }
    }

    /** Bass boost, virtualizer (stage width) and loudness enhancer on one session, where the unit has them. */
    private void extras(int sessionId) {
        try { BassBoost b = new BassBoost(0, sessionId); bbs.add(b); } catch (Throwable ignored) { }
        try { Virtualizer v = new Virtualizer(0, sessionId); vzs.add(v); } catch (Throwable ignored) { }
        try { LoudnessEnhancer l = new LoudnessEnhancer(sessionId); lds.add(l); } catch (Throwable ignored) { }
        applyFx();
    }

    private void applyFx() {
        for (int i = bbs.size() - 1; i >= 0; i--) {
            try { BassBoost b = bbs.get(i); if (b.getStrengthSupported()) b.setStrength((short) (boost * 10)); b.setEnabled(boost > 0); }
            catch (Throwable t) { bbs.remove(i); }
        }
        for (int i = vzs.size() - 1; i >= 0; i--) {
            try { Virtualizer v = vzs.get(i); if (v.getStrengthSupported()) v.setStrength((short) (width * 10)); v.setEnabled(width > 0); }
            catch (Throwable t) { vzs.remove(i); }
        }
        for (int i = lds.size() - 1; i >= 0; i--) {
            // up to +8 dB make-up gain for quiet listening; the limiter inside keeps it from clipping
            try { LoudnessEnhancer l = lds.get(i); l.setTargetGain(loud * 8); l.setEnabled(loud > 0); }
            catch (Throwable t) { lds.remove(i); }
        }
    }

    private void apply() {
        applyFx();
        for (int i = eqs.size() - 1; i >= 0; i--) {
            try {
                apply(eqs.get(i));
            } catch (Throwable t) {
                eqs.remove(i); // released by the system
                sessions.remove(i);
            }
        }
    }

    /**
     * Each band gets a blend of bass (anchored at 80 Hz), mid (1 kHz) and treble (10 kHz),
     * interpolated on a log-frequency scale.
     */
    private void apply(Equalizer e) {
        short[] range = e.getBandLevelRange();
        boolean flat = bass == 0 && mid == 0 && treble == 0;
        for (short b = 0; b < e.getNumberOfBands(); b++) {
            double f = Math.log10(Math.max(20, e.getCenterFreq(b) / 1000.0));
            double lb = Math.log10(80), lm = Math.log10(1000), lt = Math.log10(10000);
            double level;
            if (f <= lb) level = bass;
            else if (f <= lm) level = bass + (mid - bass) * (f - lb) / (lm - lb);
            else if (f <= lt) level = mid + (treble - mid) * (f - lm) / (lt - lm);
            else level = treble;
            level += curveAt(e.getCenterFreq(b) / 1000.0);
            int mb = (int) Math.round(level * STEP_MB);
            e.setBandLevel(b, (short) Math.max(range[0], Math.min(range[1], mb)));
        }
        e.setEnabled(!flat());
    }
}
