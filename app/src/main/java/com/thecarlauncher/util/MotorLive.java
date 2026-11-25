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
final class MotorLive {
    private static final String ORIGIN = "https://motor.myds/";

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences p;
    private WebView web;
    private volatile String state = "{}";
    private boolean ready;

    MotorLive(Context c) {
        app = c;
        p = c.getSharedPreferences("motor", Context.MODE_PRIVATE);
    }

    private String cfgForPage() {
        try {
            JSONObject c = new JSONObject(MotorSesi.get(app).settings());
            c.put("on", c.optBoolean("on") && !"loop".equals(c.optString("mode", "live")));
            return c.toString();
        } catch (Exception e) { return "{\"on\":false}"; }
    }

    String state() { return state; }

    /** Pass the saved settings to the engine page (starting it if the live voice is on). */
    void sync() {
        final String cfg = cfgForPage();
        final boolean want = cfg.contains("\"on\":true");
        main.post(new Runnable() { public void run() {
            if (want) ensure();
            if (ready && web != null) web.evaluateJavascript("window.M&&M.set(" + cfg + ")", null);
        } });
    }

    void pedal(final float thr, final float brake) {
        main.post(new Runnable() { public void run() { if (ready && web != null) web.evaluateJavascript("window.M&&M.pedal(" + thr + "," + brake + ")", null); } });
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
                    v.evaluateJavascript("window.M&&M.set(" + cfgForPage() + ")", null);
                }
            });
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.alpha = 1f;                       // 1×1 px, transparent: invisible, but counted as visible
            try { wm.addView(web, lp); } catch (Throwable t) { /* no overlay permission: still runs unattached */ }
            web.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 26) {
                try { web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false); } catch (Throwable ignored) { }
            }
            web.onResume();
            web.resumeTimers();
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
