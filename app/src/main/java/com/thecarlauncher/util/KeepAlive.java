package com.thecarlauncher.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps MYDS alive while other apps (Android Auto via ZLink, Spotify…) fill the screen.
 *
 * A home app in the background sits low in Android's kill order: on a 2 GB unit with projection
 * and music running, the system closes it and the driver comes back to a restarting launcher.
 * A small foreground service lifts the whole MYDS process to "perceptible", which the low-memory
 * killer leaves alone.
 */
public class KeepAlive extends Service {
    private static final String CH = "myds_keepalive";
    private static final int ID = 4711;

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, KeepAlive.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try { ObdHub.start(this); } catch (Throwable ignored) { }
        try { CarBody.start(this); } catch (Throwable ignored) { }
        try { RemoteSupport.start(this); } catch (Throwable ignored) { }
        try { AutoReturn.ensureAccess(this); } catch (Throwable ignored) { }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH, "MYDS", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
                b = new Notification.Builder(this, CH);
            } else {
                b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
            }
            Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            b.setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setContentTitle("MYDS")
                    .setContentText("Arka planda hazır")
                    .setOngoing(true);
            if (open != null) b.setContentIntent(PendingIntent.getActivity(this, 0, open, fl));
            startForeground(ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
