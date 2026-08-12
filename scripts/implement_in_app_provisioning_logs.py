#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def replace_once(src, old, new, label):
    n = src.count(old)
    if n != 1:
        raise RuntimeError(f"{label}: expected 1 occurrence, found {n}")
    return src.replace(old, new, 1)


LOGGER = r'''package com.espressif.utils;

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
'''


LOG_ACTIVITY = r'''package com.espressif.ui.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;

import com.espressif.rainmaker.R;
import com.espressif.utils.ProvisioningLog;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

/** 显示当前 App 进程内的 BLE / Claim / Wi-Fi / 节点添加配网日志。 */
public class ProvisioningLogActivity extends AppCompatActivity implements ProvisioningLog.Listener {

    private TextView logText;
    private ScrollView logScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_provisioning_log);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
        toolbar.setTitle(R.string.title_activity_logs);
        toolbar.setNavigationIcon(AppCompatResources.getDrawable(this, R.drawable.ic_arrow_left));
        toolbar.setNavigationOnClickListener(v -> finish());

        logText = findViewById(R.id.tv_provisioning_log);
        logScroll = findViewById(R.id.scroll_provisioning_log);
        MaterialButton copyButton = findViewById(R.id.btn_copy_log);
        MaterialButton clearButton = findViewById(R.id.btn_clear_log);

        copyButton.setOnClickListener(v -> copyLogs());
        clearButton.setOnClickListener(v -> ProvisioningLog.clear());
        refreshAllLogs();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ProvisioningLog.addListener(this);
        refreshAllLogs();
    }

    @Override
    protected void onStop() {
        ProvisioningLog.removeListener(this);
        super.onStop();
    }

    private void refreshAllLogs() {
        String text = ProvisioningLog.snapshotText();
        if (text.isEmpty()) {
            text = getString(R.string.logs_empty);
        }
        logText.setText(text);
        scrollToBottom();
    }

    private void copyLogs() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("ESP RainMaker logs", ProvisioningLog.snapshotText()));
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show();
    }

    private void scrollToBottom() {
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onLogAdded(final String line) {
        runOnUiThread(() -> {
            CharSequence current = logText.getText();
            if (current.length() == 0 || getString(R.string.logs_empty).contentEquals(current)) {
                logText.setText(line + "\n");
            } else {
                logText.append(line + "\n");
            }
            scrollToBottom();
        });
    }

    @Override
    public void onLogReset() {
        runOnUiThread(() -> logText.setText(""));
    }
}
'''


LOG_LAYOUT = r'''<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/color_background">

    <include
        android:id="@+id/toolbar_layout"
        layout="@layout/toolbar" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:paddingStart="12dp"
        android:paddingEnd="12dp"
        android:paddingBottom="12dp"
        app:layout_behavior="@string/appbar_scrolling_view_behavior">

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:paddingTop="10dp"
            android:paddingBottom="8dp"
            android:text="@string/logs_memory_only_note"
            android:textColor="@color/colorPrimaryDark"
            android:textSize="13sp" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="end"
            android:orientation="horizontal">

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btn_copy_log"
                style="@style/Widget.MaterialComponents.Button.TextButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/logs_copy" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btn_clear_log"
                style="@style/Widget.MaterialComponents.Button.TextButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/logs_clear" />
        </LinearLayout>

        <ScrollView
            android:id="@+id/scroll_provisioning_log"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:background="@android:color/white"
            android:fillViewport="true"
            android:scrollbars="vertical">

            <TextView
                android:id="@+id/tv_provisioning_log"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:fontFamily="monospace"
                android:padding="10dp"
                android:textColor="@android:color/black"
                android:textIsSelectable="true"
                android:textSize="11sp" />
        </ScrollView>
    </LinearLayout>
</androidx.coordinatorlayout.widget.CoordinatorLayout>
'''


def patch_application():
    p = "app/src/main/java/com/espressif/EspApplication.java"
    s = read(p)
    if "ProvisioningLog.resetForProcess" in s:
        return
    s = replace_once(s, "import com.espressif.utils.ParamUtils;\n", "import com.espressif.utils.ParamUtils;\nimport com.espressif.utils.ProvisioningLog;\n", "EspApplication import")
    s = replace_once(s, "        super.onCreate();\n        Log.d(TAG, \"ESP Application is created\");\n", "        super.onCreate();\n        ProvisioningLog.resetForProcess();\n        ProvisioningLog.i(TAG, \"ESP Application is created\");\n", "EspApplication onCreate")
    write(p, s)


