package dev.codex.audioroutelock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/**
 * 供注入到目标应用进程中的模块回传运行日志：
 * 模块通过 ContentResolver.call(content://dev.codex.audioroutelock.debuglog, "append", message, null) 追加。
 */
public final class DebugLogProvider extends ContentProvider {
    static final String AUTHORITY = "dev.codex.audioroutelock.debuglog";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("append".equals(method) && arg != null && !arg.isEmpty() && isTrustedCaller()) {
            AppLog.append(getContext(), arg);
            return new Bundle();
        }
        return null;
    }

    // 只接受「目标列表里的应用」或本应用自己（含 root）的写入。
    // 这个 provider 必须 exported（模块跑在目标应用自己的进程里，只能跨进程回传日志），
    // 但 exported 就意味着任何应用都能往日志页灌内容，所以这里按调用方 uid 做一次白名单校验。
    private boolean isTrustedCaller() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid() || uid == 0) {
            return true;
        }
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages == null || packages.length == 0) {
                return false;
            }
            RouteSettings settings = RouteSettingsStore.load(context);
            for (String packageName : packages) {
                if (settings.isTarget(packageName)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
