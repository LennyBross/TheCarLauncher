package com.thecarlauncher.phone;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.util.List;

/**
 * Notification access: the phone's media sessions (Spotify, YouTube Music, any player) and the
 * turn card Google Maps shows while navigating. Everything goes to the head unit; media keys
 * from the unit come back here.
 */
public final class MediaWatch extends NotificationListenerService {
    static final String MAPS = "com.google.android.apps.maps", WAZE = "com.waze";
    private static volatile MediaWatch sInstance;
    private MediaSessionManager msm;
    private MediaController cur;
    private String lastArt = "";
    private String lastNavKey = "";

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = new MediaSessionManager.OnActiveSessionsChangedListener() {
        public void onActiveSessionsChanged(List<MediaController> l) { pick(l); }
    };

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { sendMedia(false); }
        @Override public void onMetadataChanged(MediaMetadata m) { sendMedia(true); }
        @Override public void onSessionDestroyed() { pick(null); }
    };

    static boolean enabled(Context c) {
        String s = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }

    @Override
    public void onListenerConnected() {
        sInstance = this;
        try {
            msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, MediaWatch.class);
            msm.addOnActiveSessionsChangedListener(changed, me);
            pick(msm.getActiveSessions(me));
        } catch (Throwable ignored) {
        }
        try { LinkService.start(this); } catch (Throwable ignored) { }
    }

    @Override
    public void onListenerDisconnected() {
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(changed); } catch (Throwable ignored) { }
        sInstance = null;
    }

    private List<MediaController> sessions() {
        try { return msm.getActiveSessions(new ComponentName(this, MediaWatch.class)); } catch (Throwable t) { return null; }
    }

    /** Follow the session that is playing (else the first one). */
    private void pick(List<MediaController> l) {
        if (l == null) l = sessions();
        MediaController best = null;
        if (l != null) {
            for (MediaController c : l) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
            }
            if (best == null && !l.isEmpty()) best = l.get(0);
        }
        if (cur != null && best != null && cur.getSessionToken().equals(best.getSessionToken())) return;
        if (cur != null) try { cur.unregisterCallback(cb); } catch (Throwable ignored) { }
        cur = best;
        if (cur != null) cur.registerCallback(cb);
        lastArt = "";
        sendMedia(true);
    }

    static void resend() {
        MediaWatch w = sInstance;
        if (w != null) { w.lastArt = ""; w.sendMedia(true); }
    }

    private void sendMedia(boolean withArt) {
        try {
            MediaController c = cur;
            JSONObject o = new JSONObject().put("t", "media");
            if (c == null) { LinkService.send(o.put("title", "")); return; }
            MediaMetadata m = c.getMetadata();
            PlaybackState st = c.getPlaybackState();
            String pkg = c.getPackageName();
            o.put("pkg", pkg).put("app", label(pkg));
            if (m != null) {
                o.put("title", nz(m.getString(MediaMetadata.METADATA_KEY_TITLE)));
                String ar = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (ar == null) ar = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                o.put("artist", nz(ar)).put("dur", m.getLong(MediaMetadata.METADATA_KEY_DURATION));
            }
            if (st != null) {
                long p = st.getPosition();
                if (st.getState() == PlaybackState.STATE_PLAYING) p += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                o.put("playing", st.getState() == PlaybackState.STATE_PLAYING).put("pos", Math.max(0, p));
            }
            LinkService.send(o);
            if (withArt && m != null) {
                Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                String j = LinkService.jpeg(b, 320);
                if (!j.equals(lastArt)) {
                    lastArt = j;
                    LinkService.send(new JSONObject().put("t", "art").put("jpg", j));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Media key from the unit: play / pause / prev / next; pkg = start that player. */
    static void control(Context ctx, String a, String pkg) {
        MediaWatch w = sInstance;
        if (w == null) return;
        MediaController target = null;
        List<MediaController> l = w.sessions();
        if (pkg != null && l != null) for (MediaController c : l) if (pkg.equals(c.getPackageName())) { target = c; break; }
        if (pkg == null) target = w.cur;
        if (target != null) {
            MediaController.TransportControls t = target.getTransportControls();
            PlaybackState st = target.getPlaybackState();
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            switch (a) {
                case "next": t.skipToNext(); break;
                case "prev": t.skipToPrevious(); break;
                case "pause": t.pause(); break;
                case "play":
                    if (pkg == null && playing) t.pause(); else t.play();
                    break;
                default: if (playing) t.pause(); else t.play();
            }
            return;
        }
        if (pkg != null) {
            // Player not running yet: a media button wakes it (Spotify, YouTube Music listen for it).
            for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                        .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY));
                try { ctx.sendOrderedBroadcast(i, null); } catch (Throwable ignored) { }
            }
        }
    }

    // ---- Google Maps (and Waze) turn card

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String p = sbn.getPackageName();
        if (!MAPS.equals(p) && !WAZE.equals(p)) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return;
        Bundle e = n.extras;
        try {
            String dist = str(e.getCharSequence(Notification.EXTRA_TITLE));
            String text = str(e.getCharSequence(Notification.EXTRA_TEXT));
            String eta = str(e.getCharSequence(Notification.EXTRA_SUB_TEXT));
            if (dist.isEmpty() && text.isEmpty()) return;
            JSONObject o = new JSONObject().put("t", "nav").put("dist", dist).put("text", text).put("eta", eta);
            Bitmap arrow = bitmap(n.getLargeIcon());
            String key = dist.replaceAll("[0-9.,]", "") + "|" + text;
            if (arrow != null && !key.equals(lastNavKey)) o.put("arrow", LinkService.png(arrow));
            lastNavKey = key;
            LinkService.send(o);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!MAPS.equals(sbn.getPackageName()) && !WAZE.equals(sbn.getPackageName())) return;
        if ((sbn.getNotification().flags & Notification.FLAG_ONGOING_EVENT) == 0) return;
        lastNavKey = "";
        try { LinkService.send(new JSONObject().put("t", "nav").put("end", true)); } catch (Throwable ignored) { }
    }

    private Bitmap bitmap(Icon ic) {
        if (ic == null) return null;
        try {
            Drawable d = ic.loadDrawable(this);
            if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
            int w = Math.max(1, Math.min(256, d.getIntrinsicWidth())), h = Math.max(1, Math.min(256, d.getIntrinsicHeight()));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, w, h);
            d.draw(new Canvas(b));
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(CharSequence c) { return c == null ? "" : c.toString(); }
}
