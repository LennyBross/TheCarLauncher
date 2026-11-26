package com.thecarlauncher.radio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;

/**
 * The factory FM tuner of HCN head units, driven through the radio app's own exported service:
 * {@code com.hcn.autoradio/.service.FMPlugService}, Binder interface {@code com.hcn.autoradio.IRadioServiceAPI}.
 * The radio app's process owns the analog FM audio path, so the tuner is asked, not replaced.
 *
 * Two contracts carry the same interface name; calls 1–16 are the same, then they differ:
 *  - 31 calls (current firmware): 17 favourite, 18 band, 19 frequency, 20 RDS name, … 27 stereo,
 *    29 requestPlayAudio, 30 requestAudioFocus, 31 releaseAudioFocus;
 *  - 25 calls (older firmware): 17 band, 18 frequency, 19 IsAS, … 23 stereo, 25 requestPlayAudio.
 * They are told apart by reading: call 19 is a frequency only on the 31-call contract.
 *
 * Interface layout and contract notes from Radio+ (github.com/edman01/radio-plus-open, MIT License,
 * Copyright (c) 2026 Radio+ contributors). This class is MYDS's own implementation.
 */
public final class HcnService {
    private static final String PKG = "com.hcn.autoradio";
    private static final String DESCRIPTOR = "com.hcn.autoradio.IRadioServiceAPI";
    private static final String CALLBACK = "com.hcn.autoradio.IRadioCallBack";
    private static final String[] ACTIONS = {"com.hcn.autoradio.FM_PLUG_SERVICE", "com.hcn.radio.FM_PLUG_SERVICE"};

    // shared calls
    private static final int REGISTER_CALLBACK = 3, UNREGISTER_CALLBACK = 4, SEEK_DOWN = 9, SEEK_UP = 10,
            MANUAL_UP = 11, MANUAL_DOWN = 12, GOTO_FREQ = 14;
    // callback events
    private static final int EV_FREQ = 100, EV_BAND = 101, EV_RDS_NAME = 103;

    private static HcnService sInstance;

    /** The instance if one was made (no Context at hand), else null. */
    public static HcnService peek() { return sInstance; }

    public static synchronized HcnService get(Context c) {
        if (sInstance == null) sInstance = new HcnService(c.getApplicationContext());
        return sInstance;
    }

