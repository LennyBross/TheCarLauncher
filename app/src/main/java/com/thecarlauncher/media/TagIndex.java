package com.thecarlauncher.media;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Song tags (title, artist, album artist, album, genre, year, track number, length) of the music
 * on USB sticks and SD cards, read from the files themselves (ID3 / MP4 / Vorbis / FLAC tags).
 *
 * Reading tags takes a moment per file, so it runs in the background when a stick is plugged in
 * and the result is kept on the unit (keyed by path + size + date): the same stick opens at once
 * the next time, and only new or changed songs are read again.
 */
public final class TagIndex {
    /** One song: tags as read from the file. */
    public static final class Tags {
        public String title = "", artist = "", albumArtist = "", album = "", genre = "";
        public int year, track;
        public long duration;
    }

    private static final Pattern AUDIO = Pattern.compile("(?i).+\\.(mp3|m4a|aac|flac|ogg|oga|opus|wav|wma|amr|3gp|mka)$");
    private static final Map<String, Object[]> map = new HashMap<>();   // path -> {size, mtime, Tags}
    private static boolean loaded = false;
    private static volatile boolean running = false, again = false;
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** Set by the launcher: called on the main thread when new tags are in. */
    public static Runnable onUpdated;

    private TagIndex() { }

    private static File file(Context c) { return new File(c.getFilesDir(), "usb_tags.json"); }

