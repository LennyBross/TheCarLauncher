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

    /** Current station: [freq in kHz, stereo 0/1, preset index] or null. FM band names start with "FM". */
    public static int[] info() {
        Object p = player();
        if (p == null) return null;
        try {
            Object ri = sCls.getMethod("getRadioInfo").invoke(p);
            if (ri == null) return null;
            int f = intField(ri, "mFreq");
            Object st = field(ri, "mIsStereo");
            boolean stereo = st instanceof Boolean ? (Boolean) st : st instanceof Number && ((Number) st).intValue() != 0;
            return new int[]{f, stereo ? 1 : 0, intField(ri, "mIndexof")};
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

    /** Tunes to a frequency (kHz, e.g. 98500 for 98.5 MHz) on the current band. */
    public static boolean tune(int khz) {
        Object p = player();
        if (p == null) return false;
        // Plain setters first, if this firmware has one.
        for (String n : new String[]{"setFreq", "setFrequency", "tune", "setCurFreq", "playFreq", "gotoFreq"}) {
            if (call(p, n, khz)) return true;
        }
        try {
            Object band = sCls.getMethod("getUiband").invoke(p);
            for (Method m : sCls.getMethods()) {
                if (!m.getName().equals("setUibandIndexFreq") || m.getParameterTypes().length != 3) continue;
                int[] cur = info();
                int idx = cur == null ? 0 : Math.max(0, cur[2]);
                m.invoke(p, band, idx, khz);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Seeks to the next station up or down (falls back to a 100 kHz step). */
    public static void seek(boolean up) {
        Object p = player();
        if (p == null) return;
        String[] names = up ? new String[]{"seekUp", "searchUp", "nextStation", "seekNext", "autoSeekUp"}
                : new String[]{"seekDown", "searchDown", "prevStation", "seekPrev", "autoSeekDown"};
        for (String n : names) if (call(p, n)) return;
        for (String n : new String[]{"seek", "search", "autoSeek"}) if (call(p, n, up)) return;
        step(up);
    }

    /** One step (100 kHz on FM, 9 kHz on AM). */
    public static void step(boolean up) {
        int[] i = info();
        if (i == null) return;
        boolean fm = band().toUpperCase(Locale.ROOT).startsWith("FM") || i[0] > 30000;
        int st = fm ? 100 : 9;
        int f = i[0] + (up ? st : -st);
        if (fm) { if (f > 108000) f = 87500; if (f < 87500) f = 108000; }
        tune(f);
    }

    /** Makes sure the tuner plays (some builds need an explicit start). */
    public static void play() {
        Object p = player();
        if (p == null) return;
        for (String n : new String[]{"play", "open", "start", "resume", "powerOn"}) if (call(p, n)) return;
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
