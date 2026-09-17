// Audio Route Lock 的 native 静音通道。
//
// 为什么需要它：网页音频是 Chromium 的 native 输出（AAudio / OpenSL ES），不经过 Java 的
// AudioTrack / MediaPlayer；而 Chromium 自己的静音入口（AudioManagerAndroidJni.setMute）在真实
// WebView 构建里被 R8 混淆裁剪（实测类不存在、指针字段被改名），Java 层摸不到。
//
// 这里把 Android 上 native 输出的**三条路径**全部接管（都是「数据层清零」，不改播放时钟）：
//   ① AAudio 回调模式：hook AAudioStreamBuilder_setDataCallback，替换应用的数据回调，
//      静音时把这一帧缓冲区清零。
//   ② AAudio 阻塞写模式：hook AAudioStream_write，静音时把要写入的数据清零。
//   ③ OpenSL ES：探测出缓冲区队列接口的实现函数后 hook 它的 Enqueue，静音时把要入队的数据清零。
// 另外 hook AAudioStreamBuilder_openStream 只做计数，用来判断 AAudio 是否被用到。
//
// 每个计数器都写进 nativeStats()，状态行直接展示，所以「哪条路径在跑」一眼可见。

#include <jni.h>

#include <atomic>
#include <cstring>
#include <dlfcn.h>
#include <new>

#include <android/log.h>
#include <aaudio/AAudio.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <shadowhook.h>

#define LOG_TAG "AudioRouteLock"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char *kAaudioLib = "libaaudio.so";
constexpr const char *kOpenSlLib = "libOpenSLES.so";

/** 当前是否要求静音。只由 Java 侧在状态跳变时写一次，热路径上只读。 */
std::atomic<bool> g_muted{false};
std::atomic<bool> g_installed{false};

// ---- AAudio 回调模式 ----
std::atomic<int> g_wrapped_callbacks{0};
std::atomic<long long> g_callback_total{0};
std::atomic<long long> g_aaudio_muted{0};
// ---- AAudio 阻塞写模式 ----
std::atomic<int> g_aaudio_open{0};
std::atomic<int> g_aaudio_write{0};
// ---- OpenSL ES ----
std::atomic<long long> g_opensl_enqueue{0};
std::atomic<long long> g_opensl_muted{0};
std::atomic<bool> g_opensl_hooked{false};

typedef aaudio_result_t (*SetDataCallbackFn)(AAudioStreamBuilder *, AAudioStream_dataCallback, void *);
SetDataCallbackFn g_orig_set_data_callback = nullptr;
typedef aaudio_result_t (*OpenStreamFn)(AAudioStreamBuilder *, AAudioStream **);
OpenStreamFn g_orig_open_stream = nullptr;
typedef aaudio_result_t (*WriteFn)(AAudioStream *, const void *, int32_t, int64_t);
WriteFn g_orig_write = nullptr;
typedef SLresult (*EnqueueFn)(SLAndroidSimpleBufferQueueItf, const void *, SLuint32);
EnqueueFn g_orig_opensl_enqueue = nullptr;

/**
 * 每个被包装的回调对应一个小结构：原回调 + 原 userData + 该流的格式缓存。
 * 与流同寿，故意不释放（进程内输出流数量有限，几十字节的泄漏可忽略；释放会带来 UAF 风险）。
 */
struct CallbackBox {
    AAudioStream_dataCallback orig;
    void *user;
    int32_t channels;
    int32_t bytes_per_sample;
};

/** 一段 PCM 数据的字节数（格式未知时按 16bit 估算）。 */
size_t PcmBytes(AAudioStream *stream, int32_t num_frames) {
    int32_t channels = stream == nullptr ? 0 : AAudioStream_getChannelCount(stream);
    aaudio_format_t format = stream == nullptr ? AAUDIO_FORMAT_UNSPECIFIED : AAudioStream_getFormat(stream);
    if (channels <= 0) {
        channels = 2;
    }
    size_t bytes_per_sample = (format == AAUDIO_FORMAT_PCM_FLOAT) ? 4 : 2;
    return static_cast<size_t>(num_frames) * static_cast<size_t>(channels) * bytes_per_sample;
}

// ------------------------------------------------------------------
// ① AAudio 回调模式
// ------------------------------------------------------------------