def patch_settings():
    p = "app/src/main/java/com/espressif/ui/fragments/UserProfileFragment.java"
    s = read(p)
    if "R.string.title_activity_logs" not in s:
        s = replace_once(s,
            "        userInfoList.add(getString(R.string.title_activity_about));\n        userInfoList.add(getString(R.string.preferences));\n",
            "        userInfoList.add(getString(R.string.title_activity_about));\n        userInfoList.add(getString(R.string.title_activity_logs));\n        userInfoList.add(getString(R.string.preferences));\n",
            "settings logs row")
        write(p, s)

    p = "app/src/main/java/com/espressif/ui/adapters/UserProfileAdapter.java"
    s = read(p)
    if "ProvisioningLogActivity" not in s:
        s = replace_once(s, "import com.espressif.ui.activities.PreferencesActivity;\n", "import com.espressif.ui.activities.PreferencesActivity;\nimport com.espressif.ui.activities.ProvisioningLogActivity;\n", "adapter activity import")
        s = replace_once(s,
            "                } else if (str.equals(context.getString(R.string.title_activity_group_sharing_requests))) {\n\n                    context.startActivity(new Intent(context, GroupShareActivity.class));\n\n                } else if (str.equals(context.getString(R.string.preferences))) {",
            "                } else if (str.equals(context.getString(R.string.title_activity_group_sharing_requests))) {\n\n                    context.startActivity(new Intent(context, GroupShareActivity.class));\n\n                } else if (str.equals(context.getString(R.string.title_activity_logs))) {\n\n                    context.startActivity(new Intent(context, ProvisioningLogActivity.class));\n\n                } else if (str.equals(context.getString(R.string.preferences))) {",
            "adapter logs click")
        write(p, s)


def patch_manifest():
    p = "app/src/main/AndroidManifest.xml"
    s = read(p)
    if "ProvisioningLogActivity" in s:
        return
    anchor = '''        <activity
            android:name="com.espressif.ui.activities.AboutAppActivity"
            android:label="@string/title_activity_about"
            android:screenOrientation="portrait"
            android:theme="@style/AppTheme.NoActionBar" />
'''
    addition = anchor + '''        <activity
            android:name="com.espressif.ui.activities.ProvisioningLogActivity"
            android:label="@string/title_activity_logs"
            android:screenOrientation="portrait"
            android:theme="@style/AppTheme.NoActionBar" />
'''
    s = replace_once(s, anchor, addition, "manifest logs activity")
    write(p, s)


def append_strings(path, content):
    s = read(path)
    if "title_activity_logs" in s:
        return
    idx = s.rfind("</resources>")
    if idx < 0:
        raise RuntimeError(f"No resources close tag: {path}")
    s = s[:idx] + content + s[idx:]
    write(path, s)


def patch_strings():
    append_strings("app/src/main/res/values/strings.xml", '''\n    <!-- In-memory provisioning logs -->\n    <string name="title_activity_logs">Logs</string>\n    <string name="logs_memory_only_note">BLE, Security, Claim, Wi-Fi and device-add logs from this app process. Logs are stored in memory only and are cleared when the app process restarts. Sensitive credentials are hidden.</string>\n    <string name="logs_copy">Copy</string>\n    <string name="logs_clear">Clear</string>\n    <string name="logs_empty">No provisioning logs in this app session.</string>\n    <string name="logs_copied">Logs copied</string>\n\n''')
    zh = '''\n    <!-- 本次 App 启动的内存配网日志 -->\n    <string name="title_activity_logs">日志</string>\n    <string name="logs_memory_only_note">显示本次 App 启动以来的蓝牙、Security、证书 Claim、Wi-Fi 配网和设备添加日志。仅保存在内存中，App 进程重启后自动清空；敏感凭据会自动隐藏。</string>\n    <string name="logs_copy">复制</string>\n    <string name="logs_clear">清空</string>\n    <string name="logs_empty">本次启动暂无配网日志。</string>\n    <string name="logs_copied">日志已复制</string>\n\n'''
    append_strings("app/src/main/res/values-zh-rCN/strings.xml", zh)
    append_strings("app/src/main/res/values-zh-rSG/strings.xml", zh)


def patch_log_proxy(path):
    s = read(path)
    if "import com.espressif.utils.ProvisioningLog;" not in s:
        if "import android.util.Log;\n" not in s:
            raise RuntimeError(f"No android.util.Log import in {path}")
        s = s.replace("import android.util.Log;\n", "import com.espressif.utils.ProvisioningLog;\n", 1)
    s = s.replace("Log.", "ProvisioningLog.")
    write(path, s)


