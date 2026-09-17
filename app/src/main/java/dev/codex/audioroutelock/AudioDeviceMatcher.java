package dev.codex.audioroutelock;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

final class AudioDeviceMatcher {
    private AudioDeviceMatcher() {
    }

    /**
     * 在系统输出设备里定位「锁定设备」。匹配优先级：地址精确匹配 → 同名（产品名）匹配 → 同类型兜底。
     *
     * <p>地址匹配失败时<strong>不能</strong>直接判离线：部分 ROM 上报的 {@code AudioDeviceInfo.getAddress()}
     * 为空或不稳定（重新连接后地址/格式变化），会把「蓝牙明明连着」误判成离线，导致目标应用被一路静音
     * （症状：连上耳机也没声音）。所以地址对不上时按「同名 → 同类型」放宽：宁可把同类型的另一只蓝牙
     * 当在线（顶多不静音），也不误判离线把目标应用永久静音。
     */
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
        return fallbackByName != null ? fallbackByName : fallbackByType;
    }

    static String displayName(AudioDeviceInfo device) {
        CharSequence name = device.getProductName();
        return name == null || name.length() == 0 ? "unknown" : name.toString();
    }
}
