package com.thecarlauncher.util;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/**
 * Android Auto (ZLink) jumps in front by itself whenever the phone connects, and MYDS is gone.
 * When MYDS goes to the background without the driver having opened something from MYDS, this
 * looks at which app came to the front (usage stats) and, if it is ZLink, brings MYDS back after
 * a moment. ZLink keeps running behind (music keeps playing); opening Android Auto from MYDS
 * itself is never undone.
 */
public final class AutoReturn {
    public static final String P_ON = "aa_return";
    private static final String[] POPPERS = {"com.zjinnova.zlink"};
    private static volatile long ownAt;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private AutoReturn() {}

    public static boolean on(Context c) { return Prefs.bool(c, P_ON, true); }

    /** MYDS itself is opening another app (or the driver just touched MYDS). */
    public static void markOwn() { ownAt = System.currentTimeMillis(); }

    /** Called when the launcher goes to the background. */
    public static void launcherStopped(final Context c) {
        if (!on(c) || System.currentTimeMillis() - ownAt < 4000) return;
        final Context app = c.getApplicationContext();
        H.postDelayed(new Runnable() {
            public void run() {
                if (System.currentTimeMillis() - ownAt < 5000) return;
                String fg = foreground(app);
                if (fg == null) return;
                for (String p : POPPERS) if (p.equals(fg)) { BringBack.toLauncher(app); return; }
            }
        }, 1500);
    }

    /** Package in front (needs usage access; tried to grant itself once). */
    public static String foreground(Context c) {
        try {
            UsageStatsManager um = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageEvents ev = um.queryEvents(now - 60000, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.getPackageName();
            }
            return last;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean hasAccess(Context c) {
        try {
            AppOpsManager ao = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Usage access: as a privileged app set the app-op directly; else try root once. */
    public static void ensureAccess(final Context c) {
        if (hasAccess(c)) return;
        try {
            AppOpsManager ao = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int op = (Integer) AppOpsManager.class.getField("OP_GET_USAGE_STATS").get(null);
            AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class)
                    .invoke(ao, op, Process.myUid(), c.getPackageName(), AppOpsManager.MODE_ALLOWED);
        } catch (Throwable ignored) {
        }
        if (hasAccess(c) || Prefs.bool(c, "aa_return_su", false)) return;
        Prefs.put(c, "aa_return_su", true);
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.lang.Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "appops set " + c.getPackageName() + " GET_USAGE_STATS allow"});
                    p.waitFor();
                } catch (Throwable ignored) {
                }
            }
        }, "usage-access").start();
    }

    /** Settings page for usage access (when nothing above worked). */
    public static void request(Context c) {
        if (Build.VERSION.SDK_INT < 21) return;
        Ui.start(c, new android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS));
    }
}
