package com.thecarlauncher.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.InputStream;

/**
 * Motor sesi: the engine voice of MYDS, replacing CataraPatara. Engine_Sim (MIT,
 * github.com/YigitSalihEmecen/Engine_Sim) runs in a tiny invisible WebView kept on screen as a
 * 1×1 overlay (so its timers and audio keep running behind Android Auto). Its ES modules and
 * audio worklet need a secure http(s) origin, so the assets are served from https://motor.myds/.
 *
 * The page asks for the car (OBD rpm, engine load as the accelerator, speed) through MotorCar.data()
 * and reports its state back; motor.html (the settings page) talks to it through {@link #set}.
 */
public final class MotorSesi {
    private static final String ORIGIN = "https://motor.myds/";
    private static MotorSesi sInstance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences p;
    private WebView web;
    private volatile String state = "{}";
    private boolean ready;

    public static synchronized MotorSesi get(Context c) {
        if (sInstance == null) sInstance = new MotorSesi(c.getApplicationContext());
        return sInstance;
    }

    private MotorSesi(Context c) {
        app = c;
        p = c.getSharedPreferences("motor", Context.MODE_PRIVATE);
    }

    /** Saved settings as JSON (motor.html reads them to draw itself). */
    public String settings() { return p.getString("cfg", "{\"on\":false,\"engine\":\"v8flat\",\"vehicle\":\"supercar\",\"follow\":true,\"carMax\":6000,\"volume\":0.8,\"interior\":true,\"pops\":0.6}"); }

    public String state() { return state; }

    public boolean on() {
        try { return new JSONObject(settings()).optBoolean("on"); } catch (Exception e) { return false; }
    }

    /** Merge a partial settings object, save it, and pass it to the engine page. */
    public void set(String partial) {
        try {
            JSONObject cur = new JSONObject(settings()), o = new JSONObject(partial);
            java.util.Iterator<String> k = o.keys();
            while (k.hasNext()) { String n = k.next(); cur.put(n, o.get(n)); }
            p.edit().putString("cfg", cur.toString()).apply();
            final String js = "window.M&&M.set(" + cur.toString() + ")";
            main.post(new Runnable() { public void run() { ensure(); if (ready && web != null) web.evaluateJavascript(js, null); } });
        } catch (Exception ignored) { }
    }

    public void toggle() {
        set("{\"on\":" + !on() + "}");
    }

    /** Test pedal from motor.html (used when not following the car). */
    public void pedal(final float thr, final float brake) {
        main.post(new Runnable() { public void run() { if (ready && web != null) web.evaluateJavascript("window.M&&M.pedal(" + thr + "," + brake + ")", null); } });
    }

    /** Start the hidden page if the voice is switched on (called from KeepAlive). */
    public void start() {
        main.post(new Runnable() { public void run() { if (on()) ensure(); } });
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void ensure() {
        if (web != null) return;
        try {
            web = new WebView(app);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setAllowFileAccess(false);
            web.addJavascriptInterface(new Bridge(), "MotorCar");
            web.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    return serve(r.getUrl().toString());
                }

                @Override
                @SuppressWarnings("deprecation")
                public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
                    return serve(url);
                }

                @Override
                public void onPageFinished(WebView v, String url) {
                    ready = true;
                    v.evaluateJavascript("window.M&&M.set(" + settings() + ")", null);
                }
            });
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.alpha = 0f;
            try { wm.addView(web, lp); } catch (Throwable t) { /* no overlay permission: still runs unattached */ }
            web.loadUrl(ORIGIN + "host.html");
        } catch (Throwable t) {
            web = null;
            state = "{\"err\":\"" + t.getClass().getSimpleName() + "\"}";
        }
    }

    private WebResourceResponse serve(String url) {
        if (url == null || !url.startsWith(ORIGIN)) return null;
        String path = url.substring(ORIGIN.length());
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.contains("..")) return null;
        String mime = path.endsWith(".js") ? "text/javascript" : path.endsWith(".html") ? "text/html" : path.endsWith(".json") ? "application/json" : "application/octet-stream";
        try {
            InputStream in = app.getAssets().open("motor/" + path);
            WebResourceResponse r = new WebResourceResponse(mime, "utf-8", in);
            if (Build.VERSION.SDK_INT >= 21) {
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("Access-Control-Allow-Origin", "*");
                h.put("Cache-Control", "no-cache");
                r.setResponseHeaders(h);
            }
            return r;
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null);
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public String data() {
            JSONObject o = new JSONObject();
            try {
                ObdHub h = ObdHub.get();
                float rpm = h != null ? h.rpm() : -1;
                o.put("rpm", rpm > 0 ? rpm : 0);
                if (h != null) {
                    // a diesel's throttle valve says little; calculated load is the driver's demand
                    float load = h.value("load");
                    float thr = h.value("thr");
                    float a = !Float.isNaN(load) ? (load - 12f) / 70f : !Float.isNaN(thr) ? (thr - 15f) / 70f : Float.NaN;
                    if (!Float.isNaN(a)) o.put("thr", Math.max(0, Math.min(1, a)));
                    float v = h.speed();
                    if (v >= 0) o.put("kmh", v);
                }
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface
        public void report(String s) { state = s; }
    }
}
