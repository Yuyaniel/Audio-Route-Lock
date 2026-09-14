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

    /** 清空本地与框架侧的全部设置，并清空应用内日志。 */
    static void reset(Context context) {
        local(context).edit().clear().apply();
        AppLog.clear(context);
        XposedService service = App.getXposedService();
        if (service != null) {
            try {
                service.deleteRemotePreferences(RouteSettings.PREF_GROUP);
            } catch (Throwable t) {
                Log.w(TAG, "Delete remote preferences failed", t);
            }
        }
    }
}
