package dev.codex.audioroutelock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.service.XposedService;

/**
 * 本应用的 SharedPreferences 是唯一数据源（与 LSPosed 服务是否连接无关，保证设置不会丢失）。
 * 只要框架服务可用，就把设置镜像到 libxposed 的 RemotePreferences（存放于 LSPosed 数据库），
 * 供目标进程内的模块读取。见官方文档 “Develop Xposed Modules Using Modern Xposed API”。
 */
final class RouteSettingsStore {
    private static final String TAG = "AudioRouteLockStore";

    /** 合并窗口：窗口内连续到达的编辑只镜像最后一次。 */
    private static final long MIRROR_MERGE_WINDOW_MS = 200L;
    private static final java.util.concurrent.ExecutorService MIRROR_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    runnable -> new Thread(runnable, "route-settings-mirror"));
    private static final java.util.concurrent.atomic.AtomicReference<RouteSettings> PENDING_MIRROR =
            new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicBoolean MIRROR_SCHEDULED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private RouteSettingsStore() {
    }

    static SharedPreferences local(Context context) {
        return context.getSharedPreferences(RouteSettings.PREF_GROUP, Context.MODE_PRIVATE);
    }

    static RouteSettings load(Context context) {
        return RouteSettings.from(local(context));
    }

    static boolean save(Context context, RouteSettings settings) {
        settings.writeTo(local(context).edit()).apply();
        return mirrorToRemote(settings);
    }

    // 高频编辑（例如在应用选择器里连续勾选多个应用）用这个：本地立即写，框架侧合并成一次后台任务。
    // save() 会在调用线程上做 binder + 同步 commit（保证"改完立刻杀掉本应用也不丢"），连点十下就是
    // 十次主线程 IPC——所以这里把镜像降到后台线程、并在 200ms 窗口内合并成最后一次。
    // 开关、设备选择、移除应用这类单次操作仍走同步的 save()。
    static void saveDeferred(Context context, RouteSettings settings) {
        settings.writeTo(local(context).edit()).apply();
        PENDING_MIRROR.set(settings);
        if (MIRROR_SCHEDULED.compareAndSet(false, true)) {
            MIRROR_EXECUTOR.execute(RouteSettingsStore::drainMirror);
        }
    }

    private static void drainMirror() {
        try {
            Thread.sleep(MIRROR_MERGE_WINDOW_MS);
        } catch (InterruptedException ignored) {
        }
        RouteSettings latest;
        while ((latest = PENDING_MIRROR.getAndSet(null)) != null) {
            mirrorToRemote(latest);
        }
        MIRROR_SCHEDULED.set(false);
        // 收尾：drain 期间又来了新状态（此时没人再调度）→ 再排一次，避免漏掉最后一批。
        if (PENDING_MIRROR.get() != null && MIRROR_SCHEDULED.compareAndSet(false, true)) {
            MIRROR_EXECUTOR.execute(RouteSettingsStore::drainMirror);
        }
    }

    /** 把本地设置推送到框架侧，目标进程才能读到；同步 commit，避免进程被杀导致丢失。 */
    static boolean mirrorToRemote(RouteSettings settings) {
        XposedService service = App.getXposedService();
        if (service == null) {
            return false;
        }
        try {
            SharedPreferences remote = service.getRemotePreferences(RouteSettings.PREF_GROUP);
            return settings.writeTo(remote.edit()).commit();
        } catch (Throwable t) {
            Log.w(TAG, "Mirror settings to framework failed", t);
            return false;
        }
    }

    static void syncLocalToRemote(Context context) {
        mirrorToRemote(load(context));
    }
}
