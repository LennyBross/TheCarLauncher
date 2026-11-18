package com.catarapatara;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Lets MYDS switch the sound: extra "cmd" = toggle / on / off, or "rev" = true/false while standing. */
public final class ControlReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i.hasExtra("rev")) {
            EngineService s = EngineService.get();
            if (s != null) s.rev(i.getBooleanExtra("rev", false));
            return;
        }
        String cmd = i.getStringExtra("cmd");
        if (cmd == null) return;
        boolean on = EngineService.prefs(c).getBoolean("on", false);
        boolean want = "toggle".equals(cmd) ? !on : "on".equals(cmd);
        try {
            if (want != on) EngineService.toggle(c);
            else EngineService.start(c);
        } catch (Exception ignored) {
        }
    }
}
