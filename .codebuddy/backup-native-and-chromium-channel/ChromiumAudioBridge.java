package dev.codex.audioroutelock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Chromium（WebView 提供方 / Chrome 等 Chromium 内核应用）音频输出的静音通道。
 *
 * <p>为什么 AudioTrack / MediaPlayer 钩子对浏览器网页音频无效：Chromium 的普通播放由 native
 * AAudio / OpenSL ES 直接输出，Java 层的 {@code android.media.AudioTrack} 只在 HDMI 码流直通时
 * 才会用到（{@code media/audio/android/audio_manager_android.cc} 里只有 {@code MakeBitstreamOutputStream}
 * 走 android_media_AudioTrack）。所以网页音频既不会创建 Java 的 AudioTrack，也不会创建 MediaPlayer，
 * 把应用本身、甚至把 WebView 提供方加入作用域，都没有可以钩住的对象。
 *
 * <p>Chromium 自己提供了静音入口：native {@code AudioManagerAndroid::SetMute} 会把该进程内所有
 * Chromium 输出流静音（OpenSL ES / AAudio 的 SetMute），播放本身继续，因此播放时钟、进度条都不受影响。
 * Java 侧触发它有两种形态（不同 WebView 版本二选一）：
 *
 * <ul>
 *   <li>新版（JNI zero）：{@code AudioManagerAndroidJni.get().setMute(nativePtr, muted)}，
 *       {@code get()} 返回实现 {@code AudioManagerAndroid.Natives} 接口的实例；</li>
 *   <li>旧版（手写生成的 JNI）：{@code AudioManagerAndroidJni.setMute(nativePtr, muted)} 是静态方法，
 *       没有 {@code get()}。</li>
 * </ul>
 *
 * <p>这里只负责用反射把这个通道解析出来，真正的调用在 {@link ModuleMain} 里按设备状态触发。
 */
final class ChromiumAudioBridge {
    private static final String AUDIO_MANAGER_CLASS = "org.chromium.media.AudioManagerAndroid";
    private static final String AUDIO_MANAGER_JNI_CLASS = "org.chromium.media.AudioManagerAndroidJni";
    /** Java 侧保存的 native AudioManagerAndroid* 指针。 */
    private static final String NATIVE_POINTER_FIELD = "mNativeAudioManagerAndroid";
    private static final String SET_MUTE_METHOD = "setMute";

    /** 最近一次解析失败的原因，供日志页展示，帮助定位目标 WebView 的形态差异。 */
    static volatile String lastFailure;

    // 最近一次 setMute 调用是否成功，以及失败细节（供链路自检定位到具体一环）。
    static volatile boolean lastInvokeOk;
    static volatile String lastInvokeDetail = "尚未调用";
    // 通道形态描述，例如「JNI zero（Natives 实例）」或「旧版静态方法」。
    volatile String shape = "未知";

    private final Field nativePointer;
    /** JNI zero 形态下持有的 Natives 实例；旧版静态方法形态下为 null（invoke 时传 null 接收者）。 */
    private final Object jniBridge;
    private final Method setMute;

    private ChromiumAudioBridge(Field nativePointer, Object jniBridge, Method setMute) {
        this.nativePointer = nativePointer;
        this.jniBridge = jniBridge;
        this.setMute = setMute;
    }