    private final Context app;
    private final Handler worker;
    private volatile IBinder service;
    private volatile int contract;          // 31, 25, or 0 = unknown / not usable
    private volatile int unit = 10;         // kHz per raw frequency step (10 → 9850 = 98.50 MHz)
    private volatile int freqKhz, band;
    private volatile String ps = "";
    private volatile boolean binding, stereo;
    private int action;
    private volatile String how = "";

    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == FIRST_CALL_TRANSACTION) {
                try {
                    data.enforceInterface(CALLBACK);
                    int ev = data.readInt();
                    String v = data.readString();
                    onEvent(ev, v == null ? "" : v.trim());
                } catch (Throwable ignored) { }
                return true;
            }
            try { return super.onTransact(code, data, reply, flags); } catch (Throwable t) { return false; }
        }
    };

    private HcnService(Context c) {
        app = c;
        HandlerThread t = new HandlerThread("hcn-radio");
        t.start();
        worker = new Handler(t.getLooper());
    }

    /** True when the radio app is installed on this unit. */
    public boolean installed() {
        try { app.getPackageManager().getPackageInfo(PKG, 0); return true; } catch (Throwable t) { return false; }
    }

    /** Connected to the tuner service with a known contract. */
    public boolean ready() { return service != null && contract != 0; }

    public int contract() { return contract; }
    public String how() { return how; }

    /** Connect (starting the service if create = true; starting it may take the speakers). */
    public void connect(boolean create) {
        if (service != null || binding || !installed()) return;
        binding = true;
        Intent it = new Intent(ACTIONS[action]).setComponent(new ComponentName(PKG, PKG + ".service.FMPlugService"))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        try {
            if (!app.bindService(it, conn, create ? Context.BIND_AUTO_CREATE : 0)) binding = false;
        } catch (Throwable t) {
            binding = false;
        }
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, final IBinder b) {
            worker.post(new Runnable() { public void run() { attach(b); } });
        }
        @Override public void onServiceDisconnected(ComponentName n) { service = null; contract = 0; binding = false; }
        @Override public void onBindingDied(ComponentName n) {
            service = null; contract = 0; binding = false;
            try { app.unbindService(this); } catch (Throwable ignored) { }
        }
    };

    private void attach(IBinder b) {
        binding = false;
        try {
            if (!DESCRIPTOR.equals(b.getInterfaceDescriptor())) throw new IllegalStateException("descriptor");
        } catch (Throwable t) {
            next();
            return;
        }
        // Just started, the tuner may still say 0: give it a moment.
        int found = 0;
        for (int i = 0; i < 8 && found == 0; i++) {
            Integer f31 = readInt(b, 19), f25 = readInt(b, 18);
            if (f31 != null && plausible(f31)) found = 31;
            else if (f25 != null && plausible(f25)) found = 25;
            else try { Thread.sleep(250); } catch (InterruptedException ignored) { }
        }
        if (found == 0) { how = "unknown contract"; next(); return; }
        service = b;
        contract = found;
        how = "service " + found;
        try {
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken(DESCRIPTOR);
                d.writeStrongBinder(callback);
                b.transact(REGISTER_CALLBACK, d, r, 0);
                r.readException();
            } finally { d.recycle(); r.recycle(); }
        } catch (Throwable ignored) { }
        refresh();
    }

    /** That action did not give the interface: try the other one, else stay on the old path. */
    private void next() {
        try { app.unbindService(conn); } catch (Throwable ignored) { }
        service = null;
        contract = 0;
        if (action + 1 < ACTIONS.length) { action++; connect(true); }
    }

    /** FM 87.5–108 MHz or AM 522–1710 kHz in any of the units firmwares use. */
    private boolean plausible(int raw) {
        if (raw >= 8750 && raw <= 10800) { unit = 10; return true; }        // 10 kHz steps
        if (raw >= 87500 && raw <= 108000) { unit = 1; return true; }       // kHz
        if (raw >= 875 && raw <= 1080) { unit = 100; return true; }         // 100 kHz steps
        return raw >= 522 && raw <= 1710;                                   // AM, kHz
    }

    private int toKhz(int raw) {
        if (raw >= 522 && raw <= 1710 && !(raw >= 875 && raw <= 1080 && unit == 100)) return raw;   // AM
        return raw * unit;
    }

    private int fromKhz(int khz) { return khz < 2000 ? khz : Math.round(khz / (float) unit); }

    // ---- state --------------------------------------------------------------------------------

    /** [freq kHz, stereo 0/1, -1] or null when not connected. */
    public int[] info() {
        if (!ready() || freqKhz <= 0) return null;
        return new int[]{freqKhz, stereo ? 1 : 0, -1};
    }

    public String ps() { return ps; }

    public String band() { return freqKhz > 0 && freqKhz < 2000 ? "AM" : "FM"; }

    /** Reads frequency, RDS name and stereo again (on the worker thread). */
    public void refresh() {
        worker.post(new Runnable() { public void run() {
            IBinder b = service;
            if (b == null) return;
            Integer f = readInt(b, contract == 31 ? 19 : 18);
            if (f != null && plausible(f)) {
                int k = toKhz(f);
                if (k != freqKhz) ps = "";
                freqKhz = k;
            }
            Integer st = readInt(b, contract == 31 ? 27 : 23);
            if (st != null) stereo = st != 0;
            if (contract == 31) {
                String s = readString(b, 20);
                if (s != null) ps = s.trim();
            }
        } });
    }

    private void onEvent(int ev, String v) {
        if (ev == EV_FREQ) {
            try {
                int raw = Integer.parseInt(v.replaceAll("[^0-9]", ""));
                if (plausible(raw)) { int k = toKhz(raw); if (k != freqKhz) ps = ""; freqKhz = k; }
            } catch (Throwable ignored) { }
        } else if (ev == EV_RDS_NAME) {
            ps = v;
        } else if (ev == EV_BAND) {
            refresh();
        }
    }

    // ---- commands -----------------------------------------------------------------------------

    /** Makes the tuner the audio source (without opening the radio app). */
    public void play() {
        worker.post(new Runnable() { public void run() {
            IBinder b = service;
            if (b == null) return;
            Integer ok = readInt(b, contract == 31 ? 29 : 25);
            if (contract == 31 && ok != null && ok != 0) call(b, 30, null);   // also unmutes on this firmware
        } });
    }

    /** Gives the speakers back (31-call firmware); the older one has no call for it. */
    public boolean release() {
        if (!ready() || contract != 31) return false;
        worker.post(new Runnable() { public void run() { IBinder b = service; if (b != null) call(b, 31, null); } });
        return true;
    }

    public void tune(final int khz) {
        worker.post(new Runnable() { public void run() {
            IBinder b = service;
            if (b == null) return;
            final int raw = fromKhz(khz);
            call(b, GOTO_FREQ, new Writer() { public void write(Parcel d) { d.writeInt(raw); } });
            freqKhz = khz; ps = "";
        } });
        worker.postDelayed(new Runnable() { public void run() { refresh(); } }, 400);
    }

    public void seek(final boolean up) {
        worker.post(new Runnable() { public void run() { IBinder b = service; if (b != null) call(b, up ? SEEK_UP : SEEK_DOWN, null); } });
        for (int ms : new int[]{900, 2000, 4000}) worker.postDelayed(new Runnable() { public void run() { refresh(); } }, ms);
    }

    public void step(final boolean up) {
        worker.post(new Runnable() { public void run() { IBinder b = service; if (b != null) call(b, up ? MANUAL_UP : MANUAL_DOWN, null); } });
        worker.postDelayed(new Runnable() { public void run() { refresh(); } }, 300);
    }

    // ---- raw Binder calls (no stub classes) ---------------------------------------------------

    private interface Writer { void write(Parcel d); }

    private static boolean call(IBinder b, int code, Writer w) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            if (w != null) w.write(d);
            if (!b.transact(code, d, r, 0)) return false;
            r.readException();
            return true;
        } catch (Throwable t) {
            return false;
        } finally { d.recycle(); r.recycle(); }
    }

    private static Integer readInt(IBinder b, int code) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            if (!b.transact(code, d, r, 0)) return null;
            r.readException();
            return r.readInt();
        } catch (Throwable t) {
            return null;
        } finally { d.recycle(); r.recycle(); }
    }

    private static String readString(IBinder b, int code) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            if (!b.transact(code, d, r, 0)) return null;
            r.readException();
            return r.readString();
        } catch (Throwable t) {
            return null;
        } finally { d.recycle(); r.recycle(); }
    }
}