/** 数据回调壳。实时安全：只做原子读、memset 和一次第一帧格式探测。 */
aaudio_data_callback_result_t DataCallbackTrampoline(AAudioStream *stream, void *user,
                                                     void *audio_data, int32_t num_frames) {
    CallbackBox *box = static_cast<CallbackBox *>(user);
    if (box == nullptr || box->orig == nullptr) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    g_callback_total.fetch_add(1, std::memory_order_relaxed);
    aaudio_data_callback_result_t result = box->orig(stream, box->user, audio_data, num_frames);
    if (!g_muted.load(std::memory_order_relaxed)) {
        return result;
    }
    if (box->bytes_per_sample == 0) {
        int32_t channels = AAudioStream_getChannelCount(stream);
        aaudio_format_t format = AAudioStream_getFormat(stream);
        box->channels = channels > 0 ? channels : 2;
        box->bytes_per_sample = (format == AAUDIO_FORMAT_PCM_FLOAT) ? 4 : 2;
    }
    if (audio_data != nullptr && num_frames > 0) {
        size_t bytes = static_cast<size_t>(num_frames) *
                       static_cast<size_t>(box->channels) *
                       static_cast<size_t>(box->bytes_per_sample);
        if (bytes > 0) {
            memset(audio_data, 0, bytes);
            g_aaudio_muted.fetch_add(1, std::memory_order_relaxed);
        }
    }
    return result;
}

aaudio_result_t SetDataCallbackProxy(AAudioStreamBuilder *builder, AAudioStream_dataCallback callback,
                                     void *user_data) {
    if (g_orig_set_data_callback == nullptr) {
        return AAUDIO_ERROR_INVALID_STATE;
    }
    if (callback == nullptr) {
        // 不设回调 = 走阻塞写入模式（下面另有 write 钩子处理）。
        return g_orig_set_data_callback(builder, nullptr, user_data);
    }
    CallbackBox *box = new (std::nothrow) CallbackBox{callback, user_data, 0, 0};
    if (box == nullptr) {
        return g_orig_set_data_callback(builder, callback, user_data);
    }
    g_wrapped_callbacks.fetch_add(1, std::memory_order_relaxed);
    return g_orig_set_data_callback(builder, DataCallbackTrampoline, box);
}

/** 只计数：用来判断这个进程到底有没有创建 AAudio 输出流。 */
aaudio_result_t OpenStreamProxy(AAudioStreamBuilder *builder, AAudioStream **stream) {
    g_aaudio_open.fetch_add(1, std::memory_order_relaxed);
    return g_orig_open_stream(builder, stream);
}

// ------------------------------------------------------------------
// ② AAudio 阻塞写模式
// ------------------------------------------------------------------

aaudio_result_t WriteProxy(AAudioStream *stream, const void *buffer, int32_t num_frames,
                           int64_t timeout_nanoseconds) {
    g_aaudio_write.fetch_add(1, std::memory_order_relaxed);
    if (g_muted.load(std::memory_order_relaxed) && buffer != nullptr && num_frames > 0) {
        memset(const_cast<void *>(buffer), 0, PcmBytes(stream, num_frames));
        g_aaudio_muted.fetch_add(1, std::memory_order_relaxed);
    }
    return g_orig_write(stream, buffer, num_frames, timeout_nanoseconds);
}

// ------------------------------------------------------------------
// ③ OpenSL ES
// ------------------------------------------------------------------

/**
 * OpenSL ES 的缓冲区来自调用方（Chromium 填完才 Enqueue），所以在 Enqueue 处清零就等于静音。
 * 接口没导出成符号，所以先自己建一个探测用 player 拿到 vtable，再从 vtable 里取出实现函数地址
 * （所有实例共用同一份实现，hook 一次即覆盖全进程）。
 */
SLresult EnqueueProxy(SLAndroidSimpleBufferQueueItf self, const void *buffer, SLuint32 size) {
    g_opensl_enqueue.fetch_add(1, std::memory_order_relaxed);
    if (g_muted.load(std::memory_order_relaxed) && buffer != nullptr && size > 0) {
        memset(const_cast<void *>(buffer), 0, size);
        g_opensl_muted.fetch_add(1, std::memory_order_relaxed);
    }
    return g_orig_opensl_enqueue(self, buffer, size);
}

