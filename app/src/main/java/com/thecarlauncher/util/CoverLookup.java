package com.thecarlauncher.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Album art for songs that arrive without it (Bluetooth from the phone: the unit's Bluetooth
 * module passes the title and artist over AVRCP but no cover). Looks the song up in Apple's
 * public iTunes Search API (no key), keeps the picture in memory and on disk, so each song is
 * fetched once. Needs internet (e.g. the phone's hotspot); without it simply nothing shows.
 */
public final class CoverLookup {
    private static final Map<String, Bitmap> MEM = new LinkedHashMap<String, Bitmap>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 12; }
    };
    private static final Map<String, Long> MISS = new LinkedHashMap<>();
    private static volatile String pending = "";
    private static volatile int version;

    private CoverLookup() {}

    /** Changes whenever a new cover was found (so the screens fetch it again). */
    public static int version() { return version; }

    private static String key(String artist, String title) {
        return ((artist == null ? "" : artist) + " – " + (title == null ? "" : title)).trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The cover if known; otherwise null and a background lookup starts. */
    public static synchronized Bitmap get(final Context c, final String artist, final String title) {
        if (title == null || title.trim().isEmpty()) return null;
        final String k = key(artist, title);
        Bitmap b = MEM.get(k);
        if (b != null) return b;
        final File f = new File(c.getCacheDir(), "covers/" + Integer.toHexString(k.hashCode()) + ".jpg");
        if (f.exists()) {
            b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) { MEM.put(k, b); return b; }
        }
        Long miss = MISS.get(k);
        if (miss != null && System.currentTimeMillis() - miss < 10 * 60000) return null;
        if (k.equals(pending)) return null;
        pending = k;
        new Thread(new Runnable() {
            public void run() {
                Bitmap got = null;
                try { got = fetch(artist, title); } catch (Throwable ignored) { }
                synchronized (CoverLookup.class) {
                    pending = "";
                    if (got == null) { MISS.put(k, System.currentTimeMillis()); return; }
                    MEM.put(k, got);
                    version++;
                }
                try {
                    f.getParentFile().mkdirs();
                    FileOutputStream o = new FileOutputStream(f);
                    got.compress(Bitmap.CompressFormat.JPEG, 88, o);
                    o.close();
                } catch (Throwable ignored) {
                }
            }
        }, "cover").start();
        return null;
    }

    private static Bitmap fetch(String artist, String title) throws Exception {
        // Titles often carry " - Remastered 2011", "(feat. …)": search with the plain name first.
        String clean = title.replaceAll("\\s*[\\(\\[].*?[\\)\\]]", "").replaceAll("\\s+-\\s+.*$", "").trim();
        String term = ((artist == null ? "" : artist) + " " + clean).trim();
        String json = get("https://itunes.apple.com/search?media=music&entity=song&limit=5&country=TR&term=" + URLEncoder.encode(term, "UTF-8"));
        JSONArray r = new JSONObject(json).optJSONArray("results");
        if (r == null || r.length() == 0) {
            json = get("https://itunes.apple.com/search?media=music&entity=song&limit=5&term=" + URLEncoder.encode(term, "UTF-8"));
            r = new JSONObject(json).optJSONArray("results");
        }
        if (r == null || r.length() == 0) return null;
        String want = artist == null ? "" : artist.toLowerCase(java.util.Locale.ROOT);
        JSONObject best = r.getJSONObject(0);
        for (int i = 0; i < r.length(); i++) {
            String a = r.getJSONObject(i).optString("artistName").toLowerCase(java.util.Locale.ROOT);
            if (!want.isEmpty() && (a.contains(want) || want.contains(a))) { best = r.getJSONObject(i); break; }
        }
        String u = best.optString("artworkUrl100");
        if (u.isEmpty()) return null;
        u = u.replace("100x100bb", "600x600bb");
        HttpURLConnection c = open(u);
        InputStream in = c.getInputStream();
        Bitmap b = BitmapFactory.decodeStream(in);
        in.close();
        c.disconnect();
        return b;
    }

    private static HttpURLConnection open(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "MyDS");
        return c;
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c = open(u);
        InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        c.disconnect();
        return o.toString("UTF-8");
    }
}
