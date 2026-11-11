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
    public static final int CLASSIC = 0, MODERN = 1, RETRO = 2, PILOT = 3, PANEL = 4, KOKPIT = 5, DRIVE = 6, DS4PLUS = 7;
    public static final String[][] NAMES = {
            {"Klasična", "Classic"}, {"Moderna", "Modern"}, {"Retro", "Retro"}, {"Pilot", "Pilot"}, {"Panel", "Panel"},
            {"Kokpit", "Cockpit"}, {"Vožnja", "Driving mode"}, {"DS4+", "DS4+"}};
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
            main.postDelayed(this, 1000);
        }
    };

    public ThemeScreen(LauncherActivity act, int theme) {
        super(act);
        this.theme = theme;
    }

    /** The chosen home page; the old "modern" switch still counts. */
    public static int current(Context c) {
        int t = Prefs.integer(c, P_THEME, -1);
        if (t < 0) t = ModernScreen.enabled(c) ? MODERN : CLASSIC;
        return t;
    }

    public static void set(Context c, int t) {
        Prefs.put(c, P_THEME, t);
        Prefs.put(c, ModernScreen.P_MODERN, t == MODERN);
    }

    public static Screen create(LauncherActivity act) {
        int t = current(act);
        if (t == MODERN) return new ModernScreen(act);
        if (t >= RETRO && t <= DS4PLUS) return new ThemeScreen(act, t);
        return new MenuScreen(act);
    }

    @Override
    public String title() { return "MENU"; }

    private String page() {
        switch (theme) {
            case RETRO: return "retro.html";
            case PILOT: return "pilot.html";
            case KOKPIT: return "kokpit.html";
            case DRIVE: return "drive.html";
            case DS4PLUS: return "ds4plus.html";
            default: return "panel.html";
        }
    }

    /** Loads this theme's driving-mode page (edge panel button). */
    public void openDrive() {
        load(theme == DS4PLUS ? "ds4plus-drive.html" : "drive.html");
    }

    private void load(String pg) {
        if (web == null) return;
        ready = false;
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
                ready = true;
                push();
            }
        });
        web.addJavascriptInterface(new Bridge(), "Car");
        String url = "file:///android_asset/themes/" + page();
        if (theme == PILOT && !sIntroShown) {
            sIntroShown = true;
            url += "#intro";
        }
        web.loadUrl(url);
        return web;
    }

    // ---- State to the page --------------------------------------------------------------------

    private void push() {
        if (web == null || !ready) return;
        try {
            web.evaluateJavascript("window.car&&car.update(" + state().toString() + ")", null);
        } catch (Throwable ignored) {
        }
    }

    private JSONObject state() throws Exception {
        JSONObject o = new JSONObject();
        Date now = new Date();
        o.put("time", new SimpleDateFormat(Prefs.use24h(act) ? "HH:mm" : "h:mm", TR).format(now));
        o.put("date", new SimpleDateFormat("d MMMM EEEE", TR).format(now));
        o.put("night", DayNight.isNight(act));
        o.put("dimOn", NightDim.on(act));
        o.put("dimLevel", NightDim.level(act));

        LocationTracker t = LocationTracker.get(act);
        if (!t.hasPermission()) o.put("gps", "perm");
        else if (!t.hasFix()) o.put("gps", "search");
        else {
            o.put("gps", "ok");
            o.put("speed", Math.round(t.speed(act)));
        }

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

        float temp = Prefs.of(act).getFloat("weather_temp", Float.NaN);
        if (!Float.isNaN(temp)) o.put("temp", Math.round(temp));
        o.put("hasMaps", installed(PKG_MAPS));
        CarData cd = CarData.get(act);
        if (cd.hasOdometer()) o.put("odo", cd.odometer());
        double avg = cd.averageConsumption();
        if (avg > 0) o.put("avgL100", avg);
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

    @Override
    public void onShow() {
        main.removeCallbacks(tick);
        main.post(tick);
        if (FuelPrice.shouldRetry(act)) FuelPrice.refresh(act, true, null);
    }

    @Override
    public void onHide() {
        main.removeCallbacks(tick);
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
                        case "radio": act.openRadio(); break;
                        case "media": act.show(LauncherActivity.MEDIA, true); break;
                        case "nav": act.show(LauncherActivity.NAV, true); break;
                        case "phone": act.openPhone(); break;
                        case "aa": act.openProjection(); break;
                        case "apps": act.show(LauncherActivity.APPS, true); break;
                        case "vehicle": load("car3d.html"); break;
                        case "vehicleNative": act.show(LauncherActivity.VEHICLE, true); break;
                        case "themehome": load(page()); break;
                        case "engine": load("engine.html"); break;
                        case "settings": act.show(LauncherActivity.SETTINGS, true); break;
                        case "sound": act.show(LauncherActivity.SOUND, true); break;
                        case "spotify": openSpotify(); break;
                        case "ytmusic": openPkg(PKG_YTMUSIC); break;
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
        public String engineProfiles() { return com.thecarlauncher.engine.EngineSound.profilesJson(); }

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
