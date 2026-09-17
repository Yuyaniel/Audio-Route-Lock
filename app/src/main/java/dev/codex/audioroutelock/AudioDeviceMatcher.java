package dev.codex.audioroutelock;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

final class AudioDeviceMatcher {
    private AudioDeviceMatcher() {
    }

    static AudioDeviceInfo findLockedDevice(Context context, int deviceType, String deviceName, String deviceAddress) {
        AudioManager audioManager = context.getSystemService(AudioManager.class);
        if (audioManager == null) {
            return null;
        }

        String expectedAddress = deviceAddress == null ? "" : deviceAddress;
        String expectedName = deviceName == null ? "" : deviceName;
        AudioDeviceInfo fallbackByName = null;
        AudioDeviceInfo fallbackByType = null;
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : devices) {
            if (device.getType() != deviceType || !device.isSink()) {
                continue;
            }

            String address = device.getAddress();
            if (!expectedAddress.isEmpty() && expectedAddress.equals(address)) {
                return device;
            }
            if (fallbackByType == null) {
                fallbackByType = device;
            }
            if (fallbackByName == null && !expectedName.isEmpty() && expectedName.equals(displayName(device))) {
                fallbackByName = device;
            }
        }
        // 存了地址却匹配不上（某些 ROM 地址为空/不稳定、或设备重连后上报变化导致条目失配）：
        // 先按产品名兜底，避免把在线设备误判成离线而一直静音；不再退到任意同类型设备（防锁错路由）。
        if (!expectedAddress.isEmpty()) {
            return fallbackByName;
        }
        return fallbackByType;
    }

    static String displayName(AudioDeviceInfo device) {
        CharSequence name = device.getProductName();
        return name == null || name.length() == 0 ? "unknown" : name.toString();
    }

    /** 常见设备类型的中文名，日志可读性用；不认识的类型原样输出数字。 */
    static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "扬声器";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "有线耳机(带麦)";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "有线耳机";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "蓝牙SCO";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "蓝牙A2DP";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "BLE耳机(LE Audio)";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return "BLE音箱(LE Audio)";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB耳机";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB设备";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            default: return "type=" + type;
        }
    }
}
