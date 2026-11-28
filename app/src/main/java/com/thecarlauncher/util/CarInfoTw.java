package com.thecarlauncher.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Read-only client for the Topway car-info service (com.tw.carinfoservice): registers a callback with
 * the descriptor com.tw.carinfoservice.CarServiceCallBack and records every value the service pushes.
 * Nothing is ever sent towards the car.
 */
public final class CarInfoTw {
    static final String SVC = "com.tw.carinfoservice.CarServiceAidl", CB = "com.tw.carinfoservice.CarServiceCallBack";
    /** Argument layout of each callback code, read from the service's own proxy (f=float, s=String, i=int, b=boolean, B=Bundle). */
    static final Map<Integer, String> SIG = new TreeMap<>();
    static {
        String[] t = {"1fs","2b","3i","4fs","5fs","6fs","7fs","8i","9b","10b","11b","12b","13b","14b","15i","16i","17is","18fs","19fs","20fs",
                "21f","22i","23b","24b","25b","26b","27b","29fs","30fs","31fs","32fs","33fs","34i","35b","36b","37fs","38fs","39fs","40fs",
                "41fs","42fs","43fs","44fs","45fs","46fs","47s","48b","49is","50ii","51ii","52ii","53ii","54is","55is","56is","57is",
                "58b","59iiiiiiiiiiiiiiii","60i","61i","62fs","63B","64fs","65b","66b"};
        for (String x : t) { int k = 0; while (Character.isDigit(x.charAt(k))) k++; SIG.put(Integer.parseInt(x.substring(0, k)), x.substring(k)); }
    }

    /** Latest value per callback code, e.g. 42 → "300.0 km". */
    public static final Map<Integer, String> last = new TreeMap<>();
    public static volatile long at;
    public static volatile String status = "";
    private static IBinder svc;
    private static ServiceConnection conn;

    static final Binder cb = new Binder() {
        @Override protected boolean onTransact(int code, Parcel d, Parcel r, int flags) {
            if (code < 1 || code > 0xFFFFFF) return false;
            try {
                d.enforceInterface(CB);
                String sig = SIG.get(code), v;
                if (sig == null) v = "?" + d.dataAvail() + "B";
                else {
                    StringBuilder b = new StringBuilder();
                    for (char ch : sig.toCharArray()) {
                        if (b.length() > 0) b.append(' ');
                        switch (ch) {
                            case 'f': b.append(d.readFloat()); break;
                            case 's': b.append(d.readString()); break;
                            case 'i': b.append(d.readInt()); break;
                            case 'b': b.append(d.readInt() != 0); break;
                            case 'B': { if (d.readInt() != 0) { android.os.Bundle bu = android.os.Bundle.CREATOR.createFromParcel(d); b.append(bu); } else b.append("null"); break; }
                        }
                    }
                    v = b.toString();
                }
                synchronized (last) { last.put(code, v); }
                at = System.currentTimeMillis();
                onValue(code, v);
            } catch (Throwable t) { status = "cb " + code + " " + t; }
            if (r != null) r.writeNoException();
            return true;
        }
    };

    /** Hook for mapping codes to range / fuel etc. once identified. */
    static void onValue(int code, String v) {
        Integer rc = rangeCode;
        if (rc != null && rc == code) {
            try { float km = Float.parseFloat(v.split(" ")[0]); if (km > 0 && km < 2000) { CanLive.range = km; CanLive.canAt = System.currentTimeMillis(); } } catch (Throwable ignored) { }
        }
    }
    public static volatile Integer rangeCode;

    /** Binds and registers with transaction {@code regCode} of the service. */
    public static synchronized String start(Context c, final int regCode) {
        if (svc != null) return "zaten bağlı";
        conn = new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
                svc = b;
                Parcel d = Parcel.obtain(), r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(SVC);
                    d.writeStrongBinder(cb);
                    b.transact(regCode, d, r, 0);
                    try { r.readException(); status = "kayıt tamam (kod " + regCode + ")"; } catch (Throwable t) { status = "kayıt yanıtı: " + t; }
                } catch (Throwable t) { status = "kayıt hata " + t; }
                finally { d.recycle(); r.recycle(); }
            }
            public void onServiceDisconnected(ComponentName n) { svc = null; status = "koptu"; }
        };
        Intent i = new Intent("com.tw.carinfoservice.CarService.Bind").setPackage("com.tw.carinfoservice");
        boolean ok = c.getApplicationContext().bindService(i, conn, Context.BIND_AUTO_CREATE);
        return "bind=" + ok;
    }

    public static synchronized void stop(Context c) {
        try { if (conn != null) c.getApplicationContext().unbindService(conn); } catch (Throwable ignored) { }
        conn = null; svc = null;
    }

    public static String dump() {
        StringBuilder b = new StringBuilder(status).append('\n');
        synchronized (last) { for (Map.Entry<Integer, String> e : last.entrySet()) b.append(String.format(Locale.ROOT, "%2d: %s%n", e.getKey(), e.getValue())); }
        return b.toString();
    }

    /**
     * Runs the service's own Stub.onTransact locally, on an instance created without its constructor
     * (so it holds no connections): shows which transaction codes take a callback binder. Local only.
     */
    public static String probe(Context c, String stubClass, int max) {
        StringBuilder out = new StringBuilder();
        try {
            Context pc = c.createPackageContext("com.tw.carinfoservice", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            Class<?> k = Class.forName(stubClass, false, pc.getClassLoader());
            Class<?> us = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f;
            try { f = us.getDeclaredField("THE_ONE"); } catch (NoSuchFieldException e) { f = us.getDeclaredField("theUnsafe"); }
            f.setAccessible(true);
            Object unsafe = f.get(null);
            Object inst = us.getMethod("allocateInstance", Class.class).invoke(unsafe, k);
            java.lang.reflect.Method ot = Binder.class.getDeclaredMethod("onTransact", int.class, Parcel.class, Parcel.class, int.class);
            ot.setAccessible(true);
            for (int code = 1; code <= max; code++) {
                Parcel d = Parcel.obtain(), r = Parcel.obtain();
                d.writeInterfaceToken(SVC);
                d.writeStrongBinder(cb);
                d.setDataPosition(0);
                String res;
                try { res = "ok " + ot.invoke(inst, code, d, r, 0); }
                catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable t = e.getCause(); StringBuilder s = new StringBuilder(t.getClass().getSimpleName());
                    if (t.getMessage() != null) s.append(':').append(t.getMessage().length() > 60 ? t.getMessage().substring(0, 60) : t.getMessage());
                    StackTraceElement[] st = t.getStackTrace();
                    for (int j = 0; j < Math.min(3, st.length); j++) s.append(" < ").append(st[j].getClassName()).append('.').append(st[j].getMethodName());
                    res = s.toString();
                } catch (Throwable t) { res = t.toString(); }
                out.append(code).append(": ").append(res).append('\n');
                d.recycle(); r.recycle();
            }
        } catch (Throwable t) { out.append("hata ").append(t); }
        return out.toString();
    }
}
