package com.thecarlauncher.util;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The artist's picture for the music players (Deezer's public artist search, no key). Fetched once
 * per artist in the background and kept in the app's cache, so it shows offline afterwards.
 * {@link #get} never blocks: "" until the picture is there.
 */
public final class ArtistPhoto {
    private static final Map<String, String> mem = new HashMap<>();
    private static final Set<String> busy = new HashSet<>(), missing = new HashSet<>();

    private ArtistPhoto() {}

    private static String key(String artist) {
        String a = artist == null ? "" : artist.trim().toLowerCase(java.util.Locale.ROOT);
        // "Tarkan feat. X", "A & B", "A, B": the first name is the one with a picture
        a = a.split("\\s+(feat\\.?|ft\\.?|&|x|ve|vs\\.?)\\s+|,|;|/")[0].trim();
        return a;
    }

    /** data: URL of the artist's picture, or "" (then a fetch starts in the background). */
    public static synchronized String get(Context c, String artist) {
        final String k = key(artist);
        if (k.length() < 2 || k.startsWith("unknown") || k.startsWith("<")) return "";
        String m = mem.get(k);
        if (m != null) return m;
        if (missing.contains(k)) return "";
        final File f = new File(c.getCacheDir(), "artist_" + Integer.toHexString(k.hashCode()) + ".jpg");
        if (f.exists()) {
            String d = read(f);
            if (!d.isEmpty()) { mem.put(k, d); return d; }
        }
        if (busy.add(k)) {
            new Thread(new Runnable() {
                public void run() {
                    byte[] img = null;
                    try {
                        JSONArray d = new JSONObject(text("https://api.deezer.com/search/artist?limit=1&q=" + URLEncoder.encode(k, "UTF-8"))).optJSONArray("data");
                        if (d != null && d.length() > 0) {
                            String u = d.getJSONObject(0).optString("picture_medium", "");
                            if (!u.isEmpty() && !u.contains("/artist//")) img = download(u);
                        }
                    } catch (Throwable ignored) {
                    }
                    synchronized (ArtistPhoto.class) {
                        busy.remove(k);
                        if (img == null || img.length < 500) { missing.add(k); return; }
                        try { FileOutputStream o = new FileOutputStream(f); o.write(img); o.close(); } catch (Throwable ignored) { }
                        mem.put(k, "data:image/jpeg;base64," + Base64.encodeToString(img, Base64.NO_WRAP));
                    }
                }
            }, "artist-photo").start();
        }
        return "";
    }

    private static String read(File f) {
        try {
            InputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable e) { return ""; }
    }

    private static String text(String url) throws Exception { return new String(download(url), "UTF-8"); }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(6000);
            con.setReadTimeout(10000);
            con.setRequestProperty("User-Agent", "TheCarLauncher");
            if (con.getResponseCode() != 200) throw new java.io.IOException("HTTP " + con.getResponseCode());
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return bo.toByteArray();
        } finally {
            con.disconnect();
        }
    }
}