def add_flow_markers():
    p = "app/src/main/java/com/espressif/ui/activities/BLEProvisionLanding.java"
    s = read(p)
    if "BLE scan start, prefix=" not in s:
        s = replace_once(s, "    private void startScan() {\n\n", "    private void startScan() {\n\n        ProvisioningLog.i(TAG, \"BLE scan start, prefix=\" + deviceNamePrefix);\n", "BLE scan start marker")
        s = replace_once(s, "        public void scanCompleted() {\n            isScanning = false;\n", "        public void scanCompleted() {\n            ProvisioningLog.i(TAG, \"BLE scan completed, devices=\" + deviceList.size());\n            isScanning = false;\n", "BLE scan completed marker")
        write(p, s)

    p = "app/src/main/java/com/espressif/ui/activities/ProofOfPossessionActivity.java"
    s = read(p)
    if "Security session initialization started" not in s:
        s = s.replace('        ProvisioningLog.d(TAG, "Set POP : " + pop);', '        ProvisioningLog.i(TAG, "PoP provided; value hidden");\n        ProvisioningLog.i(TAG, "Security session initialization started");')
        s = replace_once(s, "            public void onSuccess(byte[] returnData) {\n\n                runOnUiThread", "            public void onSuccess(byte[] returnData) {\n\n                ProvisioningLog.i(TAG, \"Security session established successfully\");\n                runOnUiThread", "PoP session success")
        s = replace_once(s, "            public void onFailure(Exception e) {\n                e.printStackTrace();\n", "            public void onFailure(Exception e) {\n                ProvisioningLog.e(TAG, \"Security session initialization failed\", e);\n                e.printStackTrace();\n", "PoP session fail")
        write(p, s)

    p = "app/src/main/java/com/espressif/ui/activities/WiFiScanActivity.java"
    s = read(p)
    if "Wi-Fi scan started, source=" not in s:
        s = replace_once(s, "    private void startScan() {\n\n", "    private void startScan() {\n\n        ProvisioningLog.i(TAG, \"Wi-Fi scan started, source=\" + BuildConfig.WIFI_SCAN_SRC);\n", "WiFi scan marker")
        s = replace_once(s, "        } else {\n            goToProvisionActivity(ssid, password);\n", "        } else {\n            ProvisioningLog.i(TAG, \"Wi-Fi selected for provisioning, ssid=\" + ssid + \"; password hidden\");\n            goToProvisionActivity(ssid, password);\n", "WiFi selected marker")
        s = replace_once(s, "                        wifiAPList.addAll(wifiList);\n                        displayWifiList();\n", "                        wifiAPList.addAll(wifiList);\n                        ProvisioningLog.i(TAG, \"Device Wi-Fi scan results received, count=\" + wifiList.size());\n                        displayWifiList();\n", "WiFi results marker")
        write(p, s)

    p = "app/src/main/java/com/espressif/ui/activities/WiFiConfigActivity.java"
    s = read(p)
    if "Manual Wi-Fi provisioning requested" not in s:
        s = replace_once(s, "            goToProvisionActivity(ssid, password);\n", "            ProvisioningLog.i(TAG, \"Manual Wi-Fi provisioning requested, ssid=\" + ssid + \"; password hidden\");\n            goToProvisionActivity(ssid, password);\n", "manual wifi marker")
        write(p, s)


def sanitize_existing_sensitive_logs():
    p = "app/src/main/java/com/espressif/ui/activities/ProvisionActivity.java"
    s = read(p)
    s = s.replace('ProvisioningLog.d(TAG, "From Intent - deviceName: " + bleLocalCtrlDeviceName + ", pop: " + bleLocalCtrlPop);',
                  'ProvisioningLog.d(TAG, "From Intent - deviceName: " + bleLocalCtrlDeviceName + ", pop=<hidden>");')
    s = s.replace('ProvisioningLog.d(TAG, "Fallback - Got PoP from ESPDevice: " + bleLocalCtrlPop);',
                  'ProvisioningLog.d(TAG, "Fallback - Got PoP from ESPDevice: <hidden>");')
    s = s.replace('ProvisioningLog.d(TAG, "Final values - deviceName: " + bleLocalCtrlDeviceName + ", pop: " + bleLocalCtrlPop);',
                  'ProvisioningLog.d(TAG, "Final values - deviceName: " + bleLocalCtrlDeviceName + ", pop=<hidden>");')
    write(p, s)


def main():
    write("app/src/main/java/com/espressif/utils/ProvisioningLog.java", LOGGER)
    write("app/src/main/java/com/espressif/ui/activities/ProvisioningLogActivity.java", LOG_ACTIVITY)
    write("app/src/main/res/layout/activity_provisioning_log.xml", LOG_LAYOUT)

    patch_application()
    patch_settings()
    patch_manifest()
    patch_strings()

    targets = [
        "app/src/main/java/com/espressif/ui/activities/BLEProvisionLanding.java",
        "app/src/main/java/com/espressif/ui/activities/ProofOfPossessionActivity.java",
        "app/src/main/java/com/espressif/ui/activities/ClaimingActivity.java",
        "app/src/main/java/com/espressif/ui/activities/WiFiScanActivity.java",
        "app/src/main/java/com/espressif/ui/activities/WiFiConfigActivity.java",
        "app/src/main/java/com/espressif/ui/activities/ProvisionActivity.java",
        "app/src/main/java/com/espressif/ui/activities/BleWifiProvisionActivity.java",
        "app/src/main/java/com/espressif/utils/ExistingWifiReuseHelper.java",
    ]
    for path in targets:
        patch_log_proxy(path)

    add_flow_markers()
    sanitize_existing_sensitive_logs()
    print("In-app provisioning log implementation applied")


if __name__ == "__main__":
    main()
