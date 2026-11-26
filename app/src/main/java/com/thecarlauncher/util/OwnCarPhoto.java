package com.thecarlauncher.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * The owner's own photo of the car, shown on the home pages instead of the built-in view.
 * Stays on the unit: the picked picture is scaled down (raw), the page cuts the background
 * out in a canvas and hands back the finished PNG (cut). Nothing leaves the app's files.
 */
public final class OwnCarPhoto {
    private static final int MAX = 1280;

    private OwnCarPhoto() {}

    private static File raw(Context c) { return new File(c.getFilesDir(), "owncar_raw.jpg"); }
    private static File cut(Context c) { return new File(c.getFilesDir(), "owncar.png"); }

    /** Scales a picked picture down and keeps it as the raw copy; true when it was usable. */
    public static boolean take(Context c, Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            if (o.outWidth <= 0) return false;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            in = c.getContentResolver().openInputStream(uri);
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            in.close();
            if (b == null) return false;
            float f = Math.min(1f, MAX / (float) Math.max(b.getWidth(), b.getHeight()));
            if (f < 1f) b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * f), Math.round(b.getHeight() * f), true);
            FileOutputStream fo = new FileOutputStream(raw(c));
            b.compress(Bitmap.CompressFormat.JPEG, 90, fo);
            fo.close();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static String rawData(Context c) { return data(raw(c), "image/jpeg"); }
    public static String cutData(Context c) { return data(cut(c), "image/png"); }

    /** Stores the finished cut-out (a data: URL from the page). */
    public static boolean save(Context c, String dataUrl) {
        try {
            int i = dataUrl.indexOf(',');
            byte[] png = Base64.decode(dataUrl.substring(i + 1), Base64.DEFAULT);
            if (png.length < 100 || png.length > 6_000_000) return false;
            FileOutputStream fo = new FileOutputStream(cut(c));
            fo.write(png);
            fo.close();
            Prefs.put(c, "owncar_pending", false);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** A picture taken from the stick that the page has not cut out yet. */
    public static boolean pending(Context c) { return Prefs.bool(c, "owncar_pending", false) && raw(c).exists(); }

    /**
     * Looks on the USB sticks / SD cards for the owner's car picture: a file called arac / araç /
     * car / ds4 / myds (.jpg .jpeg .png .webp) in the stick's top folder or in a MYDS folder.
     * A new or changed file is taken over; true when it was.
     */
    public static boolean fromUsb(Context c) {
        File best = null;
        try {
            for (File root : Storage.removableRoots(c)) {
                for (File dir : new File[]{root, new File(root, "MYDS"), new File(root, "myds"), new File(root, "MyDS")}) {
                    File[] fs = dir.listFiles();
                    if (fs == null) continue;
                    for (File f : fs) {
                        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                        int dot = n.lastIndexOf('.');
                        if (dot < 1 || !f.isFile()) continue;
                        String base = n.substring(0, dot), ext = n.substring(dot + 1);
                        if (!(ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("webp"))) continue;
                        if (!(base.startsWith("arac") || base.startsWith("araç") || base.startsWith("car")
                                || base.startsWith("ds4") || base.startsWith("myds"))) continue;
                        if (best == null || f.lastModified() > best.lastModified()) best = f;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (best == null) return false;
        String sig = best.getPath() + "|" + best.length() + "|" + best.lastModified();
        if (sig.equals(Prefs.str(c, "owncar_src", ""))) return false;
        if (!take(c, Uri.fromFile(best))) return false;
        Prefs.put(c, "owncar_src", sig);
        Prefs.put(c, "owncar_pending", true);
        return true;
    }

    public static void clear(Context c) {
        Prefs.put(c, "owncar_pending", false);
        Prefs.put(c, "owncar_src", "");
        //noinspection ResultOfMethodCallIgnored
        cut(c).delete();
        //noinspection ResultOfMethodCallIgnored
        raw(c).delete();
    }

    private static String data(File f, String mime) {
        if (!f.exists()) return "";
        try {
            InputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return "data:" + mime + ";base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable e) {
            return "";
        }
    }
}
