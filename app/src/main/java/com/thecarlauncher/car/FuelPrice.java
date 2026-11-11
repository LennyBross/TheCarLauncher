package com.thecarlauncher.car;

import android.content.Context;

import com.thecarlauncher.util.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Diesel (motorin) price per litre in Turkish lira.
 *
 * The price comes from Opet's public price list for the chosen province (plate code, e.g. 34;
 * 934 is Istanbul's European side), refreshed in the background at most every few hours and kept
 * for offline use. A price typed in by the driver always wins.
 */
public final class FuelPrice {
    public static final String P_MANUAL = "fuel_price_manual", P_PROVINCE = "fuel_province",
            P_ONLINE = "fuel_price_online", P_ONLINE_AT = "fuel_price_online_at", P_PRODUCT = "fuel_price_product";
    private static final long REFRESH_MS = 4L * 60 * 60 * 1000;
    private static final String URL = "https://api.opet.com.tr/api/fuelprices/prices?ProvinceCode=%s&IncludeAllProducts=true";

    public interface Listener { void onPrice(); }

    private static volatile boolean sLoading;
    private static volatile long sLastTry;
    /** Why the last online try failed (null after a success), shown on the fuel card. */
    private static volatile String sError;

    public static String lastError() { return sError; }

    /** True when there is no online price yet and the last try is a minute old. */
    public static boolean shouldRetry(Context c) {
        return d(c, P_ONLINE) <= 0 && System.currentTimeMillis() - sLastTry > 60000;
    }

    private FuelPrice() {}

    private static double d(Context c, String key) {
        try {
            return Double.parseDouble(Prefs.str(c, key, "-1"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Price in effect: typed in, else the online one, else the last fill-up's; -1 if none. */
    public static double perLitre(Context c) {
        double m = d(c, P_MANUAL);
        if (m > 0) return m;
        double o = d(c, P_ONLINE);
        if (o > 0) return o;
        java.util.List<CarData.Refuel> l = CarData.get(c).refuels();
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).price > 0) return l.get(i).price;
        return -1;
    }

    /** Where the price in effect comes from, for the card's small print. */
    public static String source(Context c) {
        if (d(c, P_MANUAL) > 0) return "manual";
        if (d(c, P_ONLINE) > 0) return "online";
        return perLitre(c) > 0 ? "refuel" : "none";
    }

    public static String province(Context c) { return Prefs.str(c, P_PROVINCE, "34"); }

    public static void setProvince(Context c, String code) {
        Prefs.put(c, P_PROVINCE, code == null ? "34" : code.trim());
        Prefs.put(c, P_ONLINE_AT, 0L);
    }

    public static void setManual(Context c, double price) { Prefs.put(c, P_MANUAL, String.valueOf(price)); }

    public static long onlineAt(Context c) { return Prefs.longVal(c, P_ONLINE_AT, 0); }

    /** Fetches the online price when it is old (or forced); calls back on the main thread. */
    public static void refresh(final Context ctx, boolean force, final Listener cb) {
        final Context c = ctx.getApplicationContext();
        if (sLoading) return;
        if (!force && System.currentTimeMillis() - onlineAt(c) < REFRESH_MS) return;
        sLoading = true;
        sLastTry = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    double p = fetch(province(c));
                    if (p > 0) {
                        Prefs.put(c, P_ONLINE, String.valueOf(p));
                        Prefs.put(c, P_ONLINE_AT, System.currentTimeMillis());
                        sError = null;
                    } else if (sError == null) {
                        sError = "fiyat listesinde motorin yok (il " + province(c) + ")";
                    }
                } catch (java.net.UnknownHostException e) {
                    sError = "internet yok";
                } catch (java.net.SocketTimeoutException e) {
                    sError = "zaman aşımı";
                } catch (javax.net.ssl.SSLException e) {
                    sError = "güvenli bağlantı kurulamadı (saat/tarih doğru mu?)";
                } catch (Throwable e) {
                    sError = e.getClass().getSimpleName();
                } finally {
                    sLoading = false;
                }
                if (cb != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        public void run() { cb.onPrice(); }
                    });
                }
            }
        }, "fuel-price").start();
    }

    /** Diesel price of the province's first district: the standard grade over the premium one. */
    static double fetch(String province) throws Exception {
        sError = null;
        HttpURLConnection h = (HttpURLConnection) new URL(String.format(Locale.US, URL, province)).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(8000);
        h.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36");
        h.setRequestProperty("Origin", "https://www.opet.com.tr");
        h.setRequestProperty("Channel", "Web");
        h.setRequestProperty("Accept", "application/json");
        h.setRequestProperty("Accept-Language", "tr-TR");
        h.setRequestProperty("Referer", "https://www.opet.com.tr/");
        try {
            int code = h.getResponseCode();
            if (code != 200) {
                sError = "Opet HTTP " + code;
                return -1;
            }
            InputStream in = h.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return parse(new String(out.toByteArray(), "UTF-8"));
        } finally {
            h.disconnect();
        }
    }

    /** Picks the diesel price from the response: [{"prices":[{"productName":..,"amount":..}]}]. */
    static double parse(String json) throws Exception {
        JSONArray districts = new JSONArray(json);
        if (districts.length() == 0) return -1;
        JSONArray prices = districts.getJSONObject(0).optJSONArray("prices");
        if (prices == null) return -1;
        double any = -1, standard = -1;
        for (int i = 0; i < prices.length(); i++) {
            JSONObject p = prices.getJSONObject(i);
            String name = p.optString("productName", "").toLowerCase(new Locale("tr"));
            double amount = p.optDouble("amount", -1);
            if (amount <= 0 || !name.contains("motorin")) continue;
            if (any < 0) any = amount;
            // "Motorin EcoForce" / plain "Motorin" is the everyday grade; UltraForce is premium.
            if (standard < 0 && !name.contains("ultra")) standard = amount;
        }
        return standard > 0 ? standard : any;
    }
}
