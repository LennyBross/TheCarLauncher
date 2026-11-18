package com.catarapatara;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings the floating button (and the sound, if it was on) back after the unit starts. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!EngineService.prefs(c).getBoolean("boot", true)) return;
        if (!Bubble.wanted(c) && !EngineService.prefs(c).getBoolean("on", false)) return;
        try {
            EngineService.start(c);
        } catch (Exception ignored) {
            // Some units block starting services this early; the app starts it when opened.
        }
    }
}
