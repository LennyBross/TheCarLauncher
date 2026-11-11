package com.thecarlauncher.util;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.thecarlauncher.LauncherActivity;
import com.thecarlauncher.car.FuelCost;
import com.thecarlauncher.media.MediaHub;
import com.thecarlauncher.nav.LocationTracker;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Edge panel: a thin handle on the right edge of the screen over every other app. A tap slides
 * out a narrow panel with the time, speed, fuel cost, music controls and quick buttons back to
 * the home screen or into driving mode. Hidden while the launcher itself is in front.
 */
public final class EdgePanel {
    public static final String P_ON = "edge_panel_on";
    private static final int ACCENT = 0xFF5CC8B0, BG = 0xF0161B23, FG = 0xFFF3F5F7, MUTED = 0xFFA3ADBA;

    private static View sHandle, sPanel;
    private static boolean sLauncherFront = true;
    private static TextView tClock, tSpeed, tFuel, tTitle, tPlay;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable TICK = new Runnable() {
        public void run() {
            if (sPanel == null) return;
            refresh(sPanel.getContext());
            H.postDelayed(this, 1000);
        }
    };

    private EdgePanel() {}

    public static boolean on(Context c) { return Prefs.bool(c, P_ON, true); }

    public static void setOn(Context c, boolean on) {
        Prefs.put(c, P_ON, on);
        apply(c);
    }

    /** Called by the launcher when it comes to the front or leaves it. */
    public static void launcherFront(Context c, boolean front) {
        sLauncherFront = front;
        apply(c);
    }

