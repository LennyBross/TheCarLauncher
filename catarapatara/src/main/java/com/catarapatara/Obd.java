package com.catarapatara;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.UUID;

/**
 * Reads engine rpm, accelerator pedal and speed from an ELM327 OBD-II adapter (Wi-Fi or
 * Bluetooth) — read-only, standard mode 01 requests only, nothing is ever written to the car.
 *
 * PSA mode: the adapter only listens (silent monitor, no ACK, no requests) to the 125 kbit/s
 * comfort / infotainment CAN behind the head unit and decodes frame 0x0B6 FAST_BSI_DATA
 * (engine rpm, vehicle speed, fuel counter). Frame layout from PSA-RE
 * (github.com/prototux/PSA-RE, Apache-2.0): buses/AEE2010.full/LS.INFO-DIV/0B6.yml.
 */
public final class Obd {
    public interface Listener {
        void onRpm(float rpm);
        void onPedal(float pedal01);
        void onSpeed(float kmh);
        void onStatus(String text, boolean ok);
        /** Any other live value (key → value in display units, see SLOW). */
        void onPid(String key, float value);
        /** Stored fault codes ("P0401,P2463"), empty when none. */
        void onDtc(String codes);
    }

    /**
     * Slower values, one per round so rpm stays fast: {pid, key}. Decoded in value(). Only PIDs the
     * engine reports as supported (0100/0120/0140/0160 bitmaps) are asked. Tuned for the DS4 1.6
     * e-HDi (Bosch EDC17, diesel): rail pressure, boost (MAP − baro), EGR, MAF, temperatures.
     */
    static final int[][] SLOW_PIDS = {{0x05}, {0x0B}, {0x23}, {0x10}, {0x04}, {0x0F}, {0x42}, {0x2C}, {0x2D}, {0x2F}, {0x5C},
            {0x46}, {0x33}, {0x1F}, {0x01}, {0x5E}, {0x0E}, {0x11}, {0x06}, {0x07}, {0x31}, {0x21}, {0x30}, {0x7C}, {0xA4}};

    /** Decodes a mode 01 answer to the value in display units; NaN if unknown. key out[0]. */
    static float value(int pid, int[] b, String[] key) {
        switch (pid) {
            case 0x05: key[0] = "cool"; return b[0] - 40;
            // transmission actual gear (SAE J1979 PID A4): byte B high nibble; 0 = neutral / not engaged
            case 0xA4: key[0] = "gearA4"; return (b[0] & 1) != 0 ? (b[1] >> 4) : Float.NaN;
            case 0x0B: key[0] = "map"; return b[0];
            case 0x23: key[0] = "rail"; return ((b[0] << 8) | b[1]) * 10f / 1000f;      // MPa
            case 0x10: key[0] = "maf"; return ((b[0] << 8) | b[1]) / 100f;              // g/s
            case 0x04: key[0] = "load"; return b[0] / 2.55f;
            case 0x0F: key[0] = "iat"; return b[0] - 40;
            case 0x42: key[0] = "volt"; return ((b[0] << 8) | b[1]) / 1000f;
            case 0x2C: key[0] = "egr"; return b[0] / 2.55f;
            case 0x2D: key[0] = "egrErr"; return b[0] / 1.28f - 100f;
            case 0x2F: key[0] = "fuelPct"; return b[0] / 2.55f;
            case 0x5C: key[0] = "oil"; return b[0] - 40;
            case 0x46: key[0] = "amb"; return b[0] - 40;
            case 0x33: key[0] = "baro"; return b[0];
            case 0x1F: key[0] = "run"; return (b[0] << 8) | b[1];                        // s
            case 0x01: key[0] = "dtcN"; return b[0] & 0x7F;                               // (+ MIL below)
            case 0x5E: key[0] = "lph"; return ((b[0] << 8) | b[1]) / 20f;
            case 0x0E: key[0] = "timing"; return b[0] / 2f - 64f;
            case 0x11: key[0] = "thr"; return b[0] / 2.55f;
            case 0x06: key[0] = "stft"; return b[0] / 1.28f - 100f;
            case 0x07: key[0] = "ltft"; return b[0] / 1.28f - 100f;
            case 0x31: key[0] = "clrKm"; return (b[0] << 8) | b[1];
            case 0x21: key[0] = "milKm"; return (b[0] << 8) | b[1];
            case 0x30: key[0] = "warm"; return b[0];
            case 0x7C: key[0] = "dpfT"; return ((b[0] << 8) | b[1]) / 10f - 40f;         // DPF temperature
            default: return Float.NaN;
        }
    }

