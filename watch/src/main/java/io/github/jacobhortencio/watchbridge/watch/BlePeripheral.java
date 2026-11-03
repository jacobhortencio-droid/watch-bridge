package io.github.jacobhortencio.watchbridge.watch;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Arrays;

import io.github.jacobhortencio.watchbridge.common.Chunker;
import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;

/** Lado do relógio: anuncia um serviço BLE próprio e aceita a ligação do telemóvel. */
@SuppressLint("MissingPermission")
class BlePeripheral {
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

    private BluetoothManager bm;
    private BluetoothGattServer server;
    private BluetoothGattCharacteristic tx;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothDevice device;
    private boolean advertising, notifyOn, sending;
    private int mtu = 23;
    private int retries;
    String lastError = "";

    BlePeripheral(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    boolean isConnected() {
        return device != null && notifyOn;
    }

    boolean start() {
        bm = ctx.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null || !a.isEnabled()) {
            lastError = "Bluetooth desligado";
            return false;
        }
        server = bm.openGattServer(ctx, cb);
        if (server == null) {
            lastError = "GATT server indisponível";
            return false;
        }
        BluetoothGattService svc = new BluetoothGattService(Proto.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic rx = new BluetoothGattCharacteristic(Proto.RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        tx = new BluetoothGattCharacteristic(Proto.TX,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        tx.addDescriptor(new BluetoothGattDescriptor(Proto.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        svc.addCharacteristic(rx);
        svc.addCharacteristic(tx);
        server.addService(svc);
        return true;
    }

    void stop() {
        h.removeCallbacksAndMessages(null);
        stopAdvertising();
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
            }
            server = null;
        }
        device = null;
        notifyOn = false;
    }

    private void startAdvertising() {
        if (advertising) return;
        BluetoothAdapter a = bm.getAdapter();
        advertiser = a == null ? null : a.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            lastError = "Este relógio não permite anunciar por BLE";
            return;
        }
        AdvertiseSettings s = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        AdvertiseData d = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(Proto.SERVICE))
                .setIncludeDeviceName(false)
                .build();
        try {
            advertiser.startAdvertising(s, d, advCb);
            advertising = true;
        } catch (Exception e) {
            lastError = "Falha a anunciar: " + e.getMessage();
        }
    }

    private void stopAdvertising() {
        if (advertising && advertiser != null) {
            try {
                advertiser.stopAdvertising(advCb);
            } catch (Exception ignored) {
            }
        }
        advertising = false;
    }

    private final AdvertiseCallback advCb = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            lastError = "";
        }

        @Override
        public void onStartFailure(int errorCode) {
            h.post(() -> {
                advertising = false;
                lastError = "Anúncio BLE falhou (código " + errorCode + ")";
                h.postDelayed(BlePeripheral.this::startAdvertising, 10_000);
            });
        }
    };

    private final BluetoothGattServerCallback cb = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            h.post(BlePeripheral.this::startAdvertising);
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice d, int status, int newState) {
            h.post(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (device != null && !device.equals(d)) return; // já há um telemóvel
                    device = d;
                    notifyOn = false;
                    mtu = 23;
                    asm.reset();
                    stopAdvertising();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && d.equals(device)) {
                    boolean was = notifyOn;
                    device = null;
                    notifyOn = false;
                    sending = false;
                    queue.clear();
                    asm.reset();
                    if (was) listener.onConnectionChanged(false);
                    startAdvertising();
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothDevice d, int m) {
            h.post(() -> mtu = m);
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice d, int requestId, BluetoothGattCharacteristic c,
                                                 boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded && server != null) {
                server.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
            if (!Proto.RX.equals(c.getUuid())) return;
            byte[] full = asm.feed(value);
            if (full == null) return;
            JSONObject m = Msg.decode(full);
            if (m != null) h.post(() -> listener.onMessage(m));
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice d, int requestId, int offset,
                                                BluetoothGattCharacteristic c) {
            if (server != null) server.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, new byte[0]);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice d, int requestId, BluetoothGattDescriptor desc,
                                             boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded && server != null) {
                server.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
            if (!Proto.CCCD.equals(desc.getUuid())) return;
            boolean on = Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            h.post(() -> {
                if (!d.equals(device)) return;
                boolean was = notifyOn;
                notifyOn = on;
                if (on && !was) listener.onConnectionChanged(true);
                if (!on && was) listener.onConnectionChanged(false);
                pump();
            });
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice d, int requestId, int offset,
                                            BluetoothGattDescriptor desc) {
            byte[] v = notifyOn ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            if (server != null) server.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, v);
        }

        @Override
        public void onNotificationSent(BluetoothDevice d, int status) {
            h.post(() -> {
                h.removeCallbacks(sendWatchdog);
                sending = false;
                pump();
            });
        }
    };

    void send(JSONObject m) {
        byte[] data = Msg.encode(m);
        h.post(() -> {
            if (!isConnected()) return;
            if (queue.size() > 4000) queue.clear();
            queue.addAll(Chunker.split(data, mtu - 3));
            pump();
        });
    }

    private final Runnable sendWatchdog = () -> {
        sending = false;
        pump();
    };

    private void pump() {
        if (sending || queue.isEmpty() || !isConnected() || server == null) return;
        byte[] c = queue.peek();
        int r;
        try {
            r = server.notifyCharacteristicChanged(device, tx, false, c);
        } catch (Exception e) {
            Log.w(TAG, "notify falhou", e);
            r = -1;
        }
        if (r == BluetoothStatusCodes.SUCCESS) {
            queue.poll();
            sending = true;
            retries = 0;
            h.postDelayed(sendWatchdog, 2_000);
        } else if (++retries > 100) {
            retries = 0;
            queue.clear();
        } else {
            h.postDelayed(this::pump, 30);
        }
    }
}
