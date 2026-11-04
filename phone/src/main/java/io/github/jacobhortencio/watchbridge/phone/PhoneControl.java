package io.github.jacobhortencio.watchbridge.phone;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;

import io.github.jacobhortencio.watchbridge.common.Msg;
import io.github.jacobhortencio.watchbridge.common.Proto;
import io.github.jacobhortencio.watchbridge.common.WLog;

/** Controlo do telemóvel a partir do relógio. Alguns comandos precisam do Shizuku. */
final class PhoneControl {
    private PhoneControl() {}

    private static boolean torch;

    static void handle(Context c, String cmd) {
        String r;
        switch (cmd) {
            case "torch":
                r = toggleTorch(c);
                break;
            case "ring_normal":
                r = ringer(c, AudioManager.RINGER_MODE_NORMAL, "Som ativado");
                break;
            case "ring_vibrate":
                r = ringer(c, AudioManager.RINGER_MODE_VIBRATE, "Só vibração");
                break;
            case "ring_silent":
                r = ringer(c, AudioManager.RINGER_MODE_SILENT, "Silêncio");
                break;
            case "wifi_on":
                r = shizuku("svc wifi enable", "Wi-Fi ligado");
                break;
            case "wifi_off":
                r = shizuku("svc wifi disable", "Wi-Fi desligado");
                break;
            case "data_on":
                r = shizuku("svc data enable", "Dados móveis ligados");
                break;
            case "data_off":
                r = shizuku("svc data disable", "Dados móveis desligados");
                break;
            case "airplane_on":
                r = shizuku("cmd connectivity airplane-mode enable", "Modo de avião ligado");
                break;
            case "airplane_off":
                r = shizuku("cmd connectivity airplane-mode disable", "Modo de avião desligado");
                break;
            case "lock":
                r = shizuku("input keyevent 223", "Ecrã bloqueado");
                break;
            case "screenshot":
                r = shizuku("screencap -p /sdcard/Pictures/Screenshots/WatchBridge_" + System.currentTimeMillis() + ".png",
                        "Captura guardada em Imagens/Screenshots");
                break;
            default:
                r = "Comando desconhecido";
        }
        WLog.i("WB-Ctl", cmd + ": " + r);
        BridgeService.send(Msg.of(Proto.RESULT, "msg", r));
    }

    private static String toggleTorch(Context c) {
        try {
            CameraManager cm = c.getSystemService(CameraManager.class);
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    torch = !torch;
                    cm.setTorchMode(id, torch);
                    return torch ? "Lanterna ligada" : "Lanterna desligada";
                }
            }
            return "Sem lanterna";
        } catch (Exception e) {
            return "Lanterna falhou: " + e.getMessage();
        }
    }

    private static String ringer(Context c, int mode, String ok) {
        try {
            c.getSystemService(AudioManager.class).setRingerMode(mode);
            return ok;
        } catch (Exception e) {
            return "Precisa de permitir o Não incomodar no telemóvel";
        }
    }

    private static String shizuku(String cmd, String ok) {
        if (!ShizukuTool.granted()) return "Precisa do Shizuku ativo e autorizado no telemóvel";
        String out = ShizukuTool.run(cmd);
        return out.startsWith("erro") ? out : ok;
    }
}
