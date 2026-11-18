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
final class Obd {
    interface Listener {
        void onRpm(float rpm);
        void onPedal(float pedal01);
        void onSpeed(float kmh);
        void onStatus(String text, boolean ok);
    }

    static final String WIFI = "wifi", BT = "bt";
    /** proto: "std" = OBD-II mode 01 requests, "psa" = passive listen to 0x0B6 on the PSA 125k bus. */
    static final String STD = "std", PSA = "psa";
    private volatile String proto = STD;
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Listener l;
    private volatile boolean run;
    private Thread thread;
    private volatile Object socket;

    Obd(Listener l) { this.l = l; }

    /** mode: "wifi" (host:port, default 192.168.0.10:35000) or "bt" (paired device name contains target, default OBD/ELM/Vlink). */
    synchronized void start(final String mode, final String target, String proto) {
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

    synchronized void stop() {
        run = false;
        close();
        if (thread != null) thread.interrupt();
        thread = null;
    }

    @SuppressLint("MissingPermission")
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
                if (want != null ? n.contains(want) : (n.contains("obd") || n.contains("elm") || n.contains("vlink") || n.contains("v-link") || n.contains("vgate"))) { dev = d; break; }
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
        for (String c : new String[]{"ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT2", "ATSP0"}) {
            cmd(in, out, c, c.equals("ATZ") ? 2500 : 1500);
        }
        // First request lets the adapter find the protocol (can take a few seconds).
        cmd(in, out, "0100", 8000);
        l.onStatus("OBD bağlı", true);
        boolean pedalD = true;
        int n = 0;
        while (run) {
            String r = cmd(in, out, "010C", 1000);
            int[] b = data(r, 0x0C, 2);
            if (b != null) l.onRpm(((b[0] << 8) | b[1]) / 4f);
            if (n % 2 == 0) {
                // Accelerator pedal position D (0x49); falls back to throttle position (0x11).
                int[] p = data(cmd(in, out, pedalD ? "0149" : "0111", 1000), pedalD ? 0x49 : 0x11, 1);
                if (p == null && pedalD) pedalD = false;
                else if (p != null) l.onPedal(pedalD ? Math.max(0, (p[0] / 2.55f - 14f) / 66f) : p[0] / 255f);
            }
            if (n % 10 == 0) {
                int[] s = data(cmd(in, out, "010D", 1000), 0x0D, 1);
                if (s != null) l.onSpeed(s[0]);
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
