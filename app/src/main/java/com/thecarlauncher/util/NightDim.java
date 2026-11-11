package com.thecarlauncher.util;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

/**
 * Extra dimming for night driving: a black layer over the whole screen, every app included
 * (navigation too), so the picture can go below the unit's lowest brightness.
 *
 * The layer never takes touches. Android 12 lets touches pass through another app's layer only
 * while that layer is at most 80 % opaque, so the strength is capped there.
 */
public final class NightDim {
    public static final String P_ON = "night_dim_on", P_LEVEL = "night_dim_level";
    private static final int MAX = 80, MIN = 10;

    private static View sView;

    private NightDim() {}

    public static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    public static void request(Context c) {
        if (Build.VERSION.SDK_INT < 23) return;
        Ui.start(c, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.getPackageName())));
    }

    public static boolean on(Context c) {
        return Prefs.bool(c, P_ON, false);
    }

    /** Darkness 10..80 %. */
    public static int level(Context c) {
        return Math.max(MIN, Math.min(MAX, Prefs.integer(c, P_LEVEL, 40)));
    }

    public static void setOn(Context c, boolean on) {
        Prefs.put(c, P_ON, on);
        apply(c);
    }

    public static void setLevel(Context c, int pct) {
        Prefs.put(c, P_LEVEL, Math.max(MIN, Math.min(MAX, pct)));
        apply(c);
    }

    /** Shows, updates or removes the layer to match the settings. */
    public static void apply(Context c) {
        Context app = c.getApplicationContext();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        if (!on(app) || !canDraw(app)) {
            if (sView != null) {
                try {
                    wm.removeView(sView);
                } catch (Exception ignored) {
                }
                sView = null;
            }
            return;
        }
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.alpha = level(app) / 100f;
        try {
            if (sView == null) {
                sView = new View(app);
                sView.setBackgroundColor(0xFF000000);
                wm.addView(sView, lp);
            } else {
                wm.updateViewLayout(sView, lp);
            }
        } catch (Exception e) {
            sView = null;
        }
    }
}
