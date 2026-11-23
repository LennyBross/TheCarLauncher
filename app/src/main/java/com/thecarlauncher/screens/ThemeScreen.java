package com.thecarlauncher.screens;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.thecarlauncher.LauncherActivity;
import com.thecarlauncher.car.CarData;
import com.thecarlauncher.car.FuelCost;
import com.thecarlauncher.car.FuelPrice;
import com.thecarlauncher.media.MediaHub;
import com.thecarlauncher.nav.Guidance;
import com.thecarlauncher.nav.LocationTracker;
import com.thecarlauncher.util.DayNight;
import com.thecarlauncher.util.NightDim;
import com.thecarlauncher.util.Prefs;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A home page drawn in HTML from assets/themes (Retro, Pilot, Panel). The page gets the car's
 * state once a second through {@code car.update(json)} and calls back through {@code Car.*}
 * to open sections, control media, switch day / night and dim the screen.
 */
public class ThemeScreen extends Screen {
    public static final String P_THEME = "home_theme", P_PREV = "home_theme_prev";
    public static final int CLASSIC = 0, MODERN = 1, RETRO = 2, PILOT = 3, PANEL = 4, KOKPIT = 5, DRIVE = 6, DS4PLUS = 7, STOCK = 8, LIVE = 9, DENON = 10;
    /** The themes offered in Settings (ids into NAMES). */
    public static final int[] OFFERED = {DENON, LIVE, STOCK, DS4PLUS, PANEL};
    public static final String[][] NAMES = {
            {"Klasična", "Classic"}, {"Moderna", "Modern"}, {"Retro", "Retro"}, {"Pilot", "Pilot"}, {"Panel", "Panel"},
            {"Kokpit", "Cockpit"}, {"Vožnja", "Driving mode"}, {"DS4+", "DS4+"}, {"Stock", "Stock"}, {"DS Live", "DS Live"}, {"Denon", "Denon"}};
    public static final String PKG_MAPS = "com.google.android.apps.maps", PKG_SPOTIFY = "com.spotify.music",
            PKG_YTMUSIC = "com.google.android.apps.youtube.music", PKG_YOUTUBE = "com.google.android.youtube";
    private static final Locale TR = new Locale("tr", "TR");
    /** The welcome animation plays once per app start, and only on the Pilot page. */
    private static boolean sIntroShown;

