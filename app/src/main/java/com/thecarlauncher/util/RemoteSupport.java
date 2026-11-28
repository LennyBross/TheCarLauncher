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
        // opened in the last 3 hours (e.g. just before an update) → open again, same PIN
        if (System.currentTimeMillis() - Prefs.longVal(c, "rs_open_at", 0L) < 3 * 3600000L) {
            final Context cc = c;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() { public void run() { openSetup(cc); } }, 8000);
        }
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
        // the same PIN until it is closed by hand, and the console comes back by itself after an update
        setupPin = Prefs.str(c, "rs_pin", "");
        if (setupPin.length() != 4) { setupPin = String.format(Locale.US, "%04d", new Random().nextInt(10000)); Prefs.put(c, "rs_pin", setupPin); }
        Prefs.put(c, "rs_open_at", System.currentTimeMillis());
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
                long end = System.currentTimeMillis() + 60 * 60000;   // open for an hour (also the local console)
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
        // Local console on the home Wi-Fi / hotspot, no GitHub needed: GET /k?pin=1234&c=<command>
        // runs one support command (durum, log, obd, sh …) and answers plain text; /konsol?pin= is a page for it.
        String path = first.split(" ").length > 1 ? first.split(" ")[1] : "/";
        // GET /dosya?pin=1234&p=/system/app/AutoRadio/AutoRadio.apk → the file as a download (read-only, ≤ 64 MB)
        if (path.startsWith("/dosya?")) {
            String pin = "", fp = "";
            for (String kv : path.substring(7).split("&")) {
                String[] p = kv.split("=", 2);
                String v = p.length > 1 ? URLDecoder.decode(p[1], "UTF-8") : "";
                if (p[0].equals("pin")) pin = v.trim(); else if (p[0].equals("p")) fp = v.trim();
            }
            java.io.File f = new java.io.File(fp);
            OutputStream o = s.getOutputStream();
            if (!pin.equals(setupPin) || !f.isFile() || !f.canRead() || f.length() > 64L << 20) {
                byte[] b = ("Yok / okunamıyor: " + fp).getBytes(StandardCharsets.UTF_8);
                o.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                o.write(b); o.flush(); return;
            }
            s.setSoTimeout(60000);
            o.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"" + f.getName() + "\"\r\nContent-Length: " + f.length() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            InputStream fi = new java.io.FileInputStream(f);
            byte[] buf = new byte[65536]; int n;
            while ((n = fi.read(buf)) > 0) o.write(buf, 0, n);
            fi.close(); o.flush();
            return;
        }
        // GET /rapor?pin=1234 → one text file with everything a remote look needs (device, OBD, PSA ECUs,
        // Topway radio / reverse internals) to download on the phone and attach in the chat — no laptop needed
        if (path.startsWith("/rapor")) {
            String pin = "";
            int q = path.indexOf('?');
            if (q >= 0) for (String kv : path.substring(q + 1).split("&")) { String[] p = kv.split("=", 2); if (p[0].equals("pin") && p.length > 1) pin = URLDecoder.decode(p[1], "UTF-8").trim(); }
            OutputStream o = s.getOutputStream();
            byte[] b = (pin.equals(setupPin) ? report(c) : "PIN yanlış.").getBytes(StandardCharsets.UTF_8);
            String fn = "myds-rapor-" + new java.text.SimpleDateFormat("MMdd-HHmm", Locale.US).format(new java.util.Date()) + ".txt";
            o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Disposition: attachment; filename=\"" + fn + "\"\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            o.write(b); o.flush();
            return;
        }
        if (path.startsWith("/k?") || path.startsWith("/konsol")) {
            String pin = "", cmd = "";
            int q = path.indexOf('?');
            if (q >= 0) for (String kv : path.substring(q + 1).split("&")) {
                String[] p = kv.split("=", 2);
                String v = p.length > 1 ? URLDecoder.decode(p[1], "UTF-8") : "";
                if (p[0].equals("pin")) pin = v.trim(); else if (p[0].equals("c")) cmd = v.trim();
            }
            String out, type = "text/plain";
            if (!pin.equals(setupPin)) out = "PIN yanlış.";
            else if (path.startsWith("/konsol")) {
                type = "text/html";
                out = "<!doctype html><meta charset=utf-8><title>MyDS konsol</title><style>body{font:15px monospace;background:#0b0e13;color:#eee;padding:16px}"
                        + "input{width:70%;font:16px monospace;padding:8px;background:#161b23;color:#fff;border:1px solid #444}button{font-size:16px;padding:8px 16px}"
                        + "pre{white-space:pre-wrap;background:#11151c;padding:12px;border-radius:8px}</style><h3>MyDS konsol</h3>"
                        + "<form onsubmit=\"run();return false\"><input id=c autofocus placeholder='durum · log 200 · obd · sh getprop'><button>Çalıştır</button></form><pre id=o></pre>"
                        + "<script>function run(){var c=document.getElementById('c').value;document.getElementById('o').textContent='…';"
                        + "fetch('/k?pin=" + setupPin + "&c='+encodeURIComponent(c)).then(function(r){return r.text()}).then(function(t){document.getElementById('o').textContent=t})}</script>";
            } else {
                try { out = cmd.isEmpty() ? Commands.run(c, "yardim") : Commands.run(c, cmd); } catch (Throwable e) { out = "Hata: " + e; }
                if (out == null) out = "";
                if (out.length() > 200000) out = out.substring(out.length() - 200000);
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            OutputStream o = s.getOutputStream();
            o.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + b.length + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            o.write(b);
            o.flush();
            return;
        }
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

    // ---- Diagnosis helpers

    /** Methods, fields (static values too) and constructors of a class on this unit. */
    static String classDump(String name) {
        StringBuilder b = new StringBuilder();
        try {
            Class<?> k;
            int cut = name.indexOf(':');
            if (cut > 0) {
                Context pc = com.thecarlauncher.LauncherActivity.live.createPackageContext(name.substring(0, cut),
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                k = Class.forName(name.substring(cut + 1), false, pc.getClassLoader());
            } else k = Class.forName(name);
            b.append(k).append(" extends ").append(k.getSuperclass()).append('\n');
            for (java.lang.reflect.Constructor<?> m : k.getDeclaredConstructors()) b.append("  new ").append(m).append('\n');
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) b.append("  ").append(m.toString().replace("java.lang.", "")).append('\n');
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                b.append("  .").append(f.getType().getSimpleName()).append(' ').append(f.getName());
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    try { f.setAccessible(true); b.append(" = ").append(f.get(null)); } catch (Throwable ignored) { }
                }
                b.append('\n');
            }
            for (Class<?> in : k.getDeclaredClasses()) b.append("  class ").append(in.getName()).append('\n');
        } catch (Throwable e) {
            b.append("yok: ").append(e);
        }
        return b.toString();
    }

    /** Which audio input carries the FM tuner's sound: half a second from each, loudness per input. */
    static String fmProbe(Context c) {
        if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            final com.thecarlauncher.LauncherActivity a = com.thecarlauncher.LauncherActivity.live;
            if (a != null) a.runOnUiThread(new Runnable() { public void run() { a.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 77); } });
            return "Ses kaydı izni yok: cihaz ekranında 'İzin ver'e bas, sonra tekrar fmtest.";
        }
        StringBuilder b = new StringBuilder();
        int[] srcs = {1998, 0, 1, 5, 6, 7, 9, 10, 1999};
        int[] rates = {44100, 48000};
        for (int src : srcs) for (int rate : rates) {
            android.media.AudioRecord r = null;
            try {
                int min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT);
                r = new android.media.AudioRecord(src, rate, android.media.AudioFormat.CHANNEL_IN_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192) * 2);
                if (r.getState() != android.media.AudioRecord.STATE_INITIALIZED) { b.append(String.format(Locale.US, "kaynak %4d %d: açılmadı%n", src, rate)); continue; }
                r.startRecording();
                short[] buf = new short[rate / 2 * 2];
                int got = 0; long t0 = System.currentTimeMillis();
                while (got < buf.length && System.currentTimeMillis() - t0 < 1500) { int n = r.read(buf, got, buf.length - got); if (n <= 0) break; got += n; }
                double sum = 0; int peak = 0;
                for (int i = 0; i < got; i++) { sum += (double) buf[i] * buf[i]; peak = Math.max(peak, Math.abs(buf[i])); }
                b.append(String.format(Locale.US, "kaynak %4d %d: %d örnek · rms %.0f · tepe %d%n", src, rate, got, got > 0 ? Math.sqrt(sum / got) : 0, peak));
                break;   // one working rate per source is enough
            } catch (Throwable e) {
                b.append(String.format(Locale.US, "kaynak %4d %d: %s%n", src, rate, e.getClass().getSimpleName()));
            } finally {
                try { if (r != null) { r.stop(); r.release(); } } catch (Throwable ignored) { }
            }
        }
        return b.toString();
    }

    /** Everything for a remote diagnosis in one text: each part with its own time limit, errors written in. */
    static String report(Context c) {
        String[][] parts = {
                {"CİHAZ", "durum"},
                {"OBD", "obd"},
                {"ŞANZIMAN (6A9/689) · padded", "elm ATH1;ATSH6A9;ATCRA689;22D500;22D501;1003;22D500"},
                {"ŞANZIMAN · raw 4 bayt", "elm ATCAF0;0322D500;0322D501;ATCAF1"},
                {"BSI (752/652) · padded", "elm ATSH752;ATCRA652;22D8E1;22D8C3;22D4D2;1003;22D8E1"},
                {"BSI · raw 4 bayt", "elm ATCAF0;0322D8E1;0322D8C3;0210C0;0322D8E1;ATCAF1"},
                {"GÖSTERGE (75F/65F)", "elm ATSH75F;ATCRA65F;1003;22D8E1;ATCAF0;0322D8E1;ATCAF1"},
                {"OBD geri dönüş", "elm ATAR;ATSH7DF;ATH0;0100"},
                {"TOPWAY özellikler", "sh getprop | grep -i -E 'tw\\.|reverse|backcar|radio|fm|tuner' | head -80"},
                {"TOPWAY paketler", "sh pm path com.tw.radio; pm path com.tw.reverse; pm path com.tw.service; pm path com.tw.car; pm path com.tw.core"},
                {"TWUtil", "sinif android.tw.john.TWUtil"},
                {"RADYO dex (TWUtil çağrıları)", "sh P=$(pm path com.tw.radio | head -1 | cut -d: -f2); dexdump -d \"$P\" 2>/dev/null | grep -E 'const(/16|/high16|/4)? v[0-9]+, #|TWUtil;\\.(write|open|addHandler)|^  Class descriptor|name *:' | grep -B4 -A1 'TWUtil;' | head -400"},
                {"GERİ VİTES dex (TWUtil çağrıları)", "sh P=$(pm path com.tw.reverse | head -1 | cut -d: -f2); dexdump -d \"$P\" 2>/dev/null | grep -E 'const(/16|/high16|/4)? v[0-9]+, #|TWUtil;\\.(write|open|addHandler)' | grep -B4 -A1 'TWUtil;' | head -250"},
                {"RADYO dex (sabitler)", "sh P=$(pm path com.tw.radio | head -1 | cut -d: -f2); dexdump \"$P\" 2>/dev/null | grep -E 'Class descriptor|static final' -A0 | head -150"},
                {"OBD izi", "obdiz"},
        };
        StringBuilder sb = new StringBuilder("MyDS uzaktan teşhis raporu · " + new java.util.Date() + "\n");
        for (String[] p : parts) {
            sb.append("\n===== ").append(p[0]).append(" =====\n");
            try { sb.append(Commands.run(c, p[1])).append('\n'); }
            catch (Throwable t) { sb.append("HATA: ").append(t).append('\n'); }
        }
        return sb.toString();
    }

    // ---- Commands

    static final class Commands {
        static String run(Context c, String cmd) throws Exception {
            String[] w = cmd.split("\\s+", 2);
            String a = w.length > 1 ? w[1] : "";
            switch (w[0].toLowerCase(Locale.ROOT)) {
                case "yardim": case "help":
                    return "durum · obd · obdiz · sinif <sınıf> · fmtest · elm <komut> · motor [ac|kapat|{json}] · ara · bagla <MAC> [le] · pin <PIN> · log [satır] [süzgeç] · sh <komut> · guncelle · yeniden";
                case "durum": return state(c);
                case "rapor": return report(c);
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
                case "obdiz": return com.catarapatara.Obd.trace();
                case "sinif": return classDump(a.trim());
                case "dex": return dexStrings(c, a.trim());
                case "dinle": return listen(c, a.trim());
                case "aidl": return aidlDump(c, a.trim());
                case "kod": return proxyCodes(c, a.trim());
                case "carbind": return carBind(c, a.trim());
                case "stubdene": { String[] q = a.trim().split("\\s+"); return CarInfoTw.probe(c, q[0], q.length > 1 ? Integer.parseInt(q[1]) : 20); }
                case "carkayit": return CarInfoTw.start(c, Integer.parseInt(a.trim()));
                case "cardeger": return CarInfoTw.dump();
                case "carbirak": CarInfoTw.stop(c); return "bırakıldı";
                case "fmtest": return fmProbe(c);
                case "elm": { String r = com.catarapatara.Obd.raw(a.trim().toUpperCase(Locale.ROOT)); return r == null ? "yanıt yok (OBD bağlı mı?)" : r; }
                case "motor": {
                    MotorSesi m = MotorSesi.get(c);
                    if (a.trim().equals("ac")) m.set("{\"on\":true}"); else if (a.trim().equals("kapat")) m.set("{\"on\":false}");
                    else if (a.trim().startsWith("{")) m.set(a.trim());
                    Thread.sleep(a.trim().isEmpty() ? 0 : 1500);
                    return "ayarlar: " + m.settings() + "\ndurum: " + m.state();
                }
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
            sb.append("Gerçek Android: SDK ").append(android.os.Build.VERSION.SDK_INT).append(" · WebView ").append(WebViewCheck.major())
              .append(" · çekirdek ").append(Runtime.getRuntime().availableProcessors()).append(" · güçlü=").append(MotorSesi.strongUnit(c)).append('\n');
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

    /** "dex <pkg> [regex]": printable strings from an installed app's dex files (read-only). */
    static String dexStrings(Context c, String arg) {
        String[] p = arg.split("\\s+", 2);
        java.util.regex.Pattern re = java.util.regex.Pattern.compile(p.length > 1 ? p[1] : "(?i)(action|content://|range|mileage|fuel|oil|door|carinfo|broadcast)");
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        try {
            String apk = c.getPackageManager().getApplicationInfo(p[0], 0).sourceDir;
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    if (!e.getName().matches("classes\\d*\\.dex")) continue;
                    java.io.InputStream in = new java.io.BufferedInputStream(z.getInputStream(e));
                    StringBuilder b = new StringBuilder(); int ch;
                    while ((ch = in.read()) != -1) {
                        if (ch >= 32 && ch < 127) { b.append((char) ch); continue; }
                        if (b.length() >= 5 && b.length() < 200) { String t = b.toString(); if (re.matcher(t).find()) out.add(t); }
                        b.setLength(0);
                        if (out.size() > 400) break;
                    }
                    in.close();
                }
            }
        } catch (Throwable t) { return "hata: " + t; }
        StringBuilder r = new StringBuilder(out.size() + " satır\n");
        for (String t : out) r.append(t).append('\n');
        return r.toString();
    }

    /** "dinle act1,act2 [saniye]": listens to the given broadcasts (max 20 s) and dumps their extras. */
    static String listen(Context c, String arg) {
        String[] p = arg.split("\\s+");
        int sec = p.length > 1 ? Math.min(20, Math.max(1, Integer.parseInt(p[1]))) : 8;
        final StringBuilder r = new StringBuilder();
        android.content.IntentFilter f = new android.content.IntentFilter();
        for (String a : p[0].split(",")) if (a.length() > 0) f.addAction(a);
        android.content.BroadcastReceiver br = new android.content.BroadcastReceiver() {
            public void onReceive(Context x, android.content.Intent i) {
                synchronized (r) {
                    if (r.length() > 20000) return;
                    r.append(i.getAction());
                    android.os.Bundle b = i.getExtras();
                    if (b != null) for (String k : b.keySet()) r.append(' ').append(k).append('=').append(b.get(k));
                    r.append('\n');
                }
            }
        };
        android.os.HandlerThread ht = new android.os.HandlerThread("dinle"); ht.start();
        c.getApplicationContext().registerReceiver(br, f, null, new android.os.Handler(ht.getLooper()));
        try { Thread.sleep(sec * 1000L); } catch (InterruptedException ignored) { }
        try { c.getApplicationContext().unregisterReceiver(br); } catch (Throwable ignored) { }
        ht.quitSafely();
        synchronized (r) { return r.length() == 0 ? "(yayın yok)" : r.toString(); }
    }

    /** "aidl <pkg>": every IInterface/Binder class in an app, with its methods and TRANSACTION codes. */
    @SuppressWarnings("deprecation")
    static String aidlDump(Context c, String pkg) {
        StringBuilder b = new StringBuilder();
        try {
            Context pc = c.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader cl = pc.getClassLoader();
            String apk = c.getPackageManager().getApplicationInfo(pkg, 0).sourceDir;
            java.io.File odex = new java.io.File(c.getCacheDir(), "aidl.dex");
            dalvik.system.DexFile df = dalvik.system.DexFile.loadDex(apk, odex.getPath(), 0);
            java.util.Enumeration<String> en = df.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement();
                if (n.startsWith("android.") || n.startsWith("androidx.")) continue;
                Class<?> k;
                try { k = Class.forName(n, false, cl); } catch (Throwable t) { continue; }
                boolean ii = android.os.IInterface.class.isAssignableFrom(k), bi = android.os.Binder.class.isAssignableFrom(k);
                if (!ii && !bi) continue;
                b.append(k.isInterface() ? "interface " : "class ").append(n);
                if (k.getSuperclass() != null) b.append(" : ").append(k.getSuperclass().getName());
                for (Class<?> in : k.getInterfaces()) b.append(" +").append(in.getName());
                b.append('\n');
                for (java.lang.reflect.Method m : k.getDeclaredMethods()) b.append("   ").append(m.toString().replace("java.lang.", "")).append('\n');
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try { f.setAccessible(true); b.append("   .").append(f.getName()).append(" = ").append(f.get(null)).append('\n'); } catch (Throwable ignored) { }
                }
                if (b.length() > 60000) break;
            }
        } catch (Throwable t) { b.append("hata: ").append(t); }
        return b.length() == 0 ? "(bulunamadı)" : b.toString();
    }

    /** Records what a proxy would send: transaction code + interface token, without reaching any real service. */
    static final class Rec extends android.os.Binder {
        final StringBuilder log = new StringBuilder();
        @Override protected boolean onTransact(int code, android.os.Parcel d, android.os.Parcel r, int f) {
            log.append(" code=").append(code).append(" token=").append(token(d));
            if (r != null) { r.writeInt(0); }
            return true;
        }
    }

    static String token(android.os.Parcel d) {
        try {
            d.setDataPosition(0);
            for (int skip = 0; skip < 4; skip++) {
                d.setDataPosition(skip * 4);
                int len = d.readInt();
                if (len > 3 && len < 120) {
                    d.setDataPosition(skip * 4);
                    String t = d.readString();
                    if (t != null && t.matches("[A-Za-z0-9_.$]+")) return t;
                }
            }
        } catch (Throwable ignored) { }
        return "?";
    }

    /** "kod <pkg> <proxyClass>": calls each proxy method against a recorder binder, prints method → code. */
    static String proxyCodes(Context c, String arg) {
        StringBuilder b = new StringBuilder();
        try {
            String[] p = arg.split("\\s+");
            Context pc = c.createPackageContext(p[0], Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            Class<?> k = Class.forName(p[1], false, pc.getClassLoader());
            java.lang.reflect.Constructor<?> ctor = k.getDeclaredConstructor(android.os.IBinder.class);
            ctor.setAccessible(true);
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if (m.getName().equals("asBinder") || java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Rec rec = new Rec();
                Object px = ctor.newInstance(rec);
                Class<?>[] ts = m.getParameterTypes();
                Object[] args = new Object[ts.length];
                for (int j = 0; j < ts.length; j++) {
                    Class<?> t = ts[j];
                    args[j] = t == int.class ? (Object) 0 : t == float.class ? (Object) 0f : t == boolean.class ? (Object) false
                            : t == long.class ? (Object) 0L : t == double.class ? (Object) 0d : t == String.class ? "" : null;
                }
                try { m.setAccessible(true); m.invoke(px, args); } catch (Throwable t) { rec.log.append(" (").append(t.getClass().getSimpleName()).append(')'); }
                b.append(m.getName()).append(java.util.Arrays.toString(ts).replace("class ", "").replace("java.lang.", "")).append(rec.log).append('\n');
            }
        } catch (Throwable t) { b.append("hata: ").append(t); }
        return b.toString();
    }

    /** "carbind [action]": binds com.tw.carinfoservice and reports the binder's interface descriptor. */
    static String carBind(Context c, String action) {
        final String[] res = {"bağlanamadı"};
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        android.content.ServiceConnection sc = new android.content.ServiceConnection() {
            public void onServiceConnected(android.content.ComponentName n, android.os.IBinder b) {
                try { res[0] = n.flattenToShortString() + " → " + b.getInterfaceDescriptor(); } catch (Throwable t) { res[0] = "hata " + t; }
                done.countDown();
            }
            public void onServiceDisconnected(android.content.ComponentName n) { }
        };
        Intent i = new Intent(action.length() > 0 ? action : "com.tw.carinfoservice.CarService.Bind");
        i.setPackage("com.tw.carinfoservice");
        boolean ok;
        try { ok = c.getApplicationContext().bindService(i, sc, Context.BIND_AUTO_CREATE); } catch (Throwable t) { return "bind hata: " + t; }
        try { done.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        try { c.getApplicationContext().unbindService(sc); } catch (Throwable ignored) { }
        return "bind=" + ok + " " + res[0];
    }
}
