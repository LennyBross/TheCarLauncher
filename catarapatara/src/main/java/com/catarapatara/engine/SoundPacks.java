package com.catarapatara.engine;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Engine sound packs made from real recordings: a zip with pack.json (rpm map of the recording)
 * and a mono 16-bit WAV. Imported once into the app's own storage; nothing is bundled in the APK.
 */
public final class SoundPacks {
    private SoundPacks() {}

    public static final class Info {
        public final String id, name, sub;
        public final int redline;
        Info(String id, String name, String sub, int redline) { this.id = id; this.name = name; this.sub = sub; this.redline = redline; }
    }

    static File root(Context c) {
        File d = new File(c.getFilesDir(), "soundpacks");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    public static List<Info> list(Context c) {
        List<Info> out = new ArrayList<>();
        File[] dirs = root(c).listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            try {
                JSONObject j = new JSONObject(read(new File(d, "pack.json")));
                out.add(new Info("pack:" + d.getName(), j.optString("name", d.getName()), j.optString("sub", "Gerçek kayıt"),
                        j.optInt("redline", 6500)));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static boolean delete(Context c, String id) {
        if (!id.startsWith("pack:")) return false;
        File d = new File(root(c), id.substring(5).replace("/", "").replace("..", ""));
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        return d.delete();
    }

    /** Imports a pack zip. Returns the pack's name, or null if it is not a valid pack. */
    public static String importZip(Context c, Uri uri) {
        try (InputStream raw = c.getContentResolver().openInputStream(uri)) {
            return importStream(c, raw, null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Packs that ship inside the app (assets/packs/*.zip, from the repo's sounds/packs folder):
     * unpacked once, again whenever the zip changes. Folder name "b-<zip name>" so they never clash
     * with packs the user imports.
     */
    public static void syncAssets(Context c) {
        try {
            String[] zips = c.getAssets().list("packs");
            if (zips == null) return;
            android.content.SharedPreferences p = c.getSharedPreferences("packs", Context.MODE_PRIVATE);
            for (String z : zips) {
                if (!z.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) continue;
                String id = "b-" + z.substring(0, z.length() - 4).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                long size;
                try (android.content.res.AssetFileDescriptor fd = c.getAssets().openFd("packs/" + z)) {
                    size = fd.getLength();
                } catch (Exception e) {
                    size = -1; // compressed in the APK: fall back to the app version
                }
                String stamp = size + "/" + appVersion(c);
                if (stamp.equals(p.getString(id, "")) && new File(root(c), id + "/pack.json").isFile()) continue;
                try (InputStream in = c.getAssets().open("packs/" + z)) {
                    if (importStream(c, in, id) != null) p.edit().putString(id, stamp).apply();
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Where shared packs live: the sounds/ folder of the project repository on GitHub. */
    public static final String GH_DIR = "https://api.github.com/repos/LennyBross/TheCarLauncher/contents/sounds?ref=main";
    public static volatile String ghStatus = "";

    /**
     * Downloads new or changed packs from GitHub (sounds/*.zip). Returns how many were added or
     * updated; packs already up to date (same git sha) are skipped.
     */
    public static synchronized int syncGitHub(Context c) {
        int added = 0;
        try {
            ghStatus = "GitHub kontrol ediliyor…";
            org.json.JSONArray list = new org.json.JSONArray(new String(httpGet(GH_DIR), "UTF-8"));
            android.content.SharedPreferences p = c.getSharedPreferences("packs", Context.MODE_PRIVATE);
            int total = 0;
            for (int i = 0; i < list.length(); i++) {
                org.json.JSONObject o = list.getJSONObject(i);
                String name = o.optString("name");
                if (!"file".equals(o.optString("type")) || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) continue;
                total++;
                String id = "b-" + name.substring(0, name.length() - 4).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                String sha = o.optString("sha");
                if (sha.equals(p.getString("gh:" + id, "")) && new File(root(c), id + "/pack.json").isFile()) continue;
                ghStatus = "İndiriliyor: " + name;
                byte[] zip = httpGet(o.optString("download_url"));
                if (importStream(c, new java.io.ByteArrayInputStream(zip), id) != null) {
                    p.edit().putString("gh:" + id, sha).apply();
                    added++;
                }
            }
            ghStatus = "GitHub: " + total + " paket" + (added > 0 ? " · " + added + " yeni" : " · güncel");
        } catch (Exception e) {
            ghStatus = "GitHub'a ulaşılamadı";
        }
        return added;
    }

    private static byte[] httpGet(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "CataraPatara");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                if (o.size() > 60 * 1024 * 1024) throw new java.io.IOException("too large");
            }
            return o.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static long appVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Imports a pack zip stream into its own folder (forcedId, or one made from its name). */
    public static synchronized String importStream(Context c, InputStream raw, String forcedId) {
        File tmp = new File(root(c), ".import");
        deleteDir(tmp);
        tmp.mkdirs();
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry e;
            long total = 0;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = new File(e.getName()).getName(); // flatten, no paths
                String low = name.toLowerCase(java.util.Locale.ROOT);
                if (name.startsWith(".") || !(low.equals("pack.json") || low.endsWith(".wav"))) continue;
                try (OutputStream o = new FileOutputStream(new File(tmp, name))) {
                    int n;
                    while ((n = z.read(buf)) > 0) {
                        total += n;
                        if (total > 120L * 1024 * 1024) throw new java.io.IOException("too large");
                        o.write(buf, 0, n);
                    }
                }
            }
            JSONObject j = new JSONObject(read(new File(tmp, "pack.json")));
            if (!new File(tmp, j.optString("file", "sound.wav")).isFile()) throw new java.io.IOException("no audio");
            String name = j.optString("name", "Ses paketi");
            String id = forcedId != null ? forcedId : name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            if (id.isEmpty()) id = "pack" + System.currentTimeMillis();
            File dest = new File(root(c), id);
            deleteDir(dest);
            if (!tmp.renameTo(dest)) throw new java.io.IOException("rename");
            return name;
        } catch (Exception e) {
            deleteDir(tmp);
            return null;
        }
    }

    /** Loads a pack into a grain engine rendering at outRate, plus its idle and redline. */
    public static Loaded load(Context c, String id, int outRate) {
        try {
            File d = new File(root(c), id.substring(5));
            JSONObject j = new JSONObject(read(new File(d, "pack.json")));
            File wav = new File(d, j.optString("file", "sound.wav"));
            int[] sr = new int[1];
            short[] pcm = readWav(wav, sr);
            if (pcm == null) return null;
            JSONObject bg = j.optJSONObject("bankGain");
            float[] gains = bg == null ? null : new float[]{(float) bg.optDouble("idle", 1), (float) bg.optDouble("on", 1), (float) bg.optDouble("off", 1)};
            float idle = (float) j.optDouble("idle", 800), red = (float) j.optDouble("redline", 6500);
            GrainEngine g = new GrainEngine(pcm, sr[0], outRate, idle, (float) j.optDouble("maxRate", 1.8),
                    (float) j.optDouble("grain", .085), bank(j, "idleBank"), bank(j, "onBank"), bank(j, "offBank"), gains);
            return new Loaded(g, idle, red, j.optString("name", "Ses paketi"), j.optString("sub", ""));
        } catch (Exception e) {
            return null;
        }
    }

    public static final class Loaded {
        public final GrainEngine engine;
        public final float idle, redline;
        public final String name, sub;
        Loaded(GrainEngine e, float i, float r, String n, String s) { engine = e; idle = i; redline = r; name = n; sub = s; }
    }

    private static float[][] bank(JSONObject j, String key) {
        JSONArray a = j.optJSONArray(key);
        if (a == null) return null;
        float[][] out = new float[a.length()][];
        for (int i = 0; i < a.length(); i++) {
            JSONArray p = a.optJSONArray(i);
            out[i] = new float[]{(float) p.optDouble(0), (float) p.optDouble(1), (float) p.optDouble(2, 1)};
        }
        return out;
    }

    /** Mono or stereo 16-bit PCM WAV → mono samples. */
    private static short[] readWav(File f, int[] srOut) throws Exception {
        byte[] b = readBytes(f);
        if (b.length < 44 || b[0] != 'R' || b[8] != 'W') return null;
        int p = 12, ch = 1, bits = 16, sr = 32000, dataAt = -1, dataLen = 0;
        while (p + 8 <= b.length) {
            String id = new String(b, p, 4, "US-ASCII");
            int len = le32(b, p + 4);
            if (id.equals("fmt ")) { ch = le16(b, p + 10); sr = le32(b, p + 12); bits = le16(b, p + 22); }
            else if (id.equals("data")) { dataAt = p + 8; dataLen = Math.min(len, b.length - dataAt); break; }
            p += 8 + len + (len & 1);
        }
        if (dataAt < 0 || bits != 16 || ch < 1) return null;
        int frames = dataLen / (2 * ch);
        short[] out = new short[frames];
        for (int i = 0; i < frames; i++) {
            int s = 0;
            for (int k = 0; k < ch; k++) s += (short) le16(b, dataAt + 2 * (i * ch + k));
            out[i] = (short) (s / ch);
        }
        srOut[0] = sr;
        return out;
    }

    private static int le16(byte[] b, int i) { return (b[i] & 255) | ((b[i + 1] & 255) << 8); }
    private static int le32(byte[] b, int i) { return le16(b, i) | (le16(b, i + 2) << 16); }

    private static byte[] readBytes(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    private static String read(File f) throws Exception { return new String(readBytes(f), "UTF-8"); }

    private static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }
}
