package com.thecarlauncher.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Listens to ZLink (Android Auto / CarPlay app of the head unit): it announces its state with the
 * broadcast action "com.zjinnova.zlink", extra "status" = CONNECTED / DISCONNECT / MAIN_PAGE_SHOW /
 * MAIN_PAGE_HIDDEN and "phoneType" (e.g. android_auto…, ios_carplay). (Broadcast names as documented
 * in the CarLink fork j0hann3sk/CarLink-ZLINK_fix; this listener is MYDS's own.)
 *
 * Used for: "Android Auto connected" on the MYDS screens, and keeping the car's urgent warnings
 * (DPF cleaning, charging, oil, tyres…) visible as a small strip on top of Android Auto.
 */
public final class ZLinkWatch {
    private static volatile boolean connected, shown;
    private static volatile String phone = "";
    private static volatile long shownAt;
    private static boolean started;
    private static Context app;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static TextView strip;
    private static String stripText = "", dismissed = "";

    private ZLinkWatch() { }

    public static boolean connected() { return connected; }
    /** ZLink's projection screen is in front right now. */
    public static boolean shown() { return shown; }
    public static String phone() { return phone; }

    public static synchronized void start(Context c) {
        if (started) return;
        started = true;
        app = c.getApplicationContext();
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent i) {
                    String st = i.getStringExtra("status");
                    if (st == null) return;
                    String pt = i.getStringExtra("phoneType");
                    if (pt != null) phone = pt;
                    switch (st) {
                        case "CONNECTED": connected = true; break;
                        case "DISCONNECT": connected = false; shown = false; break;
                        case "MAIN_PAGE_SHOW": shown = true; shownAt = System.currentTimeMillis(); break;
                        case "MAIN_PAGE_HIDDEN": shown = false; break;
                        default: return;
                    }
                    tick();
                }
            }, new IntentFilter("com.zjinnova.zlink"));
        } catch (Throwable ignored) { }
        H.postDelayed(loop, 3000);
    }

    private static final Runnable loop = new Runnable() {
        public void run() { tick(); H.postDelayed(this, 3000); }
    };

    /** Urgent warnings over Android Auto: only while ZLink is in front, only level 2. */
    private static void tick() {
        String text = "";
        if (shown) {
            JSONArray a = CarAlerts.get().list();
            JSONObject top = a.length() > 0 ? a.optJSONObject(0) : null;
            if (top != null && top.optInt("lvl") >= 2) text = top.optString("t");
        }
        if (text.equals(dismissed)) text = "";
        else dismissed = "";
        show(text);
    }

    private static void show(String text) {
        if (text.equals(stripText)) return;
        stripText = text;
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (text.isEmpty()) {
            if (strip != null) { try { wm.removeView(strip); } catch (Throwable ignored) { } strip = null; }
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) return;
        if (strip == null) {
            strip = new TextView(app);
            strip.setTextColor(Color.WHITE);
            strip.setTextSize(TypedValue.COMPLEX_UNIT_PX, app.getResources().getDisplayMetrics().heightPixels * 0.032f);
            int pad = (int) (app.getResources().getDisplayMetrics().heightPixels * 0.014f);
            strip.setPadding(pad * 2, pad, pad * 2, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF0C62828);
            bg.setCornerRadius(pad * 3);
            strip.setBackground(bg);
            strip.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) { dismissed = stripText; show(""); }   // hidden until the warning changes
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = pad;
            try { wm.addView(strip, lp); } catch (Throwable t) { strip = null; return; }
        }
        strip.setText("⚠  " + text);
    }
}
