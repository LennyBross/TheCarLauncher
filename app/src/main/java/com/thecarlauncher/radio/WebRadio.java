package com.thecarlauncher.radio;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Internet radio: Turkish (and any other) stations streamed over the unit's mobile data.
 * Stream addresses come from the open radio-browser.info directory at play time (stations move
 * their streams now and then), and the last good address is remembered for each station.
 * Plays through its own MediaPlayer, so the sound goes through the Denon amp and the EQ like any
 * other music, and keeps playing behind Android Auto.
 */
public final class WebRadio implements AudioManager.OnAudioFocusChangeListener {
    private static WebRadio sInstance;
    public static synchronized WebRadio get(Context c) {
        if (sInstance == null) sInstance = new WebRadio(c.getApplicationContext());
        return sInstance;
    }

    private static final String[] API = { "https://de1.api.radio-browser.info", "https://nl1.api.radio-browser.info",
            "https://at1.api.radio-browser.info", "https://all.api.radio-browser.info" };
    private static final String UA = "MyDS-car-launcher/1.0";

    private final Context ctx;
    private final SharedPreferences p;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager am;
    private MediaPlayer mp;
    private volatile String name = "", url = "", status = "off", err = "";
    private volatile int gen = 0;

    private WebRadio(Context c) {
        ctx = c;
        p = c.getSharedPreferences("webradio", Context.MODE_PRIVATE);
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        name = p.getString("last", "");
    }

    public boolean isPlaying() { return "playing".equals(status) || "loading".equals(status); }
    public String current() { return name; }

    /** {on, name, status: off|loading|playing|error, err, favs:[...], recent:[...]} */
    public String state() {
        try {
            JSONObject o = new JSONObject();
            o.put("on", isPlaying()); o.put("name", name); o.put("status", status); o.put("err", err);
            o.put("favs", new JSONArray(p.getString("favs", "[]")));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    public void setFavs(String json) { try { new JSONArray(json); p.edit().putString("favs", json).apply(); } catch (Exception ignored) { } }

    /** Plays a station by name; url may be empty (then it is looked up). */
    public void play(final String stName, final String stUrl) {
        final int g = ++gen;
        name = stName == null ? "" : stName; err = ""; status = "loading";
        p.edit().putString("last", name).apply();
        try { RadioController.get(ctx).stop(); } catch (Throwable ignored) { }
        new Thread(new Runnable() {
            public void run() {
                String u = stUrl != null && stUrl.startsWith("http") ? stUrl : p.getString("u:" + name, "");
                if (u.isEmpty()) u = lookup(name);
                if (g != gen) return;
                if (u.isEmpty()) { status = "error"; err = "İstasyon bulunamadı (internet var mı?)"; return; }
                final String fu = u;
                main.post(new Runnable() { public void run() { if (g == gen) start(fu, g, stUrl == null || stUrl.isEmpty()); } });
            }
        }, "webradio").start();
    }

    @SuppressWarnings("deprecation")
    private void start(final String u, final int g, final boolean mayRetry) {
        release();
        url = u;
        am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        final MediaPlayer m = new MediaPlayer();
        mp = m;
        try {
            m.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            m.setDataSource(u);
            m.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer x) {
                    if (g != gen) return;
                    x.start(); status = "playing";
                    p.edit().putString("u:" + name, u).apply();
                }
            });
            m.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer x, int what, int extra) {
                    if (g != gen) return true;
                    // a remembered address that went dead: look it up again once
                    if (mayRetry && !p.getString("u:" + name, "").isEmpty()) {
                        p.edit().remove("u:" + name).apply();
                        play(name, "");
                    } else { status = "error"; err = "Yayın açılmadı (" + what + "/" + extra + ")"; release(); }
                    return true;
                }
            });
            m.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer x) { if (g == gen && "playing".equals(status)) play(name, url); }   // stream dropped: reconnect
            });
            m.prepareAsync();
        } catch (Exception e) {
            status = "error"; err = "Yayın açılmadı"; release();
        }
    }

    @SuppressWarnings("deprecation")
    public void stop() {
        gen++;
        release();
        status = "off";
        try { am.abandonAudioFocus(this); } catch (Throwable ignored) { }
    }

    private void release() {
        MediaPlayer m = mp; mp = null;
        if (m != null) { try { m.reset(); m.release(); } catch (Throwable ignored) { } }
    }

    /** Next / previous favourite (steering-wheel keys). */
    public void skip(boolean fwd) {
        try {
            JSONArray f = new JSONArray(p.getString("favs", "[]"));
            if (f.length() == 0) return;
            int at = -1;
            for (int i = 0; i < f.length(); i++) if (name.equals(f.getJSONObject(i).optString("n"))) at = i;
            int k = ((at < 0 ? 0 : at + (fwd ? 1 : -1)) % f.length() + f.length()) % f.length();
            JSONObject s = f.getJSONObject(k);
            play(s.optString("n"), s.optString("u"));
        } catch (Exception ignored) { }
    }

    @Override
    public void onAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS) { if (isPlaying()) stop(); }
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { try { if (mp != null) mp.setVolume(.3f, .3f); } catch (Throwable ignored) { } }
        else if (change == AudioManager.AUDIOFOCUS_GAIN) { try { if (mp != null) mp.setVolume(1f, 1f); } catch (Throwable ignored) { } }
    }

    // ---- radio-browser.info directory ----------------------------------------------------------

    /** Best stream address for a station name (Turkish stations first). */
    private String lookup(String n) {
        String r = searchRaw(n, true, 5);
        if (r == null || r.length() < 5) r = searchRaw(n, false, 5);
        try {
            JSONArray a = new JSONArray(r == null ? "[]" : r);
            for (int i = 0; i < a.length(); i++) {
                String u = a.getJSONObject(i).optString("url_resolved", a.getJSONObject(i).optString("url"));
                if (u.startsWith("http")) return u;
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** Search for the page: [{n, u, tags, logo, kbps}] */
    public String search(String q) {
        String r = searchRaw(q, true, 30);
        try {
            JSONArray a = new JSONArray(r == null ? "[]" : r), out = new JSONArray();
            if (a.length() == 0) a = new JSONArray(searchRaw(q, false, 30));
            for (int i = 0; i < a.length() && out.length() < 30; i++) {
                JSONObject s = a.getJSONObject(i), o = new JSONObject();
                o.put("n", s.optString("name").trim());
                o.put("u", s.optString("url_resolved", s.optString("url")));
                o.put("tags", s.optString("tags"));
                o.put("kbps", s.optInt("bitrate"));
                out.put(o);
            }
            return out.toString();
        } catch (Exception e) { return "[]"; }
    }

    private String searchRaw(String q, boolean tr, int limit) {
        String path;
        try {
            path = "/json/stations/search?name=" + URLEncoder.encode(q, "UTF-8") + (tr ? "&countrycode=TR" : "")
                    + "&hidebroken=true&order=clickcount&reverse=true&limit=" + limit;
        } catch (Exception e) { return null; }
        for (String h : API) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(h + path).openConnection();
                c.setConnectTimeout(5000); c.setReadTimeout(7000);
                c.setRequestProperty("User-Agent", UA);
                if (c.getResponseCode() != 200) continue;
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192]; int k;
                while ((k = in.read(b)) > 0) bo.write(b, 0, k);
                return bo.toString("UTF-8");
            } catch (Exception ignored) {
            } finally { if (c != null) c.disconnect(); }
        }
        return null;
    }
}
