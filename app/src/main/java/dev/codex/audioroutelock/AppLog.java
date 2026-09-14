package dev.codex.audioroutelock;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateFormat;

import java.util.Date;

/**
 * 应用内运行日志，模块进程通过 DebugLogProvider 追加、本应用进程直接写入，
 * 「日志」页读取展示。
 */
final class AppLog {
    static final String PREF = "app_log";
    static final String KEY_LINES = "lines";
    private static final int MAX_CHARS = 40000;

    private AppLog() {
    }

    static synchronized void append(Context context, String message) {
        if (context == null || message == null) {
            return;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String existing = prefs.getString(KEY_LINES, "");
            String timestamp = DateFormat.format("HH:mm:ss", new Date()).toString();
            StringBuilder builder = new StringBuilder(existing);
            builder.append(timestamp).append(' ').append(message).append('\n');
            if (builder.length() > MAX_CHARS) {
                int start = builder.length() - MAX_CHARS;
                int newline = builder.indexOf("\n", start);
                builder = new StringBuilder(newline >= 0 ? builder.substring(newline + 1) : builder.substring(start));
            }
            prefs.edit().putString(KEY_LINES, builder.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    static String read(Context context) {
        if (context == null) {
            return "";
        }
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LINES, "");
    }

    static void clear(Context context) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_LINES).apply();
    }
}
