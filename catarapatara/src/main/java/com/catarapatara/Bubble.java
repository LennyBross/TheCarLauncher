package com.catarapatara;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * A small floating exhaust button over every app (Android Auto included): tap to switch the
 * engine sound on or off, hold to open CataraPatara, drag to move it.
 */
final class Bubble {
    private static View sView;
    private static WindowManager.LayoutParams sLp;

    private Bubble() {}

    static boolean wanted(Context c) { return EngineService.prefs(c).getBoolean("bubble", true); }

    static boolean canDraw(Context c) { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c); }

    static void refresh(Context c) {
        final Context app = c.getApplicationContext();
        if (!wanted(app) || !canDraw(app)) { remove(app); return; }
        if (sView == null) add(app);
        paint(app);
    }

    private static void add(final Context app) {
        final int dp = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, app.getResources().getDisplayMetrics()));
        FrameLayout box = new FrameLayout(app);
        ImageView icon = new ImageView(app);
        icon.setImageResource(R.drawable.ic_bubble);
        int pad = 9 * dp;
        icon.setPadding(pad, pad, pad, pad);
        box.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(58 * dp, 58 * dp, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = EngineService.prefs(app).getInt("bx", 12 * dp);
        lp.y = EngineService.prefs(app).getInt("by", 120 * dp);
        box.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            long down;
            boolean moved;

            public boolean onTouch(View v, MotionEvent e) {
                WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = lp.x; oy = lp.y; down = System.currentTimeMillis(); moved = false;
                        v.setScaleX(.9f); v.setScaleY(.9f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - sx) > 10 * dp || Math.abs(e.getRawY() - sy) > 10 * dp) moved = true;
                        if (moved) {
                            lp.x = ox + Math.round(e.getRawX() - sx);
                            lp.y = oy + Math.round(e.getRawY() - sy);
                            try { wm.updateViewLayout(v, lp); } catch (Exception ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setScaleX(1f); v.setScaleY(1f);
                        if (moved) {
                            EngineService.prefs(app).edit().putInt("bx", lp.x).putInt("by", lp.y).apply();
                        } else if (System.currentTimeMillis() - down > 600) {
                            app.startActivity(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } else {
                            EngineService.toggle(app);
                            v.postDelayed(new Runnable() { public void run() { paint(app); } }, 200);
                        }
                        return true;
                    default:
                        v.setScaleX(1f); v.setScaleY(1f);
                        return true;
                }
            }
        });
        try {
            ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).addView(box, lp);
            sView = box;
            sLp = lp;
        } catch (Exception ignored) {
        }
    }

    private static void paint(Context app) {
        if (sView == null) return;
        EngineService s = EngineService.get();
        boolean on = s != null && s.playing();
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(on ? 0xF0E8541E : 0xE0181C22);
        bg.setStroke(Math.round(2 * app.getResources().getDisplayMetrics().density), on ? 0xFFFFB37A : 0xFF3A424E);
        sView.setBackground(bg);
        sView.setAlpha(on ? 1f : .85f);
    }

    private static void remove(Context app) {
        if (sView == null) return;
        try { ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).removeView(sView); } catch (Exception ignored) { }
        sView = null;
        sLp = null;
    }
}