    private static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        File f = file(c);
        if (!f.isFile()) return;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int n = 0; while (n < b.length) { int k = in.read(b, n, b.length - n); if (k < 0) break; n += k; }
            in.close();
            JSONObject o = new JSONObject(new String(b, 0, n, "UTF-8"));
            JSONArray names = o.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String p = names.getString(i);
                JSONArray a = o.getJSONArray(p);
                Tags t = new Tags();
                t.title = a.optString(2); t.artist = a.optString(3); t.albumArtist = a.optString(4); t.album = a.optString(5);
                t.genre = a.optString(6); t.year = a.optInt(7); t.track = a.optInt(8); t.duration = a.optLong(9);
                map.put(p, new Object[]{a.optLong(0), a.optLong(1), t});
            }
        } catch (Throwable ignored) { }
    }

    private static synchronized void save(Context c) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Object[]> e : map.entrySet()) {
                Object[] v = e.getValue(); Tags t = (Tags) v[2];
                JSONArray a = new JSONArray();
                a.put(v[0]).put(v[1]).put(t.title).put(t.artist).put(t.albumArtist).put(t.album).put(t.genre).put(t.year).put(t.track).put(t.duration);
                o.put(e.getKey(), a);
            }
            File tmp = new File(c.getFilesDir(), "usb_tags.json.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(o.toString().getBytes("UTF-8"));
            out.close();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file(c));
        } catch (Throwable ignored) { }
    }

    /** Tags for a file if they were read already and the file has not changed since; else null. */
    public static synchronized Tags get(Context c, File f) {
        load(c);
        Object[] v = map.get(f.getPath());
        if (v == null) return null;
        if ((Long) v[0] != f.length() || (Long) v[1] != f.lastModified()) return null;
        return (Tags) v[2];
    }

    /** Same, by path, for songs Android's own media index already lists. */
    public static synchronized Tags get(Context c, String path) {
        load(c);
        Object[] v = map.get(path);
        return v == null ? null : (Tags) v[2];
    }

    public static boolean busy() { return running; }

    /** Reads the tags of every song on the USB sticks / SD cards that is not known yet. */
    public static void scan(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (running) { again = true; return; }
        if (!MusicLibrary.hasPermission(c)) return;
        if (com.thecarlauncher.util.Storage.needsAllFiles() && !com.thecarlauncher.util.Storage.hasAllFiles()) return;
        running = true;
        new Thread(new Runnable() { public void run() {
            try {
                do { again = false; pass(c); } while (again);
            } finally { running = false; }
        } }, "usb-tags").start();
    }

    private static void pass(Context c) {
        load(c);
        List<File> todo = new ArrayList<>();
        List<File> roots = new ArrayList<>(com.thecarlauncher.util.Storage.removableRoots(c));
        for (String s : MusicLibrary.musicFolders(c)) roots.add(new File(s));
        for (File r : roots) collect(c, r, 0, todo);
        if (todo.isEmpty()) return;
        String key = "usbtags";
        com.thecarlauncher.mib.LoadingBanner.start(key, msg(c, 0, todo.size()));
        long lastShow = System.currentTimeMillis();
        try {
            for (int i = 0; i < todo.size(); i++) {
                File f = todo.get(i);
                Tags t = read(f.getPath());
                synchronized (TagIndex.class) { map.put(f.getPath(), new Object[]{f.length(), f.lastModified(), t}); }
                if (i % 25 == 0) com.thecarlauncher.mib.LoadingBanner.update(key, msg(c, i + 1, todo.size()));
                // show what is read so far every ~20 s on a big stick
                if (System.currentTimeMillis() - lastShow > 20000) { lastShow = System.currentTimeMillis(); save(c); publish(); }
            }
            save(c);
        } finally {
            com.thecarlauncher.mib.LoadingBanner.stop(key);
        }
        publish();
        com.thecarlauncher.mib.LoadingBanner.flash("usbtags-done", com.thecarlauncher.mib.Mib.t(c,
                "USB: " + todo.size() + " pesama pročitano", "USB: " + todo.size() + " şarkı tarandı"), 3500);
    }

    private static String msg(Context c, int i, int n) {
        return com.thecarlauncher.mib.Mib.t(c, "Čitam USB: " + i + " / " + n, "USB taranıyor: " + i + " / " + n + " şarkı");
    }

    private static void publish() {
        MusicLibrary.invalidate();
        final Runnable r = onUpdated;
        if (r != null) main.post(r);
    }

    private static void collect(Context c, File dir, int depth, List<File> todo) {
        if (depth > 12 || todo.size() > 20000) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(".") || n.equals("LOST.DIR") || n.equals("Android")) continue;
            if (f.isDirectory()) collect(c, f, depth + 1, todo);
            else if (AUDIO.matcher(n).matches() && get(c, f) == null) todo.add(f);
        }
    }

    private static Tags read(String path) {
        Tags t = new Tags();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            t.title = s(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            t.artist = s(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            t.albumArtist = s(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            t.album = s(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            t.genre = genre(s(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)));
            t.year = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR));
            if (t.year == 0) t.year = num(s(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)));
            if (t.year < 1900 || t.year > 2100) t.year = 0;
            t.track = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER));
            t.duration = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Throwable ignored) {
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
        return t;
    }

    private static String s(String v) {
        if (v == null) return "";
        v = v.replace('\u0000', ' ').trim();
        return "<unknown>".equalsIgnoreCase(v) ? "" : v;
    }

    /** "3/12", "2004-05-01", "07" → the leading number. */
    private static int num(String v) {
        if (v == null) return 0;
        int n = 0, i = 0;
        while (i < v.length() && !Character.isDigit(v.charAt(i))) i++;
        while (i < v.length() && Character.isDigit(v.charAt(i)) && n < 100000000) n = n * 10 + (v.charAt(i++) - '0');
        return n;
    }

    // ID3v1 genre numbers ("(17)", "17") still found in many MP3s
    private static final String[] ID3 = {"Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
            "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska", "Death Metal",
            "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental",
            "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise", "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative",
            "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance",
            "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret",
            "New Wave", "Psychedelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical",
            "Rock & Roll", "Hard Rock", "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion", "Bebop", "Latin", "Revival", "Celtic",
            "Bluegrass", "Avantgarde", "Gothic Rock", "Progressive Rock", "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus",
            "Easy Listening", "Acoustic", "Humour", "Speech", "Chanson", "Opera", "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus",
            "Porn Groove", "Satire", "Slow Jam", "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle",
            "Duet", "Punk Rock", "Drum Solo", "A Cappella", "Euro-House", "Dance Hall"};

    static String genre(String g) {
        if (g.isEmpty()) return g;
        String x = g.replace("(", "").replace(")", "").trim();
        if (x.matches("\\d{1,3}")) { int k = Integer.parseInt(x); return k < ID3.length ? ID3[k] : ""; }
        if (g.matches("\\(\\d{1,3}\\).+")) g = g.substring(g.indexOf(')') + 1).trim();
        // "Pop; Dance" / "Pop/Rock": the first one names the group
        int cut = g.indexOf(';'); if (cut > 0) g = g.substring(0, cut).trim();
        if (g.length() > 0) g = Character.toUpperCase(g.charAt(0)) + g.substring(1);
        return g;
    }
}
