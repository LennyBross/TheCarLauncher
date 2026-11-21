package com.catarapatara;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bluetooth Low Energy ELM327 adapters (VehiConn, Vgate iCar BLE, Veepeak BLE…) have no serial
 * port: they carry the ELM text over a GATT "serial" service. This turns that into the same
 * input / output streams the classic adapters give, so the OBD code does not care.
 */
@SuppressLint("MissingPermission")
final class BleSerial {
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // Known serial services: notify + write characteristics.
    private static final String[][] KNOWN = {
            {"0000fff0", "0000fff1", "0000fff2"},
            {"0000ffe0", "0000ffe1", "0000ffe1"},
            {"000018f0", "00002af0", "00002af1"},
            {"e7810a71", "bef8d6c9", "bef8d6c9"},
    };

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx;
    private final LinkedBlockingQueue<Byte> rx = new LinkedBlockingQueue<>();
    private final Semaphore ready = new Semaphore(0), wrote = new Semaphore(0);
    private volatile boolean up, failed;

    final InputStream in = new InputStream() {
        @Override public int read() throws IOException {
            try {
                while (true) {
                    if (!up) throw new IOException("BLE koptu");
                    Byte b = rx.poll(200, TimeUnit.MILLISECONDS);
                    if (b != null) return b & 0xFF;
                }
            } catch (InterruptedException e) {
                throw new IOException("interrupted");
            }
        }
        @Override public int available() throws IOException {
            if (!up) throw new IOException("BLE koptu");
            return rx.size();
        }
    };

    final OutputStream out = new OutputStream() {
        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (!up || tx == null) throw new IOException("BLE koptu");
            for (int i = 0; i < len; i += 20) {
                byte[] chunk = new byte[Math.min(20, len - i)];
                System.arraycopy(b, off + i, chunk, 0, chunk.length);
                tx.setValue(chunk);
                boolean resp = (tx.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                tx.setWriteType(resp ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                wrote.drainPermits();
                if (!gatt.writeCharacteristic(tx)) throw new IOException("BLE yazılamadı");
                try { wrote.tryAcquire(1, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException("interrupted"); }
            }
        }
    };

    /** Connects and finds the serial service; throws with a readable reason. */
    void connect(Context c, BluetoothDevice d) throws Exception {
        gatt = d.connectGatt(c, false, cb, BluetoothDevice.TRANSPORT_LE);
        if (!ready.tryAcquire(15, TimeUnit.SECONDS) || failed) { close(); throw new Exception("BLE adaptör bağlanmadı"); }
        up = true;
    }

    void close() {
        up = false;
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (Throwable ignored) { }
        gatt = null;
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(185);
                g.discoverServices();
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                up = false;
                failed = true;
                ready.release();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattCharacteristic notify = null, write = null;
            for (String[] k : KNOWN) {
                for (BluetoothGattService s : g.getServices()) {
                    if (!s.getUuid().toString().startsWith(k[0])) continue;
                    for (BluetoothGattCharacteristic ch : s.getCharacteristics()) {
                        String u = ch.getUuid().toString();
                        if (u.startsWith(k[1]) && (ch.getProperties() & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) notify = ch;
                        if (u.startsWith(k[2]) && (ch.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) write = ch;
                    }
                }
                if (notify != null && write != null) break;
                notify = write = null;
            }
            if (notify == null || write == null) {
                // Unknown layout: any service that has both a notify and a write characteristic.
                for (BluetoothGattService s : g.getServices()) {
                    BluetoothGattCharacteristic n = null, w = null;
                    for (BluetoothGattCharacteristic ch : s.getCharacteristics()) {
                        int p = ch.getProperties();
                        if ((p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0 && n == null) n = ch;
                        if ((p & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0 && w == null) w = ch;
                    }
                    if (n != null && w != null && !s.getUuid().toString().startsWith("00001800") && !s.getUuid().toString().startsWith("00001801")) { notify = n; write = w; break; }
                }
            }
            if (notify == null || write == null) { failed = true; ready.release(); return; }
            tx = write;
            g.setCharacteristicNotification(notify, true);
            BluetoothGattDescriptor d = notify.getDescriptor(CCCD);
            if (d != null) {
                boolean ind = (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
                d.setValue(ind ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
            } else {
                ready.release();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { ready.release(); }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) { wrote.release(); }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] v = ch.getValue();
            if (v != null) for (byte b : v) rx.offer(b);
        }
    };
}
