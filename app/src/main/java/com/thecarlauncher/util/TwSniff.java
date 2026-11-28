package com.thecarlauncher.util;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Listen-only tap on the Topway MCU channel (android.tw.john.TWUtil): opens a range of message ids and
 * records what arrives. write() is never called, so nothing goes to the MCU or the car.
 */
public final class TwSniff {
    static final ArrayDeque<String> log = new ArrayDeque<>();
    static final TreeMap<Integer, String[]> kinds = new TreeMap<>();
    public static volatile String status = "kapalı";
    static Object tw;
    static HandlerThread ht;
    static long t0;

    private TwSniff() {}

    static String fmt(Object o) {
        if (o == null) return "";
        if (o instanceof byte[]) { StringBuilder s = new StringBuilder("b:"); for (byte x : (byte[]) o) s.append(String.format(Locale.ROOT, "%02X", x & 0xFF)); return s.toString(); }
        if (o instanceof int[]) return "i:" + java.util.Arrays.toString((int[]) o);
        if (o instanceof short[]) return "s:" + java.util.Arrays.toString((short[]) o);
        if (o instanceof long[]) return "l:" + java.util.Arrays.toString((long[]) o);
        if (o instanceof Object[]) return "o:" + java.util.Arrays.deepToString((Object[]) o);
        return o.getClass().getSimpleName() + ":" + o;
    }

    /** Lets this process see the vendor's hidden framework API (Android 10: meta-reflection on VMRuntime). */
    static String exempt() {
        try {
            java.lang.reflect.Method forName = Class.class.getDeclaredMethod("forName", String.class);
            java.lang.reflect.Method gdm = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vm = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            java.lang.reflect.Method rt = (java.lang.reflect.Method) gdm.invoke(vm, "getRuntime", null);
            java.lang.reflect.Method ex = (java.lang.reflect.Method) gdm.invoke(vm, "setHiddenApiExemptions", new Object[]{new Class[]{String[].class}});
            ex.invoke(rt.invoke(null), new Object[]{new String[]{"L"}});
            return "gizli API açık";
        } catch (Throwable t) { return "gizli API: " + (t.getCause() != null ? t.getCause() : t); }
    }

    /** Default: the channels the TS10 car app itself reads (canbox frames, car setup, reverse, power, engine, battery). */
    static final String DEFAULT = "0x501,0x10B,0x20B,0x9F1C,0x202,0x50B,0x50C";

    public static synchronized String start(String spec) {
        if (tw != null) return "zaten açık · " + status;
        if (spec == null || spec.trim().length() == 0 || spec.equals("null")) spec = DEFAULT;
        String[] parts = spec.split(",");
        if (parts.length > 64) return "en fazla 64 kanal";
        short[] ids = new short[parts.length];
        for (int k = 0; k < parts.length; k++) ids[k] = (short) (int) Integer.decode(parts[k].trim());
        String ex = exempt();
        try {
            Class<?> k = Class.forName("android.tw.john.TWUtil");
            Object o = k.getConstructor().newInstance();
            int rc = (Integer) k.getMethod("open", short[].class).invoke(o, (Object) ids);
            if (rc != 0) { status = ex + " · open rc=" + rc + " (izin yok)"; return status; }
            k.getMethod("start").invoke(o);
            ht = new HandlerThread("twsniff"); ht.start();
            Handler h = new Handler(ht.getLooper()) {
                @Override public void handleMessage(Message m) {
                    try {
                        String v = String.format(Locale.ROOT, "%d %d %s", m.arg1, m.arg2, fmt(m.obj));
                        String line = String.format(Locale.ROOT, "%6.2f %04X %s", (System.currentTimeMillis() - t0) / 1000.0, m.what & 0xFFFF, v);
                        int key = (m.what & 0xFFFF) == 0x501 ? (0x501 << 16) | (m.arg1 & 0xFFFF) : (m.what & 0xFFFF);
                        synchronized (log) {
                            log.addLast(line); while (log.size() > 3000) log.removeFirst();
                            String[] kv = kinds.get(key);
                            if (kv == null) kinds.put(key, new String[]{"1", v, "1"});
                            else { kv[0] = String.valueOf(Integer.parseInt(kv[0]) + 1); if (!kv[1].equals(v)) kv[2] = String.valueOf(Integer.parseInt(kv[2]) + 1); kv[1] = v; }
                        }
                    } catch (Throwable ignored) { }
                }
            };
            k.getMethod("addHandler", String.class, Handler.class).invoke(o, "myds", h);
            tw = o; t0 = System.currentTimeMillis();
            status = ex + " · açık " + spec;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            status = ex + " · hata " + c;
        }
        return status;
    }

    public static synchronized String stop() {
        try { if (tw != null) { tw.getClass().getMethod("stop").invoke(tw); tw.getClass().getMethod("close").invoke(tw); } } catch (Throwable ignored) { }
        tw = null; if (ht != null) ht.quitSafely(); ht = null; status = "kapalı";
        return status;
    }

    /** "twlog [n] [filtre]": newest first. */
    public static String dump(String arg) {
        String[] p = arg.trim().split("\\s+");
        int n = 150; String f = null;
        try { if (p.length > 0 && p[0].length() > 0) n = Integer.parseInt(p[0]); } catch (NumberFormatException e) { f = p[0].toUpperCase(Locale.ROOT); }
        if (p.length > 1) f = p[1].toUpperCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(status).append(" · ").append(log.size()).append(" satır\n");
        synchronized (log) {
            String[] a = log.toArray(new String[0]);
            int shown = 0;
            for (int k = a.length - 1; k >= 0 && shown < n; k--) if (f == null || a[k].toUpperCase(Locale.ROOT).contains(f)) { b.append(a[k]).append('\n'); shown++; }
        }
        return b.toString();
    }

    /** "twtur": every id seen, how often, how many times its value changed, latest value. */
    public static String kinds() {
        StringBuilder b = new StringBuilder(status).append('\n');
        synchronized (log) {
            for (java.util.Map.Entry<Integer, String[]> e : kinds.entrySet())
                b.append(e.getKey() > 0xFFFF ? String.format(Locale.ROOT, "0501/%d", e.getKey() & 0xFFFF) : String.format(Locale.ROOT, "%04X", e.getKey()))
                 .append(String.format(Locale.ROOT, " ×%s değişim %s  %s%n", e.getValue()[0], e.getValue()[2], e.getValue()[1]));
        }
        return b.toString();
    }

    public static void clear() { synchronized (log) { log.clear(); kinds.clear(); } t0 = System.currentTimeMillis(); }
}
