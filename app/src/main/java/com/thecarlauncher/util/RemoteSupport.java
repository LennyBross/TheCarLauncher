package com.thecarlauncher.util;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;

/**
 * Remote support: Claude (or anyone with the owner's GitHub account) can diagnose and fix the
 * unit from afar. The channel is one issue in the driver's own PRIVATE repository: comments by
 * the repository owner that start with "/myds " are commands; the unit answers in comments
 * starting with "🤖". Needs a fine-grained GitHub token limited to that repository (Issues:
 * read and write), typed in once from the phone (the unit serves a small page for that on the
 * hotspot, protected by a 4-digit PIN). Off by default; the driver turns it on and off.
 */
public final class RemoteSupport {
    public static final String P_ON = "rs_on", P_TOKEN = "rs_token", P_REPO = "rs_repo", P_ISSUE = "rs_issue", P_LAST = "rs_last";
    static final String DEF_REPO = "LennyBross/myds-remote", TITLE = "MyDS uzaktan destek";

    private static RemoteSupport sInstance;
    private final Context app;
    public static volatile String status = "Kapalı";
    public static volatile long lastOkAt;
    private volatile boolean helloSent;

    public static synchronized void start(Context c) {
        if (sInstance == null) sInstance = new RemoteSupport(c.getApplicationContext());
    }