    public static void apply(Context c) {
        final Context app = c.getApplicationContext();
        boolean want = on(app) && NightDim.canDraw(app) && !sLauncherFront;
        if (!want) {
            closePanel(app);
            remove(app, sHandle);
            sHandle = null;
            return;
        }
        if (sHandle != null || sPanel != null) return;
        int dp = dp(app, 1);
        FrameLayout box = new FrameLayout(app);
        View bar = new View(app);
        bar.setBackground(round(ACCENT, 3 * dp));
        bar.setAlpha(.85f);
        box.addView(bar, new FrameLayout.LayoutParams(6 * dp, 96 * dp, Gravity.CENTER));
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openPanel(app); }
        });
        WindowManager.LayoutParams lp = params(28 * dp, 140 * dp, false);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        if (add(app, box, lp)) sHandle = box;
    }

    // ---- Panel --------------------------------------------------------------------------------

    private static void openPanel(final Context app) {
        remove(app, sHandle);
        sHandle = null;
        int dp = dp(app, 1);
        LinearLayout p = new LinearLayout(app);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(18 * dp, 16 * dp, 18 * dp, 16 * dp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadii(new float[]{20 * dp, 20 * dp, 0, 0, 0, 0, 20 * dp, 20 * dp});
        p.setBackground(bg);

        LinearLayout top = row(app);
        tClock = text(app, "--:--", 34, FG, true);
        top.addView(tClock, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = button(app, "✕", 0x00000000, FG, 44);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closePanel(app); apply(app); }
        });
        top.addView(close);
        p.addView(top);

        tSpeed = text(app, "— km/h", 22, ACCENT, true);
        p.addView(tSpeed, gap(app, 8));
        tFuel = text(app, "", 15, MUTED, false);
        p.addView(tFuel, gap(app, 4));

        View sep = new View(app);
        sep.setBackgroundColor(0xFF2A323E);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp);
        sl.topMargin = 14 * dp;
        p.addView(sep, sl);

        tTitle = text(app, "", 16, FG, true);
        tTitle.setSingleLine(true);
        tTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        p.addView(tTitle, gap(app, 14));

        LinearLayout ctl = row(app);
        ctl.setGravity(Gravity.CENTER);
        TextView prev = button(app, "⏮", 0x00000000, FG, 56);
        tPlay = button(app, "▶", ACCENT, 0xFF0B0E13, 64);
        TextView next = button(app, "⏭", 0x00000000, FG, 56);
        prev.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { MediaHub.get(app).previous(); later(app); } });
        tPlay.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { MediaHub.get(app).playPause(); later(app); } });
        next.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { MediaHub.get(app).next(); later(app); } });
        ctl.addView(prev); ctl.addView(tPlay, margins(app, 16)); ctl.addView(next);
        p.addView(ctl, gap(app, 10));

        final TextView eng = button(app, "", 0xFF262D38, FG, 48);
        paintEngine(app, eng);
        eng.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                com.thecarlauncher.engine.EngineSound e = com.thecarlauncher.engine.EngineSound.get(app);
                e.setOn(!e.on());
                paintEngine(app, eng);
            }
        });
        p.addView(eng, gap(app, 12, 48));

        View fill = new View(app);
        p.addView(fill, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView home = button(app, "Ana ekran", 0xFF262D38, FG, 52);
        home.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closePanel(app); BringBack.toLauncher(app); }
        });
        p.addView(home, new LinearLayout.LayoutParams(-1, 52 * dp));
        TextView drive = button(app, "Sürüş modu", ACCENT, 0xFF0B0E13, 52);
        drive.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel(app);
                Intent i = new Intent(app, LauncherActivity.class);
                i.putExtra(LauncherActivity.EXTRA_SCREEN, "drive");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                Ui.start(app, i);
            }
        });
        p.addView(drive, gap(app, 10, 52));

        WindowManager.LayoutParams lp = params(280 * dp, WindowManager.LayoutParams.MATCH_PARENT, true);
        lp.gravity = Gravity.END | Gravity.TOP;
        if (Build.VERSION.SDK_INT >= 17) lp.windowAnimations = android.R.style.Animation_Translucent;
        if (add(app, p, lp)) {
            sPanel = p;
            refresh(app);
            H.removeCallbacks(TICK);
            H.postDelayed(TICK, 1000);
        }
    }

    private static void paintEngine(Context app, TextView t) {
        boolean on = com.thecarlauncher.engine.EngineSound.get(app).on();
        t.setText(on ? "Motor sesi: açık" : "Motor sesi: kapalı");
        t.setBackground(round(on ? 0xFF3E2A26 : 0xFF262D38, dp(app, 14)));
        t.setTextColor(on ? 0xFFFF8A6B : FG);
    }

    private static void closePanel(Context app) {
        H.removeCallbacks(TICK);
        remove(app, sPanel);
        sPanel = null;
    }

    private static void later(final Context app) {
        H.postDelayed(new Runnable() { public void run() { if (sPanel != null) refresh(app); } }, 400);
    }

    private static void refresh(Context app) {
        tClock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        LocationTracker t = LocationTracker.get(app);
        tSpeed.setText(t.hasFix() ? Math.round(t.speed(app)) + " km/h" : "GPS bekleniyor");
        double l100 = FuelCost.litresPer100(app), per100 = FuelCost.liraPer100(app);
        StringBuilder f = new StringBuilder();
        if (l100 > 0) f.append(String.format(Locale.getDefault(), "%.1f L/100 km", l100));
        if (per100 > 0) f.append(f.length() > 0 ? "  ·  " : "").append(Math.round(per100)).append(" ₺/100 km");
        tFuel.setText(f.length() > 0 ? f : "Yakıt verisi yok");
        MediaHub m = MediaHub.get(app);
        tTitle.setText(m.hasTrackInfo() ? m.title() : "Çalan bir şey yok");
        tPlay.setText(m.isPlaying() ? "❚❚" : "▶");
    }

    // ---- Small view helpers -------------------------------------------------------------------

    private static WindowManager.LayoutParams params(int w, int h, boolean focusOutside) {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (focusOutside) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        return new WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT);
    }

    private static boolean add(Context app, View v, WindowManager.LayoutParams lp) {
        try {
            ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).addView(v, lp);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static void remove(Context app, View v) {
        if (v == null) return;
        try {
            ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).removeView(v);
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    private static GradientDrawable round(int color, int r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r);
        return d;
    }

    private static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static TextView button(Context c, String s, int bg, int fg, int sizeDp) {
        TextView t = text(c, s, s.length() > 2 ? 17 : 20, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(bg, dp(c, 14)));
        t.setMinWidth(dp(c, sizeDp));
        t.setMinHeight(dp(c, sizeDp));
        return t;
    }

    private static LinearLayout.LayoutParams gap(Context c, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, top);
        return lp;
    }

    private static LinearLayout.LayoutParams gap(Context c, int top, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(c, h));
        lp.topMargin = dp(c, top);
        return lp;
    }

    private static LinearLayout.LayoutParams margins(Context c, int side) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = lp.rightMargin = dp(c, side);
        return lp;
    }
}
