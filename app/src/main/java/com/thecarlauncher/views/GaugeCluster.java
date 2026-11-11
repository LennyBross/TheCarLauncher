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
    private static final float TACH = 0.71f, OVERLAP = 0.075f;
    private final GaugeView tach, speedo;

    public GaugeCluster(Context c, DayNight.Pal pal) {
        super(c);
        tach = new GaugeView(c, 7, 1, 0.5f, 6, false, pal);
        speedo = new GaugeView(c, 220, 20, 10, 0, true, pal);
        addView(tach);
        addView(speedo);
        tach.set(Float.NaN, "—", "D/DK ×1000", null, null);
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
        int h = MeasureSpec.getSize(hSpec);
        int w = MeasureSpec.getSize(wSpec);
        // Fit the height to the width if the width is the tighter limit.
        if (MeasureSpec.getMode(wSpec) != MeasureSpec.UNSPECIFIED && widthFor(h) > w) {
            h = Math.round(w / (1 + TACH - OVERLAP));
        }
        int t = Math.round(h * TACH);
        tach.measure(MeasureSpec.makeMeasureSpec(t, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(t, MeasureSpec.EXACTLY));
        speedo.measure(MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        setMeasuredDimension(widthFor(h), h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int h = speedo.getMeasuredHeight(), ts = tach.getMeasuredHeight();
        int top = (b - t) - h;
        tach.layout(0, top + h - ts, ts, top + h);
        int sx = ts - Math.round(h * OVERLAP);
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
        if (t.hasFix()) {
            float v = t.speed(getContext());
            speedo.set(v, String.valueOf(Math.round(v)), unit, "— L/100", "ANLIK");
        } else {
            speedo.set(Float.NaN, "—", unit, "— L/100", "ANLIK");
        }
    }
}
