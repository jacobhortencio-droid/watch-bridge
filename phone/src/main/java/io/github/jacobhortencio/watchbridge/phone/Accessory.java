package io.github.jacobhortencio.watchbridge.phone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanFilter;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothLeDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.net.MacAddress;
import android.os.ParcelUuid;
import android.os.Process;

import java.lang.reflect.Method;
import java.util.List;

import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.WLog;

/** Modo acessório (bonding + Companion Device Manager) e verificação do arranque automático do HyperOS. */
@SuppressLint("MissingPermission")
final class Accessory {
    private Accessory() {}

    static volatile String state = "";

    static boolean associated(Context c) {
        try {
            List<AssociationInfo> l = c.getSystemService(CompanionDeviceManager.class).getMyAssociations();
            return l != null && !l.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Passo 1: bonding Bluetooth com o relógio ligado. Passo 2: associação CDM. */
    static void enable(Activity a) {
        BridgeService s = BridgeService.get();
        BluetoothDevice d = (s == null || s.ble() == null) ? null : s.ble().device();
        if (d == null) {
            state = "Ligue primeiro o relógio por Bluetooth.";
            return;
        }
        if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
            Prefs.setBondedAddr(a, d.getAddress());
            associate(a);
            return;
        }
        state = "Aceite o pedido de emparelhamento Bluetooth no telemóvel e no relógio...";
        a.getApplicationContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                BluetoothDevice dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                if (dev == null || !dev.equals(d)) return;
                if (st == BluetoothDevice.BOND_BONDED) {
                    Prefs.setBondedAddr(c, dev.getAddress());
                    WLog.i("WB-CDM", "bonding concluído");
                    c.unregisterReceiver(this);
                    associate(a);
                } else if (st == BluetoothDevice.BOND_NONE) {
                    state = "O emparelhamento Bluetooth foi recusado. A associação continua sem ele.";
                    c.unregisterReceiver(this);
                    associate(a);
                }
            }
        }, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED);
        if (!d.createBond()) {
            state = "Não foi possível iniciar o emparelhamento Bluetooth. A associar sem ele...";
            associate(a);
        }
    }

    private static void associate(Activity a) {
        associate(a, true);
    }

    private static void associate(Activity a, boolean watchProfile) {
        CompanionDeviceManager cdm = a.getSystemService(CompanionDeviceManager.class);
        BluetoothLeDeviceFilter f = new BluetoothLeDeviceFilter.Builder()
                .setScanFilter(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Proto.SERVICE)).build())
                .build();
        AssociationRequest.Builder b = new AssociationRequest.Builder().addDeviceFilter(f).setSingleDevice(true);
        if (watchProfile) b.setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH);
        state = "Escolha o relógio na janela do sistema...";
        try {
            cdm.associate(b.build(), a.getMainExecutor(), new CompanionDeviceManager.Callback() {
                @Override
                public void onAssociationPending(IntentSender sender) {
                    try {
                        a.startIntentSenderForResult(sender, 77, null, 0, 0, 0);
                    } catch (Exception e) {
                        state = "Não foi possível abrir a janela: " + e.getMessage();
                    }
                }

                @Override
                public void onAssociationCreated(AssociationInfo info) {
                    MacAddress mac = info.getDeviceMacAddress();
                    if (mac != null) {
                        String addr = mac.toString().toUpperCase();
                        if (Prefs.bondedAddr(a).isEmpty()) Prefs.setBondedAddr(a, addr);
                        try {
                            cdm.startObservingDevicePresence(addr);
                        } catch (Exception e) {
                            WLog.w("WB-CDM", "observar presença falhou", e);
                        }
                    }
                    state = "Modo acessório ativo ✅";
                    WLog.i("WB-CDM", "associação criada");
                }

                @Override
                public void onFailure(CharSequence error) {
                    WLog.w("WB-CDM", "associação falhou: " + error);
                    if (watchProfile) {
                        associate(a, false);
                    } else {
                        state = "Associação falhou: " + error;
                    }
                }
            });
        } catch (Exception e) {
            WLog.w("WB-CDM", "associate lançou exceção", e);
            if (watchProfile) associate(a, false);
            else state = "Associação falhou: " + e.getMessage();
        }
    }

    /**
     * Arranque automático do HyperOS/MIUI (operação 10008 do AppOps).
     * @return 1 ativo, 0 desligado, -1 desconhecido
     */
    static int autostart(Context c) {
        try {
            AppOpsManager ops = c.getSystemService(AppOpsManager.class);
            Method m = AppOpsManager.class.getMethod("checkOpNoThrow", int.class, int.class, String.class);
            int r = (Integer) m.invoke(ops, 10008, Process.myUid(), c.getPackageName());
            return r == AppOpsManager.MODE_ALLOWED ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }
}
