package dev.codex.audioroutelock;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.libxposed.api.XposedModule;

/**
 * native 静音通道：在目标进程里 hook {@code AAudioStreamBuilder_setDataCallback}，
 * 静音时把数据回调产出的缓冲区清零（实现见 {@code src/main/cpp/arl_audio.cpp}）。
 *
 * <p>为什么要它：网页音频是 Chromium 的 native 输出，Java 层的 Chromium 静音入口在真实 WebView
 * 构建里被 R8 混淆裁剪，摸不到；页面脚本又覆盖不到跨域 iframe。AAudio 是 NDK 公共 API，ABI 从
 * API 26 起稳定，不依赖任何 Chromium 内部结构。
 *
 * <p><b>装载是整条链路上最脆弱的一环</b>（Android 10+ 的 W^X 不允许从可写目录执行代码，SELinux 与
 * linker namespace 又可能拒绝「加载别的应用目录下的库」），所以这里按成功率依次尝试多种方式，
 * 并把每一种的结果（含异常原文）都记进 {@link #detail()}——失败时一行日志就能定位原因。
 */
final class NativeAudioMute {
    private static final String TAG = "AudioRouteLock";

    /** System.loadLibrary 用的名字（对应 lib<name>.so）。 */
    private static final String LIB_NAME = "arl_audio";
    private static final String HOOK_LIB_NAME = "shadowhook";
    /** 文件系统里的完整文件名。 */
    private static final String LIB_FILE = "libarl_audio.so";
    private static final String HOOK_LIB_FILE = "libshadowhook.so";

    private static volatile boolean attempted;
    private static volatile boolean available;
    private static volatile String detail = "尚未尝试";
    /** 一句话短原因（异常类名 / 挂钩 errno），用于塞进状态行，不必去翻详细日志。 */
    private static volatile String failure = "尚未尝试";

    private NativeAudioMute() {
    }

    /** 通道是否装好。 */
    static boolean available() {
        return available;
    }

    /** 通道状态 / 失败原因（含每种装载方式的异常原文）。 */
    static String detail() {
        return detail;
    }

    /** 短原因：成功为"已挂钩"，失败为「①异常类 ②异常类 …」或「挂钩失败: …」。 */
    static String shortReason() {
        return available ? "已挂钩" : failure;
    }