/** 返回空字符串表示成功，否则返回失败原因（写进安装诊断）。 */
const char *HookOpenSlEs() {
    void *handle = dlopen(kOpenSlLib, RTLD_NOW);
    if (handle == nullptr) {
        return "dlopen libOpenSLES.so 失败";
    }
    typedef SLresult (*SlCreateEngineFn)(SLObjectItf *, SLuint32, const SLEngineOption *, SLuint32,
                                         const SLInterfaceID *, const SLboolean *);
    auto create_engine = reinterpret_cast<SlCreateEngineFn>(dlsym(handle, "slCreateEngine"));
    if (create_engine == nullptr) {
        return "找不到 slCreateEngine";
    }
    SLObjectItf engine_object = nullptr;
    SLObjectItf output_mix = nullptr;
    SLObjectItf player = nullptr;
    SLAndroidSimpleBufferQueueItf queue = nullptr;
    SLresult result = create_engine(&engine_object, 0, nullptr, 0, nullptr, nullptr);
    if (result == SL_RESULT_SUCCESS && engine_object != nullptr &&
        (*engine_object)->Realize(engine_object, SL_BOOLEAN_FALSE) == SL_RESULT_SUCCESS) {
        SLEngineItf engine = nullptr;
        if ((*engine_object)->GetInterface(engine_object, SL_IID_ENGINE, &engine) == SL_RESULT_SUCCESS &&
            (*engine)->CreateOutputMix(engine, &output_mix, 0, nullptr, nullptr) == SL_RESULT_SUCCESS &&
            (*output_mix)->Realize(output_mix, SL_BOOLEAN_FALSE) == SL_RESULT_SUCCESS) {
            SLDataLocator_AndroidSimpleBufferQueue queue_locator = {
                    SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
            SLDataFormat_PCM format = {SL_DATAFORMAT_PCM,
                                       2,
                                       SL_SAMPLINGRATE_44_1,
                                       SL_PCMSAMPLEFORMAT_FIXED_16,
                                       SL_PCMSAMPLEFORMAT_FIXED_16,
                                       SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT,
                                       SL_BYTEORDER_LITTLEENDIAN};
            SLDataSource source = {&queue_locator, &format};
            SLDataLocator_OutputMix mix_locator = {SL_DATALOCATOR_OUTPUTMIX, output_mix};
            SLDataSink sink = {&mix_locator, nullptr};
            const SLInterfaceID ids[1] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE};
            const SLboolean required[1] = {SL_BOOLEAN_TRUE};
            if ((*engine)->CreateAudioPlayer(engine, &player, &source, &sink, 1, ids, required) ==
                        SL_RESULT_SUCCESS &&
                (*player)->Realize(player, SL_BOOLEAN_FALSE) == SL_RESULT_SUCCESS &&
                (*player)->GetInterface(player, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &queue) ==
                        SL_RESULT_SUCCESS) {
                // 探测成功，queue 的 vtable 就是实现函数的地址表。
            }
        }
    }

    const char *failure = nullptr;
    if (queue != nullptr && (*queue)->Enqueue != nullptr) {
        void *stub = shadowhook_hook_func_addr(reinterpret_cast<void *>((*queue)->Enqueue),
                                               reinterpret_cast<void *>(EnqueueProxy),
                                               reinterpret_cast<void **>(&g_orig_opensl_enqueue));
        if (stub != nullptr && g_orig_opensl_enqueue != nullptr) {
            g_opensl_hooked.store(true, std::memory_order_relaxed);
        } else {
            int err = shadowhook_get_errno();
            LOGW("hook OpenSL ES Enqueue 失败: errno=%d (%s)", err, shadowhook_to_errmsg(err));
            failure = "hook OpenSL ES Enqueue 失败";
        }
    } else {
        failure = "拿不到 OpenSL ES 缓冲区队列接口";
    }

    // 清掉探测用的对象（钩子装在代码上，与这些对象无关）。
    if (player != nullptr) {
        (*player)->Destroy(player);
    }
    if (output_mix != nullptr) {
        (*output_mix)->Destroy(output_mix);
    }
    if (engine_object != nullptr) {
        (*engine_object)->Destroy(engine_object);
    }
    return failure == nullptr ? "" : failure;
}

}  // namespace

