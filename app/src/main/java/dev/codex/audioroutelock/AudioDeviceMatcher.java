package dev.codex.audioroutelock;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

final class AudioDeviceMatcher {
    private AudioDeviceMatcher() {
    }

    static AudioDeviceInfo findLockedDevice(Context context, int deviceType, String deviceAddress) {
        AudioManager audioManager = context.getSystemService(AudioManager.class);
        if (audioManager == null) {
            return null;
        }

        String expectedAddress = deviceAddress == null ? "" : deviceAddress;
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
        }
        return expectedAddress.isEmpty() ? fallbackByType : null;
    }

    static String displayName(AudioDeviceInfo device) {
        CharSequence name = device.getProductName();
        return name == null || name.length() == 0 ? "unknown" : name.toString();
    }
}
