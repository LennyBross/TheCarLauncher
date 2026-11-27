package com.thecarlauncher.util;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.thecarlauncher.mib.LoadingBanner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Software updates from the project's GitHub releases. Builds of the main branch are published
 * as releases ("v4.5.1"); with "test builds" on, every build counts. The APK is downloaded with
 * the loading banner and handed to Android's package installer: as an ordinary app Android asks
 * to confirm the update, as a system app (Magisk module) it installs silently.
 */
public final class Updater {
    public static final String REPO = "LennyBross/TheCarLauncher";
    /** Install found updates right away (only while the car stands still). */
    public static final String P_INSTANT = "update_instant";
    public static final String P_AUTO = "update_auto_check", P_BETA = "update_test_builds", P_LAST = "update_last_check";
    private static final String ACTION_DONE = "com.thecarlauncher.UPDATE_INSTALLED";

    public static final class Release {
        public final String tag, name, notes, apkUrl, date;
        public final long versionCode;
        public final String versionName;
        public final boolean test;

        Release(String tag, String name, String notes, String apkUrl, String date, long code, String vname, boolean test) {
            this.tag = tag;
            this.name = name;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.date = date;
            this.versionCode = code;
            this.versionName = vname;
            this.test = test;
        }
    }

    public interface Checked { void onChecked(Release latest, String error); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Release lastFound;
    private static boolean busy;
    private static volatile boolean cancelled;

    /** Steps of an update, shown like the factory "Software update in progress" list. */
    public static final String[] STEPS = {"launcher::download", "launcher::verify", "launcher::install"};
    /** Progress per step 0..100, -1 = not started; failed step index or -1. */
    private static final int[] progress = {-1, -1, -1};
    private static int failedStep = -1;
    private static String failMessage;

    public interface Progress { void onUpdateProgress(); }

    private static final java.util.List<Progress> watchers = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addWatcher(Progress p) { if (!watchers.contains(p)) watchers.add(p); }

    public static void removeWatcher(Progress p) { watchers.remove(p); }

    public static int progress(int step) { return progress[step]; }

    public static int failedStep() { return failedStep; }

    public static String failMessage() { return failMessage; }

    // ---- waiting for Android's "Update this app?" window
    private static volatile Intent confirm;
    private static volatile long confirmAt;

    /** Installed files are handed over and Android waits for the owner's "Yükle" (install step at 80 %). */
    public static boolean awaitingConfirm() { return busy && progress[2] == 80; }

