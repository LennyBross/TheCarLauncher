package com.thecarlauncher.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

/**
 * MyDS draws its themes in Android System WebView. Some head units ship a 2020 WebView (Chrome 80, e.g.
 * Topway TS10) that lacks CSS the themes use (flex gap, inset) and is much slower. When the Play Store is
 * there, offer a one-tap update, at most once a day.
 */
public final class WebViewCheck {
    public static final int NEED = 90;

    public static String pkg() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                PackageInfo p = WebView.getCurrentWebViewPackage();
                if (p != null) return p.packageName;
            }
        } catch (Throwable ignored) { }
        return "com.google.android.webview";
    }

    /** Major Chromium version of the WebView in use (0 if unknown). */
    public static int major() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                PackageInfo p = WebView.getCurrentWebViewPackage();
                if (p != null && p.versionName != null) return Integer.parseInt(p.versionName.split("\\.")[0]);
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    public static void maybeAsk(final Activity a) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                try {
                    int v = major();
                    if (v == 0 || v >= NEED || a.isFinishing()) return;
                    SharedPreferences p = a.getSharedPreferences("wvcheck", 0);
                    if (System.currentTimeMillis() - p.getLong("at", 0) < 86400000L) return;
                    if (a.getPackageManager().getLaunchIntentForPackage("com.android.vending") == null) return;
                    p.edit().putLong("at", System.currentTimeMillis()).apply();
                    new AlertDialog.Builder(a)
                            .setTitle("Ekran motoru eski (sürüm " + v + ")")
                            .setMessage("Temalar Android System WebView ile çiziliyor. Bu cihazdaki sürüm 2020'den kalma: bazı boşluklar kayabilir ve ekran daha yavaş çalışır. Play Store'dan güncellemek ücretsiz, birkaç dakika sürer.")
                            .setPositiveButton("Güncelle", new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface d, int w) { open(a); } })
                            .setNegativeButton("Sonra", null)
                            .show();
                } catch (Throwable ignored) { }
            }
        }, 8000);
    }

    public static void open(Activity a) {
        String id = pkg();
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable ignored) { }
        }
    }
}
