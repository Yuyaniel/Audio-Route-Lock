package dev.codex.audioroutelock;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class ModuleMain extends XposedModule {
    private static final String TAG = "AudioRouteLock";

    private final Set<AudioTrack> knownTracks = newWeakSet();
    private final Set<MediaPlayer> knownPlayers = newWeakSet();
    private final Set<AudioTrack> mutedTracks = newWeakSet();
    private final Set<MediaPlayer> mutedPlayers = newWeakSet();

    private volatile RouteSettings settings;
    private volatile Context appContext;
    private volatile String hookedPackage;
    private volatile boolean hooksInstalled;
    private volatile boolean deviceCallbackRegistered;
    /** 锁定设备是否离线，缓存值，避免在每次 write 里做 getDevices() 的 Binder 调用。 */
    private volatile boolean lockedDeviceMissing = true;

    /**
     * RemotePreferences 内部用弱引用保存监听器，必须自己持有强引用，否则会被 GC 回收后静默失效。
     */
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesListener;

    private static <T> Set<T> newWeakSet() {
        return Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage()) {
            return;
        }

        hookedPackage = param.getPackageName();
        appContext = findContext();

        SharedPreferences prefs = getRemotePreferences(RouteSettings.PREF_GROUP);
        settings = RouteSettings.from(prefs);

        if (!isTarget()) {
            log(Log.INFO, TAG, hookedPackage + " is not the locked target, detaching");
            detach();
            return;
        }

        preferencesListener = (p, key) -> {
            settings = RouteSettings.from(p);
            applySettings("preferences changed");
        };
        prefs.registerOnSharedPreferenceChangeListener(preferencesListener);

        applySettings("package ready");
    }

    private boolean isTarget() {
        RouteSettings current = settings;
        return current != null
                && current.enabled
                && hookedPackage != null
                && current.isTarget(hookedPackage)
                && current.deviceFor(hookedPackage) != null;
    }

    private void applySettings(String reason) {
        if (!isTarget()) {
            setMuted(false);
            debug("Inactive (" + reason + ")");
            return;
        }

        if (!hooksInstalled) {
            appContext = appContext == null ? findContext() : appContext;
            installHooks();
            hooksInstalled = true;
            log(Log.INFO, TAG, "Hooks installed for " + hookedPackage + " (" + reason + ")");
            logEvent("已对 " + hookedPackage + " 安装钩子（" + reason + "）");
        }
        updateMuteState();
    }

    private void installHooks() {
        try {
            hookAudioTrackConstructors();
            hookAudioTrackPlay();
            hookAudioTrackVolume();
            hookMediaPlayerConstructors();
            hookMediaPlayerStart();
            hookMediaPlayerVolume();
            registerDeviceCallback();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to install hooks", t);
        }
    }

    private void hookAudioTrackConstructors() {
        for (Constructor<?> constructor : AudioTrack.class.getDeclaredConstructors()) {
            hook(constructor)
                    .setId("audio-track-ctor")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object receiver = chain.getThisObject();
                        if (receiver instanceof AudioTrack) {
                            AudioTrack track = (AudioTrack) receiver;
                            knownTracks.add(track);
                            if (!applyPreferredDevice(track) && shouldSilenceWhenMissing()) {
                                muteTrack(track);
                            }
                        }
                        return result;
                    });
        }
    }

    private void hookAudioTrackPlay() throws NoSuchMethodException {
        Method play = AudioTrack.class.getDeclaredMethod("play");
        hook(play)
                .setId("audio-track-play")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object receiver = chain.getThisObject();
                    if (isTarget() && receiver instanceof AudioTrack) {
                        applyPreferredDevice((AudioTrack) receiver);
                    }
                    return chain.proceed();
                });
    }

    private void hookAudioTrackVolume() {
        try {
            Method setVolume = AudioTrack.class.getDeclaredMethod("setVolume", float.class);
            hook(setVolume)
                    .setId("audio-track-volume")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (isTarget() && !lockedDeviceAvailable() && shouldSilenceWhenMissing()) {
                            Object receiver = chain.getThisObject();
                            if (receiver instanceof AudioTrack && knownTracks.contains(receiver)) {
                                return chain.proceed(new Object[]{0f});
                            }
                        }
                        return chain.proceed();
                    });
        } catch (NoSuchMethodException ignored) {
        }
    }

    private void hookMediaPlayerVolume() {
        try {
            Method setVolume = MediaPlayer.class.getDeclaredMethod("setVolume", float.class, float.class);
            hook(setVolume)
                    .setId("media-player-volume")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (isTarget() && !lockedDeviceAvailable() && shouldSilenceWhenMissing()) {
                            Object receiver = chain.getThisObject();
                            if (receiver instanceof MediaPlayer && knownPlayers.contains(receiver)) {
                                return chain.proceed(new Object[]{0f, 0f});
                            }
                        }
                        return chain.proceed();
                    });
        } catch (NoSuchMethodException ignored) {
        }
    }

    private void hookMediaPlayerConstructors() {
        for (Constructor<?> constructor : MediaPlayer.class.getDeclaredConstructors()) {
            hook(constructor)
                    .setId("media-player-ctor")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object receiver = chain.getThisObject();
                        if (receiver instanceof MediaPlayer) {
                            knownPlayers.add((MediaPlayer) receiver);
                        }
                        return result;
                    });
        }
    }

    private void hookMediaPlayerStart() throws NoSuchMethodException {
        Method start = MediaPlayer.class.getDeclaredMethod("start");
        hook(start)
                .setId("media-player-start")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (!isTarget()) {
                        return chain.proceed();
                    }
                    Object receiver = chain.getThisObject();
                    if (receiver instanceof MediaPlayer) {
                        MediaPlayer player = (MediaPlayer) receiver;
                        knownPlayers.add(player);
                        if (!lockedDeviceAvailable() && shouldSilenceWhenMissing()) {
                            logEvent("已静音 MediaPlayer（锁定设备不可用）");
                            mutePlayer(player);
                        } else {
                            AudioDeviceInfo device = findLockedDevice();
                            if (device != null) {
                                player.setPreferredDevice(device);
                            }
                        }
                    }
                    return chain.proceed();
                });
    }

    private void registerDeviceCallback() {
        if (deviceCallbackRegistered) {
            return;
        }
        Context context = appContext == null ? findContext() : appContext;
        if (context == null) {
            return;
        }
        appContext = context;
        try {
            AudioManager audioManager = context.getSystemService(AudioManager.class);
            if (audioManager == null) {
                return;
            }
            audioManager.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    onDeviceSetChanged();
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    onDeviceSetChanged();
                }
            }, null);
            deviceCallbackRegistered = true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed to register audio device callback", t);
        }
    }

    /**
     * 蓝牙断开时系统会把正在播放的轨道改回扬声器，静音写入无法覆盖已经建立的原生输出，
     * 因此设备变化时直接把已记录的轨道/播放器音量压到 0，避免声音从扬声器出来。
     */
    private void onDeviceSetChanged() {
        if (!isTarget()) {
            setMuted(false);
            return;
        }
        refreshDeviceState();
        boolean missing = lockedDeviceMissing;
        debug("Audio devices changed, locked output " + (missing ? "unavailable" : "available"));
        logEvent("音频设备变化，锁定设备" + (missing ? "不可用" : "可用"));
        setMuted(missing);
    }

    private void updateMuteState() {
        if (!isTarget()) {
            setMuted(false);
            return;
        }
        refreshDeviceState();
        setMuted(lockedDeviceMissing);
    }

    private void setMuted(boolean mute) {
        synchronized (knownTracks) {
            for (AudioTrack track : knownTracks) {
                if (mute) {
                    muteTrack(track);
                } else if (mutedTracks.remove(track)) {
                    try {
                        track.setVolume(1f);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        synchronized (knownPlayers) {
            for (MediaPlayer player : knownPlayers) {
                if (mute) {
                    mutePlayer(player);
                } else if (mutedPlayers.remove(player)) {
                    try {
                        player.setVolume(1f, 1f);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void muteTrack(AudioTrack track) {
        try {
            track.setVolume(0f);
            mutedTracks.add(track);
        } catch (Throwable ignored) {
        }
    }

    private void mutePlayer(MediaPlayer player) {
        try {
            player.setVolume(0f, 0f);
            mutedPlayers.add(player);
        } catch (Throwable ignored) {
        }
    }

    private boolean applyPreferredDevice(AudioTrack track) {
        if (!isTarget()) {
            return false;
        }
        AudioDeviceInfo device = findLockedDevice();
        if (device == null) {
            debug("Locked output is unavailable");
            return false;
        }
        boolean ok = track.setPreferredDevice(device);
        debug("setPreferredDevice(" + AudioDeviceMatcher.displayName(device) + ") -> " + ok);
        return ok;
    }

    private boolean lockedDeviceAvailable() {
        return !lockedDeviceMissing;
    }

    /** 重新查询锁定设备是否在线，仅在设备变化/设置变化/安装钩子时调用。 */
    private void refreshDeviceState() {
        lockedDeviceMissing = findLockedDevice() == null;
    }

    private AudioDeviceInfo findLockedDevice() {
        Context context = appContext == null ? findContext() : appContext;
        appContext = context;
        RouteSettings current = settings;
        if (context == null || current == null || hookedPackage == null) {
            return null;
        }
        RouteSettings.DeviceRef device = current.deviceFor(hookedPackage);
        if (device == null) {
            return null;
        }
        return AudioDeviceMatcher.findLockedDevice(context, device.type, device.address);
    }

    private boolean shouldSilenceWhenMissing() {
        RouteSettings current = settings;
        return isTarget() && current.muteWhenMissing;
    }

    private Context findContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            Application application = (Application) currentApplication.invoke(null);
            return application == null ? null : application.getApplicationContext();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void debug(String message) {
        RouteSettings current = settings;
        if (current != null && current.debug) {
            log(Log.DEBUG, TAG, message);
        }
    }

    private boolean debugEnabled() {
        RouteSettings current = settings;
        return current != null && current.debug;
    }

    /** 值得在应用内展示的关键事件：始终写入 Xposed 日志，开启调试时回传到应用内日志页。 */
    private void logEvent(String message) {
        log(Log.INFO, TAG, message);
        if (debugEnabled()) {
            appendToAppLog(message);
        }
    }

    private void appendToAppLog(String message) {
        try {
            Context context = appContext == null ? findContext() : appContext;
            if (context == null) {
                return;
            }
            appContext = context;
            Uri uri = Uri.parse("content://" + DebugLogProvider.AUTHORITY);
            context.getContentResolver().call(uri, "append", message, new Bundle());
        } catch (Throwable ignored) {
        }
    }
}
