package com.catarapatara.canbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Follows the raw CAN-box frames that the factory CAN app (com.hcn.autocan) writes to the system
 * log ("CanService: rev: FD…"), decodes the Raise PSA trip computer and passes the values on as
 * broadcasts: com.catarapatara.DATA (for the engine sound) and com.thecarlauncher.CAN (for MYDS).
 * The serial port stays with the factory app. One write exception: the Denon amplifier's sound
 * settings (Raise command 0xC5: fader, balance, bass, treble, mid, loudness, speed volume, ambience,
 * driver-centred stage, subwoofer), sent by MYDS's Hi-Fi page — the same frame the factory CAN app's
 * EQ page sends. Nothing else is ever written.
 */
public final class BridgeService extends Service {
    static volatile String status = "Başlatılıyor…";
    static volatile float instant = -1, range = -1, outdoor = Float.NaN;
    static volatile long lastFrameAt;
    static volatile int lines, revLines, frameCount;
    static final Map<Integer, String> frames = new TreeMap<>();
    static final Map<Integer, Integer> counts = new TreeMap<>();

    private volatile boolean run;
    private Process proc;
    private final byte[] buf = new byte[512];
    private int n;

    static volatile String ampStatus = "";

    /** payload = {len, 0xC5, 8 data bytes}; framed as FD … sum&0xFF like the CAN box expects. */
    private void amp(byte[] p) {
        if (p == null || p.length != 10 || (p[0] & 0xFF) != 11 || (p[1] & 0xFF) != 0xC5) { ampStatus = "Amfi: geçersiz komut"; return; }
        byte[] f = new byte[12];
        f[0] = (byte) 0xFD;
        int sum = 0;
        for (int i = 0; i < 10; i++) { f[i + 1] = p[i]; sum += p[i]; }
        f[11] = (byte) sum;
        String[] ports = {"/dev/ttyMT2", "/dev/ttyMT1"};
        for (String port : ports) {
            java.io.File d = new java.io.File(port);
            if (!d.exists()) continue;
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(d)) {
                o.write(f);
                o.flush();
                ampStatus = "Amfi: gönderildi (" + port + ")";
                sendBroadcast(new Intent("com.thecarlauncher.AMP_ACK").putExtra("ok", true).putExtra("st", ampStatus));
                return;
            } catch (Exception e) {
                ampStatus = "Amfi: " + e.getMessage();
            }
        }
        sendBroadcast(new Intent("com.thecarlauncher.AMP_ACK").putExtra("ok", false).putExtra("st", ampStatus));
    }

    static void start(Context c) {
        Intent i = new Intent(c, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("can", "CAN köprüsü", NotificationManager.IMPORTANCE_MIN));
            startForeground(3, new Notification.Builder(this, "can").setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("CataraPatara CAN").setContentText("Araç verisi okunuyor").build());
        }
        grantMediaAccess();
        if (intent != null && "com.catarapatara.canbridge.AMP".equals(intent.getAction())) amp(intent.getByteArrayExtra("data"));
        if (!run) {
            run = true;
            new Thread(new Runnable() { public void run() { loop(); } }, "can-log").start();
            new Thread(new Runnable() {
                public void run() {
                    while (run) {
                        try {
                            StringBuilder types = new StringBuilder();
                            synchronized (frames) {
                                for (Integer k : counts.keySet()) types.append(String.format(Locale.US, "%02X×%d ", k, counts.get(k)));
                            }
                            sendBroadcast(new Intent("com.thecarlauncher.CANSTATUS").putExtra("status", status)
                                    .putExtra("lines", lines).putExtra("rev", revLines).putExtra("frames", frameCount)
                                    .putExtra("types", types.toString().trim()).putExtra("age", lastFrameAt == 0 ? -1 : System.currentTimeMillis() - lastFrameAt));
                            Thread.sleep(2000);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }, "can-status").start();
        }
        return START_STICKY;
    }

    /**
     * MYDS reads the playing song and controls Spotify / YouTube Music / Bluetooth through a
     * notification listener. On this unit nobody can reach that setting easily, so the bridge
     * (system app) switches it on for MYDS once.
     */
    private void grantMediaAccess() {
        try {
            getPackageManager().getPackageInfo("com.thecarlauncher", 0);
            String comp = "com.thecarlauncher/com.thecarlauncher.media.MediaListenerService";
            String key = "enabled_notification_listeners";
            String cur = android.provider.Settings.Secure.getString(getContentResolver(), key);
            if (cur != null && (cur.contains(comp) || cur.contains("com.thecarlauncher/.media.MediaListenerService"))) return;
            String next = cur == null || cur.isEmpty() ? comp : cur + ":" + comp;
            android.provider.Settings.Secure.putString(getContentResolver(), key, next);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        run = false;
        if (proc != null) proc.destroy();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    private void loop() {
        while (run) {
            try {
                status = "Log okunuyor…";
                // Only new lines (-T 1). No tag filter: some firmwares log under another tag; the
                // receive lines are recognised by their "rev: <hex>" text.
                proc = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "brief", "-T", "1"});
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                lines = 0;
                while (run && (line = r.readLine()) != null) {
                    lines++;
                    int i = line.indexOf("rev: ");
                    if (i >= 0) { revLines++; feedHex(line.substring(i + 5).trim()); }
                    if (lastFrameAt == 0 && lines > 50 && revLines == 0) status = "Log okunuyor (" + lines + " satır), CAN satırı yok";
                }
                status = "Log akışı kapandı, yeniden deneniyor";
            } catch (Exception e) {
                status = "Log okunamıyor: " + e.getMessage();
            }
            if (proc != null) proc.destroy();
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
        }
    }

    private void feedHex(String hex) {
        hex = hex.replaceAll("[^0-9A-Fa-f]", "");
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            if (n >= buf.length) n = 0;
            buf[n++] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        parse();
    }

    /** Raise frames: FD, len (len byte + data + checksum), data…, checksum = sum(len..data) & 0xFF. */
    private void parse() {
        int start = 0;
        while (true) {
            while (start < n && (buf[start] & 0xFF) != 0xFD) start++;
            if (n - start < 3) break;
            int len = buf[start + 1] & 0xFF;
            if (len < 2 || len > 64) { start++; continue; }
            if (n - start < 1 + len) break;
            int sum = 0;
            for (int k = 1; k < len; k++) sum += buf[start + k] & 0xFF;
            if ((sum & 0xFF) != (buf[start + len] & 0xFF)) { start++; continue; }
            byte[] f = new byte[1 + len];
            System.arraycopy(buf, start, f, 0, f.length);
            frame(f);
            start += 1 + len;
        }
        System.arraycopy(buf, start, buf, 0, n - start);
        n -= start;
    }

    private final java.util.HashMap<Integer, String> lastRaw = new java.util.HashMap<>();

    private void frame(byte[] f) {
        int cmd = f[2] & 0xFF;
        frameCount++;
        lastFrameAt = System.currentTimeMillis();
        status = "Bağlı · CAN verisi geliyor";
        synchronized (frames) {
            StringBuilder sb = new StringBuilder();
            for (byte b : f) sb.append(String.format(Locale.US, "%02X ", b & 0xFF));
            frames.put(cmd, sb.toString().trim());
            Integer c = counts.get(cmd);
            counts.put(cmd, c == null ? 1 : c + 1);
        }
        // Every frame type, raw, whenever it changes: MYDS learns doors / lights / indicators from these.
        String hex;
        synchronized (frames) { hex = frames.get(cmd); }
        String prev = lastRaw.get(cmd);
        if (prev == null || !prev.equals(hex)) {
            lastRaw.put(cmd, hex);
            sendBroadcast(new Intent("com.thecarlauncher.CANRAW").putExtra("cmd", cmd).putExtra("hex", hex));
        }
        Intent out = null;
        if (cmd == 0x33 && f.length >= 10) {
            instant = (((f[3] & 0xFF) << 8) | (f[4] & 0xFF)) * 0.1f;
            range = ((f[5] & 0xFF) << 8) | (f[6] & 0xFF);
            out = new Intent("com.catarapatara.DATA").putExtra("l100", Math.min(300f, instant));
            sendBroadcast(new Intent("com.thecarlauncher.CAN").putExtra("l100", instant).putExtra("range", range));
        } else if (cmd == 0x36 && f.length >= 5) {
            int t = f[3] & 0x7F;
            outdoor = (f[3] & 0x80) != 0 ? -t : t;
            sendBroadcast(new Intent("com.thecarlauncher.CAN").putExtra("outdoor", outdoor));
        }
        if (out != null) sendBroadcast(out);
    }
}
