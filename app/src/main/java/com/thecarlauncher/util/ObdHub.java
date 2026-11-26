package com.thecarlauncher.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

import com.catarapatara.Obd;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The OBD-II adapter (Bluetooth ELM327 in the OBD socket), read by MYDS itself and always on:
 * engine data for the screens (coolant, boost, rail pressure, battery, fault codes…) and rpm /
 * pedal / speed for the CataraPatara engine sound. Only one program may talk to the adapter, so
 * CataraPatara no longer opens it; its OBD settings (prefs "cp") are still the ones used here.
 * Read-only: mode 01 / 03 requests, nothing is written to the car.
 */
public final class ObdHub implements Obd.Listener {
    private static ObdHub sInstance;

    public static synchronized void start(Context c) {
        if (sInstance == null) sInstance = new ObdHub(c.getApplicationContext());
        sInstance.apply();
    }

    public static ObdHub get() { return sInstance; }

    private final Context app;
    private Obd obd;
    private String applied = "";
    private final Map<String, Float> val = new ConcurrentHashMap<>();
    private final Map<String, Long> at = new ConcurrentHashMap<>();
    private volatile String status = "", dtc = "";
    private volatile boolean ok;
    private volatile float rpm = -1, speed = -1, pedal = -1;
    private volatile long rpmAt, speedAt, pedalAt, lastSendAt;