    private RemoteSupport(Context c) {
        app = c;
        try { ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        // A private build may carry the support key (assets/remote.cfg: token, repo); never in the public one.
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("remote.cfg"), StandardCharsets.UTF_8));
            String token = r.readLine(), repo = r.readLine();
            r.close();
            if (token != null && !token.trim().equals(Prefs.str(c, P_TOKEN, ""))) saveToken(c, token, repo);
        } catch (Exception ignored) {
        }
        new Thread(new Runnable() { public void run() { loop(); } }, "remote").start();
    }

    public static boolean on(Context c) { return Prefs.bool(c, P_ON, false); }

    public static boolean configured(Context c) { return Prefs.str(c, P_TOKEN, "").length() > 20; }

    public static String repo(Context c) { return Prefs.str(c, P_REPO, DEF_REPO); }

    public static void setOn(Context c, boolean on) {
        Prefs.put(c, P_ON, on);
        if (sInstance != null) sInstance.helloSent = false;
        status = on ? "Başlıyor…" : "Kapalı";
    }

    private void loop() {
        while (true) {
            try {
                if (on(app) && configured(app)) {
                    poll();
                    Thread.sleep(8000);
                } else {
                    if (!on(app)) status = "Kapalı";
                    else status = "Anahtar girilmedi";
                    Thread.sleep(5000);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                status = "Hata: " + t.getMessage();
                try { Thread.sleep(15000); } catch (InterruptedException e) { return; }
            }
        }
    }

    // ---- GitHub

    private String api(String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com" + path).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + Prefs.str(app, P_TOKEN, ""));
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "MyDS-remote");
        if (body != null) {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(b.length);
            OutputStream o = c.getOutputStream();
            o.write(b);
            o.close();
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String r = read(in);
        c.disconnect();
        if (code == 401) throw new Exception("anahtar geçersiz ya da süresi doldu");
        if (code == 403 || code == 404) throw new Exception("depoya erişim yok (" + repo(app) + ", Issues izni?)");
        if (code >= 300) throw new Exception("GitHub " + code);
        return r;
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l).append('\n');
        r.close();
        return sb.toString();
    }

    private int issue() throws Exception {
        int n = Prefs.integer(app, P_ISSUE, 0);
        if (n > 0) return n;
        JSONArray l = new JSONArray(api("GET", "/repos/" + repo(app) + "/issues?state=open&per_page=50", null));
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.getJSONObject(i);
            if (TITLE.equals(o.optString("title")) && !o.has("pull_request")) { n = o.getInt("number"); break; }
        }
        if (n == 0) {
            JSONObject o = new JSONObject(api("POST", "/repos/" + repo(app) + "/issues",
                    new JSONObject().put("title", TITLE).put("body", "Araç ünitesi ile uzaktan destek kanalı. Komutlar: `/myds yardim`").toString()));
            n = o.getInt("number");
        }
        Prefs.put(app, P_ISSUE, n);
        return n;
    }

    private void comment(int n, String text) throws Exception {
        if (text.length() > 60000) text = text.substring(0, 60000) + "\n…(kısaltıldı)";
        api("POST", "/repos/" + repo(app) + "/issues/" + n + "/comments", new JSONObject().put("body", text).toString());
    }

    private void poll() throws Exception {
        int n = issue();
        String owner = repo(app).split("/")[0];
        if (!helloSent) {
            comment(n, "🤖 **MyDS çevrimiçi** · " + version() + "\n```\n" + Commands.state(app) + "\n```");
            helloSent = true;
            // only commands written from now on
            JSONArray l = new JSONArray(api("GET", "/repos/" + repo(app) + "/issues/" + n + "/comments?per_page=100", null));
            long max = Prefs.longVal(app, P_LAST, 0);
            for (int i = 0; i < l.length(); i++) max = Math.max(max, l.getJSONObject(i).getLong("id"));
            Prefs.put(app, P_LAST, max);
        }
        long last = Prefs.longVal(app, P_LAST, 0);
        // newest page of comments
        JSONObject is = new JSONObject(api("GET", "/repos/" + repo(app) + "/issues/" + n, null));
        int total = is.optInt("comments", 0);
        int page = Math.max(1, (total + 99) / 100);
        JSONArray l = new JSONArray(api("GET", "/repos/" + repo(app) + "/issues/" + n + "/comments?per_page=100&page=" + page, null));
        lastOkAt = System.currentTimeMillis();
        status = "Bağlı · #" + n;
        for (int i = 0; i < l.length(); i++) {
            JSONObject c = l.getJSONObject(i);
            long id = c.getLong("id");
            if (id <= last) continue;
            Prefs.put(app, P_LAST, id);
            String body = c.optString("body").trim();
            String who = c.optJSONObject("user") == null ? "" : c.getJSONObject("user").optString("login");
            if (!body.startsWith("/myds ") || !owner.equalsIgnoreCase(who)) continue;
            String cmd = body.substring(6).trim();
            status = "Çalışıyor: " + cmd;
            String out;
            try { out = Commands.run(app, cmd); } catch (Throwable t) { out = "HATA: " + t; }
            comment(n, "🤖 `" + cmd.replace("`", "'") + "`\n```\n" + out.replace("```", "'''") + "\n```");
            status = "Bağlı · #" + n;
        }
    }

    static String ver = "";

    static String version() {
        return "MyDS " + ver + " · Android " + Build.VERSION.RELEASE + " · " + Build.MODEL;
    }

    // ---- Setup from the phone: a page on the unit (hotspot), PIN-protected

    private static ServerSocket setupSock;
    public static volatile String setupPin = "", setupUrl = "";

    @SuppressWarnings("deprecation")
    public static synchronized void openSetup(final Context c) {
        if (setupSock != null) return;
        setupPin = String.format(Locale.US, "%04d", new Random().nextInt(10000));
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            String host = ip == 0 ? "?" : (ip & 255) + "." + ((ip >> 8) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 24) & 255);
            setupSock = new ServerSocket(8088);
            setupUrl = "http://" + host + ":8088";
        } catch (Exception e) {
            setupUrl = "";
            return;
        }
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                long end = System.currentTimeMillis() + 15 * 60000;   // open for 15 minutes
                ServerSocket ss = setupSock;
                try {
                    ss.setSoTimeout(5000);
                    while (System.currentTimeMillis() < end && !ss.isClosed()) {
                        final Socket s;
                        try { s = ss.accept(); } catch (java.net.SocketTimeoutException t) { continue; } catch (Throwable t) { break; }
                        // each request on its own thread; a bad one never stops the page
                        new Thread(new Runnable() { public void run() {
                            try { serveSetup(app, s); } catch (Throwable ignored) { }
                            try { s.close(); } catch (Throwable ignored) { }
                        } }, "remote-setup-req").start();
                    }
                } catch (Throwable ignored) {
                } finally {
                    closeSetup();
                }
            }
        }, "remote-setup").start();
    }

    public static synchronized void closeSetup() {
        try { if (setupSock != null) setupSock.close(); } catch (Exception ignored) { }
        setupSock = null;
        setupUrl = "";
    }

    private static void serveSetup(Context c, Socket s) throws Exception {
        s.setSoTimeout(5000);
        InputStream in = s.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String first = r.readLine();
        if (first == null || first.isEmpty()) return;
        int len = 0;
        String h;
        while ((h = r.readLine()) != null && !h.isEmpty()) if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
        String msg = "";
        if (first.startsWith("POST")) {
            char[] b = new char[Math.min(len, 4000)];
            int got = 0;
            while (got < b.length) { int k = r.read(b, got, b.length - got); if (k < 0) break; got += k; }
            String body = new String(b, 0, got);
            String pin = "", token = "", repo = "";
            for (String kv : body.split("&")) {
                String[] p = kv.split("=", 2);
                String v = p.length > 1 ? URLDecoder.decode(p[1], "UTF-8").trim() : "";
                if (p[0].equals("pin")) pin = v; else if (p[0].equals("token")) token = v; else if (p[0].equals("repo")) repo = v;
            }
            if (!pin.equals(setupPin)) msg = "PIN yanlış.";
            else if (token.length() < 20) msg = "Anahtar eksik.";
            else {
                Prefs.put(c, P_TOKEN, token);
                if (repo.matches("[\\w.-]+/[\\w.-]+")) Prefs.put(c, P_REPO, repo);
                Prefs.put(c, P_ISSUE, 0);
                Prefs.put(c, P_LAST, 0L);
                setOn(c, true);
                msg = "Kaydedildi. Uzaktan destek açıldı, bu sayfayı kapatabilirsin.";
            }
        }
        String page = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                + "<style>body{font-family:sans-serif;background:#0b0e13;color:#eee;padding:20px}input{width:100%;font-size:18px;padding:10px;margin:6px 0 14px;border-radius:8px;border:1px solid #444;background:#161b23;color:#fff}button{font-size:18px;padding:12px 20px;border-radius:24px;border:0;background:#E8D5B0}</style>"
                + "<h2>MyDS · Uzaktan destek</h2>" + (msg.isEmpty() ? "" : "<p><b>" + msg + "</b></p>")
                + "<form method=post><label>Ekrandaki PIN</label><input name=pin inputmode=numeric maxlength=4>"
                + "<label>GitHub anahtarı (github_pat_…)</label><input name=token autocomplete=off>"
                + "<label>Depo</label><input name=repo value='" + repo(c) + "'><button>Kaydet</button></form>";
        byte[] b = page.getBytes(StandardCharsets.UTF_8);
        OutputStream o = s.getOutputStream();
        o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        o.write(b);
        o.flush();
    }

    /** Token typed on the unit itself (fallback when the phone page cannot be reached). */
    public static String saveToken(Context c, String token, String repo) {
        token = token == null ? "" : token.trim().replaceAll("\\s", "");
        if (!token.startsWith("github_pat_") && !token.startsWith("ghp_")) return "Anahtar github_pat_ ile başlamalı";
        Prefs.put(c, P_TOKEN, token);
        if (repo != null && repo.trim().matches("[\\w.-]+/[\\w.-]+")) Prefs.put(c, P_REPO, repo.trim());
        Prefs.put(c, P_ISSUE, 0);
        Prefs.put(c, P_LAST, 0L);
        setOn(c, true);
        return "Kaydedildi";
    }

    // ---- Commands

    static final class Commands {
        static String run(Context c, String cmd) throws Exception {
            String[] w = cmd.split("\\s+", 2);
            String a = w.length > 1 ? w[1] : "";
            switch (w[0].toLowerCase(Locale.ROOT)) {
                case "yardim": case "help":
                    return "durum · obd · ara · bagla <MAC> [le] · pin <PIN> · log [satır] [süzgeç] · sh <komut> · guncelle · yeniden";
                case "durum": return state(c);
                case "obd": { ObdHub h = ObdHub.get(); return h == null ? "ObdHub yok" : h.json().toString(2); }
                case "ara": {
                    ObdHub h = ObdHub.get();
                    if (h == null) return "ObdHub yok";
                    h.scan();
                    Thread.sleep(13500);
                    return h.json().toString(2);
                }
                case "bagla": {
                    ObdHub h = ObdHub.get();
                    if (h == null) return "ObdHub yok";
                    String[] p = a.trim().split("\\s+");
                    h.pick(p[0].toUpperCase(Locale.ROOT), p.length > 1 && p[1].equalsIgnoreCase("le"));
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 12; i++) {
                        Thread.sleep(2500);
                        sb.append(String.format(Locale.US, "%2ds  %s  rpm=%.0f%n", (i + 1) * 5 / 2, h.status(), h.rpm()));
                        if (h.rpm() >= 0) break;
                    }
                    return sb.toString();
                }
                case "pin": { ObdHub h = ObdHub.get(); if (h != null) h.setPin(a); return "PIN = " + a; }
                case "log": {
                    String[] p = a.trim().isEmpty() ? new String[0] : a.trim().split("\\s+", 2);
                    int lines = p.length > 0 ? Integer.parseInt(p[0]) : 300;
                    String filter = p.length > 1 ? p[1].toLowerCase(Locale.ROOT) : null;
                    String all = sh("logcat -d -v time -t " + Math.min(5000, lines * (filter == null ? 1 : 10)), 15000);
                    if (filter == null) return all;
                    StringBuilder sb = new StringBuilder();
                    for (String l : all.split("\n")) if (l.toLowerCase(Locale.ROOT).contains(filter)) sb.append(l).append('\n');
                    return sb.toString();
                }
                case "sh": return sh(a, 25000);
                case "guncelle": {
                    final StringBuilder r = new StringBuilder();
                    final Object lock = new Object();
                    Updater.check(c, new Updater.Checked() {
                        public void onChecked(Updater.Release latest, String error) {
                            synchronized (lock) { r.append(latest == null ? "yok: " + error : latest.versionName + " kuruluyor"); lock.notifyAll(); }
                            if (latest != null && Updater.isNewer(c, latest)) Updater.install(c, latest);
                        }
                    });
                    synchronized (lock) { lock.wait(20000); }
                    return "Güncelleme: " + r;
                }
                case "yeniden": {
                    Intent i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
                    android.app.AlarmManager am = (android.app.AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                    am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1500,
                            android.app.PendingIntent.getActivity(c, 9, i, android.app.PendingIntent.FLAG_IMMUTABLE));
                    new Thread(new Runnable() { public void run() {
                        try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                        android.os.Process.killProcess(android.os.Process.myPid());
                    } }).start();
                    return "MyDS yeniden başlıyor";
                }
                default: return "Bilinmeyen komut. /myds yardim";
            }
        }

        static String state(Context c) {
            StringBuilder sb = new StringBuilder(version()).append('\n');
            try {
                android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                sb.append("Bluetooth: ").append(a == null ? "yok" : (a.isEnabled() ? "açık" : "kapalı") + " · " + a.getName() + " · " + a.getAddress()).append('\n');
                if (a != null) for (android.bluetooth.BluetoothDevice d : a.getBondedDevices())
                    sb.append("  eşleşmiş: ").append(d.getName()).append(" ").append(d.getAddress()).append(" tip=").append(d.getType()).append('\n');
            } catch (Throwable t) {
                sb.append("Bluetooth: ").append(t).append('\n');
            }
            try {
                sb.append("Radyo: kaynak=").append(com.thecarlauncher.radio.HcnRadio.source()).append(" yol=").append(com.thecarlauncher.radio.HcnRadio.how())
                        .append(" istenen=").append(com.thecarlauncher.radio.HcnRadio.wanted()).append('\n').append(com.thecarlauncher.radio.HcnRadio.describe());
            } catch (Throwable t) {
                sb.append("Radyo: ").append(t).append('\n');
            }
            String[] perms = {"android.permission.ACCESS_FINE_LOCATION", "android.permission.BLUETOOTH_ADMIN", "android.permission.BLUETOOTH_PRIVILEGED", "android.permission.READ_LOGS"};
            for (String p : perms) sb.append(p.substring(19)).append(": ").append(c.checkSelfPermission(p) == 0 ? "var" : "YOK").append('\n');
            try {
                android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
                sb.append("Konum servisi: ").append(lm.isProviderEnabled("gps") ? "açık" : "kapalı").append('\n');
            } catch (Throwable ignored) {
            }
            ObdHub h = ObdHub.get();
            if (h != null) sb.append("OBD: ").append(h.status()).append(" · rpm=").append(h.rpm()).append(" · mod=").append(h.mode()).append('\n');
            return sb.toString();
        }

        static String sh(String cmd, long timeout) throws Exception {
            final Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            final StringBuilder sb = new StringBuilder();
            Thread t = new Thread(new Runnable() { public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                    String l;
                    while ((l = r.readLine()) != null && sb.length() < 200000) sb.append(l).append('\n');
                } catch (Exception ignored) {
                }
            } });
            t.start();
            t.join(timeout);
            p.destroy();
            return sb.length() == 0 ? "(çıktı yok)" : sb.toString();
        }
    }
}
