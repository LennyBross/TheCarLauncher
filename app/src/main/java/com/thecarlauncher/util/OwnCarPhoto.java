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
            //noinspection ResultOfMethodCallIgnored
            raw(c).delete();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void clear(Context c) {
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
