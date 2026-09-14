package dev.codex.audioroutelock;

import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class RouteSettings {
    static final String PREF_GROUP = "settings";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_TARGET_PACKAGES = "target_packages";
    /** 早期版本只支持单个目标应用，读取时做一次迁移。 */
    static final String KEY_TARGET_PACKAGE_LEGACY = "target_package";
    /** 每个目标应用各自的输出设备：package=type@address|package=type@address */
    static final String KEY_DEVICE_MAP = "device_map";
    /** 早期版本只有全局的一个输出设备，读取时迁移到每个应用。 */
    static final String KEY_DEVICE_TYPE_LEGACY = "device_type";
    static final String KEY_DEVICE_ADDRESS_LEGACY = "device_address";
    static final String KEY_MUTE_WHEN_MISSING = "mute_when_missing";
    static final String KEY_DEBUG = "debug";

    static final String SEPARATOR = ";";
    private static final String ENTRY_SEPARATOR = "|";
    private static final String PAIR_SEPARATOR = "=";
    private static final String VALUE_SEPARATOR = "@";

    /** 目标应用要锁定的输出设备。 */
    static final class DeviceRef {
        final int type;
        final String name;
        final String address;

        DeviceRef(int type, String name, String address) {
            this.type = type;
            this.name = name == null ? "" : sanitize(name);
            this.address = address == null ? "" : address;
        }

        private static String sanitize(String value) {
            return value.replace(VALUE_SEPARATOR.charAt(0), ' ')
                    .replace(PAIR_SEPARATOR.charAt(0), ' ')
                    .replace(ENTRY_SEPARATOR.charAt(0), ' ')
                    .replace('\n', ' ')
                    .trim();
        }

        String encode() {
            return type + VALUE_SEPARATOR + name + VALUE_SEPARATOR + address;
        }

        static DeviceRef decode(String raw) {
            if (raw == null) {
                return null;
            }
            int first = raw.indexOf(VALUE_SEPARATOR.charAt(0));
            if (first <= 0) {
                return null;
            }
            int last = raw.lastIndexOf(VALUE_SEPARATOR.charAt(0));
            try {
                int type = Integer.parseInt(raw.substring(0, first));
                if (last > first) {
                    return new DeviceRef(type, raw.substring(first + 1, last), raw.substring(last + 1));
                }
                // 兼容早期 type@address 格式
                return new DeviceRef(type, "", raw.substring(first + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    final boolean enabled;
    final List<String> targetPackages;
    final Map<String, DeviceRef> deviceMap;
    final boolean muteWhenMissing;
    final boolean debug;

    RouteSettings(
            boolean enabled,
            List<String> targetPackages,
            Map<String, DeviceRef> deviceMap,
            boolean muteWhenMissing,
            boolean debug
    ) {
        this.enabled = enabled;
        this.targetPackages = Collections.unmodifiableList(
                new ArrayList<>(targetPackages == null ? Collections.emptyList() : targetPackages));
        this.deviceMap = Collections.unmodifiableMap(
                new LinkedHashMap<>(deviceMap == null ? Collections.emptyMap() : deviceMap));
        this.muteWhenMissing = muteWhenMissing;
        this.debug = debug;
    }

    boolean isTarget(String packageName) {
        return packageName != null && targetPackages.contains(packageName);
    }

    DeviceRef deviceFor(String packageName) {
        return packageName == null ? null : deviceMap.get(packageName);
    }

    static String encode(List<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String packageName : packages) {
            if (packageName != null && !packageName.isEmpty()) {
                unique.add(packageName);
            }
        }
        return String.join(SEPARATOR, unique);
    }

    static List<String> decode(String raw) {
        List<String> packages = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return packages;
        }
        for (String packageName : raw.split(SEPARATOR)) {
            String trimmed = packageName.trim();
            if (!trimmed.isEmpty() && !packages.contains(trimmed)) {
                packages.add(trimmed);
            }
        }
        return packages;
    }

    static String encodeDeviceMap(Map<String, DeviceRef> deviceMap) {
        if (deviceMap == null || deviceMap.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, DeviceRef> entry : deviceMap.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(ENTRY_SEPARATOR);
            }
            builder.append(entry.getKey()).append(PAIR_SEPARATOR).append(entry.getValue().encode());
        }
        return builder.toString();
    }

    static Map<String, DeviceRef> decodeDeviceMap(String raw) {
        Map<String, DeviceRef> deviceMap = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return deviceMap;
        }
        for (String entry : raw.split("\\|")) {
            int index = entry.indexOf(PAIR_SEPARATOR.charAt(0));
            if (index <= 0) {
                continue;
            }
            String packageName = entry.substring(0, index);
            DeviceRef ref = DeviceRef.decode(entry.substring(index + 1));
            if (ref != null) {
                deviceMap.put(packageName, ref);
            }
        }
        return deviceMap;
    }

    static RouteSettings from(SharedPreferences prefs) {
        List<String> packages = decode(prefs.getString(KEY_TARGET_PACKAGES, ""));
        if (packages.isEmpty()) {
            packages = decode(prefs.getString(KEY_TARGET_PACKAGE_LEGACY, ""));
        }

        Map<String, DeviceRef> deviceMap = decodeDeviceMap(prefs.getString(KEY_DEVICE_MAP, ""));
        if (deviceMap.isEmpty() && prefs.contains(KEY_DEVICE_TYPE_LEGACY)) {
            DeviceRef legacy = new DeviceRef(
                    prefs.getInt(KEY_DEVICE_TYPE_LEGACY, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
                    "",
                    prefs.getString(KEY_DEVICE_ADDRESS_LEGACY, "")
            );
            for (String packageName : packages) {
                deviceMap.put(packageName, legacy);
            }
        }

        return new RouteSettings(
                prefs.getBoolean(KEY_ENABLED, false),
                packages,
                deviceMap,
                prefs.getBoolean(KEY_MUTE_WHEN_MISSING, true),
                prefs.getBoolean(KEY_DEBUG, false)
        );
    }

    SharedPreferences.Editor writeTo(SharedPreferences.Editor editor) {
        editor.putBoolean(KEY_ENABLED, enabled);
        editor.putString(KEY_TARGET_PACKAGES, encode(targetPackages));
        editor.putString(KEY_DEVICE_MAP, encodeDeviceMap(deviceMap));
        editor.remove(KEY_TARGET_PACKAGE_LEGACY);
        editor.remove(KEY_DEVICE_TYPE_LEGACY);
        editor.remove(KEY_DEVICE_ADDRESS_LEGACY);
        editor.putBoolean(KEY_MUTE_WHEN_MISSING, muteWhenMissing);
        editor.putBoolean(KEY_DEBUG, debug);
        return editor;
    }
}
