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
}
