package com.thecarlauncher.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import com.thecarlauncher.util.DayNight;

/**
 * A round instrument in the style of the car's own cluster: chrome bezel, dark (or white) face,
 * numbered scale on a 270° sweep, an accent arc up to the value, and digital text in the middle.
 * Everything is drawn in a 300×300 box scaled to the view.
 */
public class GaugeView extends View {
    private final float max, step, minor, redFrom, labelStep;
    private final boolean band;
    private float value, shown;
    private boolean hasValue;
    private String big = "—", unit = "", line2 = null, line3 = null;
    private DayNight.Pal pal;
    private ValueAnimator anim;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private static Typeface sBold, sSemi;

    public GaugeView(Context c, float max, float step, float minor, float labelStep, float redFrom, boolean band,
                     DayNight.Pal pal) {
        super(c);
        this.labelStep = labelStep;
        this.max = max;
        this.step = step;
        this.minor = minor;
        this.redFrom = redFrom;
        this.band = band;
        this.pal = pal;
        if (sBold == null) {
            try {
                sBold = Typeface.createFromAsset(c.getAssets(), "fonts/gauge_bold.ttf");
                sSemi = Typeface.createFromAsset(c.getAssets(), "fonts/gauge_semibold.ttf");
            } catch (Exception e) {
                sBold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
                sSemi = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            }
        }
    }

    public void setPal(DayNight.Pal pal) {
        this.pal = pal;
        invalidate();
    }

    /** Value for the arc (NaN: none) and the texts in the middle. */
    public void set(float v, String big, String unit, String line2, String line3) {
        this.big = big;
        this.unit = unit;
        this.line2 = line2;
        this.line3 = line3;
        boolean has = !Float.isNaN(v);
        float target = has ? Math.max(0, Math.min(max, v)) : 0;
        if (!hasValue || !has) {
            shown = target;
        } else if (Math.abs(target - value) > max * 0.002f) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(shown, target);
            anim.setDuration(450);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    shown = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }
        value = target;
        hasValue = has;
        invalidate();
    }

    private static float cos(float deg) { return (float) Math.cos(Math.toRadians(deg)); }

    private static float sin(float deg) { return (float) Math.sin(Math.toRadians(deg)); }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()) / 300f;
        c.save();
        c.translate((getWidth() - 300 * s) / 2f, (getHeight() - 300 * s) / 2f);
        c.scale(s, s);
        float cx = 150, cy = 150, r = 124;
        boolean night = pal.night;
        int glow = (pal.scale & 0x00FFFFFF) | 0x66000000;

        // Face: a soft radial shade, like a backlit dial.
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, cy - 20, 150,
                night ? new int[]{0xFF15181E, pal.face} : new int[]{0xFFFFFFFF, 0xFFEEF0F3}, null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, 145, p);
        // Satin chrome bezel: dark in the middle so it never reads as a plain white ring.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6);
        p.setShader(new LinearGradient(40, 0, 260, 300,
                new int[]{0xFFC9CDD3, 0xFF5A5F67, 0xFF2B2E34, 0xFF6B7078, 0xFFB4B9C0},
                new float[]{0, 0.3f, 0.5f, 0.72f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, 146, p);
        p.setShader(null);
        p.setStrokeWidth(1.2f);
        p.setColor(pal.ring);
        c.drawCircle(cx, cy, 141, p);

        // Faint track and red zone just outside the ticks
        oval.set(cx - r - 6, cy - r - 6, cx + r + 6, cy + r + 6);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(3);
        p.setColor(pal.track);
        c.drawArc(oval, 135, 270, false, p);
        if (redFrom > 0) {
            p.setColor(0xFFE8563A);
            p.setStrokeWidth(4);
            c.drawArc(oval, 135 + 270 * redFrom / max, 270 * (1 - redFrom / max), false, p);
        }

        // Scale: ticks every minor step, longer ones on the major step, numbers on labelStep.
        p.setTextAlign(Paint.Align.CENTER);
        if (night) p.setShadowLayer(5, 0, 0, glow);
        int n = Math.round(max / minor);
        for (int i = 0; i <= n; i++) {
            float v = i * minor, a = 135 + 270 * v / max;
            boolean major = Math.abs(v / step - Math.round(v / step)) < 1e-3;
            boolean label = Math.abs(v / labelStep - Math.round(v / labelStep)) < 1e-3;
            int col = redFrom > 0 && v >= redFrom ? 0xFFE8563A : pal.scale;
            p.setStyle(Paint.Style.STROKE);
            p.setColor(col);
            p.setStrokeWidth(label ? 3.2f : major ? 2.2f : 1.3f);
            float r2 = r - (label ? 15 : major ? 10 : 6);
            c.drawLine(cx + r * cos(a), cy + r * sin(a), cx + r2 * cos(a), cy + r2 * sin(a), p);
            if (label) {
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(sSemi);
                p.setTextSize(23);
                c.drawText(String.valueOf(Math.round(v)), cx + (r - 34) * cos(a), cy + (r - 34) * sin(a) + 8, p);
            }
        }
        p.clearShadowLayer();

        // Digital middle
        p.setStyle(Paint.Style.FILL);
        float y;
        if (band) {
            p.setColor(pal.band);
            c.drawRoundRect(new RectF(86, 116, 214, 184), 12, 12, p);
            y = 172;
        } else {
            y = 166;
        }
        if (night) p.setShadowLayer(6, 0, 0, glow);
        p.setColor(pal.digit);
        p.setTypeface(sBold);
        p.setTextSize(band ? 62 : 52);
        c.drawText(big, cx, y, p);
        p.clearShadowLayer();
        p.setTypeface(sSemi);
        p.setTextSize(14);
        p.setColor(pal.scale2);
        p.setLetterSpacing(0.1f);
        c.drawText(unit, cx, band ? 202 : y + 22, p);
        p.setLetterSpacing(0);
        if (line2 != null) {
            p.setTypeface(sBold);
            p.setTextSize(19);
            p.setColor(pal.digit);
            c.drawText(line2, cx, band ? 232 : y + 52, p);
        }

        // Needle: only its outer part shows, the hub sits behind the display as in the car.
        if (hasValue) {
            float a = 135 + 270 * shown / max;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setShadowLayer(6, 0, 0, (pal.accent & 0x00FFFFFF) | 0x99000000);
            p.setColor(pal.accent);
            p.setStrokeWidth(6);
            float r1 = r * 0.6f, r2 = r + 4;
            c.drawLine(cx + r1 * cos(a), cy + r1 * sin(a), cx + r2 * cos(a), cy + r2 * sin(a), p);
            p.clearShadowLayer();
        }
        c.restore();
    }
}
