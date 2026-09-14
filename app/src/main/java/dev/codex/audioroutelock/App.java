package dev.codex.audioroutelock;

import android.app.Application;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class App extends Application implements XposedServiceHelper.OnServiceListener {
    private static final String TAG = "AudioRouteLockApp";

    private static final List<Runnable> SERVICE_LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile XposedService xposedService;

    public static XposedService getXposedService() {
        return xposedService;
    }

    /** 注册框架服务状态回调，注册时立即回调一次当前状态。 */
    public static void addServiceListener(Runnable listener) {
        SERVICE_LISTENERS.add(listener);
        runSafely(listener);
    }

    public static void removeServiceListener(Runnable listener) {
        SERVICE_LISTENERS.remove(listener);
    }

    private static void runSafely(Runnable listener) {
        try {
            listener.run();
        } catch (Throwable t) {
            Log.w(TAG, "Service listener failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService service) {
        xposedService = service;
        try {
            Log.i(TAG, "Xposed service bound: " + service.getFrameworkName()
                    + " " + service.getFrameworkVersion() + ", API " + service.getApiVersion());
            AppLog.append(this, "LSPosed 服务已连接（" + service.getFrameworkName()
                    + " " + service.getFrameworkVersion() + "，API " + service.getApiVersion() + "）");
        } catch (Throwable t) {
            Log.i(TAG, "Xposed service bound");
            AppLog.append(this, "LSPosed 服务已连接");
        }
        // 服务可能在用户保存设置之后才连上，这里补一次镜像，保证模块能读到设置。
        new Thread(() -> RouteSettingsStore.syncLocalToRemote(getApplicationContext()),
                "route-settings-sync").start();
        for (Runnable listener : SERVICE_LISTENERS) {
            runSafely(listener);
        }
    }

    @Override
    public void onServiceDied(XposedService service) {
        if (xposedService == service) {
            xposedService = null;
        }
        Log.w(TAG, "Xposed service died");
        AppLog.append(this, "LSPosed 服务已断开");
        for (Runnable listener : SERVICE_LISTENERS) {
            runSafely(listener);
        }
    }
}