    private ObdHub(Context c) {
        app = c;
        IntentFilter f = new IntentFilter("com.thecarlauncher.OBD_APPLY");
        BroadcastReceiver r = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { apply(); }
        };
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED); else app.registerReceiver(r, f);
        // A newly paired adapter is picked up without a restart.
        new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try { Thread.sleep(15000); } catch (InterruptedException e) { return; }
                    if (obd == null || PhoneLink.connected()) { applied = ""; apply(); }
                }
            }
        }, "obd-watch").start();
        // trip computer: once a second while the adapter answers
        new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                    try { if (connected()) ObdFuel.get(app).tick(ObdHub.this, -1); } catch (Throwable ignored) { }
                }
            }
        }, "obd-fuel").start();
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences cp() { return app.getSharedPreferences("cp", Context.MODE_MULTI_PROCESS); }

    synchronized void apply() {
        SharedPreferences p = cp();
        String mode = p.getString("obd", "auto");
        if ("auto".equals(mode)) mode = Obd.pairedAdapter() != null ? Obd.BT : "off";
        // "ble": a BLE adapter (VehiConn…) straight from the unit, no pairing (chosen on the Motor screen).
        if ("ble".equals(mode)) mode = Obd.BT;
        if (scanning) mode = "off";
        // The phone bridge is feeding us: the adapter is busy with the phone, leave it alone.
        if (PhoneLink.connected()) mode = "off";
        String key = mode + "|" + p.getString("obdTarget", "") + "|" + p.getString("obdProto", Obd.STD);
        if (key.equals(applied) && (obd != null || "off".equals(mode))) return;
        applied = key;
        if (obd != null) obd.stop();
        obd = null;
        ok = false;
        status = "off".equals(mode) ? "" : "OBD: hazırlanıyor…";
        if (Obd.WIFI.equals(mode) || Obd.BT.equals(mode)) {
            Obd.ctx = app;
            obd = new Obd(this);
            // full read-only set: rpm every round, speed, MAF, load, fuel rate / level, temperatures…
            obd.rpmOnly = cp().getBoolean("obdRpmOnly", false);
            obd.pin = p.getString("obdPin", "1234");
            obd.start(mode, p.getString("obdTarget", ""), p.getString("obdProto", Obd.STD));
        }
    }

    private volatile long phoneAt;

    // ---- Adapter search on the unit (like Car Scanner): classic discovery + BLE scan, unpaired too.

    private volatile boolean scanning;
    private final Map<String, JSONObject> found = new ConcurrentHashMap<>();

    @SuppressWarnings("MissingPermission")
    public synchronized void scan() {
        if (scanning) return;
        final android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        if (a == null) { status = "Bluetooth yok"; return; }
        scanning = true;
        found.clear();
        applied = "";
        apply();   // frees the adapter while searching
        try {
            if (!a.isEnabled()) a.enable();
            for (android.bluetooth.BluetoothDevice d : a.getBondedDevices()) add(d, 0, false, true);
        } catch (Throwable ignored) {
        }
        final BroadcastReceiver r = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                android.bluetooth.BluetoothDevice d = i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
                if (d != null) add(d, i.getShortExtra(android.bluetooth.BluetoothDevice.EXTRA_RSSI, (short) 0), false, false);
            }
        };
        final android.bluetooth.le.ScanCallback cb = new android.bluetooth.le.ScanCallback() {
            @Override public void onScanResult(int t, android.bluetooth.le.ScanResult res) {
                String n = res.getScanRecord() == null ? null : res.getScanRecord().getDeviceName();
                add(res.getDevice(), res.getRssi(), true, false, n);
            }
        };
        try { app.registerReceiver(r, new IntentFilter(android.bluetooth.BluetoothDevice.ACTION_FOUND)); } catch (Throwable ignored) { }
        try { a.startDiscovery(); } catch (Throwable ignored) { }
        try { if (a.getBluetoothLeScanner() != null) a.getBluetoothLeScanner().startScan(cb); } catch (Throwable ignored) { }
        status = "Adaptör aranıyor…";
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                try { a.cancelDiscovery(); } catch (Throwable ignored) { }
                try { if (a.getBluetoothLeScanner() != null) a.getBluetoothLeScanner().stopScan(cb); } catch (Throwable ignored) { }
                try { app.unregisterReceiver(r); } catch (Throwable ignored) { }
                scanning = false;
                status = found.isEmpty() ? "Cihaz bulunamadı (konum izni / kontak?)" : "Listeden adaptörü seç";
                applied = "";
                apply();
            }
        }, 12000);
    }

    private void add(android.bluetooth.BluetoothDevice d, int rssi, boolean le, boolean bonded) { add(d, rssi, le, bonded, null); }

    @SuppressWarnings("MissingPermission")
    private void add(android.bluetooth.BluetoothDevice d, int rssi, boolean le, boolean bonded, String n2) {
        try {
            String n = d.getName();
            if (n == null || n.isEmpty()) n = n2;
            if (n == null || n.isEmpty()) return;   // nameless beacons are not adapters
            JSONObject o = found.get(d.getAddress());
            if (o == null) o = new JSONObject().put("addr", d.getAddress());
            o.put("name", n).put("rssi", rssi).put("bonded", bonded || d.getBondState() == android.bluetooth.BluetoothDevice.BOND_BONDED);
            if (le) o.put("le", true); else o.put("classic", true);
            String low = n.toLowerCase(java.util.Locale.ROOT);
            o.put("obd", low.contains("obd") || low.contains("elm") || low.contains("vehiconn") || low.contains("vlink") || low.contains("veepeak") || low.contains("vgate") || low.contains("konnwei") || low.contains("icar"));
            found.put(d.getAddress(), o);
        } catch (Throwable ignored) {
        }
    }

    /** The driver picked a device from the list: connect to it (classic, or BLE if le). */
    public void pick(String addr, boolean le) {
        cp().edit().putString("obd", "bt").putString("obdTarget", addr + (le ? "|le" : "")).apply();
        applied = "";
        apply();
    }

    public void setPin(String pin) {
        cp().edit().putString("obdPin", pin == null || pin.trim().isEmpty() ? "1234" : pin.trim()).apply();
        applied = "";
        apply();
    }

    /** Motor screen: "ble" = connect a BLE adapter directly, "auto" = only a paired one / the phone. */
    public void setMode(String m) {
        cp().edit().putString("obd", m).apply();
        applied = "";
        apply();
    }

    public String mode() { return cp().getString("obd", "auto"); }

    public void readDtc() {
        Obd o = obd;
        if (o != null) o.readDtc();
        else if (System.currentTimeMillis() - phoneAt < 10000) { try { PhoneLink.send(new JSONObject().put("t", "dtc")); } catch (Exception ignored) { } }
    }

    /** Values from the ELM327 that the phone (MYDS Telefon) reads and passes on. */
    public void fromPhone(JSONObject o) {
        phoneAt = System.currentTimeMillis();
        if (o.has("st")) { onStatus(o.optString("st"), o.optBoolean("ok")); return; }
        if (o.has("dtc")) { onDtc(o.optString("dtc")); return; }
        String k = o.optString("k");
        float v = (float) o.optDouble("v", Double.NaN);
        if (Float.isNaN(v)) return;
        if (!ok) { ok = true; status = "OBD · telefon"; }
        switch (k) {
            case "rpm": onRpm(v); break;
            case "pedal": onPedal(v); break;
            case "speed": onSpeed(v); break;
            default: onPid(k, v);
        }
    }

    // ---- from the adapter ----

    @Override public void onRpm(float r) {
        long now = System.currentTimeMillis();
        // how fast the revs climb (rpm per second, smoothed): the driver's demand when no pedal value comes
        if (rpmAt > 0 && now - rpmAt < 1500 && now > rpmAt) {
            float rate = (r - rpm) * 1000f / (now - rpmAt);
            rise += (rate - rise) * 0.35f;
        }
        rpm = r; rpmAt = now; send(false);
    }
    private volatile float rise;

    /**
     * Driver's demand 0…1 for the engine sound: the accelerator pedal when the ECU gives it, else the
     * calculated load, else read from the revs (climbing = on the gas; cruising at speed = part gas).
     */
    public float demand() {
        long now = System.currentTimeMillis();
        float d = 0;
        float load = value("load");
        if (!Float.isNaN(load)) d = Math.max(d, (load - 12f) / 70f);
        if (now - pedalAt < 3000 && pedal > 0.16f) d = Math.max(d, pedal);   // 0x11 on a diesel sits at 12.5 %: not a pedal
        float r = rpm(), v = speed();
        if (r > 0) {
            d = Math.max(d, rise / 900f);                                   // +900 rpm/s ≈ full gas
            if (v > 8 && r > 1250 && rise > -150) d = Math.max(d, 0.4f);   // cruising: part gas, not coasting
            if (r > 2600) d = Math.max(d, 0.55f);
        }
        return Math.max(0, Math.min(1, d));
    }
    @Override public void onPedal(float p) { pedal = p; pedalAt = System.currentTimeMillis(); }
    @Override public void onSpeed(float kmh) { speed = kmh; speedAt = System.currentTimeMillis(); }
    @Override public void onStatus(String t, boolean good) { status = t; ok = good; send(true); }
    @Override public void onPid(String k, float v) { val.put(k, v); at.put(k, System.currentTimeMillis()); }
    @Override public void onDtc(String codes) { dtc = codes == null ? "" : codes; }

    /** rpm / pedal / speed to the engine sound (same broadcast as the CAN bridge uses). */
    private void send(boolean statusOnly) {
        long now = System.currentTimeMillis();
        try {
            Intent i = new Intent("com.catarapatara.DATA").setPackage(app.getPackageName()).putExtra("obdStatus", status);
            if (!statusOnly) {
                if (now - lastSendAt < 60) return;
                lastSendAt = now;
                i.putExtra("rpm", rpm).putExtra("rpmMax", (float) cp().getInt("carMax", 4500));
                if (now - pedalAt < 2000) i.putExtra("pedal", pedal);
                if (now - speedAt < 3000) i.putExtra("speed", speed);
            }
            app.sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    // ---- for the screens ----

    public boolean connected() { return ok && System.currentTimeMillis() - rpmAt < 2000; }

    /** Engine rpm if it came from the ECU in the last 2 s, else -1 (never an old value as live). */
    public float rpm() { return System.currentTimeMillis() - rpmAt < 2000 ? rpm : -1; }

    public long rpmAge() { return rpmAt == 0 ? -1 : System.currentTimeMillis() - rpmAt; }

    public String status() { return status; }

    public float speed() { return System.currentTimeMillis() - speedAt < 4000 ? speed : -1; }

    /** A value if it came in the last minute, else NaN. */
    public float value(String k) {
        Long t = at.get(k);
        Float v = val.get(k);
        return t == null || v == null || System.currentTimeMillis() - t > 60000 ? Float.NaN : v;
    }

    public JSONObject json() {
        JSONObject o = new JSONObject();
        try {
            o.put("on", obd != null || System.currentTimeMillis() - phoneAt < 10000).put("ok", connected()).put("st", status).put("mode", mode());
            JSONObject v = new JSONObject();
            float r = rpm();
            if (r >= 0) v.put("rpm", Math.round(r));
            float s = speed();
            if (s >= 0) v.put("speed", Math.round(s));
            if (System.currentTimeMillis() - pedalAt < 3000) v.put("pedal", Math.round(pedal * 100));
            for (String k : val.keySet()) {
                float f = value(k);
                if (!Float.isNaN(f)) v.put(k, Math.round(f * 10) / 10.0);
            }
            o.put("v", v);
            JSONArray d = new JSONArray();
            if (!dtc.isEmpty()) for (String c : dtc.split(",")) d.put(c);
            o.put("dtc", d);
            o.put("scan", scanning).put("target", cp().getString("obdTarget", "")).put("pin", cp().getString("obdPin", "1234"));
            JSONArray fl = new JSONArray();
            for (JSONObject f : found.values()) fl.put(f);
            o.put("devs", fl);
        } catch (Exception ignored) {
        }
        return o;
    }
}
