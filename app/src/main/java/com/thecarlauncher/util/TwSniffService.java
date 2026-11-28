package com.thecarlauncher.util;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Runs the TWUtil listener in its own process (":twsniff"): if the vendor library crashes, only this
 * process dies and MyDS keeps running. Results are written to files the console reads.
 */
public class TwSniffService extends Service {
    static File f(Context c, String n) { return new File(c.getFilesDir(), n); }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        final String spec = i == null ? "" : String.valueOf(i.getStringExtra("spec"));
        write(f(this, "tw_status.txt"), "açılıyor " + spec);
        new Thread(new Runnable() { public void run() {
            String st = TwSniff.start(spec);
            write(f(TwSniffService.this, "tw_status.txt"), st);
            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                write(f(TwSniffService.this, "tw_log.txt"), TwSniff.dump("1500"));
                write(f(TwSniffService.this, "tw_kinds.txt"), TwSniff.kinds());
            }
        } }, "twsniff-io").start();
        return START_NOT_STICKY;
    }

    static void write(File f, String s) {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); } catch (Throwable ignored) { }
    }

    static String read(Context c, String n) {
        try { byte[] b = java.nio.file.Files.readAllBytes(f(c, n).toPath()); return new String(b, StandardCharsets.UTF_8); } catch (Throwable t) { return "(yok)"; }
    }

    /** Console side: start in the separate process. */
    public static String launch(Context c, String spec) {
        f(c, "tw_log.txt").delete(); f(c, "tw_kinds.txt").delete();
        c.startService(new Intent(c, TwSniffService.class).putExtra("spec", spec));
        return "ayrı süreçte başlatıldı (" + spec + "), 3 sn sonra twdurum";
    }

    public static String state(Context c) {
        boolean alive = false;
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) if (p.processName.endsWith(":twsniff")) alive = true;
        return "süreç " + (alive ? "çalışıyor" : "YOK (çökmüş olabilir)") + " · " + read(c, "tw_status.txt");
    }

    public static String kill(Context c) {
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) if (p.processName.endsWith(":twsniff")) android.os.Process.killProcess(p.pid);
        return "durduruldu";
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
