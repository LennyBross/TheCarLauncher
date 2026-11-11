package com.thecarlauncher.util;

import android.content.Context;
import android.location.Location;

import com.thecarlauncher.mib.Mib;
import com.thecarlauncher.nav.LocationTracker;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Day / night look of the home page and the gauges.
 * Automatic follows sunrise and sunset at the car's last known position (07:00–18:30 without one).
 */
public final class DayNight {
    public static final String P_MODE = "daynight_mode";
    public static final int AUTO = 0, NIGHT = 1, DAY = 2;

    /** Colours of one look. */
    public static final class Pal {
        public final boolean night;
        public final int skyTop, skyBottom, bg, card, inner, text, sub, head, dock, pill;
        public final int face, ring, scale, scale2, digit, band, track, line;
        public final int accent, onAccent;

        Pal(boolean night) {
            this.night = night;
            if (night) {
                skyTop = 0xFF3A3B40; skyBottom = 0xFF1A1B1F; bg = 0xFF08090A; card = 0xFF1B1C22; inner = 0xFF26272F;
                text = 0xFFF7F7F5; sub = 0xFFB5B6BF; head = 0xFFC9CAD2; dock = 0xFF121318; pill = 0xB80C0C12;
                face = 0xFF050608; ring = 0xFF1C1F26; scale = 0xFFEEF3FF; scale2 = 0xFFAEB6C6; digit = 0xFFF4F7FF;
                band = 0xFF0E1117; track = 0xFF2E3038; line = 0xFF1E2126;
                accent = Mib.BLUE; onAccent = 0xFF0B0C10;
            } else {
                skyTop = 0xFFD5DCE5; skyBottom = 0xFFEEF1F4; bg = 0xFFE9ECF0; card = 0xFFFFFFFF; inner = 0xFFEEF0F3;
                text = 0xFF15171B; sub = 0xFF565B64; head = 0xFF3D424A; dock = 0xFFFFFFFF; pill = 0xD9FFFFFF;
                face = 0xFFFFFFFF; ring = 0xFFE3E6EA; scale = 0xFF1D2128; scale2 = 0xFF575D67; digit = 0xFF111418;
                band = 0xFFF0F2F5; track = 0xFFE1E4E9; line = 0xFFD3D7DD;
                // The ambient colour darkened so it reads on white.
                accent = Mib.shade(Mib.BLUE, 0.68f, 0xFF); onAccent = 0xFFFFFFFF;
            }
        }
    }

    private DayNight() {}

    public static int mode(Context c) { return Prefs.integer(c, P_MODE, AUTO); }

    public static void setMode(Context c, int m) { Prefs.put(c, P_MODE, m); }

    public static boolean isNight(Context c) {
        int m = mode(c);
        if (m == NIGHT) return true;
        if (m == DAY) return false;
        Calendar now = Calendar.getInstance();
        double hour = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0;
        double[] sun = sunTimes(c, now);
        if (sun == null) return hour < 7.0 || hour >= 18.5;
        return hour < sun[0] || hour >= sun[1];
    }

    public static Pal pal(Context c) { return new Pal(isNight(c)); }

    /** Local sunrise / sunset hours for today, or null without a position. */
    static double[] sunTimes(Context c, Calendar day) {
        Location l = LocationTracker.get(c).location();
        if (l == null) return null;
        double lat = l.getLatitude(), lon = l.getLongitude();
        int n = day.get(Calendar.DAY_OF_YEAR);
        // NOAA approximation: declination and equation of time from the fractional year.
        double g = 2 * Math.PI / 365.0 * (n - 1);
        double eqt = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double latR = Math.toRadians(lat);
        double cosH = (Math.cos(Math.toRadians(90.833)) - Math.sin(latR) * Math.sin(decl))
                / (Math.cos(latR) * Math.cos(decl));
        if (cosH > 1 || cosH < -1) return null; // polar day / night
        double ha = Math.toDegrees(Math.acos(cosH));
        double tz = TimeZone.getDefault().getOffset(day.getTimeInMillis()) / 3600000.0;
        double noon = 720 - 4 * lon - eqt; // minutes UTC
        double rise = (noon - 4 * ha) / 60.0 + tz, set = (noon + 4 * ha) / 60.0 + tz;
        return new double[]{rise, set};
    }
}
