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

    public static synchronized String start(String spec) {
        if (tw != null) return "zaten açık · " + status;
        int from = 0x0100, to = 0x011F;
        try { if (spec.contains("-")) { String[] p = spec.split("-"); from = Integer.decode(p[0].trim()); to = Integer.decode(p[1].trim()); } } catch (Throwable ignored) { }
        if (to - from > 63) return "en fazla 64 kanal (çökme riski)";
        short[] ids = new short[to - from + 1];
        for (int k = 0; k < ids.length; k++) ids[k] = (short) (from + k);
        try {
            Class<?> k = Class.forName("android.tw.john.TWUtil");
            Object o = k.getConstructor().newInstance();
            int rc = (Integer) k.getMethod("open", short[].class).invoke(o, (Object) ids);
            ht = new HandlerThread("twsniff"); ht.start();
            Handler h = new Handler(ht.getLooper()) {
                @Override public void handleMessage(Message m) {
                    String v = String.format(Locale.ROOT, "%d %d %s", m.arg1, m.arg2, fmt(m.obj));
                    String line = String.format(Locale.ROOT, "%6.2f %04X %s", (System.currentTimeMillis() - t0) / 1000.0, m.what & 0xFFFF, v);
                    synchronized (log) {
                        log.addLast(line); while (log.size() > 1500) log.removeFirst();
                        String[] kv = kinds.get(m.what);
                        if (kv == null) kinds.put(m.what, new String[]{"1", v, "1"});
                        else { kv[0] = String.valueOf(Integer.parseInt(kv[0]) + 1); if (!kv[1].equals(v)) kv[2] = String.valueOf(Integer.parseInt(kv[2]) + 1); kv[1] = v; }
                    }
                }
            };
            k.getMethod("addHandler", String.class, Handler.class).invoke(o, "myds", h);
            k.getMethod("start").invoke(o);
            tw = o; t0 = System.currentTimeMillis();
            status = String.format(Locale.ROOT, "açık rc=%d id %04X-%04X", rc, from, to);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            status = "hata " + c;
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
                b.append(String.format(Locale.ROOT, "%04X ×%s değişim %s  %s%n", e.getKey() & 0xFFFF, e.getValue()[0], e.getValue()[2], e.getValue()[1]));
        }
        return b.toString();
    }

    public static void clear() { synchronized (log) { log.clear(); kinds.clear(); } t0 = System.currentTimeMillis(); }
}