    static int len(int pid) {
        switch (pid) {
            case 0x23: case 0x10: case 0x42: case 0x1F: case 0x5E: case 0x31: case 0x21: case 0x7C: return 2;
            case 0x01: case 0xA4: return 4;
            default: return 1;
        }
    }

    private volatile boolean wantDtc = true;

    /** Ask the stored fault codes again (mode 03) on the next round. */
    public void readDtc() { wantDtc = true; }

    public static final String WIFI = "wifi", BT = "bt";
    /** proto: "std" = OBD-II mode 01 requests, "psa" = passive listen to 0x0B6 on the PSA 125k bus. */
    public static final String STD = "std", PSA = "psa";
    private volatile String proto = STD;
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Listener l;
    private volatile boolean run;
    private Thread thread;
    private volatile Object socket;

    public Obd(Listener l) { this.l = l; }

    /** Bridge mode: only mode 01 PID 0C (engine rpm) is ever sent to the ECU. */
    public volatile boolean rpmOnly = true;

    /**
     * Engine rpm only: "010C" (read-only, standard OBD-II). Protocol 6 (ISO 15765-4 CAN 11/500,
     * the DS4's) first, else the adapter's automatic search. Reports when the ECU stops answering
     * (ignition off) and carries on by itself when it answers again.
     */
    private void rpmLoop(InputStream in, OutputStream out) throws Exception {
        int[] b = data(cmd(in, out, "010C", 4000), 0x0C, 2);
        if (b == null) {
            cmd(in, out, "ATSP0", 1500);
            b = data(cmd(in, out, "010C", 10000), 0x0C, 2);
        }
        int miss = b == null ? 5 : 0;
        if (b != null) { l.onStatus("ECU bağlı", true); l.onRpm(((b[0] << 8) | b[1]) / 4f); }
        else l.onStatus("ECU yanıt vermiyor (kontak açık mı?)", false);
        while (run) {
            String r = cmd(in, out, "010C1", 800);
            b = data(r, 0x0C, 2);
            if (b != null) {
                if (miss >= 5) l.onStatus("ECU bağlı", true);
                miss = 0;
                l.onRpm(((b[0] << 8) | b[1]) / 4f);
            } else if (++miss == 5) {
                l.onStatus("ECU yanıt vermiyor (kontak açık mı?)", false);
            }
            rawTurn(in, out);
            if (miss >= 5) Thread.sleep(700); // ignition off: ask slowly until it answers again
        }
    }

    /** PIN for in-app pairing of classic adapters (1234 on most ELM327s). */
    public volatile String pin = "1234";
    private Exception lastErr;

    private static String name(BluetoothDevice d) {
        String n = d.getName();
        return n == null || n.isEmpty() ? d.getAddress() : n;
    }

    /** Four ways to open the serial port; null if none worked. */
    private Object[] classic(BluetoothDevice dev) {
        for (int k = 0; k < 4 && run; k++) {
            try {
                BluetoothSocket s = k == 0 ? dev.createInsecureRfcommSocketToServiceRecord(SPP)
                        : k == 1 ? dev.createRfcommSocketToServiceRecord(SPP)
                        : (BluetoothSocket) dev.getClass().getMethod(k == 2 ? "createInsecureRfcommSocket" : "createRfcommSocket", int.class).invoke(dev, 1);
                socket = s;
                s.connect();
                return new Object[]{s.getInputStream(), s.getOutputStream()};
            } catch (Exception e) {
                lastErr = e;
                close();
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            }
        }
        return null;
    }

