package com.espressif.utils;

import android.content.Context;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * 当前 App 进程内的配网诊断日志。
 *
 * 不写文件、不写数据库、不写 SharedPreferences；进程重启后自然清空。
 * 该类同时代理 android.util.Log，使配网代码既保留 Logcat，又能在“设置 -> 日志”查看。
 */
public final class ProvisioningLog {

    public interface Listener {
        void onLogAdded(String line);
        void onLogReset();
    }

    private static final int MAX_LINES = 3000;
    private static final int MAX_MESSAGE_CHARS = 2400;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LINES = new ArrayDeque<>(MAX_LINES);
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(password|passphrase|passwd|pwd|pop|proof[ _-]?of[ _-]?possession|access[ _-]?token|id[ _-]?token|refresh[ _-]?token|token|secret[ _-]?key|secret)\\s*[:=]\\s*([^,;\\s}]+)");
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\\\"(?:password|passphrase|pop|access_token|id_token|refresh_token|secret_key|secret)\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")");
    private static final Pattern PEM_MATERIAL = Pattern.compile(
            "(?s)-----BEGIN [^-]+-----.*?-----END [^-]+-----");

    private ProvisioningLog() {}

    /** 在 Application.onCreate() 调用，一次进程启动只保留本次运行日志。 */
    public static void resetForProcess() {
        synchronized (LOCK) {
            LINES.clear();
        }
        for (Listener listener : LISTENERS) {
            listener.onLogReset();
        }
        add("I", "APP", "========== 本次 App 启动配网日志 ==========");
        add("I", "APP", "日志仅保存在内存中，App 进程重启后自动清空");
    }

    public static void addListener(Listener listener) {
        if (listener != null) LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    public static List<String> snapshotLines() {
        synchronized (LOCK) {
            return new ArrayList<>(LINES);
        }
    }

    public static String snapshotText() {
        StringBuilder builder = new StringBuilder();
        synchronized (LOCK) {
            for (String line : LINES) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
        }
        for (Listener listener : LISTENERS) {
            listener.onLogReset();
        }
        add("I", "APP", "日志已手动清空");
    }

    private static String resolveText(Context context, int resId) {
        if (context == null) return "<context-null>";
        try { return context.getString(resId); }
        catch (Exception e) { return "<resource:" + resId + ">"; }
    }

    public static void uiStatus(Context context, String tag, int resId) {
        uiStatus(tag, resolveText(context, resId));
    }

    public static void uiStatus(Context context, String tag, CharSequence text) {
        uiStatus(tag, text);
    }

    public static void uiStatus(String tag, CharSequence text) {
        add("I", tag, "[UI状态] " + String.valueOf(text));
    }

    public static void uiNotice(Context context, String tag, int resId) {
        uiNotice(tag, resolveText(context, resId));
    }

    public static void uiNotice(Context context, String tag, CharSequence text) {
        uiNotice(tag, text);
    }

    public static void uiNotice(String tag, CharSequence text) {
        add("I", tag, "[UI通知] " + String.valueOf(text));
    }

    public static void uiProgress(Context context, String tag, String state, int resId) {
        uiProgress(tag, state, resolveText(context, resId));
    }

    public static void uiProgress(Context context, String tag, String state, CharSequence text) {
        uiProgress(tag, state, text);
    }

    public static void uiProgress(String tag, String state, CharSequence text) {
        add("I", tag, "[UI进度][" + String.valueOf(state) + "] " + String.valueOf(text));
    }

    private static String sanitize(String message) {
        if (message == null) return "null";
        String out = message;
        out = PEM_MATERIAL.matcher(out).replaceAll("<证书/密钥内容已隐藏>");
        out = JSON_SECRET.matcher(out).replaceAll("$1<已隐藏>$3");
        out = KEY_VALUE_SECRET.matcher(out).replaceAll("$1=<已隐藏>");

        String lower = out.toLowerCase(Locale.US);
        if (lower.startsWith("api response :") || lower.startsWith("api response:")) {
            out = "API Response: <响应正文已隐藏，仅记录流程状态>";
        }
        if (lower.contains("csr data") || lower.contains("certificate data")) {
            out = out.replaceAll("(?i)(csr data|certificate data).*", "$1: <正文已隐藏>");
        }
        if (out.length() > MAX_MESSAGE_CHARS) {
            out = out.substring(0, MAX_MESSAGE_CHARS) + "…<已截断>";
        }
        return out;
    }

    private static String throwableSummary(Throwable tr) {
        if (tr == null) return "";
        String msg = tr.getMessage();
        return tr.getClass().getSimpleName() + (msg == null ? "" : ": " + sanitize(msg));
    }

    private static void add(String level, String tag, String message) {
        String safeTag = tag == null ? "" : tag;
        String safeMessage = sanitize(message);
        String timestamp;
        synchronized (TIME_FORMAT) {
            timestamp = TIME_FORMAT.format(new Date());
        }
        String line = timestamp + " " + level + "/" + safeTag + ": " + safeMessage;
        synchronized (LOCK) {
            while (LINES.size() >= MAX_LINES) {
                LINES.removeFirst();
            }
            LINES.addLast(line);
        }
        for (Listener listener : LISTENERS) {
            listener.onLogAdded(line);
        }
    }

    public static int v(String tag, String msg) { add("V", tag, msg); return Log.v(tag, sanitize(msg)); }
    public static int d(String tag, String msg) { add("D", tag, msg); return Log.d(tag, sanitize(msg)); }
    public static int i(String tag, String msg) { add("I", tag, msg); return Log.i(tag, sanitize(msg)); }
    public static int w(String tag, String msg) { add("W", tag, msg); return Log.w(tag, sanitize(msg)); }
    public static int e(String tag, String msg) { add("E", tag, msg); return Log.e(tag, sanitize(msg)); }
    public static int wtf(String tag, String msg) { add("A", tag, msg); return Log.wtf(tag, sanitize(msg)); }

    public static int v(String tag, String msg, Throwable tr) {
        add("V", tag, msg + " | " + throwableSummary(tr));
        return Log.v(tag, sanitize(msg), tr);
    }
    public static int d(String tag, String msg, Throwable tr) {
        add("D", tag, msg + " | " + throwableSummary(tr));
        return Log.d(tag, sanitize(msg), tr);
    }
    public static int i(String tag, String msg, Throwable tr) {
        add("I", tag, msg + " | " + throwableSummary(tr));
        return Log.i(tag, sanitize(msg), tr);
    }
    public static int w(String tag, String msg, Throwable tr) {
        add("W", tag, msg + " | " + throwableSummary(tr));
        return Log.w(tag, sanitize(msg), tr);
    }
    public static int e(String tag, String msg, Throwable tr) {
        add("E", tag, msg + " | " + throwableSummary(tr));
        return Log.e(tag, sanitize(msg), tr);
    }
    public static int wtf(String tag, String msg, Throwable tr) {
        add("A", tag, msg + " | " + throwableSummary(tr));
        return Log.wtf(tag, sanitize(msg), tr);
    }

    public static int w(String tag, Throwable tr) {
        add("W", tag, throwableSummary(tr));
        return Log.w(tag, tr);
    }

    public static String getStackTraceString(Throwable tr) {
        return Log.getStackTraceString(tr);
    }

    public static boolean isLoggable(String tag, int level) {
        return Log.isLoggable(tag, level);
    }
}
