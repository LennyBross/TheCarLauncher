package com.thecarlauncher.util;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Keeps the Android Bluetooth radio on for the OBD adapter. Some firmwares (Topway TS10) hide Android's
 * Bluetooth switch and turn the radio off at boot or when their own phone app takes the module; the
 * ELM327 then can never be found. Here: switch it back on whenever it goes off, check every 20 s, and if
 * a silent enable() is refused, show Android's own "allow Bluetooth" window once per start.
 */
@SuppressWarnings({"deprecation", "MissingPermission"})
public final class BtGuard {
    private static boolean started;
    private static long askedAt;
    private static int failed;
    private static final Handler H = new Handler(Looper.getMainLooper());

    public static synchronized void start(final Context ctx) {
        if (started) return;
        started = true;
        final Context app = ctx.getApplicationContext();
        IntentFilter f = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        BroadcastReceiver r = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (st == BluetoothAdapter.STATE_OFF) H.postDelayed(new Runnable() { public void run() { ensure(app); } }, 3000);
                if (st == BluetoothAdapter.STATE_ON) {
                    failed = 0;
                    try { app.sendBroadcast(new Intent("com.thecarlauncher.OBD_APPLY").setPackage(app.getPackageName())); } catch (Throwable ignored) { }
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED); else app.registerReceiver(r, f);
        H.postDelayed(new Runnable() {
            public void run() { ensure(app); H.postDelayed(this, 20000); }
        }, 4000);
    }

    /** Only when the OBD adapter is a Bluetooth one (not Wi-Fi / phone / off). */
    private static boolean wanted(Context c) {
        try {
            String m = c.getSharedPreferences("cp", Context.MODE_MULTI_PROCESS).getString("obd", "auto");
            return !"off".equals(m) && !"wifi".equals(m) && !"phone".equals(m);
        } catch (Throwable t) { return true; }
    }

    static void ensure(Context c) {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null || a.isEnabled() || !wanted(c)) return;
            int st = a.getState();
            if (st == BluetoothAdapter.STATE_TURNING_ON) return;
            boolean ok = Build.VERSION.SDK_INT < 33 && a.enable();
            if (!ok) failed++;
            else failed = 0;
            // silent switch-on refused twice: Android's own window, at most once every 10 minutes
            if (failed >= 2 && System.currentTimeMillis() - askedAt > 600000) {
                askedAt = System.currentTimeMillis();
                Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            }
        } catch (Throwable ignored) { }
    }
}