    private final int theme;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private boolean ready;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            push();
            updateMap();
            main.postDelayed(this, 1000);
        }
    };

    // ---- Split screen (map + our panel), like Android Auto ---------------------------------------

    private android.widget.FrameLayout root;
    private com.thecarlauncher.views.MapTileView map;
    private boolean split;
    private static final float MAP_W = .56f;

    private void showSplit(final boolean on) {
        split = on;
        if (root == null || map == null || web == null) return;
        root.post(new Runnable() {
            public void run() {
                int w = root.getWidth();
                android.widget.FrameLayout.LayoutParams mp = (android.widget.FrameLayout.LayoutParams) map.getLayoutParams();
                mp.width = Math.round(w * MAP_W);
                map.setLayoutParams(mp);
                map.setVisibility(on ? View.VISIBLE : View.GONE);
                android.widget.FrameLayout.LayoutParams wp = (android.widget.FrameLayout.LayoutParams) web.getLayoutParams();
                wp.leftMargin = on ? Math.round(w * MAP_W) : 0;
                web.setLayoutParams(wp);
                if (on) {
                    map.setSource(DayNight.isNight(act) ? com.thecarlauncher.util.Prefs.TILES_OSM : Prefs.tiles(act));
                    map.setAccent(com.thecarlauncher.mib.Mib.BLUE);
                    map.setFollow(true);
                    updateMap();
                }
            }
        });
    }

    private void updateMap() {
        if (!split || map == null) return;
        LocationTracker t = LocationTracker.get(act);
        android.location.Location l = t.location();
        if (l != null && t.hasFix()) map.setMyLocation(l.getLatitude(), l.getLongitude(), t.bearing(), l.getAccuracy(), true);
        else if (com.thecarlauncher.util.PhoneLink.locFresh()) map.setMyLocation(com.thecarlauncher.util.PhoneLink.lat, com.thecarlauncher.util.PhoneLink.lon, com.thecarlauncher.util.PhoneLink.brg, 15, true);
        else if (l != null) map.setMyLocation(l.getLatitude(), l.getLongitude(), t.bearing(), l.getAccuracy(), false);
    }

    public ThemeScreen(LauncherActivity act, int theme) {
        super(act);
        this.theme = theme;
    }

    /** The chosen home page; the old "modern" switch still counts. */
    public static int current(Context c) {
        int t = Prefs.integer(c, P_THEME, -1);
        // Only DS4+, Panel and Stock are offered now; anything else moves to Stock.
        if (t != DS4PLUS && t != PANEL && t != STOCK && t != LIVE && t != DENON) {
            t = STOCK;
            Prefs.put(c, P_THEME, t);
            Prefs.put(c, ModernScreen.P_MODERN, false);
        }
        return t;
    }

    public static void set(Context c, int t) {
        Prefs.put(c, P_THEME, t);
        Prefs.put(c, ModernScreen.P_MODERN, t == MODERN);
    }

    public static Screen create(LauncherActivity act) {
        int t = current(act);
        if (t == MODERN) return new ModernScreen(act);
        if (t >= RETRO && t <= DENON) return new ThemeScreen(act, t);
        return new MenuScreen(act);
    }

    @Override
    public String title() { return "MENU"; }

    private String page() {
        switch (theme) {
            case DRIVE: return "drive.html";
            case DS4PLUS: return "ds4plus.html";
            case LIVE: return "live.html";
            case DENON: return "denon.html";
            case STOCK: return "stock.html";
            default: return "panel.html";
        }
    }

    /** Loads this theme's driving-mode page (edge panel button). */
    public void openDrive() {
        load("minimal.html");
    }

    /** Reloads whatever page is showing (after a sound pack import). */
    public void reloadPage() {
        if (web != null) web.reload();
    }

    private void load(String pg) {
        if (web == null) return;
        ready = false;
        showSplit(pg.startsWith("split"));
        web.loadUrl("file:///android_asset/themes/" + pg);
    }

    /** Back from a sub-page (vehicle, driving mode) returns to the theme's home page. */
    @Override
    public boolean onBack() {
        if (web == null) return false;
        String u = web.getUrl();
        if (u == null || u.endsWith("/" + page()) || u.contains("/" + page() + "#")) return false;
        load(page());
        return true;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected View create(ViewGroup parent) {
        web = new WebView(act);
        web.setBackgroundColor(Color.BLACK);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setTextZoom(100);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                // Stock theme: every page (radio, vehicle, engine, driving…) takes the Stock look.
                if (theme == STOCK) v.evaluateJavascript("(function(){document.documentElement.classList.add('stock');"
                        + "if(!document.getElementById('stockcss')){var l=document.createElement('link');l.id='stockcss';l.rel='stylesheet';"
                        + "l.href='stock.css';document.head.appendChild(l);}})()", null);
                ready = true;
                push();
            }
        });
        final Bridge br = new Bridge();
        bridge = br;
        web.addJavascriptInterface(br, "Car");
        // The phone app ("MYDS Telefon") may open screens and read our state.
        com.thecarlauncher.util.PhoneLink.remote = new com.thecarlauncher.util.PhoneLink.Remote() {
            public void open(String what) {
                if (what.startsWith("theme:")) { try { set(act, Integer.parseInt(what.substring(6))); act.restartUi(); } catch (Exception ignored) { } }
                else if (what.startsWith("media:")) br.media(what.substring(6));
                else if (what.startsWith("bright:")) { try { br.setBrightness(Integer.parseInt(what.substring(7))); } catch (Exception ignored) { } }
                else if (what.startsWith("night:")) br.setNight("1".equals(what.substring(6)));
                else br.open(what);
            }
        };
        com.thecarlauncher.util.PhoneLink.source = new com.thecarlauncher.util.PhoneLink.Source() {
            public JSONObject snapshot() {
                try { JSONObject o = state(); o.put("theme", theme).put("panelHue", String.format("#%06X", Prefs.integer(act, "panel_hue", 0xFF56F2FF) & 0xFFFFFF)); return o; } catch (Exception e) { return new JSONObject(); }
            }
        };
        String url = "file:///android_asset/themes/" + page();
        if (theme == PILOT && !sIntroShown) {
            sIntroShown = true;
            url += "#intro";
        }
        web.loadUrl(url);
        root = new android.widget.FrameLayout(act);
        root.setBackgroundColor(Color.BLACK);
        map = new com.thecarlauncher.views.MapTileView(act, null);
        map.setVisibility(View.GONE);
        root.addView(map, new android.widget.FrameLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(web, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    // ---- State to the page --------------------------------------------------------------------

    private void push() {
        if (web == null) return;
        try {
            String js = "window.car&&car.update(" + state().toString() + ")";
            if (ready) web.evaluateJavascript(js, null);
            if (panel != null) panel.evaluateJavascript(js, null);
        } catch (Throwable ignored) {
        }
    }

    // ---- Google Maps beside our panel, like Android Auto ----------------------------------------
    // Android 8.1 cannot embed another app, but it has free-form windows: Google Maps opens in a
    // window on the left, our panel (music, next turn, keys) floats on the right above everything.
    // Without free-form support Maps opens full screen and the panel still floats on its right.

    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady;

    private void speak(final String text) {
        if (tts == null) {
            tts = new android.speech.tts.TextToSpeech(act.getApplicationContext(), new android.speech.tts.TextToSpeech.OnInitListener() {
                public void onInit(int st) {
                    ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS;
                    if (ttsReady) { tts.setLanguage(new Locale("tr", "TR")); tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "b"); }
                }
            });
            return;
        }
        if (ttsReady) tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "b" + System.currentTimeMillis());
    }

    private WebView panel;
    private Bridge bridge;
    private static final float MAPS_W = .58f;

    @SuppressWarnings("deprecation")
    private void enableFreeform() {
        try {
            android.content.ContentResolver cr = act.getContentResolver();
            if (android.provider.Settings.Global.getInt(cr, "enable_freeform_support", 0) != 1) {
                android.provider.Settings.Global.putInt(cr, "enable_freeform_support", 1);
                android.provider.Settings.Global.putInt(cr, "force_resizable_activities", 1);
                Prefs.put(act, "freeform_set_at", System.currentTimeMillis());   // takes effect after a restart
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean canOverlay() {
        if (android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(act)) return true;
        try {   // privileged app: allow ourselves
            android.app.AppOpsManager ao = (android.app.AppOpsManager) act.getSystemService(Context.APP_OPS_SERVICE);
            int op = (Integer) android.app.AppOpsManager.class.getField("OP_SYSTEM_ALERT_WINDOW").get(null);
            android.app.AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class)
                    .invoke(ao, op, android.os.Process.myUid(), act.getPackageName(), android.app.AppOpsManager.MODE_ALLOWED);
        } catch (Throwable ignored) {
        }
        return android.provider.Settings.canDrawOverlays(act);
    }

    private void openMapsSplit() {
        Intent i = act.getPackageManager().getLaunchIntentForPackage(PKG_MAPS);
        if (i == null || !canOverlay()) { load("split.html"); return; }
        enableFreeform();
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        act.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int W = dm.widthPixels, H = dm.heightPixels, mw = Math.round(W * MAPS_W);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
        try { o.setLaunchBounds(new android.graphics.Rect(0, 0, mw, H)); } catch (Throwable ignored) { }
        try { android.app.ActivityOptions.class.getMethod("setLaunchStackId", int.class).invoke(o, 2); } catch (Throwable ignored) { }      // 8.x: FREEFORM_WORKSPACE_STACK_ID
        try { android.app.ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(o, 5); } catch (Throwable ignored) { } // 9+: WINDOWING_MODE_FREEFORM
        com.thecarlauncher.util.AutoReturn.markOwn();
        try { act.startActivity(i, o.toBundle()); } catch (Throwable t) { try { act.startActivity(i); } catch (Throwable ignored) { } }
        showPanel(W - mw, H);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void showPanel(int w, int h) {
        if (panel != null) return;
        panel = new WebView(act);
        panel.setBackgroundColor(Color.BLACK);
        WebSettings s = panel.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        panel.setWebViewClient(new WebViewClient());
        panel.addJavascriptInterface(bridge, "Car");
        panel.loadUrl("file:///android_asset/themes/split.html?overlay=1");
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(w, h,
                android.os.Build.VERSION.SDK_INT >= 26 ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : android.view.WindowManager.LayoutParams.TYPE_PHONE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.OPAQUE);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        try { ((android.view.WindowManager) act.getSystemService(Context.WINDOW_SERVICE)).addView(panel, lp); } catch (Throwable t) { panel = null; }
    }

    private void closePanel() {
        if (panel == null) return;
        try { ((android.view.WindowManager) act.getSystemService(Context.WINDOW_SERVICE)).removeView(panel); } catch (Throwable ignored) { }
        try { panel.destroy(); } catch (Throwable ignored) { }
        panel = null;
    }

    private JSONObject state() throws Exception {
        JSONObject o = new JSONObject();
        Date now = new Date();
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) act.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            o.put("net", ni == null || !ni.isConnected() ? "" : ni.getType() == android.net.ConnectivityManager.TYPE_WIFI ? "wifi" : "4G");
        } catch (Throwable t) { o.put("net", ""); }
        try {
            android.bluetooth.BluetoothAdapter ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            o.put("bt", ba != null && ba.isEnabled());
        } catch (Throwable t) { o.put("bt", false); }
        o.put("time", new SimpleDateFormat(Prefs.use24h(act) ? "HH:mm" : "h:mm", TR).format(now));
        o.put("date", new SimpleDateFormat("d MMMM EEEE", TR).format(now));
        o.put("night", DayNight.isNight(act));
        o.put("dimOn", NightDim.on(act));
        o.put("dimLevel", NightDim.level(act));

        LocationTracker t = LocationTracker.get(act);
        com.thecarlauncher.util.ObdHub ob = com.thecarlauncher.util.ObdHub.get();
        float obdKmh = ob == null ? -1 : ob.speed();
        if (obdKmh >= 0) {           // the car's own speed beats GPS
            o.put("gps", "ok");
            o.put("speed", Math.round(obdKmh));
        } else if (!t.hasPermission()) o.put("gps", "perm");
        else if (!t.hasFix()) o.put("gps", "search");
        else {
            o.put("gps", "ok");
            o.put("speed", Math.round(t.speed(act)));
        }
        try { if (o.has("speed")) com.thecarlauncher.media.Tone.get(act).speed((float) o.optDouble("speed")); } catch (Throwable ignored) { }
        if (ob != null) {
            JSONObject oj = ob.json();
            o.put("obd", oj);
            JSONObject v = oj.optJSONObject("v");
            if (v != null) {
                if (v.has("rpm")) o.put("rpm", v.optInt("rpm"));
                if (v.has("cool")) o.put("coolant", v.optDouble("cool"));
                if (v.has("batt")) o.put("batt", v.optDouble("batt"));
                // boost: manifold pressure above the outside air (bar)
                if (v.has("map")) o.put("boost", Math.max(0, (v.optDouble("map") - v.optDouble("baro", 101)) / 100.0));
                if (v.has("fuelPct")) {   // the real tank sensor beats the fill-up estimate
                    o.put("fuelLeft", v.optDouble("fuelPct") / 100.0 * CarData.get(act).tank());
                }
                if (v.has("amb")) o.put("outdoorObd", v.optDouble("amb"));
            }
        }
        JSONObject fuelObd = com.thecarlauncher.util.ObdFuel.get(act).json();
        o.put("fuelObd", fuelObd);

        double price = FuelPrice.perLitre(act), l100 = FuelCost.litresPer100(act);
        if (price > 0) o.put("price", price);
        if (l100 > 0) o.put("l100", l100);
        double per100 = FuelCost.liraPer100(act);
        if (per100 > 0) {
            o.put("per100", per100);
            o.put("perKm", per100 / 100.0);
        }
        double left = CarData.get(act).fuelLeft();
        if (left >= 0) o.put("fuelLeft", left);
        o.put("tank", CarData.get(act).tank());
        double range = CarData.get(act).range();
        if (range > 0) o.put("range", range);
        o.put("tripKm", com.thecarlauncher.nav.TripComputer.get(act).movingKm());

        MediaHub m = MediaHub.get(act);
        if (m.hasTrackInfo()) {
            o.put("title", m.title());
            o.put("artist", m.artist() == null ? "" : m.artist());
        }
        o.put("playing", m.isPlaying());
        o.put("pos", m.position() / 1000);
        o.put("dur", m.duration() / 1000);
        android.graphics.Bitmap art = m.art();
        // No cover from the source (Bluetooth): look the song up on the internet.
        if (art == null && m.hasTrackInfo()) art = com.thecarlauncher.util.CoverLookup.get(act, m.artist(), m.title());
        o.put("artId", art == null ? 0 : System.identityHashCode(art));
        // Whichever source is active (Spotify, YouTube Music, Bluetooth, radio…) drives the one player.
        String src = m.packageName();
        o.put("srcLabel", m.sourceLabel(src));
        if (com.thecarlauncher.radio.HcnRadio.available() && com.thecarlauncher.radio.HcnRadio.active()) {
            int[] ri = com.thecarlauncher.radio.HcnRadio.info();
            o.put("tuner", true);
            if (ri != null) o.put("khz", ri[0]).put("stereo", ri[1] == 1).put("band", com.thecarlauncher.radio.HcnRadio.band());
            String[] rd = com.thecarlauncher.radio.HcnRadio.rds();
            o.put("rdsPs", rd[0]).put("rdsRt", rd[1]);
        }
        o.put("srcKind", src == null ? "" : src.equals(MediaHub.BLUETOOTH) ? "bt" : src.equals(MediaHub.RADIO) ? "radio"
                : src.equals(PKG_SPOTIFY) ? "spotify" : src.equals(PKG_YTMUSIC) ? "ytmusic" : src.equals(PKG_YOUTUBE) ? "youtube"
                : src.equals(act.getPackageName()) ? "own" : "app");
        o.put("mediaAccess", m.hasNotificationAccess());
        // The phone (MYDS Telefon) playing Spotify / YouTube Music: our player shows and drives it.
        if (com.thecarlauncher.util.PhoneLink.mediaActive()) {
            o.put("title", com.thecarlauncher.util.PhoneLink.title).put("artist", com.thecarlauncher.util.PhoneLink.artist).put("playing", com.thecarlauncher.util.PhoneLink.playing);
            o.put("pos", com.thecarlauncher.util.PhoneLink.position() / 1000).put("dur", com.thecarlauncher.util.PhoneLink.dur / 1000).put("artId", com.thecarlauncher.util.PhoneLink.artId);
            o.put("srcLabel", com.thecarlauncher.util.PhoneLink.app_.isEmpty() ? "Telefon" : com.thecarlauncher.util.PhoneLink.app_).put("srcKind", "phone");
        }
        // Doors, boot, bonnet, lights, indicators (learned from the CAN box frames)
        o.put("body", com.thecarlauncher.util.CarBody.json());
        // Gear from road speed / engine rpm (DS4 1.6 e-HDi, 6-speed: km/h per 1000 rpm)
        if (o.has("rpm") && o.has("speed")) {
            double r = o.optDouble("rpm"), v = o.optDouble("speed");
            if (r > 600 && v > 4) {
                double k = v / (r / 1000.0);
                double[] G = {8.2, 14.6, 22.4, 30.8, 39.0, 47.5};
                int best = 0; double bd = 1e9;
                for (int g = 0; g < G.length; g++) { double d = Math.abs(Math.log(k / G[g])); if (d < bd) { bd = d; best = g; } }
                o.put("gearObd", bd < 0.12 ? String.valueOf(best + 1) : "N");
            } else if (r > 300) o.put("gearObd", "N");
        }
        // Phone bridge: Wi-Fi link and ECU freshness, shown separately.
        com.thecarlauncher.util.ObdHub obh = com.thecarlauncher.util.ObdHub.get();
        o.put("link", new JSONObject().put("wifi", com.thecarlauncher.util.PhoneLink.connected())
                .put("wifiSt", com.thecarlauncher.util.PhoneLink.dialStatus).put("addr", com.thecarlauncher.util.PhoneLink.dialAddr)
                .put("manual", Prefs.str(act, com.thecarlauncher.util.PhoneLink.P_ADDR, ""))
                .put("ecu", obh != null && obh.rpm() >= 0).put("ecuSt", obh == null ? "" : obh.status())
                .put("ecuAge", obh == null ? -1 : obh.rpmAge()));
        JSONObject ph = new JSONObject().put("on", com.thecarlauncher.util.PhoneLink.connected()).put("name", com.thecarlauncher.util.PhoneLink.phoneName).put("via", com.thecarlauncher.util.PhoneLink.via);
        o.put("phone", ph);
        // Google Maps navigating on the unit: its next turn for our panel
        if (System.currentTimeMillis() - com.thecarlauncher.media.MediaListenerService.navAt < 30000) {
            o.put("pnav", new JSONObject().put("dist", com.thecarlauncher.media.MediaListenerService.navDist)
                    .put("text", com.thecarlauncher.media.MediaListenerService.navText).put("eta", com.thecarlauncher.media.MediaListenerService.navEta)
                    .put("id", 1000 + com.thecarlauncher.media.MediaListenerService.navId));
        }
        if (com.thecarlauncher.util.PhoneLink.navActive()) o.put("pnav", new JSONObject().put("dist", com.thecarlauncher.util.PhoneLink.navDist).put("text", com.thecarlauncher.util.PhoneLink.navText)
                .put("eta", com.thecarlauncher.util.PhoneLink.navEta).put("id", com.thecarlauncher.util.PhoneLink.navId));

        float temp = Prefs.of(act).getFloat("weather_temp", Float.NaN);
        if (!Float.isNaN(temp)) o.put("temp", Math.round(temp));
        o.put("hasMaps", installed(PKG_MAPS));
        // Live CAN (CataraPatara CAN bridge) and engine sound (CataraPatara)
        if (com.thecarlauncher.util.CanLive.canFresh()) {
            if (com.thecarlauncher.util.CanLive.l100 >= 0) o.put("inst", com.thecarlauncher.util.CanLive.l100);
            if (com.thecarlauncher.util.CanLive.range >= 0) { o.put("canRange", com.thecarlauncher.util.CanLive.range); o.put("range", com.thecarlauncher.util.CanLive.range); }
        }
        if (com.thecarlauncher.util.CanLive.outdoorFresh()) o.put("outdoor", com.thecarlauncher.util.CanLive.outdoor);
        boolean cp = com.thecarlauncher.util.CanLive.installed(act, com.thecarlauncher.util.CanLive.PKG_CP);
        o.put("cp", cp);
        o.put("canBridge", com.thecarlauncher.util.CanLive.installed(act, com.thecarlauncher.util.CanLive.PKG_BRIDGE));
        o.put("canDiag", canDiag());
        if (cp) {
            boolean fresh = com.thecarlauncher.util.CanLive.cpFresh();
            o.put("cpOn", fresh && com.thecarlauncher.util.CanLive.cpOn);
            o.put("cpRpm", fresh ? com.thecarlauncher.util.CanLive.cpRpm : 0);
            o.put("cpGear", com.thecarlauncher.util.CanLive.cpGear);
            o.put("cpRed", com.thecarlauncher.util.CanLive.cpRed);
            o.put("cpLoad", com.thecarlauncher.util.CanLive.cpLoad);
            o.put("cpName", com.thecarlauncher.util.CanLive.cpName);
            o.put("cpSrc", com.thecarlauncher.util.CanLive.cpSrc);
        }
        CarData cd = CarData.get(act);
        if (cd.hasOdometer()) o.put("odo", cd.odometer());
        double avg = cd.averageConsumption();
        if (avg > 0) o.put("avgL100", avg);
        // OBD trip computer: instant, trip / 100 km / 1000 km averages, range from the tank level
        if (fuelObd.has("inst") && !o.has("inst")) o.put("inst", fuelObd.optDouble("inst"));
        if (fuelObd.has("lph")) o.put("lph", fuelObd.optDouble("lph"));
        if (fuelObd.has("avg100")) { o.put("avg100", fuelObd.optDouble("avg100")); o.put("avgL100", fuelObd.optDouble("avg100")); }
        if (fuelObd.has("avg1000")) o.put("avg1000", fuelObd.optDouble("avg1000"));
        if (fuelObd.has("tripAvg")) { o.put("tripAvg", fuelObd.optDouble("tripAvg")); o.put("tripL", fuelObd.optDouble("tripL")); o.put("tripKm", fuelObd.optDouble("tripKm")); }
        // No tank sensor on OBD (PSA diesels rarely answer PID 2F): the car's own range from CAN is
        // computed by the BSI from the real fuel gauge, so range × average consumption = litres left.
        boolean tankSensor = ob != null && ob.json().optJSONObject("v") != null && ob.json().optJSONObject("v").has("fuelPct");
        if (o.has("canRange") && !tankSensor) {
            double use = fuelObd.has("avg100") ? fuelObd.optDouble("avg100") : avg;
            if (use > 2 && use < 20) {
                double l = o.optDouble("canRange") * use / 100.0;
                o.put("fuelLeft", Math.min(CarData.get(act).tank(), l));
                o.put("fuelSrc", "can");
            }
        }
        if (!o.has("canRange") && o.has("fuelLeft")) {
            double use = fuelObd.has("avg100") ? fuelObd.optDouble("avg100") : avg;
            if (use > 2) o.put("range", o.optDouble("fuelLeft") / use * 100.0);
        }
        if (cd.oilDueKm() > 0) o.put("oilKm", cd.oilDueKm());
        if (cd.oilDueDate() > 0) o.put("oilDate", CarData.date(cd.oilDueDate()));
        if (cd.registrationDate() > 0) o.put("regDate", CarData.date(cd.registrationDate()));
        Guidance g = Guidance.get(act);
        if (g.route() != null && g.route().distance > 0) {
            o.put("navActive", true);
            o.put("navKm", g.remaining() / 1000.0);
            o.put("navMin", Math.round(g.remainingSeconds() / 60.0));
            o.put("navProgress", 1.0 - g.remaining() / g.route().distance);
            o.put("navDest", g.destination() == null ? "" : g.destination());
            o.put("navArrive", new SimpleDateFormat("HH:mm", TR).format(new Date(System.currentTimeMillis() + (long) (g.remainingSeconds() * 1000))));
            com.thecarlauncher.nav.OsmApi.Step st = g.nextStep();
            if (st != null) {
                o.put("nextDist", g.toNext());
                o.put("nextName", st.name == null ? "" : st.name);
                o.put("nextMod", st.modifier == null ? "" : st.modifier);
                o.put("nextType", st.type == null ? "" : st.type);
            }
        }
        return o;
    }

    /**
     * Why no trip-computer data shows, in plain words (null when it flows). The chain is:
     * Raise CAN box → factory CAN app (logs every frame) → CataraPatara CAN bridge (reads the log)
     * → MYDS.
     */
    String canDiag() {
        if (com.thecarlauncher.util.CanLive.canFresh()) return null;
        if (!com.thecarlauncher.util.CanLive.installed(act, com.thecarlauncher.util.CanLive.PKG_BRIDGE)) return "CAN köprüsü yüklü değil · dokun, kur";
        if (System.currentTimeMillis() - com.thecarlauncher.util.CanLive.bridgeAt > 10000) return "CAN köprüsü çalışmıyor · dokun, aç";
        if (com.thecarlauncher.util.CanLive.bridgeLines == 0) return "Köprü sistem kaydını okuyamıyor";
        if (com.thecarlauncher.util.CanLive.bridgeRev == 0) return "Fabrika CAN uygulaması veri yazmıyor";
        if (com.thecarlauncher.util.CanLive.bridgeFrames == 0) return "CAN kutusundan kare gelmiyor";
        String t = com.thecarlauncher.util.CanLive.bridgeTypes;
        return "Kutu yakıt verisi göndermiyor" + (t == null || t.isEmpty() ? "" : " · gelen: " + t);
    }

    @Override
    public void onShow() {
        closePanel();   // MyDS itself is in front again: the floating panel goes away
        main.removeCallbacks(tick);
        main.post(tick);
        if (web != null) {
            try { web.onResume(); web.resumeTimers(); } catch (Throwable ignored) { }
            web.evaluateJavascript("window.__sleep&&__sleep(false)", null);
        }
        if (FuelPrice.shouldRetry(act)) FuelPrice.refresh(act, true, null);
    }

    @Override
    public void onHide() {
        main.removeCallbacks(tick);
        // Behind Android Auto / Spotify: stop the 3D drawing and the page timers (memory, CPU).
        if (web != null) {
            web.evaluateJavascript("window.__sleep&&__sleep(true)", null);
            try { web.onPause(); } catch (Throwable ignored) { }
        }
    }

    @Override
    public void onDestroy() {
        onHide();
        if (web != null) {
            web.removeJavascriptInterface("Car");
            web.destroy();
            web = null;
        }
    }

    // ---- Calls from the page --------------------------------------------------------------------

    private void ui(Runnable r) { main.post(r); }

    private class Bridge {
        @JavascriptInterface
        public void open(final String what) {
            ui(new Runnable() {
                public void run() {
                    switch (what == null ? "" : what) {
                        case "radio":
                            if (com.thecarlauncher.radio.HcnRadio.available()) load("radio.html");
                            else act.openRadio();
                            break;
                        case "radioFactory": openPkg("com.hcn.autoradio"); break;
                        case "media": act.show(LauncherActivity.MEDIA, true); break;
                        case "srcApp": {
                            String sp = MediaHub.get(act).packageName();
                            if (sp != null && !sp.isEmpty() && act.getPackageManager().getLaunchIntentForPackage(sp) != null) openPkg(sp);
                            else act.show(LauncherActivity.MEDIA, true);
                            break;
                        }
                        case "nav": act.show(LauncherActivity.NAV, true); break;
                        case "phone": act.openPhone(); break;
                        case "aa": if (!com.thecarlauncher.screens.AppConnectScreen.openZlink(act, false)) act.openProjection(); break;
                        case "carplay": if (!com.thecarlauncher.screens.AppConnectScreen.openZlink(act, true)) act.openProjection(); break;
                        case "appconnect": act.openProjection(); break;
                        case "apps": act.show(LauncherActivity.APPS, true); break;
                        case "vehicle": load("car3d.html"); break;
                        case "vehicleNative": act.show(LauncherActivity.VEHICLE, true); break;
                        case "themehome": load(page()); break;
                        // the Sürüş pages are replaced by the minimal screen (one tap, deep dimming)
                        case "minimal": case "drive": load("minimal.html"); break;
                        case "obd": load("obd.html"); break;
                        case "remote": load("destek.html"); break;
                        case "pavyon": load("pavyon.html"); break;
                        case "obdDtc": { com.thecarlauncher.util.ObdHub h = com.thecarlauncher.util.ObdHub.get(); if (h != null) h.readDtc(); break; }
                        case "obdSetup": com.thecarlauncher.util.CanLive.openCp(act); break;
                        case "engine":
                            com.thecarlauncher.util.CanLive.openCp(act);
                            break;
                        case "cpInstall": com.thecarlauncher.util.CanLive.openCp(act); break;
                        case "canFix":
                            if (!com.thecarlauncher.util.CanLive.installed(act, com.thecarlauncher.util.CanLive.PKG_BRIDGE))
                                com.thecarlauncher.util.Updater.installCompanionNow(act, com.thecarlauncher.util.CanLive.PKG_BRIDGE);
                            else openPkg(com.thecarlauncher.util.CanLive.PKG_BRIDGE);
                            break;
                        case "cpToggle": com.thecarlauncher.util.CanLive.cpCommand(act, "toggle"); push(); break;
                        case "canbridge": openPkg(com.thecarlauncher.util.CanLive.PKG_BRIDGE); break;
                        case "settings": act.show(LauncherActivity.SETTINGS, true); break;
                        case "sound": act.show(LauncherActivity.SOUND, true); break;
                        case "eq": load("denon.html#ses"); break;
                        case "eqDetail": load("ses.html"); break;
                        case "eqFactory": openEq(); break;
                        // tap = play in the background and stay in MYDS; the *App routes open the app itself
                        // With the phone linked, the phone's own Spotify / YouTube Music plays (sound over Bluetooth).
                        case "spotify": if (!com.thecarlauncher.util.PhoneLink.control("play", PKG_SPOTIFY)) MediaHub.get(act).playInBackground(PKG_SPOTIFY); break;
                        case "ytmusic": if (!com.thecarlauncher.util.PhoneLink.control("play", PKG_YTMUSIC)) MediaHub.get(act).playInBackground(PKG_YTMUSIC); break;
                        case "phoneMusic": com.thecarlauncher.util.PhoneLink.control("play", null); break;
                        case "split": openMapsSplit(); break;
                        case "splitOsm": load("split.html"); break;
                        case "closeSplit": closePanel(); com.thecarlauncher.util.BringBack.toLauncher(act); break;
                        case "mapsFull": closePanel(); break;
                        case "navFull": act.show(LauncherActivity.NAV, true); break;
                        case "spotifyApp": openSpotify(); break;
                        case "ytmusicApp": openPkg(PKG_YTMUSIC); break;
                        case "btMusic": MediaHub.get(act).select(MediaHub.BLUETOOTH); break;
                        case "youtube": openPkg(PKG_YOUTUBE); break;
                        case "gmaps": openPkg(PKG_MAPS); break;
                        case "navhome": navTo(Prefs.HOME_ADDR); break;
                        case "navwork": navTo(Prefs.WORK_ADDR); break;
                        case "home": set(act, Prefs.integer(act, P_PREV, CLASSIC)); act.restartUi(); break;
                        default: break;
                    }
                }
            });
        }

        // ---- Engine sound ----
        @JavascriptInterface
        public String engineProfiles() { return com.thecarlauncher.engine.EngineSound.get(act).profilesJson(); }

        @JavascriptInterface
        public void engineImport() {
            ui(new Runnable() { public void run() { act.pickSoundPack(); } });
        }

        @JavascriptInterface
        public boolean engineDelete(String id) { return com.thecarlauncher.engine.EngineSound.get(act).deletePack(id); }

        @JavascriptInterface
        public String engineConfig() {
            com.thecarlauncher.engine.EngineSound e = com.thecarlauncher.engine.EngineSound.get(act);
            try {
                return new JSONObject().put("on", e.on()).put("id", e.id()).put("ex", e.exhaust()).put("vol", e.volume()).toString();
            } catch (Exception x) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void engineSet(String key, String value) {
            com.thecarlauncher.engine.EngineSound e = com.thecarlauncher.engine.EngineSound.get(act);
            try {
                switch (key) {
                    case "on": e.setOn("1".equals(value)); break;
                    case "id": e.setProfile(value); break;
                    case "ex": e.setExhaust(Integer.parseInt(value)); break;
                    case "vol": e.setVolume(Integer.parseInt(value)); break;
                    default: break;
                }
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void engineRev(boolean on) { com.thecarlauncher.engine.EngineSound.get(act).rev(on); }

        @JavascriptInterface
        public String engineLive() { return com.thecarlauncher.engine.EngineSound.get(act).live(); }

        // ---- Radio (AutoChips / HCN tuner) ----
        @JavascriptInterface
        public String radioInfo() {
            int[] i = com.thecarlauncher.radio.HcnRadio.info();
            try {
                JSONObject o = new JSONObject().put("ok", com.thecarlauncher.radio.HcnRadio.available());
                int want = com.thecarlauncher.radio.HcnRadio.wanted();
                if (i != null) o.put("khz", i[0]).put("stereo", i[1] == 1).put("band", com.thecarlauncher.radio.HcnRadio.band());
                else if (want > 0) o.put("khz", want).put("band", "FM-1");
                o.put("tunerKhz", i != null ? i[0] : -1).put("want", want).put("how", com.thecarlauncher.radio.HcnRadio.how());
                String src = com.thecarlauncher.radio.HcnRadio.source();
                o.put("source", src).put("active", com.thecarlauncher.radio.HcnRadio.RADIO_PKG.equals(src));
                String[] rd = com.thecarlauncher.radio.HcnRadio.rds();
                o.put("ps", rd[0]).put("rt", rd[1]);
                org.json.JSONArray pr = new org.json.JSONArray();
                for (int k : com.thecarlauncher.radio.HcnRadio.presets()) if (k > 0) pr.put(k);
                o.put("presets", pr);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String hifi() { return com.thecarlauncher.media.Tone.get(act).json().toString(); }

        @JavascriptInterface
        public void hifiPreset(String id) { com.thecarlauncher.media.Tone.get(act).preset(id); }

        @JavascriptInterface
        public void hifiBand(int i, float db) { com.thecarlauncher.media.Tone.get(act).band(i, db); }

        @JavascriptInterface
        public void hifiFx(String which, int v) { com.thecarlauncher.media.Tone.get(act).fx(which, v); }

        @JavascriptInterface
        public boolean hifiFocus(String f) { return com.thecarlauncher.media.Tone.get(act).focus(f); }

        @JavascriptInterface
        public void hifiSvc(int level) { com.thecarlauncher.media.Tone.get(act).svc(level); }

        @JavascriptInterface
        public void hifiBT(int bass, int treble) { com.thecarlauncher.media.Tone.get(act).bassTreble(bass, treble); }

        @JavascriptInterface
        public void radioTune(final int khz) { main.post(new Runnable() { public void run() { com.thecarlauncher.radio.HcnRadio.tuneSmart(act, khz); } }); }

        @JavascriptInterface
        public void radioSeek(final boolean up) { main.post(new Runnable() { public void run() { com.thecarlauncher.radio.HcnRadio.seekSmart(act, up); } }); }

        @JavascriptInterface
        public void radioStep(final boolean up) { main.post(new Runnable() { public void run() { com.thecarlauncher.radio.HcnRadio.stepSmart(act, up); } }); }

        @JavascriptInterface
        public void radioPlay() {
            // Something else (Spotify…) playing goes quiet first, then the tuner takes the speakers.
            act.runOnUiThread(new Runnable() { public void run() { try { MediaHub.get(act).onRadioStarting(); } catch (Throwable ignored) { } } });
            com.thecarlauncher.radio.HcnRadio.start(act);
        }

        @JavascriptInterface
        public void setOdometer(final double km) {
            ui(new Runnable() {
                public void run() {
                    if (km >= 0 && km < 3000000) CarData.get(act).setOdometer(km);
                    push();
                }
            });
        }

        @JavascriptInterface
        public void media(final String what) {
            ui(new Runnable() {
                public void run() {
                    MediaHub m = MediaHub.get(act);
                    if (com.thecarlauncher.util.PhoneLink.mediaActive() && !(com.thecarlauncher.radio.HcnRadio.available() && com.thecarlauncher.radio.HcnRadio.active())) {
                        com.thecarlauncher.util.PhoneLink.control(what, null);
                        main.postDelayed(new Runnable() { public void run() { push(); } }, 500);
                        return;
                    }
                    // The tuner owns the speakers: prev/next seek stations.
                    if (!"play".equals(what) && com.thecarlauncher.radio.HcnRadio.available() && com.thecarlauncher.radio.HcnRadio.active()) {
                        com.thecarlauncher.radio.HcnRadio.seek("next".equals(what));
                        main.postDelayed(new Runnable() { public void run() { push(); } }, 600);
                        return;
                    }
                    if ("prev".equals(what)) m.previous();
                    else if ("next".equals(what)) m.next();
                    else m.playPause();
                    main.postDelayed(new Runnable() { public void run() { push(); } }, 400);
                }
            });
        }

        @JavascriptInterface
        public void setNight(final boolean night) {
            ui(new Runnable() {
                public void run() {
                    DayNight.setMode(act, night ? DayNight.NIGHT : DayNight.DAY);
                    push();
                }
            });
        }

        /** Panel 80 display colour (#RRGGBB): kept for native screens and CataraPatara too. */
        @JavascriptInterface
        public void panelColor(final String hex) {
            try {
                int c = 0xFF000000 | Integer.parseInt(hex.replace("#", ""), 16);
                com.thecarlauncher.util.Prefs.put(act, "panel_hue", c);
                ui(new Runnable() { public void run() { com.thecarlauncher.mib.Mib.loadAmbient(act); } });
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void setAutoNight() {
            ui(new Runnable() { public void run() { DayNight.setMode(act, DayNight.AUTO); push(); } });
        }

        @JavascriptInterface
        public int dayMode() { return DayNight.mode(act); }

        @JavascriptInterface
        public int theme() { return theme; }

        /** The current cover as a small JPEG data URL ("" when there is none). */
        @JavascriptInterface
        public String mediaArt() {
            try {
                MediaHub mh = MediaHub.get(act);
                android.graphics.Bitmap b = mh.art();
                if (b == null && mh.hasTrackInfo()) b = com.thecarlauncher.util.CoverLookup.get(act, mh.artist(), mh.title());
                if (b == null) return "";
                android.graphics.Bitmap sc = android.graphics.Bitmap.createScaledBitmap(b, 200, 200 * b.getHeight() / Math.max(1, b.getWidth()), true);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                sc.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
                return "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
            } catch (Throwable t) { return ""; }
        }

        /** OBD adapter mode from the Motor screen: "ble" (direct) or "auto". */
        @JavascriptInterface
        public void obdMode(String m) { com.thecarlauncher.util.ObdHub h = com.thecarlauncher.util.ObdHub.get(); if (h != null) h.setMode("ble".equals(m) ? "ble" : "auto"); }

        /** Remote support (GitHub issue channel): state for the page. */
        @JavascriptInterface
        public String remote() {
            try {
                return new JSONObject().put("on", com.thecarlauncher.util.RemoteSupport.on(act))
                        .put("configured", com.thecarlauncher.util.RemoteSupport.configured(act))
                        .put("repo", com.thecarlauncher.util.RemoteSupport.repo(act))
                        .put("status", com.thecarlauncher.util.RemoteSupport.status)
                        .put("lastOk", com.thecarlauncher.util.RemoteSupport.lastOkAt == 0 ? -1 : (System.currentTimeMillis() - com.thecarlauncher.util.RemoteSupport.lastOkAt) / 1000)
                        .put("url", com.thecarlauncher.util.RemoteSupport.setupUrl).put("pin", com.thecarlauncher.util.RemoteSupport.setupPin).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public String remoteToken(String t) { return com.thecarlauncher.util.RemoteSupport.saveToken(act, t, null); }

        @JavascriptInterface
        public void remoteOn(boolean on) { com.thecarlauncher.util.RemoteSupport.setOn(act, on); }

        @JavascriptInterface
        public void remoteSetup(boolean open) { if (open) com.thecarlauncher.util.RemoteSupport.openSetup(act); else com.thecarlauncher.util.RemoteSupport.closeSetup(); }

        // ---- body learning (live.html)
        @JavascriptInterface
        public void bodyLearnStart() { com.thecarlauncher.util.CarBody.learnStart(); }

        @JavascriptInterface
        public String bodyLearn(String field) { return com.thecarlauncher.util.CarBody.learn(field); }

        @JavascriptInterface
        public void bodyForget() { com.thecarlauncher.util.CarBody.forget(); }

        /** Speaks a short Turkish sentence (door open, lights on...). */
        @JavascriptInterface
        public void say(final String text) {
            main.post(new Runnable() { public void run() { speak(text); } });
        }

        /** Adapter search on the unit (classic + BLE, unpaired too). */
        @JavascriptInterface
        public void obdScan() { com.thecarlauncher.util.ObdHub h = com.thecarlauncher.util.ObdHub.get(); if (h != null) h.scan(); }

        @JavascriptInterface
        public void obdPick(String addr, boolean le) { com.thecarlauncher.util.ObdHub h = com.thecarlauncher.util.ObdHub.get(); if (h != null) h.pick(addr, le); }

        @JavascriptInterface
        public void obdPin(String pin) { com.thecarlauncher.util.ObdHub h = com.thecarlauncher.util.ObdHub.get(); if (h != null) h.setPin(pin); }

        /** The phone's address typed by the driver ("" = find it by itself). */
        @JavascriptInterface
        public void phoneAddr(String a) { Prefs.put(act, com.thecarlauncher.util.PhoneLink.P_ADDR, a == null ? "" : a.trim()); }

        /** Google Maps' turn arrow from the phone, as a PNG data URL. */
        @JavascriptInterface
        public String phoneArrow() {
            try {
                android.graphics.Bitmap b = com.thecarlauncher.util.PhoneLink.navArrow;
                if (b == null) b = com.thecarlauncher.media.MediaListenerService.navArrow;
                if (b == null) return "";
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                return "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
            } catch (Throwable t) { return ""; }
        }

        /** Brightness 10..100 from the page's dimmer wheel; full brightness turns the dark layer off. */
        @JavascriptInterface
        public void setBrightness(final int pct) {
            ui(new Runnable() {
                public void run() {
                    if (!NightDim.canDraw(act)) {
                        NightDim.request(act);
                        return;
                    }
                    if (pct >= 95) {
                        NightDim.setOn(act, false);
                    } else {
                        NightDim.setLevel(act, Math.round((100 - pct) * 0.8f));
                        if (!NightDim.on(act)) NightDim.setOn(act, true);
                    }
                }
            });
        }

        @JavascriptInterface
        public int brightness() {
            return NightDim.on(act) ? Math.max(10, 100 - Math.round(NightDim.level(act) / 0.8f)) : 100;
        }

        @JavascriptInterface
        public void toggleDim() {
            ui(new Runnable() {
                public void run() {
                    if (!NightDim.canDraw(act)) NightDim.request(act);
                    else NightDim.setOn(act, !NightDim.on(act));
                    push();
                }
            });
        }
    }

    private boolean installed(String pkg) {
        try {
            act.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Opens an installed app; if missing, its Play Store page. */
    /** The unit's equalizer: the system audio-effect panel, a vendor EQ / DSP app, else MYDS's sound page. */
    private void openEq() {
        android.content.pm.PackageManager pm = act.getPackageManager();
        try {
            Intent i = new Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(pm) != null) { act.startActivity(i); return; }
        } catch (Exception ignored) { }
        String[] known = {"com.hcn.eq", "com.hcn.autoeq", "com.hcn.sound", "com.hcn.dsp", "com.syu.eq", "com.fyt.equalizer",
                "com.ts.main.eq", "com.teyes.dsp", "com.microntek.eq", "com.android.musicfx"};
        for (String k : known) if (pm.getLaunchIntentForPackage(k) != null) { openPkg(k); return; }
        try {
            for (android.content.pm.ApplicationInfo a : pm.getInstalledApplications(0)) {
                String n = a.packageName.toLowerCase(java.util.Locale.ROOT);
                if ((n.contains("equaliz") || n.endsWith(".eq") || n.contains(".eq.") || n.contains("dsp") || n.contains("soundeffect"))
                        && pm.getLaunchIntentForPackage(a.packageName) != null) { openPkg(a.packageName); return; }
            }
        } catch (Exception ignored) { }
        act.show(LauncherActivity.SOUND, true);
    }

    private void openPkg(String pkg) {
        Intent i = act.getPackageManager().getLaunchIntentForPackage(pkg);
        try {
            if (i == null) {
                i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + pkg));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            com.thecarlauncher.util.Ui.toast(act, "Uygulama yüklü değil");
        }
    }

    /** Saved Home / Work: in Google Maps when installed, else in the built-in navigation. */
    private void navTo(String key) {
        String saved = Prefs.str(act, key, null);
        if (saved == null || saved.trim().isEmpty()) {
            act.show(LauncherActivity.NAV, true);
            return;
        }
        String[] f = saved.split("\\|");
        String target = f.length >= 3 ? f[1] + "," + f[2] : saved;
        if (installed(PKG_MAPS)) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=" + android.net.Uri.encode(target)));
                i.setPackage(PKG_MAPS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        com.thecarlauncher.nav.NavHelper.navigateTo(act, target);
    }

    private void openSpotify() {
        Intent i = act.getPackageManager().getLaunchIntentForPackage("com.spotify.music");
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                act.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        act.show(LauncherActivity.MEDIA, true);
    }
}
