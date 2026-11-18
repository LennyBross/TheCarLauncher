package com.catarapatara.canbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the bridge with the unit. */
public final class Boot extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            BridgeService.start(c);
        } catch (Exception ignored) {
        }
    }
}
