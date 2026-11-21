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
            {0x46}, {0x33}, {0x1F}, {0x01}, {0x5E}, {0x0E}, {0x11}, {0x06}, {0x07}, {0x31}, {0x21}, {0x30}, {0x7C}};

    /** Decodes a mode 01 answer to the value in display units; NaN if unknown. key out[0]. */
    static float value(int pid, int[] b, String[] key) {
        switch (pid) {
            case 0x05: key[0] = "cool"; return b[0] - 40;
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
            case 0x01: return 4;
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
    static final String[] NAMES = {"obd", "elm", "vlink", "v-link", "vgate", "icar", "konnwei", "kw9", "viecar", "obdlink", "carista", "veepeak", "scan"};

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
            String want = target == null || target.isEmpty() ? null : target.toLowerCase(Locale.ROOT);
            for (BluetoothDevice d : a.getBondedDevices()) {
                String n = d.getName() == null ? "" : d.getName().toLowerCase(Locale.ROOT);
                if (want != null ? n.contains(want) : looksLikeAdapter(n)) { dev = d; break; }
            }
            if (dev == null) throw new Exception("eşleşmiş OBD adaptörü bulunamadı");
            l.onStatus("OBD: " + dev.getName() + " bağlanıyor…", false);
            BluetoothSocket s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
            socket = s;
            s.connect();
            in = s.getInputStream();
            out = s.getOutputStream();
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
        // DS4 / C4 B7 (AEE2010): ISO 15765-4 CAN, 11 bit, 500 kbit/s = protocol 6. Else let the adapter search.
        String first = cmd(in, out, "0100", 4000);
        if (!first.contains("41")) {
            cmd(in, out, "ATSP0", 1500);
            cmd(in, out, "0100", 8000);
        }
        l.onStatus("OBD bağlı", true);
        // Which PIDs does this engine answer? (bitmaps 01-20, 21-40, 41-60, 61-80)
        boolean[] sup = new boolean[0x81];
        for (int base = 0; base <= 0x60; base += 0x20) {
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
        int[] miss = new int[0x81];
        l.onPid("pids", ns);
        boolean pedalD = true;
        int n = 0, si = 0;
        long voltAt = 0, dtcAt = 0;
        String[] key = new String[1];
        while (run) {
            String r = cmd(in, out, "010C1", 600);
            int[] b = data(r, 0x0C, 2);
            if (b != null) l.onRpm(((b[0] << 8) | b[1]) / 4f);
            if (n % 2 == 0) {
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
            if (now - voltAt > 5000) {
                voltAt = now;
                String rv = cmd(in, out, "ATRV", 600).replaceAll("[^0-9.]", "");
                try { if (!rv.isEmpty()) l.onPid("batt", Float.parseFloat(rv)); } catch (Exception ignored) { }
            }
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
        if ((h.length() / 2) % 2 == 1) h = h.substring(2); // CAN: first byte = number of codes
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

    private static String cmd(InputStream in, OutputStream out, String c, long timeoutMs) throws Exception {
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
        } catch (Exception ignored) {
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
