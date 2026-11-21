package com.thecarlauncher.util;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;

/**
 * Link to the "MYDS Telefon" app on the driver's phone, like Android Auto but in our own screens:
 * the phone sends what it is playing (Spotify, YouTube Music, anything with a media session),
 * the cover, Google Maps' turn-by-turn card and its position; MYDS sends its state back and
 * takes remote commands (open a screen, theme, engine sound, brightness…).
 *
 * Two ways in, whichever works: Bluetooth (RFCOMM, the phone is already paired with the unit)
 * and Wi-Fi (TCP 47321, found by NSD as "_myds._tcp" when both are on one network, e.g. the
 * phone's hotspot). Wi-Fi needs the 4-digit code the unit hands out over Bluetooth (or shows in
 * its settings), so no stranger on the same network can drive it. Messages are one JSON per line.
 */
public final class PhoneLink {
    public static final UUID UUID_LINK = UUID.fromString("6d796473-7068-6f6e-6500-000000000001");
    public static final int PORT = 47321;
    public static final String P_CODE = "phone_link_code";

    /** Remote "open this" from the phone (set by the theme screen). */
    public interface Remote { void open(String what); }
    public static volatile Remote remote;
    /** Unit state for the phone (set by the theme screen). */
    public interface Source { JSONObject snapshot(); }
    public static volatile Source source;

    private static PhoneLink sInstance;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Session cur;

    // ---- what the phone told us
    public static volatile String title = "", artist = "", app_ = "", appPkg = "", phoneName = "", via = "";
    public static volatile boolean playing;
    public static volatile long pos, dur, posAt, mediaAt, seenAt;
    public static volatile Bitmap art;
    public static volatile int artId;
    public static volatile String navDist = "", navText = "", navEta = "";
    public static volatile Bitmap navArrow;
    public static volatile int navId;
    public static volatile long navAt;
    public static volatile double lat = Double.NaN, lon = Double.NaN;
    public static volatile float spd, brg;
    public static volatile long locAt;

    public static synchronized void start(Context c) {
        if (sInstance != null) return;
        sInstance = new PhoneLink(c.getApplicationContext());
    }

    public static String code(Context c) {
        String k = Prefs.str(c, P_CODE, null);
        if (k == null || k.length() != 4) {
            k = String.format(java.util.Locale.US, "%04d", new Random().nextInt(10000));
            Prefs.put(c, P_CODE, k);
        }
        return k;
    }

    private PhoneLink(Context c) {
        app = c;
        code(c);
        new Thread(new Runnable() { public void run() { btLoop(); } }, "phone-bt").start();
        new Thread(new Runnable() { public void run() { tcpLoop(); } }, "phone-tcp").start();
        new Thread(new Runnable() { public void run() { dialLoop(); } }, "phone-dial").start();
        main.postDelayed(tick, 1000);
    }

    // ---- public helpers for the screens

    public static boolean connected() {
        PhoneLink p = sInstance;
        return p != null && p.cur != null && System.currentTimeMillis() - seenAt < 3500;
    }

    /** The phone has something to show in the player (playing now, or paused in the last 15 min). */
    public static boolean mediaActive() {
        return connected() && !title.isEmpty() && (playing || System.currentTimeMillis() - mediaAt < 15 * 60000);
    }

    public static long position() {
        return playing ? pos + (System.currentTimeMillis() - posAt) : pos;
    }

    public static boolean navActive() { return connected() && System.currentTimeMillis() - navAt < 20000 && !navDist.isEmpty(); }

    public static boolean locFresh() { return System.currentTimeMillis() - locAt < 5000 && !Double.isNaN(lat); }