    /** 注入 .so 并装钩子；只需一次，失败不影响其它层的静音。 */
    static synchronized void ensureLoaded(XposedModule module, Context context) {
        if (attempted) {
            return;
        }
        attempted = true;
        List<String> notes = new ArrayList<>();
        List<String> shortNotes = new ArrayList<>();

        ApplicationInfo info = null;
        try {
            info = module.getModuleApplicationInfo();
        } catch (Throwable t) {
            notes.add("getModuleApplicationInfo=" + describe(t));
        }
        String nativeDir = info == null ? null : info.nativeLibraryDir;
        String sourceDir = info == null ? null : info.sourceDir;
        boolean debuggable = info != null && (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        ClassLoader moduleLoader = NativeAudioMute.class.getClassLoader();
        notes.add("classLoader=" + (moduleLoader == null ? "null" : moduleLoader.getClass().getName()));
        if (nativeDir != null) {
            notes.add("nativeLibraryDir=" + nativeDir + "(" + new File(nativeDir).exists() + ")");
        }

        // 依次尝试：任何一种能让两个 .so 都加载成功后，就装钩子。
        // ① System.loadLibrary：和普通应用加载自己的库同一条路（走注入进程 classloader 的 native 搜索路径）
        if (tryStrategy(notes, shortNotes, "loadLibrary", () -> {
            System.loadLibrary(HOOK_LIB_NAME);
            System.loadLibrary(LIB_NAME);
        })) {
            install(debuggable, notes, shortNotes, "System.loadLibrary");
            return;
        }
        // ①b 先把模块的库目录追加到本 classloader 的 native 搜索路径，再按名字加载
        //      （有些注入框架用自定义/内存 classloader，其 nativeLibraryDirectories 里没有模块目录）
        if (nativeDir != null && tryStrategy(notes, shortNotes, "附加 nativeLibraryDirectories 后 loadLibrary", () -> {
            if (!appendNativeLibraryDir(moduleLoader, new File(nativeDir))) {
                throw new IllegalStateException("classloader 里没有 nativeLibraryDirectories 可追加");
            }
            System.loadLibrary(HOOK_LIB_NAME);
            System.loadLibrary(LIB_NAME);
        })) {
            install(debuggable, notes, shortNotes, "追加 nativeLibraryDirectories 后 System.loadLibrary");
            return;
        }
        // ② 模块 APK 解包出的 nativeLibraryDir 绝对路径
        if (nativeDir != null && tryStrategy(notes, shortNotes, "load(模块 lib 目录)", () -> {
            System.load(new File(nativeDir, HOOK_LIB_FILE).getAbsolutePath());
            System.load(new File(nativeDir, LIB_FILE).getAbsolutePath());
        })) {
            install(debuggable, notes, shortNotes, "System.load(模块 lib 目录)");
            return;
        }
        // ③ 从模块 APK 路径反推 .../lib/<abi>/（个别 ROM 的 nativeLibraryDir 不可靠）
        if (sourceDir != null && tryStrategy(notes, shortNotes, "load(APK 同级的 lib/<abi>)", () -> {
            File libDir = findSiblingLibDir(sourceDir);
            if (libDir == null) {
                throw new IllegalStateException("APK 同级没有 lib/<abi> 目录");
            }
            System.load(new File(libDir, HOOK_LIB_FILE).getAbsolutePath());
            System.load(new File(libDir, LIB_FILE).getAbsolutePath());
        })) {
            install(debuggable, notes, shortNotes, "System.load(APK 同级的 lib/<abi>)");
            return;
        }
        // ④ 解包到目标进程的可写目录再 load：Android 10+ 基本会被 W^X 拦掉，作为最后尝试 + 留证据。
        if (context != null && sourceDir != null
                && tryStrategy(notes, shortNotes, "load(解包到目标 cacheDir)", () -> {
            File cacheDir = context.getCodeCacheDir();
            System.load(extractFromApk(sourceDir, cacheDir, HOOK_LIB_FILE).getAbsolutePath());
            System.load(extractFromApk(sourceDir, cacheDir, LIB_FILE).getAbsolutePath());
        })) {
            install(debuggable, notes, shortNotes, "System.load(解包到目标 cacheDir)");
            return;
        }

        available = false;
        failure = joinShort(shortNotes);
        detail = "装载失败｜" + join(notes);
        logLine();
    }

    private static void install(boolean debuggable, List<String> notes, List<String> shortNotes,
                                String strategy) {
        try {
            String result = nativeInstall(debuggable);
            if (result != null && result.startsWith("ok")) {
                available = true;
                detail = "已挂钩 AAudio 回调 / 阻塞写 / OpenSL ES（" + strategy + "）"
                        + (result.length() > 2 ? "，附加说明：" + result.substring(2) : "");
            } else {
                available = false;
                failure = "挂钩失败:" + result;
                detail = "挂钩失败：" + result + "｜" + join(notes);
            }
        } catch (Throwable t) {
            available = false;
            failure = "nativeInstall:" + t.getClass().getSimpleName();
            detail = "nativeInstall 调用失败：" + describe(t) + "｜" + join(notes);
        }
        logLine();
    }

    /**
     * 无论「输出调试日志」是否开启都往 logcat 打这一行：native 是主力通道，它的成败必须可见，
     * 但这行只进 logcat，不写应用内日志页、不落盘。
     */
    private static void logLine() {
        Log.i(TAG, "native 静音通道：" + detail);
    }

    private interface Loader {
        void load() throws Throwable;
    }

    private static boolean tryStrategy(List<String> notes, List<String> shortNotes, String label,
                                        Loader loader) {
        try {
            loader.load();
            notes.add(label + "=成功");
            return true;
        } catch (Throwable t) {
            notes.add(label + "=" + describe(t));
            // 短原因只保留异常类名：状态行放不下完整文本（完整文本在 【native】 行里）。
            shortNotes.add(t.getClass().getSimpleName());
            return false;
        }
    }

    private static String joinShort(List<String> shortNotes) {
        if (shortNotes.isEmpty()) {
            return "未尝试（缺少模块信息）";
        }
        StringBuilder builder = new StringBuilder();
        String[] markers = {"①", "②", "③", "④"};
        for (int i = 0; i < shortNotes.size(); i++) {
            builder.append(i < markers.length ? markers[i] : "·").append(shortNotes.get(i)).append(' ');
        }
        return builder.toString().trim();
    }

    /**
     * 把目录追加进 classloader 的 native 库搜索路径（{@code DexPathList.nativeLibraryDirectories}）。
     * 只影响 {@code System.loadLibrary} 的查找范围；是否允许执行仍由 linker namespace 决定。
     */
    private static boolean appendNativeLibraryDir(ClassLoader loader, File dir) {
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            try {
                java.lang.reflect.Field pathListField = findField(current.getClass(), "pathList");
                if (pathListField == null) {
                    continue;
                }
                pathListField.setAccessible(true);
                Object pathList = pathListField.get(current);
                if (pathList == null) {
                    continue;
                }
                java.lang.reflect.Field dirsField =
                        findField(pathList.getClass(), "nativeLibraryDirectories");
                if (dirsField == null) {
                    continue;
                }
                dirsField.setAccessible(true);
                Object value = dirsField.get(pathList);
                if (value instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<File> dirs = (List<File>) value;
                    if (!dirs.contains(dir)) {
                        dirs.add(dir);
                    }
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static java.lang.reflect.Field findField(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                return type.getDeclaredField(name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** APK 与解包出的库在同一个安装目录下：{@code .../pkg-xx/base.apk} → {@code .../pkg-xx/lib/<abi>}。 */
    private static File findSiblingLibDir(String sourceDir) {
        File installDir = new File(sourceDir).getParentFile();
        if (installDir == null) {
            return null;
        }
        for (String abi : new String[]{"arm64", "arm", "arm64-v8a", "armeabi-v7a"}) {
            File dir = new File(new File(installDir, "lib"), abi);
            if (new File(dir, LIB_FILE).exists()) {
                return dir;
            }
        }
        return null;
    }

    private static File extractFromApk(String apkPath, File targetDir, String name)
            throws Exception {
        File out = new File(targetDir, name);
        if (out.exists() && out.length() > 0) {
            return out;
        }
        String entryName = null;
        for (String abi : new String[]{"arm64-v8a", "armeabi-v7a"}) {
            entryName = "lib/" + abi + "/" + name;
            break;
        }
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException("APK 内没有 " + entryName);
            }
            try (InputStream in = zip.getInputStream(entry);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    os.write(buffer, 0, read);
                }
            }
        }
        return out;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String join(List<String> notes) {
        StringBuilder builder = new StringBuilder();
        for (String note : notes) {
            if (builder.length() > 0) {
                builder.append(" ｜ ");
            }
            builder.append(note);
        }
        return builder.toString();
    }

    /** 只在状态跳变时调用（不是每帧）：热路径上 native 侧只有一次原子读。 */
    static void setMuted(boolean muted) {
        if (!available) {
            return;
        }
        try {
            nativeSetMuted(muted);
        } catch (Throwable ignored) {
        }
    }

    /** native 侧「静音期间真正清零过」的次数：> 0 说明数据层确实在起作用；不可用时返回 -1。 */
    static long mutedCallbacks() {
        if (!available) {
            return -1L;
        }
        try {
            return nativeMutedCallbackCount();
        } catch (Throwable t) {
            return -1L;
        }
    }

    /**
     * 各条路径的计数（aaudio 回调 / open / write，opensl 入队 / 清零），直接写进状态行——
     * 一眼就能看出这个进程的播放实际走的是哪条路。
     */
    static String stats() {
        if (!available) {
            return "不可用";
        }
        try {
            String value = nativeStats();
            return value == null ? "统计失败" : value;
        } catch (Throwable t) {
            return "统计失败";
        }
    }

    /** 返回以 "ok" 开头（成功，可能带附加说明）或失败原因（含 errno / dlerror 文本）。 */
    private static native String nativeInstall(boolean debuggable);

    private static native void nativeSetMuted(boolean muted);

    private static native long nativeMutedCallbackCount();

    private static native String nativeStats();
}