    /** Pairs from inside the app and answers the PIN request itself. */
    @SuppressLint("MissingPermission")
    private boolean bond(final BluetoothDevice dev) {
        final Object lock = new Object();
        final int[] state = {dev.getBondState()};
        android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null || !d.getAddress().equals(dev.getAddress())) return;
                if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(i.getAction())) {
                    int v = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                    try {
                        if (v == BluetoothDevice.PAIRING_VARIANT_PIN || v == 5 /* PIN_16_DIGITS */) d.setPin(pin.getBytes());
                        else d.setPairingConfirmation(true);
                        abortBroadcast();
                    } catch (Throwable ignored) {
                    }
                } else {
                    synchronized (lock) { state[0] = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1); lock.notifyAll(); }
                }
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.setPriority(android.content.IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        try {
            ctx.registerReceiver(r, f);
            if (!dev.createBond()) return dev.getBondState() == BluetoothDevice.BOND_BONDED;
            long end = System.currentTimeMillis() + 20000;
            synchronized (lock) {
                while (run && System.currentTimeMillis() < end && state[0] != BluetoothDevice.BOND_BONDED
                        && !(state[0] == BluetoothDevice.BOND_NONE && System.currentTimeMillis() > end - 19000)) lock.wait(500);
            }
            return dev.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (Throwable t) {
            return false;
        } finally {
            try { ctx.unregisterReceiver(r); } catch (Throwable ignored) { }
        }
    }

    /** Scans for a BLE ELM327 whose name matches (or any known adapter name). */
    @SuppressLint("MissingPermission")
    private static BluetoothDevice bleScan(BluetoothAdapter a, final String want, long ms) {
        final BluetoothDevice[] found = new BluetoothDevice[1];
        final Object lock = new Object();
        android.bluetooth.le.BluetoothLeScanner sc = a.getBluetoothLeScanner();
        if (sc == null) return null;
        android.bluetooth.le.ScanCallback cb = new android.bluetooth.le.ScanCallback() {
            @Override public void onScanResult(int type, android.bluetooth.le.ScanResult r) {
                BluetoothDevice d = r.getDevice();
                String n = d.getName();
                if (n == null && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
                if (n == null) return;
                String low = n.toLowerCase(Locale.ROOT);
                if (want != null ? low.contains(want) : looksLikeAdapter(low)) {
                    synchronized (lock) { if (found[0] == null) { found[0] = d; lock.notifyAll(); } }
                }
            }
        };
        try {
            sc.startScan(cb);
            synchronized (lock) { if (found[0] == null) lock.wait(ms); }
        } catch (Throwable ignored) {
        } finally {
            try { sc.stopScan(cb); } catch (Throwable ignored) { }
        }
        return found[0];
    }

    /** For BLE adapters (GATT needs a context). */
    public static volatile android.content.Context ctx;

    /** mode: "wifi" (host:port, default 192.168.0.10:35000) or "bt" (paired device name contains target, default OBD/ELM/Vlink). */
    public synchronized void start(final String mode, final String target, String proto) {
        stop();
        this.proto = PSA.equals(proto) ? PSA : STD;
        run = true;
        thread = new Thread(new Runnable() {
            public void run() {
                while (run) {
                    try {
                        session(mode, target);
                    } catch (Exception e) {
                        l.onStatus("OBD: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), false);
                    }
                    close();
                    sleep(4000); // retry
                }
            }
        }, "obd");
        thread.start();
    }

    public synchronized void stop() {
        run = false;
        close();
        if (thread != null) thread.interrupt();
        thread = null;
    }

    @SuppressLint("MissingPermission")
    static final String[] NAMES = {"obd", "elm", "vlink", "v-link", "vgate", "icar", "konnwei", "kw9", "viecar", "obdlink", "carista", "veepeak", "scan", "vehiconn", "obdii", "obd2"};

    static boolean looksLikeAdapter(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String k : NAMES) if (n.contains(k)) return true;
        return false;
    }

    /** A paired Bluetooth OBD adapter, or null. */
    public static BluetoothDevice pairedAdapter() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null || !a.isEnabled()) return null;
            for (BluetoothDevice d : a.getBondedDevices()) if (looksLikeAdapter(d.getName())) return d;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void session(String mode, String target) throws Exception {
        InputStream in;
        OutputStream out;
        if (BT.equals(mode)) {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) throw new Exception("Bluetooth yok");
            BluetoothDevice dev = null;
            boolean forceLe = false;
            String want = target == null || target.isEmpty() ? null : target.toLowerCase(Locale.ROOT);
            // A picked device by address ("AA:BB:CC:DD:EE:FF" or "…|le"): no pairing needed for it to be found.
            if (want != null && want.matches("^([0-9a-f]{2}:){5}[0-9a-f]{2}(\\|le)?$")) {
                forceLe = want.endsWith("|le");
                dev = a.getRemoteDevice(want.substring(0, 17).toUpperCase(Locale.ROOT));
                want = null;
            }
            if (dev == null) for (BluetoothDevice d : a.getBondedDevices()) {
                String n = d.getName() == null ? "" : d.getName().toLowerCase(Locale.ROOT);
                if (want != null ? n.contains(want) : looksLikeAdapter(n)) { dev = d; break; }
            }
            // BLE adapters (VehiConn…) need no pairing: look for one nearby.
            if (dev == null && ctx != null) {
                l.onStatus("OBD: BLE adaptör aranıyor…", false);
                dev = bleScan(a, want, 8000);
            }
            if (dev == null) throw new Exception("OBD adaptörü bulunamadı (eşleşmiş ya da yakında BLE yok)");
            l.onStatus("OBD: " + name(dev) + " bağlanıyor…", false);
            a.cancelDiscovery();
            Object[] io = null;
            Exception last = null;
            // Classic serial first (unless it is a BLE-only adapter), the way Car Scanner / AndrOBD do it:
            // insecure SPP works even without pairing on many adapters; if not, pair from inside the
            // app with the PIN (the unit's own Bluetooth app may be unable to), then the fallbacks.
            if (!forceLe && dev.getType() != BluetoothDevice.DEVICE_TYPE_LE) {
                io = classic(dev);
                if (io == null && run && ctx != null && dev.getBondState() != BluetoothDevice.BOND_BONDED) {
                    l.onStatus("OBD: " + name(dev) + " eşleştiriliyor (" + pin + ")…", false);
                    if (bond(dev)) io = classic(dev);
                }
                if (io == null) last = lastErr;
            }
            if (io == null && ctx != null && (forceLe || dev.getType() != BluetoothDevice.DEVICE_TYPE_CLASSIC)) {
                l.onStatus("OBD: " + name(dev) + " (BLE) bağlanıyor…", false);
                BleSerial b = new BleSerial();
                socket = b;
                try {
                    b.connect(ctx, dev);
                    io = new Object[]{b.in, b.out};
                } catch (Exception e) {
                    last = e;
                    try { b.close(); } catch (Throwable ignored) { }
                    socket = null;
                    // a dual-mode adapter chosen as BLE: BLE failed, so try its classic serial side too
                    if (forceLe && dev.getType() != BluetoothDevice.DEVICE_TYPE_LE) {
                        l.onStatus("OBD: " + name(dev) + " BLE olmadı, klasik bağlantı deneniyor…", false);
                        io = classic(dev);
                    }
                    if (io == null) throw e;
                }
            }
            if (io == null) throw new Exception("adaptöre bağlanılamadı (başka bir uygulama bağlı olabilir)" + (last == null || last.getMessage() == null ? "" : ": " + last.getMessage()));
            in = (InputStream) io[0];
            out = (OutputStream) io[1];
        } else {
            String host = "192.168.0.10";
            int port = 35000;
            if (target != null && !target.trim().isEmpty()) {
                String[] p = target.trim().split(":");
                host = p[0];
                if (p.length > 1) port = Integer.parseInt(p[1]);
            }
            l.onStatus("OBD: " + host + " bağlanıyor…", false);
            Socket s = new Socket();
            socket = s;
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(3000);
            in = s.getInputStream();
            out = s.getOutputStream();
        }
        if (PSA.equals(proto)) { psaListen(in, out); return; }
        for (String c : new String[]{"ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT2", "ATSP6"}) {
            cmd(in, out, c, c.equals("ATZ") ? 2500 : 1500);
        }
        if (rpmOnly) { rpmLoop(in, out); return; }
        // DS4 / C4 B7 (AEE2010): ISO 15765-4 CAN, 11 bit, 500 kbit/s = protocol 6. Else let the adapter search.
        String first = cmd(in, out, "0100", 4000);
        if (!first.contains("41")) {
            cmd(in, out, "ATSP0", 1500);
            cmd(in, out, "0100", 8000);
        }
        l.onStatus("OBD bağlı", true);
        // Which PIDs does this engine answer? (bitmaps 01-20, 21-40, 41-60, 61-80)
        boolean[] sup = new boolean[0x101];
        for (int base = 0; base <= 0xA0; base += 0x20) {
            int[] m = data(cmd(in, out, String.format(Locale.US, "01%02X", base), 1500), base, 4);
            if (m == null) break;
            long bits = ((long) m[0] << 24) | (m[1] << 16) | (m[2] << 8) | m[3];
            for (int k = 0; k < 32; k++) if ((bits & (1L << (31 - k))) != 0) sup[base + 1 + k] = true;
            if ((bits & 1) == 0) break;
        }
        int[] slow = new int[SLOW_PIDS.length];
        int ns = 0;
        for (int[] sp : SLOW_PIDS) if (sup[sp[0]]) slow[ns++] = sp[0];
        if (ns == 0) for (int[] sp : SLOW_PIDS) slow[ns++] = sp[0]; // no bitmap: try them all, drop the silent ones
        int[] miss = new int[0x101];
        l.onPid("pids", ns);
        boolean pedalD = true;
        // CAN engines answer several PIDs in one request: rpm + pedal in one round trip instead of two.
        boolean multi = false;
        {
            String t = cmd(in, out, "010C491", 700);
            multi = data(t, 0x0C, 2) != null && after(t, 0x0C, 2, 0x49, 1) != null;
        }
        l.onPid("multi", multi ? 1 : 0);
        PsaDids psa = new PsaDids();
        int n = 0, si = 0;
        long voltAt = 0, dtcAt = 0, retryAt = System.currentTimeMillis();
        String[] key = new String[1];
        while (run) {
            if (multi && pedalD) {
                String r = cmd(in, out, "010C491", 600);
                int[] b = data(r, 0x0C, 2), p = after(r, 0x0C, 2, 0x49, 1);
                if (b != null) l.onRpm(((b[0] << 8) | b[1]) / 4f);
                if (p != null) l.onPedal(Math.max(0, (p[0] / 2.55f - 14f) / 66f));
                else if (b == null) multi = false;          // the engine stopped answering it: back to one by one
            } else {
            String r = cmd(in, out, "010C1", 600);
            int[] b = data(r, 0x0C, 2);
            if (b != null) l.onRpm(((b[0] << 8) | b[1]) / 4f);
            }
            if (!(multi && pedalD) && n % 2 == 0) {
                // Accelerator pedal position D (0x49); falls back to throttle position (0x11).
                int[] p = data(cmd(in, out, pedalD ? "01491" : "01111", 600), pedalD ? 0x49 : 0x11, 1);
                if (p == null && pedalD) pedalD = false;
                else if (p != null) l.onPedal(pedalD ? Math.max(0, (p[0] / 2.55f - 14f) / 66f) : p[0] / 255f);
            }
            if (n % 5 == 0) {
                int[] s = data(cmd(in, out, "010D1", 600), 0x0D, 1);
                if (s != null) l.onSpeed(s[0]);
            }
            // one slower value per round
            if (ns > 0 && n % 2 == 1) {
                for (int tries = 0; tries < ns; tries++) {
                    int pid = slow[si % ns];
                    si++;
                    if (miss[pid] >= 3) continue;
                    int ln = len(pid);
                    int[] v = data(cmd(in, out, String.format(Locale.US, "01%02X1", pid), 700), pid, ln);
                    if (v == null) { miss[pid]++; break; }
                    miss[pid] = 0;
                    float f = value(pid, v, key);
                    if (!Float.isNaN(f)) l.onPid(key[0], f);
                    if (pid == 0x01) l.onPid("mil", (v[0] & 0x80) != 0 ? 1 : 0);
                    break;
                }
            }
            long now = System.currentTimeMillis();
            // values that were silent (often: engine not running yet when we connected) get another chance each minute
            if (now - retryAt > 60000) {
                retryAt = now;
                for (int k = 0; k < miss.length; k++) if (miss[k] >= 3) miss[k] = 0;
                if (!pedalD) pedalD = true;
                if (!multi) { String t = cmd(in, out, "010C491", 700); multi = data(t, 0x0C, 2) != null && after(t, 0x0C, 2, 0x49, 1) != null; }
            }
            psa.poll(in, out, now);
            if (now - voltAt > 5000) {
                voltAt = now;
                String rv = cmd(in, out, "ATRV", 600).replaceAll("[^0-9.]", "");
                try { if (!rv.isEmpty()) l.onPid("batt", Float.parseFloat(rv)); } catch (Exception ignored) { }
            }
            rawTurn(in, out);
            if (wantDtc || now - dtcAt > 120000) {
                wantDtc = false;
                dtcAt = now;
                l.onDtc(codes(cmd(in, out, "03", 2500)));
            }
            n++;
        }
    }

    /** Passive PSA listening: user CAN protocol B = 11-bit, 125 kbit/s (500/4), silent, filtered on 0x0B6. */
    private void psaListen(InputStream in, OutputStream out) throws Exception {
        for (String c : new String[]{"ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATCAF0", "ATCSM1"}) cmd(in, out, c, c.equals("ATZ") ? 2500 : 1500);
        String r = cmd(in, out, "ATPBC004", 1500);
        if (r.contains("?")) throw new Exception("adaptör PSA dinleme modunu desteklemiyor (ATPB)");
        cmd(in, out, "ATSPB", 1500);
        cmd(in, out, "ATCRA0B6", 1500);
        out.write("ATMA\r".getBytes("US-ASCII"));
        out.flush();
        l.onStatus("PSA CAN dinleniyor…", false);
        StringBuilder line = new StringBuilder();
        long lastFrame = System.currentTimeMillis(), lastT = 0;
        int lastFuel = -1;
        float rate = 0;
        boolean ok = false;
        while (run) {
            if (in.available() <= 0) {
                if (System.currentTimeMillis() - lastFrame > 6000) throw new Exception("PSA CAN: 0x0B6 gelmiyor (kablo / kontak?)");
                Thread.sleep(5);
                continue;
            }
            int ch = in.read();
            if (ch < 0) throw new Exception("bağlantı koptu");
            if (ch == '>') { // monitor stopped (buffer full…): restart it
                out.write("ATMA\r".getBytes("US-ASCII"));
                out.flush();
                line.setLength(0);
                continue;
            }
            if (ch != '\r' && ch != '\n') { line.append((char) ch); continue; }
            String h = line.toString().replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
            line.setLength(0);
            if (!h.startsWith("0B6") || h.length() < 3 + 14) continue;
            int[] b = new int[(h.length() - 3) / 2];
            for (int k = 0; k < b.length; k++) b[k] = Integer.parseInt(h.substring(3 + k * 2, 5 + k * 2), 16);
            long now = System.currentTimeMillis();
            lastFrame = now;
            int rawRpm = (b[0] << 8) | b[1];
            if (rawRpm != 0xFFFF) l.onRpm(rawRpm * 0.125f);
            int rawSpd = (b[2] << 8) | b[3];
            if (rawSpd != 0xFFFF) l.onSpeed(rawSpd * 0.01f);
            // Rolling fuel counter (80 mm3 per bit, wraps at 255) → fuel flow → rough pedal/load.
            int fuel = b[6];
            if (lastFuel >= 0 && lastT > 0 && now > lastT) {
                int d = (fuel - lastFuel + 256) % 256;
                float lh = d * 80e-6f / ((now - lastT) / 3_600_000f);   // litres per hour
                rate += (lh - rate) * 0.3f;
                float rpm = rawRpm * 0.125f, idle = 0.7f, max = Math.max(1.5f, rpm * 0.0045f);
                l.onPedal(Math.max(0, Math.min(1, (rate - idle) / (max - idle))));
            }
            lastFuel = fuel;
            lastT = now;
            if (!ok) { ok = true; l.onStatus("PSA CAN bağlı", true); }
        }
    }

    /** Mode 03 answer → "P0401,P2463" (handles single and multi-frame CAN answers). */
    static String codes(String r) {
        if (r == null) return "";
        StringBuilder hex = new StringBuilder();
        for (String line : r.split("[\\r\\n]+")) {
            String t = line.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty() || t.contains("NO DATA") || t.contains("SEARCH")) continue;
            if (t.matches("^[0-9A-F]:.*")) t = t.substring(2);
            else if (t.matches("^[0-9A-F]{3}$")) continue; // multi-frame byte count
            hex.append(t.replaceAll("[^0-9A-F]", ""));
        }
        String h = hex.toString();
        int i = h.indexOf("43");
        if (i < 0) return "";
        h = h.substring(i + 2);
        // CAN (the DS4): the first byte is the number of codes, the rest may be padding.
        int bytes = h.length() / 2, cnt = bytes > 0 ? Integer.parseInt(h.substring(0, 2), 16) : 0;
        if (bytes % 2 == 1 || (cnt > 0 && 1 + 2 * cnt <= bytes)) {
            h = h.substring(2, Math.min(h.length(), 2 + cnt * 4));
        }
        StringBuilder out = new StringBuilder();
        for (int k = 0; k + 4 <= h.length(); k += 4) {
            int a = Integer.parseInt(h.substring(k, k + 2), 16), b = Integer.parseInt(h.substring(k + 2, k + 4), 16);
            if (a == 0 && b == 0) continue;
            String code = "PCBU".charAt((a >> 6) & 3) + Integer.toString((a >> 4) & 3) + Integer.toHexString(a & 15).toUpperCase(Locale.ROOT)
                    + String.format(Locale.US, "%02X", b);
            if (out.length() > 0) out.append(',');
            out.append(code);
        }
        return out.toString();
    }

    /** In a multi-PID answer ("410C1AF8491F"): the bytes of the PID that follows the first one. */
    private static int[] after(String r, int pid, int len, int pid2, int len2) {
        if (r == null) return null;
        String h = r.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
        String key = String.format(Locale.US, "41%02X", pid);
        int i = h.indexOf(key);
        if (i < 0) return null;
        int j = i + 4 + len * 2;
        String k2 = String.format(Locale.US, "%02X", pid2);
        if (h.length() < j + 2 + len2 * 2 || !h.startsWith(k2, j)) return null;
        int[] b = new int[len2];
        for (int q = 0; q < len2; q++) b[q] = Integer.parseInt(h.substring(j + 2 + q * 2, j + 4 + q * 2), 16);
        return b;
    }

    /**
     * Citroën / Peugeot (AEE2010, DS4 / C4 B7) extras read with UDS ReadDataByIdentifier (0x22) on the
     * default session: the body computer (BSI, request 0x752, answer 0x652) knows the litres in the tank,
     * oil level and temperature, battery voltage / charge / temperature, the alternator set point and the
     * car's own range and consumption; the tyre module (0x6AF / 0x68F) the four pressures and temperatures.
     * Addresses and scaling as documented by nico1080 (github.com/nico1080/OBD-LCD-display-for-PSA) for a
     * DS4 with BSI2010. Read-only. One group every few seconds, so rpm stays quick; an identifier the car
     * does not answer three times is dropped.
     */
    private final class PsaDids {
        // {tx, rx, did, length, scale ×1e6, offset, key}
        private final Object[][] BSI = {
                {0x752, 0x652, 0xD8C3, 2, 10000, 0, "psaFuelL"},     // fuel in tank, measured (L)
                {0x752, 0x652, 0xD8E1, 2, 1000000, 0, "psaRange"},   // range (km)
                {0x752, 0x652, 0xDB84, 1, 1000000, 0, "psaOilLvl"},  // oil level measured (%)
                {0x752, 0x652, 0xDB83, 1, 1000000, -40, "psaOilT"},  // oil temperature (°C)
                {0x752, 0x652, 0xDA46, 2, 1000, 0, "psaBatV"},       // battery voltage (V)
                {0x752, 0x652, 0xDA21, 1, 1000000, 0, "psaBatSoc"},  // battery charge (%)
                {0x752, 0x652, 0xDA47, 1, 1000000, -40, "psaBatT"},  // battery temperature (°C)
                {0x752, 0x652, 0xDA45, 1, 100000, 0, "psaAltSet"},   // alternator set point (V)
                {0x752, 0x652, 0xD912, 1, 500000, -40, "psaOutT"},   // outside temperature (°C)
                {0x752, 0x652, 0xD8C7, 2, 100000, 0, "psaInst"},     // car's instant consumption (L/100)
        };
        private final Object[][] TYRES = {
                {0x6AF, 0x68F, 0xD610, 2, 1000, 0, "tyreFL"}, {0x6AF, 0x68F, 0xD60F, 2, 1000, 0, "tyreFR"},
                {0x6AF, 0x68F, 0xD612, 2, 1000, 0, "tyreRL"}, {0x6AF, 0x68F, 0xD611, 2, 1000, 0, "tyreRR"},
                {0x6AF, 0x68F, 0xD60E, 1, 1000000, -50, "tyreTFL"}, {0x6AF, 0x68F, 0xD60D, 1, 1000000, -50, "tyreTFR"},
                {0x6AF, 0x68F, 0xD60C, 1, 1000000, -50, "tyreTRL"}, {0x6AF, 0x68F, 0xD60B, 1, 1000000, -50, "tyreTRR"},
        };
        private final java.util.HashMap<Integer, Integer> miss = new java.util.HashMap<>();
        private long bsiAt, tyreAt;
        private int bi;
        private boolean tyresGone;
        // PSA ECUs answer unpadded 4-byte requests (DLC 4, as DiagBox and nico1080's reader send them);
        // some ignore the ELM's padded 8-byte frames. null = not known yet, true = send raw with CAF off.
        private Boolean raw;
        private boolean cafOff;

        void poll(InputStream in, OutputStream out, long now) throws Exception {
            if (now - bsiAt > 4000) {
                bsiAt = now;
                Object[][] g = new Object[3][];
                for (int k = 0; k < 3; k++) g[k] = BSI[(bi++) % BSI.length];
                read(in, out, g);
            } else if (!tyresGone && now - tyreAt > 30000) {
                tyreAt = now;
                int before = miss.size();
                read(in, out, TYRES);
                int gone = 0;
                for (Object[] d : TYRES) { Integer m = miss.get((Integer) d[2]); if (m != null && m >= 3) gone++; }
                if (gone == TYRES.length) tyresGone = true;   // no tyre module on this car
            }
        }

        /** One ReadDataByIdentifier: padded (ELM formatting) or a raw 4-byte frame "03 22 hi lo" with formatting off. */
        private String ask(InputStream in, OutputStream out, int did, boolean padded) throws Exception {
            if (padded && cafOff) { cmd(in, out, "ATCAF1", 400); cafOff = false; }
            if (!padded && !cafOff) { cmd(in, out, "ATCAF0", 400); cafOff = true; }
            String r = cmd(in, out, String.format(Locale.US, padded ? "22%04X" : "0322%04X", did), 300);
            return r == null ? "" : r.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
        }

        private void read(InputStream in, OutputStream out, Object[][] list) throws Exception {
            int tx = -1;
            try {
                for (Object[] d : list) {
                    int did = (Integer) d[2];
                    Integer m = miss.get(did);
                    if (m != null && m >= 3) continue;
                    if ((Integer) d[0] != tx) {
                        tx = (Integer) d[0];
                        cmd(in, out, String.format(Locale.US, "ATSH%03X", tx), 400);
                        cmd(in, out, String.format(Locale.US, "ATCRA%03X", (Integer) d[1]), 400);
                    }
                    int len = (Integer) d[3];
                    String key = String.format(Locale.US, "62%04X", did);
                    String h = ask(in, out, did, !Boolean.TRUE.equals(raw));
                    int i = h.indexOf(key);
                    if (i < 0 && raw == null) {           // padded frame unanswered: try the unpadded one once
                        h = ask(in, out, did, false);
                        i = h.indexOf(key);
                        if (i >= 0) raw = true;
                    } else if (i >= 0 && raw == null) raw = false;
                    if (i < 0 || h.length() < i + 6 + len * 2) { miss.put(did, (m == null ? 0 : m) + 1); continue; }
                    miss.put(did, 0);
                    long raw = 0;
                    for (int q = 0; q < len; q++) raw = (raw << 8) | Integer.parseInt(h.substring(i + 6 + q * 2, i + 8 + q * 2), 16);
                    if (raw == (len == 1 ? 0xFF : 0xFFFF)) continue;   // "not available"
                    float v = raw * ((Integer) d[4]) / 1e6f + (Integer) d[5];
                    l.onPid((String) d[6], v);
                }
            } finally {
                if (cafOff) { cmd(in, out, "ATCAF1", 400); cafOff = false; }
                if (tx >= 0) {
                    // back to the engine's OBD address for the next round
                    cmd(in, out, "ATSH7DF", 400);
                    cmd(in, out, "ATAR", 400);
                }
            }
        }
    }

    private static int[] data(String r, int pid, int len) {
        if (r == null) return null;
        String h = r.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
        String key = String.format(Locale.US, "41%02X", pid);
        int i = h.indexOf(key);
        if (i < 0 || h.length() < i + 4 + len * 2) return null;
        int[] out = new int[len];
        for (int k = 0; k < len; k++) out[k] = Integer.parseInt(h.substring(i + 4 + k * 2, i + 6 + k * 2), 16);
        return out;
    }

    // ---- support: the last adapter exchanges, and one raw command sent from the support console
    private static final java.util.ArrayDeque<String> TRACE = new java.util.ArrayDeque<>();
    private static volatile String rawReq, rawResp;
    public static String trace() { synchronized (TRACE) { StringBuilder b = new StringBuilder(); for (String t : TRACE) b.append(t).append('\n'); return b.toString(); } }
    /** Sends one command to the adapter between polls; the answer, or null after 8 s. */
    public static String raw(String c) throws InterruptedException {
        rawResp = null; rawReq = c;
        for (int i = 0; i < 80 && rawResp == null; i++) Thread.sleep(100);
        return rawResp;
    }
    private static void rawTurn(InputStream in, OutputStream out) throws Exception {
        String q = rawReq;
        if (q == null) return;
        rawReq = null;
        StringBuilder b = new StringBuilder();
        for (String c : q.split(";")) { c = c.trim(); if (!c.isEmpty()) b.append(c).append(" → ").append(cmd(in, out, c, 4000).replace('\r', ' ').trim()).append('\n'); }
        rawResp = b.toString();
    }

    private static String cmd(InputStream in, OutputStream out, String c, long timeoutMs) throws Exception {
        String r = cmd0(in, out, c, timeoutMs);
        synchronized (TRACE) {
            TRACE.addLast(String.format(java.util.Locale.US, "%tT %-10s → %s", System.currentTimeMillis(), c, r.replace('\r', ' ').replace('\n', ' ').trim()));
            while (TRACE.size() > 160) TRACE.removeFirst();
        }
        return r;
    }

    private static String cmd0(InputStream in, OutputStream out, String c, long timeoutMs) throws Exception {
        out.write((c + "\r").getBytes("US-ASCII"));
        out.flush();
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (in.available() > 0) {
                int ch = in.read();
                if (ch < 0) throw new Exception("bağlantı koptu");
                if (ch == '>') return sb.toString();
                sb.append((char) ch);
            } else {
                Thread.sleep(5);
            }
        }
        return sb.toString();
    }

    private void close() {
        Object s = socket;
        socket = null;
        try {
            if (s instanceof Socket) ((Socket) s).close();
            if (s instanceof BluetoothSocket) ((BluetoothSocket) s).close();
            if (s instanceof BleSerial) ((BleSerial) s).close();
        } catch (Exception ignored) {
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