    /** Media key to the phone: play / pause / prev / next; pkg = start this player on the phone. */
    public static boolean control(String what, String pkg) {
        try {
            JSONObject o = new JSONObject().put("t", "ctl").put("a", what);
            if (pkg != null) o.put("pkg", pkg);
            return send(o);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean send(JSONObject o) {
        PhoneLink p = sInstance;
        Session s = p == null ? null : p.cur;
        return s != null && s.write(o.toString());
    }

    // ---- sessions

    private final Runnable tick = new Runnable() {
        public void run() {
            main.postDelayed(this, 1000);
            Session s = cur;
            Source src = source;
            if (s == null) return;
            try {
                JSONObject st = src != null ? src.snapshot() : new JSONObject();
                st.put("t", "state");
                s.write(st.toString());
            } catch (Throwable ignored) {
            }
        }
    };

    @SuppressWarnings("MissingPermission")
    private void btLoop() {
        while (true) {
            try {
                BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                if (a == null) return;
                if (!a.isEnabled()) { Thread.sleep(10000); continue; }
                BluetoothServerSocket ss = a.listenUsingInsecureRfcommWithServiceRecord("MYDS", UUID_LINK);
                try {
                    while (true) {
                        final BluetoothSocket s = ss.accept();
                        final String name = s.getRemoteDevice() == null ? "" : s.getRemoteDevice().getName();
                        new Thread(new Runnable() {
                            public void run() { serve(new Session(s, "bt", name), true); }
                        }, "phone-bt-s").start();
                    }
                } finally {
                    try { ss.close(); } catch (Exception ignored) { }
                }
            } catch (Throwable e) {
                try { Thread.sleep(8000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void tcpLoop() {
        while (true) {
            try (ServerSocket ss = new ServerSocket(PORT)) {
                registerNsd();
                while (true) {
                    final Socket s = ss.accept();
                    InetAddress ra = s.getInetAddress();
                    if (ra != null && !(ra.isSiteLocalAddress() || ra.isLinkLocalAddress() || ra.isLoopbackAddress())) { s.close(); continue; }
                    s.setTcpNoDelay(true);
                    new Thread(new Runnable() {
                        public void run() { serve(new Session(s, "wifi", ""), false); }
                    }, "phone-tcp-s").start();
                }
            } catch (Throwable e) {
                try { Thread.sleep(8000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    public static final int PHONE_PORT = 47322;
    public static final String P_ADDR = "phone_addr";
    public static volatile String dialStatus = "", dialAddr = "";

    /**
     * The unit on the phone's hotspot: the phone is the gateway and listens on 47322, so MYDS
     * calls it (no discovery needed; the unit's own Bluetooth cannot carry the link).
     */
    @SuppressWarnings("deprecation")
    private void dialLoop() {
        while (true) {
            try { Thread.sleep(4000); } catch (InterruptedException e) { return; }
            if (connected()) continue;
            try {
                // An address typed in MyDS wins; else the Wi-Fi gateway (= the phone's hotspot).
                String ip = null;
                int port = PHONE_PORT;
                String manual = Prefs.str(app, P_ADDR, "").trim();
                if (!manual.isEmpty()) {
                    String[] hp = manual.split(":");
                    ip = hp[0].trim();
                    if (hp.length > 1) try { port = Integer.parseInt(hp[1].trim()); } catch (Exception ignored) { }
                } else {
                    android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) app.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    int g = wm == null || !wm.isWifiEnabled() ? 0 : wm.getDhcpInfo().gateway;
                    if (g != 0) ip = (g & 255) + "." + ((g >> 8) & 255) + "." + ((g >> 16) & 255) + "." + ((g >> 24) & 255);
                }
                if (ip == null || ip.isEmpty()) { dialStatus = "Wi-Fi'ye bağlı değil"; dialAddr = ""; continue; }
                dialAddr = ip + ":" + port;
                dialStatus = "Aranıyor: " + dialAddr;
                Socket s = new Socket();
                s.connect(new java.net.InetSocketAddress(ip, port), 2500);
                s.setTcpNoDelay(true);
                s.setSoTimeout(5000); // the phone sends every second; silence = Wi-Fi gone
                seenAt = System.currentTimeMillis();
                dialStatus = "Bağlı: " + dialAddr;
                serve(new Session(s, "wifi", ""), true);
            } catch (Throwable e) {
                dialStatus = "Telefon bulunamadı: " + dialAddr;
            }
        }
    }

    private boolean nsdDone;

    private void registerNsd() {
        if (nsdDone) return;
        try {
            NsdServiceInfo si = new NsdServiceInfo();
            si.setServiceName("MYDS");
            si.setServiceType("_myds._tcp");
            si.setPort(PORT);
            NsdManager nm = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
            nm.registerService(si, NsdManager.PROTOCOL_DNS_SD, new NsdManager.RegistrationListener() {
                public void onRegistrationFailed(NsdServiceInfo s, int e) { nsdDone = false; }
                public void onUnregistrationFailed(NsdServiceInfo s, int e) { }
                public void onServiceRegistered(NsdServiceInfo s) { }
                public void onServiceUnregistered(NsdServiceInfo s) { }
            });
            nsdDone = true;
        } catch (Throwable ignored) {
        }
    }

    private void serve(Session s, boolean trusted) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(s.in, StandardCharsets.UTF_8), 65536);
            if (trusted) {
                cur = s;
                s.write(new JSONObject().put("t", "hello").put("code", code(app)).put("unit", "MYDS").toString());
            }
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() > 2_000_000) continue;
                JSONObject o;
                try { o = new JSONObject(line); } catch (Exception e) { continue; }
                String t = o.optString("t");
                if (!trusted) {
                    if ("hello".equals(t) && code(app).equals(o.optString("code"))) {
                        trusted = true;
                        cur = s;
                        s.write(new JSONObject().put("t", "hello").put("ok", true).put("unit", "MYDS").toString());
                    } else {
                        s.write("{\"t\":\"hello\",\"ok\":false}");
                        break;
                    }
                    continue;
                }
                cur = s;
                seenAt = System.currentTimeMillis();
                via = s.kind;
                handle(o, s);
            }
        } catch (Throwable ignored) {
        } finally {
            s.close();
            if (cur == s) cur = null;
        }
    }

    private void handle(JSONObject o, Session s) {
        long now = System.currentTimeMillis();
        switch (o.optString("t")) {
            case "hello":
                phoneName = o.optString("name", s.name);
                break;
            case "ping":
                break;
            case "media":
                title = o.optString("title");
                artist = o.optString("artist");
                app_ = o.optString("app");
                appPkg = o.optString("pkg");
                playing = o.optBoolean("playing");
                pos = o.optLong("pos");
                dur = o.optLong("dur");
                posAt = now;
                if (!title.isEmpty()) mediaAt = now;
                break;
            case "art": {
                byte[] b = decode(o.optString("jpg"));
                art = b == null ? null : BitmapFactory.decodeByteArray(b, 0, b.length);
                artId = o.optString("jpg").hashCode();
                break;
            }
            case "nav": {
                navDist = o.optString("dist");
                navText = o.optString("text");
                navEta = o.optString("eta");
                navAt = o.optBoolean("end") ? 0 : now;
                if (o.has("arrow")) {
                    byte[] b = decode(o.optString("arrow"));
                    navArrow = b == null ? null : BitmapFactory.decodeByteArray(b, 0, b.length);
                    navId++;
                }
                break;
            }
            case "loc":
                lat = o.optDouble("lat", Double.NaN);
                lon = o.optDouble("lon", Double.NaN);
                spd = (float) o.optDouble("spd", 0);
                brg = (float) o.optDouble("brg", 0);
                locAt = now;
                break;
            case "open": {
                final String what = o.optString("what");
                final Remote r = remote;
                if (r != null && !what.isEmpty()) main.post(new Runnable() { public void run() { r.open(what); } });
                break;
            }
            case "obd": {
                // The ELM327 read by the phone (the unit's Bluetooth does not see it).
                ObdHub h = ObdHub.get();
                if (h != null) h.fromPhone(o);
                break;
            }
            case "pref":
                // a few switches the phone may flip
                String k = o.optString("key");
                if ("aa_return".equals(k)) Prefs.put(app, AutoReturn.P_ON, o.optBoolean("value", true));
                break;
        }
    }

    private static byte[] decode(String b64) {
        try { return b64 == null || b64.isEmpty() ? null : Base64.decode(b64, Base64.DEFAULT); } catch (Exception e) { return null; }
    }

    private static final class Session {
        final Object sock;
        final InputStream in;
        final OutputStream out;
        final String kind, name;

        Session(Object sock, String kind, String name) {
            this.sock = sock;
            this.kind = kind;
            this.name = name == null ? "" : name;
            InputStream i = null;
            OutputStream o = null;
            try {
                if (sock instanceof BluetoothSocket) { i = ((BluetoothSocket) sock).getInputStream(); o = ((BluetoothSocket) sock).getOutputStream(); }
                else { i = ((Socket) sock).getInputStream(); o = ((Socket) sock).getOutputStream(); }
            } catch (Exception ignored) {
            }
            in = i;
            out = o;
        }

        private static final java.util.concurrent.ExecutorService TX = java.util.concurrent.Executors.newSingleThreadExecutor();

        /** Queued: sockets may not be written from the main thread. */
        boolean write(final String line) {
            if (out == null) return false;
            TX.execute(new Runnable() { public void run() { writeNow(line); } });
            return true;
        }

        synchronized boolean writeNow(String line) {
            try {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (Exception e) {
                close();
                return false;
            }
        }

        void close() {
            try {
                if (sock instanceof BluetoothSocket) ((BluetoothSocket) sock).close();
                else ((Socket) sock).close();
            } catch (Exception ignored) {
            }
        }
    }
}
