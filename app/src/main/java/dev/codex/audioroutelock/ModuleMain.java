package dev.codex.audioroutelock;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.util.Log;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    /** 目标应用创建的 WebView 实例，用于网页音频静音。 */
    private final Set<WebView> knownWebViews = newWeakSet();
    /** 已挂过「页面加载完成」钩子的类（基类或应用自己的 WebViewClient 子类），避免重复安装。 */
    private final Set<Class<?>> navigationHookedClasses = Collections.synchronizedSet(new HashSet<>());

    private volatile RouteSettings settings;
    private volatile Context appContext;
    private volatile String hookedPackage;
    private volatile boolean hooksInstalled;
    private volatile boolean deviceCallbackRegistered;
    /** 锁定设备是否离线，缓存值，避免在每次 write 里做 getDevices() 的 Binder 调用。 */
    private volatile boolean lockedDeviceMissing = true;

    /** WebView 相关钩子是否已安装（同一进程只装一次）。 */
    private volatile boolean webViewHooksInstalled;

    // 当前是否要求网页音频静音（网页脚本层的状态）。
    private volatile boolean browserMuted;
    // 最近一次网页静音脚本的返回值，形如 "已静音数|元素数|iframe数|WebAudio数"，供状态行展示。
    private volatile String lastJsSummary;
    // 上一次写进日志的状态快照：内容完全相同就不再写。之前用 5 秒时间窗，设备回调风暴下
    // 状态行仍会持续刷屏，把其它应用的日志挤出缓冲区（X/XF 的记录因此看不见）。
    // 「清空日志」之后的重写由设置变化触发（applySettings 会清掉这条基线）。
    private volatile String lastStatusLine;
    // 「一次性事件」日志的按类别去重基线（key=来源类别）：同一条静音/匹配失败消息内容不变
    // 就不再重写，既能留下证据又不会刷屏。
    private final Map<String, String> lastLoggedOnce = new HashMap<>();
    // 同一次页面加载可能同时命中「基类 + 子类」两个钩子，做一次去抖避免重复注入。
    private static final long PAGE_LOAD_DEDUP_MS = 500L;
    private volatile WebView lastPageLoadView;
    private volatile long lastPageLoadAt;

    // 主线程 Handler，用于把 WebView 操作投递到 UI 线程。延迟创建，避免模块构造期的线程问题。
    private volatile Handler mainHandler;

    private Handler mainHandler() {
        Handler handler = mainHandler;
        if (handler == null) {
            synchronized (this) {
                handler = mainHandler;
                if (handler == null) {
                    handler = new Handler(Looper.getMainLooper());
                    mainHandler = handler;
                }
            }
        }
        return handler;
    }

    /**
     * RemotePreferences 内部用弱引用保存监听器，必须自己持有强引用，否则会被 GC 回收后静默失效。
     */
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesListener;

    private static <T> Set<T> newWeakSet() {
        return Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        // 这一行在设置加载之前执行，无法按「输出调试日志」开关判断；只进 logcat（Xposed 日志）、
        // 不写日志页也不落盘，作为"模块注入进了哪个进程"的排查依据保留。
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
            if (debugEnabled()) {
                log(Log.INFO, TAG, hookedPackage + " is not the locked target, detaching");
            }
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

    /**
     * 目标应用是否在锁定范围内（不要求已配置设备）：模块要在应用刚启动、还没建出 WebView 的阶段
     * 就装好钩子，所以不能复用要求已配置设备的 {@link #isTarget()}。
     */
    private boolean isScopedTarget() {
        RouteSettings current = settings;
        return current != null
                && current.enabled
                && hookedPackage != null
                && current.isTarget(hookedPackage);
    }

    /**
     * 目标应用当前是否应当静音：**锁定设备离线** 且「锁定设备不可用时静音」开关打开。
     *
     * <p>浏览器（Via 等）与其它应用走**同一套语义**：设备在线就正常出声，设备离线就静音。
     * 区别只在静音手段——Java 轨道/播放器用 {@code setVolume(0)}，网页音频注入页面脚本。
     * 网页音频是 Chromium 的 native 输出，模块无法用 {@code setPreferredDevice} 锁路由，
     * 所以这里只保证「锁定设备离线时不出声」，不强求设备在线时一定路由到该设备。
     */
    private boolean shouldSilenceOutput() {
        return lockedDeviceMissing && shouldSilenceWhenMissing();
    }

    private void applySettings(String reason) {
        if (!isScopedTarget()) {
            setMuted(false);
            debug("Inactive (" + reason + ")");
            reportStatus();
            return;
        }

        if (!hooksInstalled) {
            appContext = appContext == null ? findContext() : appContext;
            installHooks();
            hooksInstalled = true;
            logEvent("已对 " + hookedPackage + " 安装钩子（" + reason + "）");
        }
        updateMuteState();
        // 每次设置变化都留一份状态快照（仅在开启「输出调试日志」时写入）。清掉去重基线，
        // 保证「刚打开调试日志」那一次一定写出来。
        lastStatusLine = null;
        reportStatus();
    }

    private void installHooks() {
        try {
            hookAudioTrackConstructors();
            hookAudioTrackPlay();
            hookAudioTrackVolume();
            hookMediaPlayerConstructors();
            hookMediaPlayerStart();
            hookMediaPlayerVolume();
            hookBrowserAudio();
            registerDeviceCallback();
            // 安装完成时的设备现场快照：之后任何静音都能对照「当时设备长什么样」。
            logEventOnce("startup-dump", "【启动】" + hookedPackage + " 钩子已安装｜锁定目标="
                    + describeExpected() + "｜当前输出=" + describeDeviceSet());
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
                                // 之前这里静音不留任何记录，导致「没声音却查不到原因」。
                                logEventOnce("track-ctor-mute",
                                        "【静音】AudioTrack 创建即被静音（原因=锁定设备匹配失败，方法=setVolume(0)）");
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
                                logEventOnce("track-volume-force",
                                        "【静音】应用调用 AudioTrack.setVolume 被强制改为 0（原因=锁定设备不可用，方法=钩子改参）");
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
                                logEventOnce("player-volume-force",
                                        "【静音】应用调用 MediaPlayer.setVolume 被强制改为 0（原因=锁定设备不可用，方法=钩子改参）");
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
                            logDeviceMiss("MediaPlayer.start");
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

    // ------------------------------------------------------------------
    // 浏览器网页音频（Chromium / WebView）
    // ------------------------------------------------------------------

    /**
     * 浏览器网页音频既不走 Java 的 AudioTrack 也不走 MediaPlayer（Chromium 用 native
     * AAudio / OpenSL ES 直接输出），所以只能从页面侧接管：记录 WebView 实例、注入静音脚本，
     * 并在「页面加载完成」时补注入（见 {@link #hookWebViewUsage()}）。
     */
    private void hookBrowserAudio() {
        WebViewAudioMuter.resultListener = new WebViewAudioMuter.ResultListener() {
            private volatile String lastSummary;

            @Override
            public void onResult(boolean mute, String summary) {
                lastJsSummary = summary;
                // 仅在结果变化时写日志，避免重复注入刷屏。
                if (summary != null && summary.equals(lastSummary)) {
                    return;
                }
                lastSummary = summary;
                if (mute) {
                    appendToAppLog("【网页静音】" + summary);
                } else {
                    lastSummary = null;
                    appendToAppLog("【网页静音】已恢复网页音频");
                }
            }
        };
        hookWebViewConstructors();
        hookWebViewUsage();
    }

    /**
     * 模块装钩子时目标应用可能已经建好 WebView（构造钩子已经错过），因此再挂几个应用几乎必然
     * 会调用的 WebView 方法，补上实例登记与静音脚本注入。
     *
     * <p>另外从 setWebViewClient / setWebChromeClient 拿到应用自己的客户端实例，挂上
     * 「页面加载完成」事件（见 {@link #hookClientNavigation(Object)}）：跳转会换掉 document，
     * 之前注入的网页守卫随旧文档消失，需要靠这个事件补注入，而不是靠定时重注入。
     */
    private void hookWebViewUsage() {
        hookWebViewMethod("loadUrl", String.class);
        hookWebViewMethod("loadUrl", String.class, Map.class);
        hookWebViewClientLookup();
        hookWebChromeClientLookup();
    }

    private void hookWebViewClientLookup() {
        try {
            Method setter = WebView.class.getDeclaredMethod("setWebViewClient", WebViewClient.class);
            hook(setter)
                    .setId("webview-set-client")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object client = chain.getArg(0);
                        Object result = chain.proceed();
                        hookClientNavigation(client);
                        Object receiver = chain.getThisObject();
                        if (receiver instanceof WebView) {
                            observeWebView((WebView) receiver);
                        }
                        return result;
                    });
        } catch (Throwable t) {
            debug("Hook WebView#setWebViewClient failed: " + t);
        }
    }

    private void hookWebChromeClientLookup() {
        try {
            Method setter = WebView.class.getDeclaredMethod("setWebChromeClient", WebChromeClient.class);
            hook(setter)
                    .setId("webview-set-chrome-client")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object client = chain.getArg(0);
                        Object result = chain.proceed();
                        hookClientNavigation(client);
                        return result;
                    });
        } catch (Throwable t) {
            debug("Hook WebView#setWebChromeClient failed: " + t);
        }
    }

    /**
     * 挂「页面加载完成」事件。应用自己的 WebViewClient / WebChromeClient 子类通常会覆写
     * {@code onPageFinished} / {@code onProgressChanged}，覆写时基类实现不会被调用，所以要优先挂
     * 实例的实现类；没覆写才退回基类。同一个类只挂一次。
     */
    private void hookClientNavigation(Object client) {
        if (client == null) {
            return;
        }
        Class<?> owner = client.getClass();
        Method method = findDeclared(owner, "onPageFinished", WebView.class, String.class);
        boolean chromeClient = false;
        if (method == null) {
            method = findDeclared(owner, "onProgressChanged", WebView.class, int.class);
            chromeClient = true;
        }
        if (method == null) {
            owner = WebViewClient.class;
            method = findDeclared(owner, "onPageFinished", WebView.class, String.class);
            if (method == null) {
                owner = WebChromeClient.class;
                method = findDeclared(owner, "onProgressChanged", WebView.class, int.class);
                chromeClient = true;
            }
        }
        installPageLoadHook(method, owner, chromeClient);
    }

    private void installPageLoadHook(Method method, Class<?> owner, boolean chromeClient) {
        if (method == null || owner == null || !navigationHookedClasses.add(owner)) {
            return;
        }
        try {
            hook(method)
                    .setId("webview-page-load")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object view = chain.getArg(0);
                        Object progress = chromeClient ? chain.getArg(1) : null;
                        Object result = chain.proceed();
                        boolean finished = !chromeClient
                                || (progress instanceof Integer && (Integer) progress >= 100);
                        if (finished && view instanceof WebView) {
                            onPageLoadFinished((WebView) view);
                        }
                        return result;
                    });
        } catch (Throwable t) {
            debug("Hook page-load signal on " + owner.getName() + " failed: " + t);
        }
    }

    /**
     * 页面加载完成：登记 WebView 实例，并补一次静音脚本注入——跳转会换掉 document，
     * 之前注入的守卫随旧文档消失。纯事件驱动：不加载页面就什么都不做。
     */
    private void onPageLoadFinished(WebView view) {
        try {
            knownWebViews.add(view);
            long now = System.currentTimeMillis();
            if (view == lastPageLoadView && now - lastPageLoadAt < PAGE_LOAD_DEDUP_MS) {
                return;
            }
            lastPageLoadView = view;
            lastPageLoadAt = now;
            if (browserMuted) {
                WebViewAudioMuter.apply(view, true);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Method findDeclared(Class<?> owner, String name, Class<?>... parameters) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getDeclaredMethod(name, parameters);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void hookWebViewMethod(String name, Class<?>... parameterTypes) {
        try {
            Method method = WebView.class.getDeclaredMethod(name, parameterTypes);
            hook(method)
                    .setId("webview-" + name + "-" + parameterTypes.length)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object receiver = chain.getThisObject();
                        if (receiver instanceof WebView) {
                            observeWebView((WebView) receiver);
                        }
                        return result;
                    });
        } catch (Throwable t) {
            debug("Hook WebView#" + name + " failed: " + t);
        }
    }

    private void hookWebViewConstructors() {
        if (webViewHooksInstalled) {
            return;
        }
        webViewHooksInstalled = true;
        try {
            for (Constructor<?> constructor : WebView.class.getDeclaredConstructors()) {
                hook(constructor)
                        .setId("webview-ctor")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object receiver = chain.getThisObject();
                            if (receiver instanceof WebView) {
                                // 构造函数里**不要**调用 WebView 的方法（此时 native 侧尚未完全就绪，
                                // 很容易把目标应用搞崩）。只登记实例并把后续动作投递到主线程。
                                WebView view = (WebView) receiver;
                                knownWebViews.add(view);
                                if (browserMuted) {
                                    Handler handler = mainHandler();
                                    handler.post(() -> {
                                        try {
                                            WebViewAudioMuter.apply(view, true);
                                        } catch (Throwable ignored) {
                                        }
                                    });
                                }
                            }
                            return result;
                        });
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed to hook WebView", t);
        }
    }

    /**
     * 应用主动使用 WebView 时（loadUrl / setWebViewClient）调用：此时 WebView 已构造完成，
     * 可以按当前设备状态补一次静音脚本。
     */
    private void observeWebView(WebView view) {
        try {
            knownWebViews.add(view);
            if (browserMuted) {
                // 静音期间新建 / 新导航的 WebView：立刻注入一次脚本（新文档随后由
                // 「页面加载完成」事件再补一次）。
                WebViewAudioMuter.apply(view, true);
            } else if (shouldSilenceOutput()) {
                setBrowserMuted(true);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 切换静音状态。
     *
     * <p>静音侧瞬时施加（防泄露优先）；恢复侧立即恢复，脚本自身用 40ms 音量斜坡消除爆音。
     */
    private void setBrowserMuted(boolean mute) {
        if (browserMuted == mute) {
            return;
        }
        browserMuted = mute;
        setWebViewsMuted(mute);
        logEvent(mute ? "【静音】锁定设备不在线 → 已静音" : "【静音】锁定设备已恢复 → 已出声");
    }

    /** 最近一次施加给 WebView 的静音状态，避免重复写同类日志。 */
    private volatile Boolean webViewsMutedState;

    /**
     * 网页音频静音：对已知 WebView 注入 / 恢复静音脚本（元素 + Web Audio + 原型级音量写入口钳制）。
     * 这是模块对网页音频的唯一手段，细节见 {@link WebViewAudioMuter}。
     */
    private void setWebViewsMuted(boolean mute) {
        List<WebView> views;
        synchronized (knownWebViews) {
            if (knownWebViews.isEmpty()) {
                return;
            }
            views = new ArrayList<>(knownWebViews);
        }
        for (WebView view : views) {
            WebViewAudioMuter.apply(view, mute);
        }
        if (!Boolean.valueOf(mute).equals(webViewsMutedState)) {
            webViewsMutedState = mute;
            debug("WebView 网页音频兜底静音 -> " + mute + "（" + views.size() + " 个实例）");
        }
    }

    /**
     * 蓝牙断开时系统会把正在播放的轨道改回扬声器，静音写入无法覆盖已经建立的原生输出，
     * 因此设备变化时直接把已记录的轨道/播放器音量压到 0，避免声音从扬声器出来。
     */
    private void onDeviceSetChanged() {
        if (!isScopedTarget()) {
            setMuted(false);
            return;
        }
        refreshDeviceState();
        boolean missing = lockedDeviceMissing;
        debug("Audio devices changed, locked output " + (missing ? "unavailable" : "available"));
        logEvent("音频设备变化，锁定设备" + (missing ? "不可用" : "可用"));
        debug("Audio devices changed, silence=" + shouldSilenceOutput());
        setMuted(missing);
        reportStatus();
    }

    private void updateMuteState() {
        if (!isScopedTarget()) {
            setMuted(false);
            return;
        }
        refreshDeviceState();
        setMuted(lockedDeviceMissing);
    }

    /**
     * 决定静音状态。**所有应用（含 Chromium / Via）同一套语义**：锁定设备离线且「锁定设备不可用
     * 时静音」打开 → 静音；设备在线（或开关关闭、不在目标列表）→ 出声。
     *
     * <p>开关本身会走 persist -> 设置变化 -> 这里，所以打开/关闭都能得到一致的结果。
     */
    private void setMuted(boolean lockDeviceMissing) {
        boolean silence = lockDeviceMissing && shouldSilenceWhenMissing();
        // 判定变化的证据：为什么进静音/为什么解除。内容会在两种状态间翻转，每次翻转各写一条。
        logEventOnce("set-muted", "【判定】" + (silence ? "进入静音" : "解除静音")
                + "（原因=锁定设备" + (lockDeviceMissing ? "不可用" : "可用")
                + "，静音开关=" + shouldSilenceWhenMissing() + "）");
        applySilence(silence, silence);
    }

    /**
     * 统一施加静音。
     *
     * @param silenceBrowser 是否静音网页音频（注入页面脚本）
     * @param silencePlayers 是否静音 Java 轨道/播放器
     */
    private void applySilence(boolean silenceBrowser, boolean silencePlayers) {
        setBrowserMuted(silenceBrowser);
        synchronized (knownTracks) {
            for (AudioTrack track : knownTracks) {
                if (silencePlayers) {
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
                if (silencePlayers) {
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
            // 匹配失败时把「期望配置 vs 系统当前设备」完整 dump 进日志页，失配原因一目了然。
            logDeviceMiss("AudioTrack 路由");
            return false;
        }
        boolean ok = track.setPreferredDevice(device);
        debug("setPreferredDevice(" + AudioDeviceMatcher.displayName(device) + ") -> " + ok);
        if (!ok) {
            logEventOnce("spd-rejected",
                    "【路由】setPreferredDevice(" + AudioDeviceMatcher.displayName(device) + ") 返回 false（设备已找到但系统拒绝锁定）");
        }
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
        return AudioDeviceMatcher.findLockedDevice(context, device.type, device.name, device.address);
    }

    private boolean shouldSilenceWhenMissing() {
        RouteSettings current = settings;
        return isTarget() && current.muteWhenMissing;
    }

    /** 同一类别的事件日志：内容不变就不重写（防刷屏），内容一变立即写。 */
    private void logEventOnce(String key, String message) {
        synchronized (lastLoggedOnce) {
            if (message.equals(lastLoggedOnce.get(key))) {
                return;
            }
            lastLoggedOnce.put(key, message);
        }
        logEvent(message);
    }

    /**
     * 设备匹配失败时的现场快照：配置里期望的设备（类型/名称/地址）vs 系统当前上报的全部输出设备。
     * 排查「明明在线却被判离线」全靠这一条。
     */
    private void logDeviceMiss(String where) {
        if (!debugEnabled()) {
            return;
        }
        logEventOnce("device-miss", "【设备匹配失败】" + where
                + "｜锁定目标=" + describeExpected()
                + "｜当前输出=" + describeDeviceSet());
    }

    private String describeExpected() {
        RouteSettings current = settings;
        RouteSettings.DeviceRef ref = current == null || hookedPackage == null
                ? null : current.deviceFor(hookedPackage);
        if (ref == null) {
            return "无设备配置";
        }
        return AudioDeviceMatcher.typeName(ref.type)
                + "「" + (ref.name.isEmpty() ? "未存名" : ref.name) + "」"
                + "地址=" + (ref.address.isEmpty() ? "(空)" : ref.address);
    }

    private String describeDeviceSet() {
        Context context = appContext == null ? findContext() : appContext;
        appContext = context;
        AudioManager audioManager = context == null ? null : context.getSystemService(AudioManager.class);
        if (audioManager == null) {
            return "(AudioManager 不可用)";
        }
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!device.isSink()) {
                continue;
            }
            if (!first) {
                builder.append('；');
            }
            first = false;
            builder.append(AudioDeviceMatcher.typeName(device.getType()))
                    .append("「").append(AudioDeviceMatcher.displayName(device)).append("」")
                    .append("地址=").append(device.getAddress().isEmpty() ? "(空)" : device.getAddress());
        }
        if (first) {
            builder.append("(无输出设备)");
        }
        return builder.toString();
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

    /** 当前在起作用的静音手段（写进状态快照）。 */
    private String activeSilenceLayer() {
        return browserMuted ? "网页脚本（元素 + Web Audio，会改动页面音量控件）" : "未静音";
    }

    /**
     * 把一份状态快照写进应用内日志。只有开启「输出调试日志」时才会真正写入（见 appendToAppLog）。
     * 内容与上一次相同就不重复写（设备回调可能在一秒内重复触发多次）。
     */
    private void reportStatus() {
        if (!hooksInstalled && !isTarget()) {
            return;
        }
        if (!debugEnabled()) {
            // 关闭调试日志时不写、也不更新去重基线：否则重新打开开关时首帧会被误判成"重复"而丢掉。
            return;
        }
        RouteSettings current = settings;
        String line = "状态 " + hookedPackage
                + "｜启用=" + (current != null && current.enabled)
                + "｜允许出声=" + (isTarget() && !shouldSilenceOutput())
                + "｜锁定设备=" + (lockedDeviceMissing ? "不可用" : "可用")
                + "｜WebView=" + knownWebViews.size()
                + "｜网页脚本=" + (lastJsSummary == null ? "未执行" : lastJsSummary)
                + "｜生效层=" + activeSilenceLayer()
                + "｜静音中=" + browserMuted;
        if (line.equals(lastStatusLine)) {
            return;
        }
        lastStatusLine = line;
        appendToAppLog(line);
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

    /** 关键事件：只有开启「输出调试日志」时才输出（logcat INFO + 日志页）。 */
    private void logEvent(String message) {
        if (!debugEnabled()) {
            return;
        }
        log(Log.INFO, TAG, message);
        appendToAppLog(message);
    }

    /**
     * 写进应用内日志页。
     *
     * <p><b>只有开启「输出调试日志」时才写入</b>：关闭时这里是空操作——模块不产生日志、不做跨进程
     * 写文件、也不落盘。
     */
    private void appendToAppLog(String message) {
        if (!debugEnabled()) {
            return;
        }
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