/**
 * 装钩子。返回诊断字符串：以 "ok" 开头表示主通道装好（后面可能跟附加说明），
 * 否则是具体失败原因（会原样写进日志 / 日志页）。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_dev_codex_audioroutelock_NativeAudioMute_nativeInstall(JNIEnv *env, jclass, jboolean debuggable) {
    if (g_installed.load(std::memory_order_relaxed)) {
        return env->NewStringUTF("ok");
    }
    char buf[512];
    int init = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, debuggable == JNI_TRUE);
    if (init != 0) {
        snprintf(buf, sizeof(buf), "shadowhook_init 失败: errno=%d (%s)", init,
                 shadowhook_to_errmsg(init));
        LOGW("%s", buf);
        return env->NewStringUTF(buf);
    }
    if (dlopen(kAaudioLib, RTLD_NOW) == nullptr) {
        const char *err = dlerror();
        snprintf(buf, sizeof(buf), "dlopen %s 失败: %s", kAaudioLib, err == nullptr ? "未知" : err);
        LOGW("%s", buf);
        return env->NewStringUTF(buf);
    }

    // 主通道：AAudio 回调模式。
    void *stub = shadowhook_hook_sym_name(kAaudioLib, "AAudioStreamBuilder_setDataCallback",
                                          reinterpret_cast<void *>(SetDataCallbackProxy),
                                          reinterpret_cast<void **>(&g_orig_set_data_callback));
    if (stub == nullptr || g_orig_set_data_callback == nullptr) {
        int err = shadowhook_get_errno();
        snprintf(buf, sizeof(buf), "hook AAudioStreamBuilder_setDataCallback 失败: errno=%d (%s)",
                 err, shadowhook_to_errmsg(err));
        LOGW("%s", buf);
        return env->NewStringUTF(buf);
    }

    // 附加通道：失败不影响主通道，只记进说明里。
    char extra[320];
    extra[0] = '\0';
    if (shadowhook_hook_sym_name(kAaudioLib, "AAudioStreamBuilder_openStream",
                                reinterpret_cast<void *>(OpenStreamProxy),
                                reinterpret_cast<void **>(&g_orig_open_stream)) == nullptr ||
        g_orig_open_stream == nullptr) {
        strncat(extra, "；openStream 计数钩子失败", sizeof(extra) - strlen(extra) - 1);
    }
    if (shadowhook_hook_sym_name(kAaudioLib, "AAudioStream_write",
                                reinterpret_cast<void *>(WriteProxy),
                                reinterpret_cast<void **>(&g_orig_write)) == nullptr ||
        g_orig_write == nullptr) {
        strncat(extra, "；AAudioStream_write 钩子失败", sizeof(extra) - strlen(extra) - 1);
    }
    const char *opensl_failure = HookOpenSlEs();
    if (opensl_failure[0] != '\0') {
        strncat(extra, "；", sizeof(extra) - strlen(extra) - 1);
        strncat(extra, opensl_failure, sizeof(extra) - strlen(extra) - 1);
    }

    g_installed.store(true, std::memory_order_relaxed);
    LOGI("hooked AAudio setDataCallback / write / openStream / OpenSL ES");
    snprintf(buf, sizeof(buf), "ok%s", extra);
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_codex_audioroutelock_NativeAudioMute_nativeSetMuted(JNIEnv *, jclass, jboolean muted) {
    g_muted.store(muted == JNI_TRUE, std::memory_order_relaxed);
}

/** 所有路径「静音期间真正清零过数据」的次数之和：> 0 就说明数据层确实在起作用。 */
extern "C" JNIEXPORT jlong JNICALL
Java_dev_codex_audioroutelock_NativeAudioMute_nativeMutedCallbackCount(JNIEnv *, jclass) {
    return static_cast<jlong>(g_aaudio_muted.load(std::memory_order_relaxed) +
                              g_opensl_muted.load(std::memory_order_relaxed));
}

/** 各条路径的计数，直接写进状态行，用来判断播放实际走的是哪条路。 */
extern "C" JNIEXPORT jstring JNICALL
Java_dev_codex_audioroutelock_NativeAudioMute_nativeStats(JNIEnv *env, jclass) {
    char buf[256];
    snprintf(buf, sizeof(buf),
             "aaudio[open=%d 流=%d 回调=%lld 清零=%lld write=%d] opensl[%s 入队=%lld 清零=%lld]",
             g_aaudio_open.load(std::memory_order_relaxed),
             g_wrapped_callbacks.load(std::memory_order_relaxed),
             g_callback_total.load(std::memory_order_relaxed),
             g_aaudio_muted.load(std::memory_order_relaxed),
             g_aaudio_write.load(std::memory_order_relaxed),
             g_opensl_hooked.load(std::memory_order_relaxed) ? "已挂钩" : "未挂钩",
             g_opensl_enqueue.load(std::memory_order_relaxed),
             g_opensl_muted.load(std::memory_order_relaxed));
    return env->NewStringUTF(buf);
}