    /** Shows Android's confirmation window again (it can end up behind the launcher on some units). */
    public static void reopenConfirm(Context c) {
        Intent i = confirm;
        if (i == null) { classicInstall(c); return; }
        try { c.startActivity(new Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { classicInstall(c); }
    }

    /** Fallback: the same downloaded file through Android's ordinary installer screen. */
    public static void classicInstall(Context c) {
        try {
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE, ApkProvider.uri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            c.startActivity(i);
        } catch (Exception e) {
            LoadingBanner.flash("update-fail", "Yükleyici açılamadı: " + e.getMessage(), 4000);
        }
    }

    /** True while a download / install runs or after one ended (until the screen resets it). */
    public static boolean started() { return progress[0] >= 0; }

    public static void cancel() { cancelled = true; }

    public static void reset() {
        if (busy) return;
        progress[0] = progress[1] = progress[2] = -1;
        failedStep = -1;
        failMessage = null;
        notifyWatchers();
    }

    private static void step(int i, int pct) {
        progress[i] = pct;
        notifyWatchers();
    }

    private static void fail(int i, String msg) {
        failedStep = i;
        failMessage = msg;
        busy = false;
        notifyWatchers();
    }

    private static void notifyWatchers() {
        MAIN.post(new Runnable() {
            public void run() { for (Progress p : watchers) p.onUpdateProgress(); }
        });
    }

    private Updater() {}

    public static long installedCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    public static Release lastFound() { return lastFound; }

    // ---- Companion apps (CataraPatara, its CAN bridge): published in the same release --------------
    public static final class Companion {
        public final String pkg, label, asset, marker;
        public volatile String url;
        public volatile long code;

        Companion(String pkg, String label, String asset, String marker) { this.pkg = pkg; this.label = label; this.asset = asset; this.marker = marker; }
    }

    public static final Companion[] COMPANIONS = {
            new Companion("com.catarapatara.canbridge", "CataraPatara CAN", "CataraPatara-CAN.apk", "canbridge versionCode"),
    };

    static long codeOf(Context c, String pkg) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Installed and older than the release (or missing when install=true). */
    public static boolean companionNewer(Context c, Companion k, boolean includeMissing) {
        if (k.url == null || k.code <= 0) return false;
        long have = codeOf(c, k.pkg);
        return have < 0 ? includeMissing : k.code > have;
    }

    private static void parseCompanions(JSONObject o) {
        if (o == null) return;
        String body = o.optString("body", "");
        JSONArray assets = o.optJSONArray("assets");
        for (Companion k : COMPANIONS) {
            Matcher m = Pattern.compile(Pattern.quote(k.marker) + "[:= ]+(\\d+)").matcher(body);
            if (!m.find()) continue;
            String url = null;
            if (assets != null) for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a != null && k.asset.equals(a.optString("name"))) url = a.optString("browser_download_url");
            }
            if (url == null) continue;
            k.code = Long.parseLong(m.group(1));
            k.url = url;
        }
    }

