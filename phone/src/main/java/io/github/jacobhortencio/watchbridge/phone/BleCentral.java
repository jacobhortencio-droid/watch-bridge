package io.github.jacobhortencio.watchbridge.phone;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Collections;

import io.github.jacobhortencio.watchbridge.common.Chunker;
import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Lado do telemóvel: procura o relógio por BLE e liga-se ao serviço próprio. */
@SuppressLint("MissingPermission")
class BleCentral {
    interface Listener {
        void onMessage(JSONObject m);

        void onConnectionChanged(boolean connected);
    }

    private static final String TAG = "WB-BLE";

    private final Context ctx;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private final Chunker.Assembler asm = new Chunker.Assembler();

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private boolean running, scanning, ready, writing;
    private int mtu = 23;
    private int writeRetries;

    BleCentral(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    boolean isReady() {
        return ready;
    }

    void start() {
        running = true;
        h.post(this::scan);
    }

    void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
        stopScan();
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        ready = false;
    }

    private void scan() {
        if (!running || gatt != null || scanning) return;
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null || !a.isEnabled()) {
            h.postDelayed(this::scan, 10_000);
            return;
        }
        scanner = a.getBluetoothLeScanner();
        if (scanner == null) {
            h.postDelayed(this::scan, 10_000);
            return;
        }
        ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Proto.SERVICE)).build();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(Collections.singletonList(f), s, scanCb);
            scanning = true;
            BridgeService.setStatus("A procurar o relógio por Bluetooth...");
            h.postDelayed(scanTimeout, 20_000);
        } catch (Exception e) {
            Log.w(TAG, "startScan falhou", e);
            h.postDelayed(this::scan, 15_000);
        }
    }

    private final Runnable scanTimeout = () -> {
        stopScan();
        h.postDelayed(this::scan, 12_000);
    };

    private void stopScan() {
        h.removeCallbacks(scanTimeout);
        if (scanning && scanner != null) {
            try {
                scanner.stopScan(scanCb);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            h.post(() -> {
                if (gatt != null || !running) return;
                stopScan();
                connect(result.getDevice());
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            h.post(() -> {
                scanning = false;
                h.postDelayed(BleCentral.this::scan, 15_000);
            });
        }
    };

    private void connect(BluetoothDevice d) {
        Log.i(TAG, "a ligar a " + d.getAddress());
        BridgeService.setStatus("Relógio encontrado, a ligar...");
        gatt = d.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        h.postDelayed(connectTimeout, 20_000);
    }

    private final Runnable connectTimeout = () -> {
        if (!ready) {
            Log.w(TAG, "tempo de ligação esgotado");
            cleanupAndRetry();
        }
    };

    private void cleanupAndRetry() {
        h.removeCallbacks(connectTimeout);
        h.removeCallbacks(writeWatchdog);
        boolean was = ready;
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (Exception ignored) {
            }
            gatt.close();
        }
        gatt = null;
        rx = null;
        ready = false;
        writing = false;
        queue.clear();
        asm.reset();
        if (was) listener.onConnectionChanged(false);
        if (running) h.postDelayed(this::scan, 3_000);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            h.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
                    if (!g.requestMtu(517)) g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    cleanupAndRetry();
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int m, int status) {
            h.post(() -> {
                if (g != gatt) return;
                mtu = status == BluetoothGatt.GATT_SUCCESS ? m : 23;
                g.discoverServices();
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            h.post(() -> {
                if (g != gatt) return;
                BluetoothGattService svc = g.getService(Proto.SERVICE);
                if (svc == null) {
                    cleanupAndRetry();
                    return;
                }
                rx = svc.getCharacteristic(Proto.RX);
                BluetoothGattCharacteristic tx = svc.getCharacteristic(Proto.TX);
                if (rx == null || tx == null) {
                    cleanupAndRetry();
                    return;
                }
                g.setCharacteristicNotification(tx, true);
                BluetoothGattDescriptor d = tx.getDescriptor(Proto.CCCD);
                if (d == null) {
                    cleanupAndRetry();
                    return;
                }
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            h.post(() -> {
                if (g != gatt) return;
                h.removeCallbacks(connectTimeout);
                ready = true;
                listener.onConnectionChanged(true);
                pump();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            h.post(() -> {
                h.removeCallbacks(writeWatchdog);
                writing = false;
                pump();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (!Proto.TX.equals(c.getUuid())) return;
            byte[] full = asm.feed(value);
            if (full == null) return;
            JSONObject m = Msg.decode(full);
            if (m != null) h.post(() -> listener.onMessage(m));
        }
    };

    void send(JSONObject m) {
        byte[] data = Msg.encode(m);
        h.post(() -> {
            if (!ready) return;
            if (queue.size() > 4000) queue.clear();
            queue.addAll(Chunker.split(data, mtu - 3));
            pump();
        });
    }

    private final Runnable writeWatchdog = () -> {
        writing = false;
        pump();
    };

    private void pump() {
        if (writing || queue.isEmpty() || gatt == null || rx == null) return;
        byte[] c = queue.peek();
        int r;
        try {
            r = gatt.writeCharacteristic(rx, c, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } catch (Exception e) {
            r = -1;
        }
        if (r == BluetoothStatusCodes.SUCCESS) {
            queue.poll();
            writing = true;
            writeRetries = 0;
            h.postDelayed(writeWatchdog, 3_000);
        } else if (++writeRetries > 100) {
            writeRetries = 0;
            queue.clear();
        } else {
            h.postDelayed(this::pump, 30);
        }
    }
}
