package com.thecarlauncher.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * Listen-only client of the Topway UART service (com.tw.uart.IUartController) that carries the CAN box
 * frames: registers an IUartReceiver and keeps the last frames. Never calls the send method.
 */
public final class UartTw {
    static final String CTRL = "com.tw.uart.IUartController", RCV = "com.tw.uart.IUartReceiver";
    static final ArrayDeque<String> log = new ArrayDeque<>();
    public static volatile String status = "";
    static volatile long t0;
    private static ServiceConnection conn;

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02X", x & 0xFF));
        return s.toString();
    }

    static final Binder rcv = new Binder() {
        @Override protected boolean onTransact(int code, Parcel d, Parcel r, int flags) {
            if (code < 1 || code > 0xFFFFFF) return false;
            try {
                int start = d.dataPosition();
                byte[] all = d.marshall();
                String body;
                try { d.setDataPosition(start); d.enforceInterface(RCV); byte[] p = d.createByteArray(); body = p == null ? "null" : hex(p); }
                catch (Throwable t) { body = "raw:" + hex(all); }
                String line = String.format(Locale.ROOT, "%6.2f c%d %s", (System.currentTimeMillis() - t0) / 1000.0, code, body);
                synchronized (log) { log.addLast(line); while (log.size() > 600) log.removeFirst(); }
                CanFrames.feed(body);
            } catch (Throwable t) { status = "rcv " + t; }
            if (r != null) r.writeNoException();
            return true;
        }
    };

    public static synchronized String start(Context c, final int regCode) {
        if (conn != null) return "zaten bağlı · " + status;
        Intent i = new Intent("com.tw.uart.UartService.Bind");
        List<ResolveInfo> l = c.getPackageManager().queryIntentServices(i, 0);
        if (l == null || l.isEmpty()) return "UartService bulunamadı";
        i.setComponent(new ComponentName(l.get(0).serviceInfo.packageName, l.get(0).serviceInfo.name));
        t0 = System.currentTimeMillis();
        conn = new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
                Parcel d = Parcel.obtain(), r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(CTRL);
                    d.writeStrongBinder(rcv);
                    b.transact(regCode, d, r, 0);
                    try { r.readException(); status = "dinliyor " + n.flattenToShortString(); } catch (Throwable t) { status = "kayıt yanıtı " + t; }
                } catch (Throwable t) { status = "kayıt hata " + t; }
                finally { d.recycle(); r.recycle(); }
            }
            public void onServiceDisconnected(ComponentName n) { status = "koptu"; }
        };
        boolean ok = c.getApplicationContext().bindService(i, conn, Context.BIND_AUTO_CREATE);
        return "bind=" + ok + " " + i.getComponent().flattenToShortString();
    }

    /** "uartlog [n] [filtre]": the last n frames, optionally only those containing a hex string. */
    public static String dump(String arg) {
        String[] p = arg.trim().split("\\s+");
        int n = 120; String f = null;
        try { if (p.length > 0 && p[0].length() > 0) n = Integer.parseInt(p[0]); } catch (NumberFormatException e) { f = p[0].toUpperCase(Locale.ROOT); }
        if (p.length > 1) f = p[1].toUpperCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(status).append(" · ").append(log.size()).append(" satır\n");
        synchronized (log) {
            String[] a = log.toArray(new String[0]);
            int shown = 0;
            for (int k = a.length - 1; k >= 0 && shown < n; k--) { if (f == null || a[k].contains(f)) { b.append(a[k]).append('\n'); shown++; } }
        }
        return b.toString();
    }

    /** Unique frame "kinds" (first 3 bytes) with count and latest payload: quick map of what the box sends. */
    public static String kinds() {
        java.util.TreeMap<String, String[]> m = new java.util.TreeMap<>();
        synchronized (log) {
            for (String s : log) {
                String[] q = s.trim().split("\\s+");
                if (q.length < 3) continue;
                String h = q[2]; String k = h.length() >= 6 ? h.substring(0, 6) : h;
                String[] v = m.get(k);
                if (v == null) m.put(k, new String[]{"1", h, h});
                else { v[0] = String.valueOf(Integer.parseInt(v[0]) + 1); if (!v[2].equals(h)) v[1] = v[1].equals(v[2]) ? v[1] : v[1]; v[2] = h; }
            }
        }
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, String[]> e : m.entrySet()) b.append(e.getKey()).append(" ×").append(e.getValue()[0]).append("  ").append(e.getValue()[2]).append('\n');
        return b.length() == 0 ? "(veri yok)" : b.toString();
    }

    public static void clear() { synchronized (log) { log.clear(); } t0 = System.currentTimeMillis(); }
}
