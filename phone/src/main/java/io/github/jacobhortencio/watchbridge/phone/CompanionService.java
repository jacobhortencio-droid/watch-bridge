package io.github.jacobhortencio.watchbridge.phone;

import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;

import io.github.jacobhortencio.watchbridge.common.WLog;

/**
 * Modo acessório: o Android acorda a app quando o relógio aparece por perto,
 * mesmo que o HyperOS a tenha fechado.
 */
public class CompanionService extends CompanionDeviceService {
    @Override
    public void onDeviceAppeared(AssociationInfo info) {
        WLog.i("WB-CDM", "relógio apareceu");
        BridgeService.start(this);
    }

    @Override
    public void onDeviceDisappeared(AssociationInfo info) {
        WLog.i("WB-CDM", "relógio desapareceu");
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onDeviceAppeared(String address) {
        BridgeService.start(this);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onDeviceDisappeared(String address) {
    }
}
