package com.thecarlauncher.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * Live car data from the companion apps:
 * - CataraPatara CAN bridge (com.thecarlauncher.CAN): instant consumption, range, outdoor temperature
 *   read from the CAN box frames;
 * - CataraPatara (com.catarapatara.LIVE): engine sound state, rpm and gear.
 */
public final class CanLive {
    public static final String PKG_CP = "com.catarapatara", PKG_BRIDGE = "com.catarapatara.canbridge";

    public static volatile float l100 = -1, range = -1, outdoor = Float.NaN;
    public static volatile long canAt, outdoorAt;

    public static volatile boolean cpOn;
    public static volatile int cpRpm, cpGear, cpRed = 6500, cpLoad;
    public static volatile String cpName = "", cpSrc = "";
    public static volatile long cpAt;

    public static volatile String bridgeStatus = "", bridgeTypes = "";
    public static volatile int bridgeLines, bridgeRev, bridgeFrames;
    public static volatile long bridgeAt;

    private static boolean registered;

    private CanLive() {}

    public static synchronized void start(Context c) {
        if (registered) return;
        registered = true;
        Context app = c.getApplicationContext();
        BroadcastReceiver r = new BroadcastReceiver() {
            public void onReceive(Context ctx, Intent i) {
                long now = System.currentTimeMillis();
                if ("com.thecarlauncher.CANSTATUS".equals(i.getAction())) {
                    String st = i.getStringExtra("status");
                    if (st != null) bridgeStatus = st;
                    String ty = i.getStringExtra("types");
                    if (ty != null) bridgeTypes = ty;
                    bridgeLines = i.getIntExtra("lines", 0);
                    bridgeRev = i.getIntExtra("rev", 0);
                    bridgeFrames = i.getIntExtra("frames", 0);
                    bridgeAt = now;
                    return;
                }
                if ("com.thecarlauncher.CAN".equals(i.getAction())) {
                    if (i.hasExtra("l100")) { l100 = i.getFloatExtra("l100", -1); canAt = now; }
                    if (i.hasExtra("range")) { range = i.getFloatExtra("range", -1); canAt = now; }
                    if (i.hasExtra("outdoor")) { outdoor = i.getFloatExtra("outdoor", Float.NaN); outdoorAt = now; }
                } else {
                    cpOn = i.getBooleanExtra("on", false);
                    cpRpm = i.getIntExtra("rpm", 0);
                    cpGear = i.getIntExtra("gear", 0);
                    cpRed = i.getIntExtra("red", 6500);
                    cpLoad = i.getIntExtra("load", 0);
                    String n = i.getStringExtra("name");
                    if (n != null) cpName = n;
                    String s = i.getStringExtra("src");
                    if (s != null) cpSrc = s;
                    cpAt = now;
                }
            }
        };
        IntentFilter f = new IntentFilter("com.thecarlauncher.CAN");
        f.addAction("com.catarapatara.LIVE");
        f.addAction("com.thecarlauncher.CANSTATUS");
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else app.registerReceiver(r, f);
        // The CAN bridge, if installed, runs with MYDS too (not only while the engine sound plays).
        try {
            Intent b = new Intent().setClassName(PKG_BRIDGE, "com.catarapatara.canbridge.BridgeService");
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(b); else app.startService(b);
        } catch (Exception ignored) {
        }
    }

    public static boolean canFresh() { return System.currentTimeMillis() - canAt < 10000; }

    public static boolean outdoorFresh() { return System.currentTimeMillis() - outdoorAt < 120000; }

    /** CataraPatara reported within the last few seconds (it sends 4× a second while playing). */
    public static boolean cpFresh() { return System.currentTimeMillis() - cpAt < 3000; }

    public static boolean installed(Context c, String pkg) {
        if (PKG_CP.equals(pkg)) return true;   // CataraPatara is built into MYDS now
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Opens the built-in CataraPatara screen. */
    public static void openCp(Context c) {
        try {
            c.startActivity(new Intent().setClassName(c.getPackageName(), "com.catarapatara.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    /** The old stand-alone CataraPatara APK is still on the unit (it would play a second engine). */
    public static boolean standaloneCp(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG_CP, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Opens the system uninstall dialog for the old stand-alone CataraPatara. */
    public static void removeStandaloneCp(Context c) {
        try {
            c.startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + PKG_CP)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    /** Asks CataraPatara to switch its sound: "toggle", "on" or "off". */
    public static void cpCommand(Context c, String cmd) {
        Intent i = new Intent("com.catarapatara.CONTROL").setClassName(c.getPackageName(), "com.catarapatara.ControlReceiver").putExtra("cmd", cmd);
        c.sendBroadcast(i);
        if ("toggle".equals(cmd)) cpOn = !cpOn; else cpOn = "on".equals(cmd);
        cpAt = System.currentTimeMillis();
    }
}
