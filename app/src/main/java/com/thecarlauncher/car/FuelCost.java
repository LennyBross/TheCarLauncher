package com.thecarlauncher.car;

import android.content.Context;

import com.thecarlauncher.nav.TripComputer;
import com.thecarlauncher.util.Prefs;

/**
 * What driving costs: litres per 100 km times the price per litre.
 *
 * Consumption comes, best first, from the value the driver reads off the car's own trip computer
 * (the DS 4 shows it on the instrument cluster), then from the fuel log measured fill-to-fill.
 * A live CAN-bus feed can be added as a further source once the unit's CAN interface is known.
 */
public final class FuelCost {
    public static final String P_MANUAL_L100 = "fuel_l100_manual";

    private FuelCost() {}

    private static double d(Context c, String key) {
        try {
            return Double.parseDouble(Prefs.str(c, key, "-1"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static void setManualConsumption(Context c, double l100) {
        Prefs.put(c, P_MANUAL_L100, String.valueOf(l100));
    }

    /** Average L/100 km in effect, or -1. */
    public static double litresPer100(Context c) {
        double m = d(c, P_MANUAL_L100);
        if (m > 0) return m;
        return CarData.get(c).averageConsumption();
    }

    public static boolean consumptionIsManual(Context c) { return d(c, P_MANUAL_L100) > 0; }

    /** Lira per 100 km, or -1. */
    public static double liraPer100(Context c) {
        double l = litresPer100(c), p = FuelPrice.perLitre(c);
        return l > 0 && p > 0 ? l * p : -1;
    }

    /** Lira per km, or -1. */
    public static double liraPerKm(Context c) {
        double v = liraPer100(c);
        return v > 0 ? v / 100.0 : -1;
    }

    /** km per litre, or -1. */
    public static double kmPerLitre(Context c) {
        double l = litresPer100(c);
        return l > 0 ? 100.0 / l : -1;
    }

    /** Fuel cost of the current trip (GPS distance since the trip was reset), or -1. */
    public static double tripLira(Context c) {
        double perKm = liraPerKm(c);
        if (perKm <= 0) return -1;
        return TripComputer.get(c).movingKm() * perKm;
    }
}
