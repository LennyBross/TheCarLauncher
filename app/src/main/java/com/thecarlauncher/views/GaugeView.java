package com.thecarlauncher.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
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
    private final float max, step, minor, redFrom;
    private final boolean band;
    private float value, shown;
    private boolean hasValue;
    private String big = "—", unit = "", line2 = null, line3 = null;
    private DayNight.Pal pal;
    private ValueAnimator anim;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private static Typeface sBold, sSemi;

    public GaugeView(Context c, float max, float step, float minor, float redFrom, boolean band, DayNight.Pal pal) {
        super(c);
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
        float cx = 150, cy = 150, r = 126;

        // Bezel and face
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(pal.face);
        c.drawCircle(cx, cy, 145, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(8);
        p.setShader(new LinearGradient(0, 0, 300, 300,
                new int[]{0xFFF4F5F7, 0xFF8C9199, 0xFF5E636B, 0xFFD9DCE1}, new float[]{0, 0.45f, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, 145, p);
        p.setShader(null);
        p.setStrokeWidth(1.5f);
        p.setColor(pal.ring);
        c.drawCircle(cx, cy, 136, p);

        // Red zone and value arc
        oval.set(cx - r - 3, cy - r - 3, cx + r + 3, cy + r + 3);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(5);
        if (redFrom > 0) {
            p.setColor(0xD9E8563A);
            c.drawArc(oval, 135 + 270 * redFrom / max, 270 * (1 - redFrom / max), false, p);
        }
        if (hasValue && shown > 0) {
            p.setColor(pal.accent);
            float sweep = 270 * shown / max;
            c.drawArc(oval, 135, sweep, false, p);
            float a = 135 + sweep;
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx + (r + 3) * cos(a), cy + (r + 3) * sin(a), 6, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(pal.face);
            c.drawCircle(cx + (r + 3) * cos(a), cy + (r + 3) * sin(a), 6, p);
        }

        // Scale
        p.setTextAlign(Paint.Align.CENTER);
        int n = Math.round(max / minor);
        for (int i = 0; i <= n; i++) {
            float v = i * minor, a = 135 + 270 * v / max;
            boolean major = Math.abs(v / step - Math.round(v / step)) < 1e-3;
            int col = redFrom > 0 && v >= redFrom ? 0xFFE8563A : pal.scale;
            p.setStyle(Paint.Style.STROKE);
            p.setColor(col);
            p.setStrokeWidth(major ? 2.6f : 1.4f);
            float r2 = r - (major ? 14 : 7);
            c.drawLine(cx + r * cos(a), cy + r * sin(a), cx + r2 * cos(a), cy + r2 * sin(a), p);
            if (major) {
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(sSemi);
                p.setTextSize(19);
                c.drawText(String.valueOf(Math.round(v)), cx + (r - 30) * cos(a), cy + (r - 30) * sin(a) + 6, p);
            }
        }

        // Digital middle
        p.setStyle(Paint.Style.FILL);
        float y = 150;
        if (band) {
            p.setColor(pal.band);
            c.drawRoundRect(new RectF(78, 128, 222, 186), 10, 10, p);
            y = 172;
        } else {
            y = 168;
        }
        p.setColor(pal.digit);
        p.setTypeface(sBold);
        p.setTextSize(band ? 58 : 50);
        c.drawText(big, cx, y, p);
        p.setTypeface(sSemi);
        p.setTextSize(13);
        p.setColor(pal.scale2);
        p.setLetterSpacing(0.08f);
        c.drawText(unit, cx, y + 18, p);
        p.setLetterSpacing(0);
        if (line2 != null) {
            p.setTypeface(sBold);
            p.setTextSize(21);
            p.setColor(pal.digit);
            c.drawText(line2, cx, y + 50, p);
        }
        if (line3 != null) {
            p.setTypeface(sSemi);
            p.setTextSize(12);
            p.setColor(pal.scale2);
            p.setLetterSpacing(0.12f);
            c.drawText(line3, cx, y + 66, p);
            p.setLetterSpacing(0);
        }
        c.restore();
    }
}
