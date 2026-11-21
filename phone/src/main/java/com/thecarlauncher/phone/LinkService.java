package com.thecarlauncher.phone;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Keeps the link to the head unit (MYDS) open: Wi-Fi when the unit is found on the same network
 * (NSD "_myds._tcp", needs the 4-digit code), else Bluetooth (RFCOMM, the phone is paired with
 * the unit anyway). Sends media, cover, Google Maps' next turn and the position; receives the
 * unit's state for the remote-control screen and media keys for the phone's players.
 */
public final class LinkService extends Service implements LocationListener {
    static final UUID UUID_LINK = UUID.fromString("6d796473-7068-6f6e-6500-000000000001");
    static final int PORT = 47321;
    private static final String CH = "myds_link";

    static volatile LinkService sInstance;
    static volatile String status = "Bağlı değil", via = "";
    static volatile JSONObject unitState;
    static volatile long stateAt;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean run;
    private volatile Object sock;
    private volatile OutputStream out;
    private volatile String wifiHost;
    private volatile int wifiPort = PORT;
    private NsdManager.DiscoveryListener disco;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("link", MODE_PRIVATE); }

    static void start(Context c) {
        Intent i = new Intent(c, LinkService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static boolean connected() { return sInstance != null && sInstance.out != null; }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        foreground();
        run = true;
        new Thread(new Runnable() { public void run() { loop(); } }, "link").start();
        discover();
        startLocation();
    }

    @Override
    public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    @Override
    public void onDestroy() {
        run = false;
        close();
        try { if (disco != null) ((NsdManager) getSystemService(NSD_SERVICE)).stopServiceDiscovery(disco); } catch (Exception ignored) { }
        try { ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(this); } catch (Exception ignored) { }
        sInstance = null;
        super.onDestroy();
    }

    private void foreground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "MYDS bağlantısı", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("MYDS Telefon").setContentText("Araçla bağlantı açık").setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        int types = 0;
        if (Build.VERSION.SDK_INT >= 29) {
            if (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29 && types != 0) startForeground(7, b.build(), types);
            else startForeground(7, b.build());
        } catch (Throwable t) {
            try { startForeground(7, b.build()); } catch (Throwable ignored) { }
        }
    }

    // ---- connection

    private void loop() {
        while (run) {
            try {
                if (wifiHost != null && prefs(this).getString("code", "").length() == 4) session(true);
                else session(false);
            } catch (Throwable e) {
                status = "Bağlanamadı: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            close();
            sleep(5000);
        }
    }

    @SuppressLint("MissingPermission")
    private void session(boolean wifi) throws Exception {
        InputStream in;
        if (wifi) {
            status = "Wi-Fi ile bağlanıyor…";
            Socket s = new Socket();
            sock = s;
            s.connect(new InetSocketAddress(wifiHost, wifiPort), 4000);
            s.setTcpNoDelay(true);
            in = s.getInputStream();
            out = s.getOutputStream();
            via = "Wi-Fi";
        } else {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null || !a.isEnabled()) throw new Exception("Bluetooth kapalı");
            String addr = prefs(this).getString("unit", "");
            if (addr.isEmpty()) throw new Exception("Araç seçilmedi");
            BluetoothDevice d = a.getRemoteDevice(addr);
            status = "Bluetooth ile bağlanıyor…";
            BluetoothSocket s = d.createInsecureRfcommSocketToServiceRecord(UUID_LINK);
            sock = s;
            a.cancelDiscovery();
            s.connect();
            in = s.getInputStream();
            out = s.getOutputStream();
            via = "Bluetooth";
        }
        JSONObject h = new JSONObject().put("t", "hello").put("name", Build.MODEL);
        if (wifi) h.put("code", prefs(this).getString("code", ""));
        write(h);
        status = "Bağlı · " + via;
        MediaWatch.resend();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 65536);
        String line;
        while (run && (line = r.readLine()) != null) {
            JSONObject o;
            try { o = new JSONObject(line); } catch (Exception e) { continue; }
            switch (o.optString("t")) {
                case "hello":
                    if (o.has("code")) prefs(this).edit().putString("code", o.optString("code")).apply();
                    if (o.has("ok") && !o.optBoolean("ok")) { prefs(this).edit().remove("code").apply(); throw new Exception("Wi-Fi kodu yanlış"); }
                    break;
                case "state":
                    unitState = o;
                    stateAt = System.currentTimeMillis();
                    break;
                case "ctl":
                    final String a = o.optString("a"), pkg = o.has("pkg") ? o.optString("pkg") : null;
                    main.post(new Runnable() { public void run() { MediaWatch.control(LinkService.this, a, pkg); } });
                    break;
            }
            // Bluetooth is up but the unit is also on our Wi-Fi: Wi-Fi is faster (covers).
            if (!wifi && wifiHost != null && prefs(this).getString("code", "").length() == 4) break;
        }
    }

    private void close() {
        Object s = sock;
        sock = null;
        out = null;
        via = "";
        if (run) status = "Bağlı değil";
        try {
            if (s instanceof Socket) ((Socket) s).close();
            if (s instanceof BluetoothSocket) ((BluetoothSocket) s).close();
        } catch (Exception ignored) {
        }
    }

    synchronized boolean write(JSONObject o) {
        OutputStream os = out;
        if (os == null) return false;
        try {
            os.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            return true;
        } catch (Exception e) {
            close();
            return false;
        }
    }

    private static final java.util.concurrent.ExecutorService TX = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Queued: sockets may not be written from the main thread. */
    static boolean send(final JSONObject o) {
        final LinkService s = sInstance;
        if (s == null || s.out == null) return false;
        TX.execute(new Runnable() { public void run() { s.write(o); } });
        return true;
    }

    static void open(String what) {
        try { send(new JSONObject().put("t", "open").put("what", what)); } catch (Exception ignored) { }
    }

    static String jpeg(Bitmap b, int max) {
        if (b == null) return "";
        try {
            float k = Math.min(1f, max / (float) Math.max(b.getWidth(), b.getHeight()));
            Bitmap s = k < 1 ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true) : b;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            s.compress(Bitmap.CompressFormat.JPEG, 82, bo);
            return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    static String png(Bitmap b) {
        if (b == null) return "";
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bo);
            return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    // ---- Wi-Fi discovery

    private void discover() {
        try {
            final NsdManager nm = (NsdManager) getSystemService(NSD_SERVICE);
            disco = new NsdManager.DiscoveryListener() {
                public void onStartDiscoveryFailed(String t, int e) { }
                public void onStopDiscoveryFailed(String t, int e) { }
                public void onDiscoveryStarted(String t) { }
                public void onDiscoveryStopped(String t) { }
                public void onServiceLost(NsdServiceInfo s) { wifiHost = null; }
                public void onServiceFound(NsdServiceInfo s) {
                    if (!s.getServiceName().startsWith("MYDS")) return;
                    nm.resolveService(s, new NsdManager.ResolveListener() {
                        public void onResolveFailed(NsdServiceInfo s, int e) { }
                        public void onServiceResolved(NsdServiceInfo s) {
                            wifiHost = s.getHost().getHostAddress();
                            wifiPort = s.getPort();
                        }
                    });
                }
            };
            nm.discoverServices("_myds._tcp", NsdManager.PROTOCOL_DNS_SD, disco);
        } catch (Throwable ignored) {
        }
    }

    // ---- position (the unit uses it when its own GPS has no fix)

    @SuppressLint("MissingPermission")
    private void startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            ((LocationManager) getSystemService(LOCATION_SERVICE)).requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        } catch (Throwable ignored) {
        }
    }

    private long locSentAt;

    @Override
    public void onLocationChanged(Location l) {
        long now = System.currentTimeMillis();
        if (now - locSentAt < 1000) return;
        locSentAt = now;
        try {
            send(new JSONObject().put("t", "loc").put("lat", l.getLatitude()).put("lon", l.getLongitude())
                    .put("spd", l.getSpeed() * 3.6).put("brg", l.getBearing()));
        } catch (Exception ignored) {
        }
    }

    @Override public void onStatusChanged(String p, int s, Bundle b) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
}