    /** Downloads and installs a companion app (Android asks to confirm). */
    public static void installCompanion(final Context c, final Companion k) {
        if (busy || k == null || k.url == null) return;
        if (!mayInstall(c)) {
            askInstallPermission(c);
            return;
        }
        busy = true;
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                File f = new File(app.getCacheDir(), "companion.apk");
                try {
                    LoadingBanner.start("update", k.label + LoadingBanner.t(" — preuzimanje…", " — downloading…"));
                    download(k.url, f);
                    PackageInfo pi = app.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
                    if (pi == null || !k.pkg.equals(pi.packageName)) throw new java.io.IOException(LoadingBanner.t("Pogrešan paket", "Wrong package"));
                    LoadingBanner.start("update", k.label + LoadingBanner.t(" — instaliranje…", " — installing…"));
                    commit(app, f, k.pkg);
                } catch (Exception e) {
                    busy = false;
                    LoadingBanner.stop("update");
                    LoadingBanner.flash("update-fail", k.label + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), 4000);
                }
            }
        }, "companion-install").start();
    }

    /** Newer, or the build for this unit while another unit's build is installed (same version is fine). */
    public static boolean isNewer(Context c, Release r) {
        return r != null && (r.versionCode > installedCode(c) || (switching(c) && r.versionCode >= installedCode(c)));
    }

    /** The installed build is not the one made for this unit (e.g. the QF build on an hzbhd unit). */
    public static boolean switching(Context c) {
        return !com.thecarlauncher.unit.Unit.ID.equals(UnitKind.detect(c));
    }

    public static boolean busy() { return busy; }

    /** Looks for the newest release (in the background); the answer comes on the main thread. */
    public static void check(final Context c, final Checked done) {
        final boolean beta = Prefs.bool(c, P_BETA, false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Release found = null;
                String err = null;
                try {
                    String url = "https://api.github.com/repos/" + REPO + (beta ? "/releases?per_page=15" : "/releases/latest");
                    String json = new String(get(url), "UTF-8");
                    JSONArray a = beta ? new JSONArray(json) : new JSONArray().put(new JSONObject(json));
                    // The release's unit rules first: they may know units added after this version.
                    fetchRules(c, a);
                    String want = UnitKind.assetName(c, UnitKind.detect(c));
                    if (beta) {
                        for (int i = 0; i < a.length(); i++) {
                            Release r = parse(a.getJSONObject(i), want);
                            if (r != null && (found == null || r.versionCode > found.versionCode)) found = r;
                        }
                    } else {
                        found = parse(a.getJSONObject(0), want);
                    }
                    for (int i = a.length() - 1; i >= 0; i--) parseCompanions(a.optJSONObject(i)); // newest wins
                    if (found == null) err = LoadingBanner.t("nema verzije za ovaj uređaj", "no build for this unit");
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                }
                Prefs.put(c, P_LAST, System.currentTimeMillis());
                final Release r = found;
                final String er = err;
                MAIN.post(new Runnable() {
                    public void run() {
                        if (r != null) lastFound = r;
                        if (done != null) done.onChecked(r, er);
                    }
                });
            }
        }, "update-check").start();
    }

    /** units.json from the newest release that has one (best effort: the launcher's own rules stay otherwise). */
    private static void fetchRules(Context c, JSONArray releases) {
        for (int i = 0; i < releases.length(); i++) {
            JSONArray assets = releases.optJSONObject(i) == null ? null : releases.optJSONObject(i).optJSONArray("assets");
            if (assets == null) continue;
            for (int k = 0; k < assets.length(); k++) {
                JSONObject a = assets.optJSONObject(k);
                if (a == null || !"units.json".equals(a.optString("name"))) continue;
                try {
                    UnitKind.offerRules(c, new String(get(a.optString("browser_download_url")), "UTF-8"));
                } catch (Exception ignored) {
                }
                return;
            }
        }
    }

    private static final Pattern CODE = Pattern.compile("versionCode[:= ]+(\\d+)");
    private static final Pattern NAME = Pattern.compile("versionName[:= ]+([0-9][0-9A-Za-z.\\-]*)");

    /** A release, with the APK built for this unit ("TheCarLauncher-hzbhd.apk" …); null without one. */
    private static Release parse(JSONObject o, String wantAsset) {
        String apk = null;
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a != null && a.optString("name").equals(wantAsset)) {
                    apk = a.optString("browser_download_url");
                    break;
                }
            }
        }
        if (apk == null || apk.isEmpty()) return null;
        String body = o.optString("body", "");
        Matcher m = CODE.matcher(body);
        if (!m.find()) return null; // only releases made by the build workflow say which version they hold
        long code = Long.parseLong(m.group(1));
        Matcher n = NAME.matcher(body);
        String vname = n.find() ? n.group(1) : o.optString("tag_name").replaceFirst("^v", "");
        // Notes: the body without the workflow's version lines.
        String notes = body.replaceAll("(?m)^.*version(Code|Name)[:= ].*$", "").replaceAll("(?m)^Download .*$", "").trim();
        String date = o.optString("published_at", "");
        if (date.length() >= 10) date = date.substring(0, 10);
        return new Release(o.optString("tag_name"), o.optString("name"), notes, apk, date, code, vname, o.optBoolean("prerelease"));
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            con.setRequestProperty("User-Agent", "TheCarLauncher-updater");
            int code = con.getResponseCode();
            if (code >= 400) throw new java.io.IOException("HTTP " + code);
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            in.close();
            return bo.toByteArray();
        } finally {
            con.disconnect();
        }
    }

    // ---- Install -------------------------------------------------------------------------------

    /** True when the launcher installs without asking (system app with INSTALL_PACKAGES). */
    public static boolean silent(Context c) {
        return c.checkSelfPermission("android.permission.INSTALL_PACKAGES") == PackageManager.PERMISSION_GRANTED;
    }

    /** Ordinary app on Android 8+: "Install unknown apps" must be allowed once. */
    public static boolean mayInstall(Context c) {
        return silent(c) || Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    public static void askInstallPermission(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        Ui.start(c, new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName())));
    }

    /** Downloads the release (banner shows progress) and installs it. */
    public static void install(final Context c, final Release r) {
        if (busy || r == null) return;
        if (!mayInstall(c)) {
            askInstallPermission(c);
            LoadingBanner.flash("update", LoadingBanner.t("Dozvoli instaliranje aplikacija za MYDS, pa ponovo",
                    "Allow installing apps for MYDS, then try again"), 3500);
            return;
        }
        busy = true;
        cancelled = false;
        progress[0] = 0;
        progress[1] = progress[2] = -1;
        failedStep = -1;
        failMessage = null;
        notifyWatchers();
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File f = new File(app.getCacheDir(), "update.apk");
                int at = 0;
                try {
                    download(r.apkUrl, f);
                    if (cancelled) throw new java.io.InterruptedIOException("cancelled");
                    step(0, 100);
                    at = 1;
                    step(1, 0);
                    verify(app, f);
                    step(1, 100);
                    at = 2;
                    step(2, 10);
                    LoadingBanner.start("update", LoadingBanner.t("Instaliranje ", "Installing ") + r.versionName + "…");
                    commit(app, f);
                    step(2, 60);
                } catch (Exception e) {
                    LoadingBanner.stop("update");
                    String msg = cancelled ? LoadingBanner.t("Otkazano", "Cancelled") : e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    fail(at, msg);
                    LoadingBanner.flash("update-fail", LoadingBanner.t("Ažuriranje nije uspelo: ", "Update failed: ") + msg, 4000);
                }
            }
        }, "update-install").start();
    }

    private static void download(String url, File out) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setInstanceFollowRedirects(true);
        con.setConnectTimeout(10000);
        con.setReadTimeout(20000);
        con.setRequestProperty("User-Agent", "TheCarLauncher-updater");
        try {
            if (con.getResponseCode() >= 400) throw new java.io.IOException("HTTP " + con.getResponseCode());
            long total = con.getContentLength();
            InputStream in = con.getInputStream();
            OutputStream o = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            long got = 0;
            int r, lastPct = -1;
            while ((r = in.read(buf)) > 0) {
                o.write(buf, 0, r);
                got += r;
                if (cancelled) break;
                int pct = total > 0 ? (int) (got * 100 / total) : -1;
                if (pct != lastPct) {
                    lastPct = pct;
                    if (pct >= 0) step(0, pct);
                    LoadingBanner.start("update", LoadingBanner.t("Preuzimanje ažuriranja… ", "Downloading the update… ")
                            + (pct >= 0 ? pct + " %" : (got / 1024) + " KB"));
                }
            }
            o.close();
            in.close();
        } finally {
            con.disconnect();
        }
    }

    /** The download must be this launcher, newer than the installed one. */
    private static void verify(Context c, File apk) throws Exception {
        PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
        if (pi == null) throw new java.io.IOException(LoadingBanner.t("Preuzeti fajl nije ispravan", "The download is not a valid APK"));
        if (!c.getPackageName().equals(pi.packageName)) throw new java.io.IOException(LoadingBanner.t("Pogrešan paket", "Wrong package"));
        long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        boolean ok = code > installedCode(c) || (switching(c) && code >= installedCode(c));
        if (!ok) throw new java.io.IOException(LoadingBanner.t("Verzija nije novija", "Not a newer version"));
    }

    private static void commit(final Context c, File apk) throws Exception {
        commit(c, apk, c.getPackageName());
    }

    private static void commit(final Context c, File apk, String pkg) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(pkg);
        int id = pi.createSession(p);
        PackageInstaller.Session s = pi.openSession(id);
        InputStream in = new FileInputStream(apk);
        OutputStream o = s.openWrite("update.apk", 0, apk.length());
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
        s.fsync(o);
        o.close();
        in.close();
        IntentFilter f = new IntentFilter(ACTION_DONE);
        BroadcastReceiver done = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // Ordinary app: Android's own "Update this app?" dialog.
                    Intent cf = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    confirm = cf; confirmAt = System.currentTimeMillis();
                    if (cf != null) {
                        cf.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            ctx.startActivity(cf);
                        } catch (Exception e) {
                            classicInstall(ctx);
                        }
                    } else classicInstall(ctx);
                    LoadingBanner.stop("update");
                    step(2, 80);
                    return;
                }
                busy = false;
                confirm = null;
                LoadingBanner.stop("update");
                try {
                    ctx.unregisterReceiver(this);
                } catch (Exception ignored) {
                }
                if (status == PackageInstaller.STATUS_SUCCESS) step(2, 100);
                else fail(2, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                if (status != PackageInstaller.STATUS_SUCCESS) {
                    String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    LoadingBanner.flash("update-fail", LoadingBanner.t("Ažuriranje nije instalirano", "Update not installed")
                            + (msg != null ? ": " + msg : ""), 4000);
                }
                // On success the launcher is restarted by Android in the new version.
            }
        };
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(done, f, Context.RECEIVER_NOT_EXPORTED);
        else c.registerReceiver(done, f);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pend = PendingIntent.getBroadcast(c, id, new Intent(ACTION_DONE).setPackage(c.getPackageName()), flags);
        s.commit(pend.getIntentSender());
        s.close();
    }

    private static long sAutoAt;

    /**
     * Every 10 minutes while the launcher runs (and on every return to it): looks at GitHub for a
     * newer MYDS, CataraPatara or CataraPatara CAN and, with "install at once" on, installs it
     * straight away when the car is standing still; otherwise mentions it in the banner.
     */
    public static void autoCheck(final Context c) {
        if (!Prefs.bool(c, P_AUTO, true) || busy) return;
        long now = System.currentTimeMillis();
        if (now - sAutoAt < 10L * 60 * 1000) return;
        sAutoAt = now;
        check(c, new Checked() {
            public void onChecked(Release r, String error) {
                boolean instant = Prefs.bool(c, P_INSTANT, true) && stationary(c) && mayInstall(c);
                for (Companion k : COMPANIONS) {
                    // Missing companions are installed too: CataraPatara and its CAN bridge are part of MYDS.
                    // Only companions already on the unit are kept up to date: no install prompts out of nowhere.
                    if (companionNewer(c, k, false)) {
                        if (instant && !busy) {
                            sAutoAt = 0; // look again right after this one, for the next update
                            installCompanion(c, k);
                            return;
                        }
                        LoadingBanner.flash("update-" + k.pkg, k.label + LoadingBanner.t(": nova verzija — Podešavanje → Android sistem",
                                ": new version — Setup → Android system"), 6000);
                    }
                }
                if (isNewer(c, r)) {
                    if (instant && !busy) {
                        install(c, r);
                        return;
                    }
                    LoadingBanner.flash("update-available", LoadingBanner.t("Dostupna je nova verzija " + r.versionName
                            + " — Podešavanje → Ažuriranje softvera", "Version " + r.versionName
                            + " is available — Setup → Software update"), 6000);
                }
            }
        });
    }

    /** Installs (or updates) one companion app now, looking it up on GitHub first if needed. */
    public static void installCompanionNow(final Context c, final String pkg) {
        for (final Companion k : COMPANIONS) {
            if (!k.pkg.equals(pkg)) continue;
            if (k.url != null) { installCompanion(c, k); return; }
            LoadingBanner.start("update", k.label + LoadingBanner.t(" — traženje…", " — looking it up…"));
            check(c, new Checked() {
                public void onChecked(Release r, String error) {
                    LoadingBanner.stop("update");
                    if (k.url != null) installCompanion(c, k);
                    else LoadingBanner.flash("update-fail", k.label + ": " + (error != null ? error : "?"), 4000);
                }
            });
        }
    }

    /** Below walking pace (or no GPS fix): safe to put an installer on screen. */
    private static boolean stationary(Context c) {
        com.thecarlauncher.nav.LocationTracker t = com.thecarlauncher.nav.LocationTracker.get(c);
        return !t.hasFix() || t.speedMps() < 1.5f;
    }
}
