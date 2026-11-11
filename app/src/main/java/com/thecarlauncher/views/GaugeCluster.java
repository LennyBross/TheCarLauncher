package com.thecarlauncher.views;

import android.content.Context;
import android.view.ViewGroup;

import com.thecarlauncher.nav.LocationTracker;
import com.thecarlauncher.util.DayNight;

/**
 * Rev counter and speedometer side by side, the smaller one tucked in behind, like the car's
 * cluster. Speed comes from GPS; engine speed and live consumption need the CAN bus and show a
 * dash until a source for them is connected.
 */
public class GaugeCluster extends ViewGroup implements LocationTracker.Listener {
    private static final float TACH = 0.64f, OVERLAP = 0.1f;
    private final GaugeView tach, speedo;

    public GaugeCluster(Context c, DayNight.Pal pal) {
        super(c);
        tach = new GaugeView(c, 7, 1, 0.5f, 1, 6, false, pal);
        speedo = new GaugeView(c, 220, 20, 10, 40, 0, true, pal);
        addView(tach);
        addView(speedo);
        tach.set(Float.NaN, "—", "×1000 D/DK", null, null);
        update(LocationTracker.get(c));
    }

    public void setPal(DayNight.Pal pal) {
        tach.setPal(pal);
        speedo.setPal(pal);
    }

    /** Width this cluster takes at a given height. */
    public static int widthFor(int h) {
        return Math.round(h * (1 + TACH - OVERLAP));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int W = MeasureSpec.getSize(wSpec), H = MeasureSpec.getSize(hSpec);
        int h = H;
        if (MeasureSpec.getMode(wSpec) != MeasureSpec.UNSPECIFIED && widthFor(h) > W) {
            h = Math.round(W / (1 + TACH - OVERLAP));
        }
        int t = Math.round(h * TACH);
        tach.measure(MeasureSpec.makeMeasureSpec(t, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(t, MeasureSpec.EXACTLY));
        speedo.measure(MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        int w = MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY ? W : widthFor(h);
        setMeasuredDimension(w, MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY ? H : h);
    }

    /** Centred in the space given, rev counter low on the left, speedometer on the right. */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int h = speedo.getMeasuredHeight(), ts = tach.getMeasuredHeight();
        int w = widthFor(h);
        int left = ((r - l) - w) / 2, top = ((b - t) - h) / 2;
        tach.layout(left, top + h - ts - h / 14, left + ts, top + h - h / 14);
        int sx = left + ts - Math.round(h * OVERLAP);
        speedo.layout(sx, top, sx + h, top + h);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        LocationTracker.get(getContext()).addListener(this);
        update(LocationTracker.get(getContext()));
    }

    @Override
    protected void onDetachedFromWindow() {
        LocationTracker.get(getContext()).removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onLocationUpdate(final LocationTracker t) {
        post(new Runnable() {
            public void run() { update(t); }
        });
    }

    private void update(LocationTracker t) {
        String unit = LocationTracker.unit(getContext()).toUpperCase(java.util.Locale.ROOT).replace("KM/H", "KM/S");
        if (!t.hasPermission()) {
            speedo.set(Float.NaN, "—", "KONUM İZNİ YOK", "ANLIK  — L/100", null);
        } else if (t.hasFix()) {
            float v = t.speed(getContext());
            speedo.set(v, String.valueOf(Math.round(v)), unit, "ANLIK  — L/100", null);
        } else {
            speedo.set(Float.NaN, "—", "GPS ARANIYOR", "ANLIK  — L/100", null);
        }
    }

    /** Location permission granted later: start listening without waiting for a restart. */
    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            LocationTracker t = LocationTracker.get(getContext());
            t.start();
            update(t);
        }
    }
}
