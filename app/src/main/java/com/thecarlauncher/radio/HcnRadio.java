package com.thecarlauncher.radio;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * FM/AM radio on AutoChips (AC8227L) units with the HCN firmware: the tuner is driven through the
 * firmware's own framework class android.radio.RadioPlayer (the factory radio and CAN apps use it).
 * Everything goes through reflection; methods are looked up by name so slightly different
 * firmware builds still work, and {@link #describe()} lists the real API for the device report.
 */
public final class HcnRadio {
    private static Object sPlayer;
    private static Class<?> sCls;
    private static boolean sTried;

    private HcnRadio() {}

    private static synchronized Object player() {
        if (sTried) return sPlayer;
        sTried = true;
        try {
            sCls = Class.forName("android.radio.RadioPlayer");
            sPlayer = sCls.getMethod("getRadioPlayer").invoke(null);
        } catch (Throwable t) {
            sPlayer = null;
        }
        return sPlayer;
    }

    public static boolean available() { return player() != null; }

    /*
     * How the firmware works (from the factory CAN app, com.hcn.autocan):
     *  - RadioInfo.mFreq is in 10 kHz units on FM (9850 = 98.50 MHz) and in kHz on AM; mUiBand "FM-1".."AM-1".
     *  - RadioPlayer.setUibandIndexFreq(RadioPlayer.BAND.FM1, 0, freq) tunes (same units as mFreq).
     *  - RadioPlayer.getPreset(BAND) returns the band's stored stations.
     *  - android.sourceservice.SourceInfo.getInstance().onRequestPlayAudio("com.hcn.autoradio") makes the
     *    tuner the active audio source; getSourcePackage() says which source plays now.
     */
    public static final String RADIO_PKG = "com.hcn.autoradio";

    private static boolean fm(String band, int raw) {
        String b = band == null ? "" : band.toUpperCase(Locale.ROOT);
        return b.startsWith("FM") || (!b.startsWith("AM") && raw > 3000);
    }

    /** Current station: [freq in kHz, stereo 0/1, preset index] or null. */
    public static int[] info() {
        Object p = player();
        if (p == null) return null;
        try {
            Object ri = sCls.getMethod("getRadioInfo").invoke(p);
            if (ri == null) return null;
            int raw = intField(ri, "mFreq");
            Object b = field(ri, "mUiBand");
            int khz = fm(b == null ? "" : b.toString(), raw) ? raw * 10 : raw;
            Object st = field(ri, "mIsStereo");
            boolean stereo = st instanceof Boolean ? (Boolean) st : st instanceof Number && ((Number) st).intValue() != 0;
            return new int[]{khz, stereo ? 1 : 0, intField(ri, "mIndexof")};
        } catch (Throwable t) {
            return null;
        }
    }

    public static String band() {
        Object p = player();
        if (p == null) return "";
        try {
            Object ri = sCls.getMethod("getRadioInfo").invoke(p);
            Object b = field(ri, "mUiBand");
            return b == null ? "" : b.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** RadioPlayer.BAND constant by name ("FM1", "AM1"…), or null. */
    private static Object bandConst(String name) {
        try {
            for (Class<?> c : sCls.getDeclaredClasses()) {
                if (!c.getSimpleName().equals("BAND")) continue;
                if (c.isEnum()) for (Object e : c.getEnumConstants()) if (e.toString().equalsIgnoreCase(name)) return e;
                try { return c.getField(name).get(null); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object curBand() {
        try { return sCls.getMethod("getUiband").invoke(player()); } catch (Throwable t) { return null; }
    }

    /** Tunes to a frequency in kHz (98500 = 98.5 MHz; 531..1602 = AM). */
    public static boolean tune(int khz) {
        Object p = player();
        if (p == null) return false;
        boolean wantFm = khz > 30000;
        Object band = curBand();
        String bn = band == null ? "" : band.toString().toUpperCase(Locale.ROOT).replace("-", "");
        if (band == null || bn.startsWith("FM") != wantFm) band = bandConst(wantFm ? "FM1" : "AM1");
        int raw = wantFm ? Math.round(khz / 10f) : khz;
        try {
            for (Method m : sCls.getMethods()) {
                if (!m.getName().equals("setUibandIndexFreq") || m.getParameterTypes().length != 3) continue;
                m.invoke(p, band, 0, raw);
                return true;
            }
        } catch (Throwable ignored) {
        }
        for (String n : new String[]{"setFreq", "setFrequency", "tune", "setCurFreq", "playFreq", "gotoFreq"}) {
            if (call(p, n, raw)) return true;
        }
        return false;
    }

    /** Stations stored in the firmware for the current band (kHz). */
    public static int[] presets() {
        Object p = player();
        if (p == null) return new int[0];
        try {
            Object band = curBand();
            if (band == null) band = bandConst("FM1");
            String b = band == null ? "FM" : band.toString();
            Object r = null;
            for (Method m : sCls.getMethods()) {
                if (m.getName().equals("getPreset") && m.getParameterTypes().length == 1) { r = m.invoke(p, band); break; }
            }
            if (!(r instanceof int[])) return new int[0];
            int[] raw = (int[]) r, out = new int[raw.length];
            for (int i = 0; i < raw.length; i++) out[i] = fm(b, raw[i]) ? raw[i] * 10 : raw[i];
            return out;
        } catch (Throwable t) {
            return new int[0];
        }
    }

    /** Seeks to the next station up or down (falls back to a 100 kHz step). */
    public static void seek(boolean up) {
        Object p = player();
        if (p == null) return;
        String[] names = up ? new String[]{"seekUp", "searchUp", "nextStation", "seekNext", "autoSeekUp", "seekForward", "next"}
                : new String[]{"seekDown", "searchDown", "prevStation", "seekPrev", "autoSeekDown", "seekBackward", "prev"};
        for (String n : names) if (call(p, n)) return;
        for (String n : new String[]{"seek", "search", "autoSeek"}) if (call(p, n, up)) return;
        step(up);
    }

    /** One step (100 kHz on FM, 9 kHz on AM). */
    public static void step(boolean up) {
        int[] i = info();
        if (i == null) return;
        boolean fm = i[0] > 30000;
        int st = fm ? 100 : 9;
        int f = i[0] + (up ? st : -st);
        if (fm) { if (f > 108000) f = 87500; if (f < 87500) f = 108000; }
        tune(f);
    }

    /** Which package owns the speakers now (e.g. com.hcn.autoradio), or "" if unknown. */
    public static String source() {
        try {
            Class<?> c = Class.forName("android.sourceservice.SourceInfo");
            Object si = c.getMethod("getInstance").invoke(null);
            Object r = c.getMethod("getSourcePackage").invoke(si);
            return r == null ? "" : r.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Requests the tuner as audio source only when something else plays. */
    public static void ensure() { if (!active()) play(); }

    /** True when the tuner is the active audio source. */
    public static boolean active() { return RADIO_PKG.equals(source()); }

    /**
     * Makes the tuner the active audio source without opening the factory radio app
     * (the same system call the factory apps use), then starts the tuner.
     */
    public static boolean play() {
        boolean ok = false;
        try {
            Class<?> c = Class.forName("android.sourceservice.SourceInfo");
            Object si = c.getMethod("getInstance").invoke(null);
            if (si != null) {
                c.getMethod("onRequestPlayAudio", String.class).invoke(si, RADIO_PKG);
                ok = true;
            }
        } catch (Throwable ignored) {
        }
        Object p = player();
        if (p != null) for (String n : new String[]{"play", "open", "start", "resume", "powerOn"}) if (call(p, n)) break;
        return ok;
    }

    /** The class's API, for the device report. */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        Object p = player();
        sb.append("  android.radio.RadioPlayer: ").append(p != null ? "available" : "not available").append('\n');
        if (sCls != null) {
            for (Method m : sCls.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                sb.append("    ").append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int k = 0; k < ps.length; k++) sb.append(k > 0 ? ", " : "").append(ps[k].getSimpleName());
                sb.append(")\n");
            }
            for (Class<?> in : sCls.getDeclaredClasses()) sb.append("    class ").append(in.getSimpleName()).append(in.isInterface() ? " (interface)" : "").append('\n');
        }
        int[] i = info();
        if (i != null) sb.append("  now: ").append(i[0]).append(" kHz band=").append(band()).append(" stereo=").append(i[1]).append(" index=").append(i[2]).append('\n');
        for (String cn : new String[]{"android.radio.RadioInfo", "android.sourceservice.SourceManager", "android.carsource.McuManager"}) {
            try {
                Class<?> c = Class.forName(cn);
                sb.append("  ").append(cn).append(":");
                for (Method m : c.getDeclaredMethods()) if (Modifier.isPublic(m.getModifiers())) sb.append(' ').append(m.getName());
                for (Field f : c.getDeclaredFields()) if (Modifier.isPublic(f.getModifiers())) sb.append(" .").append(f.getName());
                sb.append('\n');
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    // ---- reflection helpers ----

    private static boolean call(Object p, String name, Object... args) {
        for (Method m : sCls.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
            try {
                Class<?>[] ps = m.getParameterTypes();
                Object[] a = new Object[args.length];
                for (int k = 0; k < args.length; k++) {
                    Object v = args[k];
                    if ((ps[k] == int.class || ps[k] == Integer.class) && v instanceof Integer) a[k] = v;
                    else if ((ps[k] == boolean.class || ps[k] == Boolean.class) && v instanceof Boolean) a[k] = v;
                    else if ((ps[k] == float.class) && v instanceof Integer) a[k] = ((Integer) v) / 1000f; // MHz
                    else { a = null; break; }
                }
                if (a == null) continue;
                m.invoke(p, a);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static Object field(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intField(Object o, String name) {
        Object v = field(o, name);
        return v instanceof Number ? ((Number) v).intValue() : -1;
    }
}