    /** 该 ClassLoader 能加载到 Chromium 音频管理器时返回它的 Class，否则返回 null（非 Chromium 进程）。 */
    static Class<?> loadAudioManagerClass(ClassLoader loader) {
        if (loader == null) {
            return null;
        }
        try {
            return Class.forName(AUDIO_MANAGER_CLASS, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 解析 Chromium 的 setMute 通道；任意一环缺失都返回 null（调用方只记日志，不影响其它功能）。
     *
     * <p>注意：类和方法都来自 WebView 提供方的 dex，不属于 framework，因此这里用反射访问它们
     * 不受 hidden API 限制。
     */
    static ChromiumAudioBridge resolve(ClassLoader loader) {
        Class<?> managerClass = loadAudioManagerClass(loader);
        if (managerClass == null) {
            lastFailure = "AudioManagerAndroid 不存在";
            return null;
        }
        try {
            Field pointer = managerClass.getDeclaredField(NATIVE_POINTER_FIELD);
            pointer.setAccessible(true);

            Class<?> jniClass;
            try {
                jniClass = Class.forName(AUDIO_MANAGER_JNI_CLASS, false, loader);
            } catch (Throwable t) {
                lastFailure = "AudioManagerAndroidJni 不存在: " + t.getClass().getSimpleName();
                return null;
            }

            // 形态一（JNI zero）：AudioManagerAndroidJni.get() 返回 Natives 实例，setMute 是实例方法。
            try {
                Method get = jniClass.getDeclaredMethod("get");
                get.setAccessible(true);
                Object bridge = get.invoke(null);
                if (bridge != null) {
                    Method method = findSetMute(bridge.getClass());
                    if (method != null) {
                        method.setAccessible(true);
                        ChromiumAudioBridge resolved =
                                new ChromiumAudioBridge(pointer, bridge, method);
                        resolved.shape = "JNI zero（Natives 实例）";
                        return resolved;
                    }
                    lastFailure = "Natives 实例上未找到 setMute(long,boolean)";
                } else {
                    lastFailure = "AudioManagerAndroidJni.get() 为 null";
                }
            } catch (NoSuchMethodException ignored) {
                // 没有 get()：可能是旧版形态，继续尝试静态方法。
            }

            // 形态二（旧版）：AudioManagerAndroidJni.setMute(long, boolean) 是静态方法。
            try {
                Method method = jniClass.getDeclaredMethod(SET_MUTE_METHOD, long.class, boolean.class);
                method.setAccessible(true);
                ChromiumAudioBridge resolved = new ChromiumAudioBridge(pointer, null, method);
                resolved.shape = "旧版静态方法";
                return resolved;
            } catch (NoSuchMethodException ignored) {
                if (lastFailure == null) {
                    lastFailure = "AudioManagerAndroidJni 上既无 get() 也无静态 setMute";
                }
            }
            return null;
        } catch (Throwable t) {
            lastFailure = t.getClass().getSimpleName() + ": " + t.getMessage();
            return null;
        }
    }

    private static Method findSetMute(Class<?> owner) {
        for (Method method : owner.getMethods()) {
            if (matchesSetMute(method)) {
                return method;
            }
        }
        for (Class<?> type : owner.getInterfaces()) {
            for (Method method : type.getMethods()) {
                if (matchesSetMute(method)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static boolean matchesSetMute(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        return SET_MUTE_METHOD.equals(method.getName())
                && parameters.length == 2
                && parameters[0] == long.class
                && parameters[1] == boolean.class;
    }

    /** 静音通道在这个 Chromium 构建里是否存在（两种形态任一）。 */
    static boolean isChannelPresent(ClassLoader loader) {
        try {
            Class<?> jniClass = Class.forName(AUDIO_MANAGER_JNI_CLASS, false, loader);
            try {
                jniClass.getDeclaredMethod("get");
                return true;
            } catch (NoSuchMethodException ignored) {
            }
            try {
                jniClass.getDeclaredMethod(SET_MUTE_METHOD, long.class, boolean.class);
                return true;
            } catch (NoSuchMethodException ignored) {
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Chromium 的 native 音频部分是否已初始化（JNI zero 的实例是否已注册）。
     *
     * <p>JNI zero 形态下**只以 {@code get()} 的实际返回值**判断：不能因为「方法存在」就当成可用，
     * 否则 native 还没注册时会被误判成已就绪，解析失败后立刻 {@code chromiumUnavailable = true}
     * 把通道永久判死（"等 native 就绪再解析"的保护形同不存在）。
     */
    static boolean isJniRegistered(ClassLoader loader) {
        try {
            Class<?> jniClass = Class.forName(AUDIO_MANAGER_JNI_CLASS, false, loader);
            try {
                Method get = jniClass.getDeclaredMethod("get");
                get.setAccessible(true);
                return get.invoke(null) != null;
            } catch (NoSuchMethodException ignored) {
                // 旧版静态形态不依赖注册时机，通道存在即视为可用。
                return isChannelPresent(loader);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** 静音 / 恢复该音频管理器下所有 Chromium 输出流，返回是否调用成功。 */
    boolean setMute(Object audioManager, boolean muted) {
        try {
            Object raw = nativePointer.get(audioManager);
            long pointer = raw instanceof Number ? ((Number) raw).longValue() : 0L;
            if (pointer == 0L) {
                lastInvokeOk = false;
                lastInvokeDetail = "native 指针为 0（音频管理器已 close 或尚未初始化）";
                return false;
            }
            setMute.invoke(jniBridge, pointer, muted);
            lastInvokeOk = true;
            lastInvokeDetail = "成功（muted=" + muted + "，形态：" + shape + "）";
            return true;
        } catch (Throwable t) {
            lastInvokeOk = false;
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            lastInvokeDetail = "调用异常 " + cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : "：" + cause.getMessage());
            return false;
        }
    }
}
