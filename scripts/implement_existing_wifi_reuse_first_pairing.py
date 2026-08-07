#!/usr/bin/env python3
"""按 01_20260808 计划实现首次配对复用设备现有 Wi-Fi。

该脚本只用于功能分支的一次性、可审计源码变换。它会直接修改产品源码；
GitHub Actions 在编译通过后再提交这些源码修改。
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 anchor, found {count}")
    return source.replace(old, new, 1)


HELPER = r'''// Copyright 2026
package com.espressif.utils;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;

import com.espressif.AppConstants;
import com.espressif.provisioning.ESPConstants;
import com.espressif.provisioning.ESPDevice;
import com.espressif.provisioning.listeners.ResponseListener;
import com.espressif.provisioning.utils.MessengeHelper;
import com.espressif.rainmaker.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import espressif.Constants;
import espressif.NetworkConfig;
import espressif.NetworkConstants;

/**
 * 首次 RainMaker 添加设备时，读取设备当前标准 Provisioning Wi-Fi 状态，
 * 并在“当前 Wi-Fi 确实已连接且 SSID/IP 有效”时提供复用选择。
 *
 * 重要边界：
 * - 本类从不读取、缓存或下发旧 Wi-Fi 密码；
 * - “继续使用当前网络”只产生 UI 决策，不调用 ESPDevice.provision()；
 * - 查询失败、状态不是 CONNECTED、SSID/IP 无效时，一律回退现有 Wi-Fi 配置流程。
 */
public final class ExistingWifiReuseHelper {

    private static final String TAG = "ExistingWifiReuse";
    private static final long STATUS_QUERY_TIMEOUT_MS = 8000L;

    private ExistingWifiReuseHelper() {
    }

    public interface DecisionListener {
        void onReuseCurrentWifi(CurrentWifiStatus status);
        void onReconfigureWifi();
    }

    public static final class CurrentWifiStatus {
        private final boolean connected;
        private final String ssid;
        private final String ipAddress;
        private final String bssid;
        private final int channel;
        private final NetworkConstants.WifiAuthMode authMode;

        CurrentWifiStatus(boolean connected, String ssid, String ipAddress,
                          String bssid, int channel, NetworkConstants.WifiAuthMode authMode) {
            this.connected = connected;
            this.ssid = ssid == null ? "" : ssid;
            this.ipAddress = ipAddress == null ? "" : ipAddress;
            this.bssid = bssid == null ? "" : bssid;
            this.channel = channel;
            this.authMode = authMode;
        }

        public boolean isConnected() {
            return connected;
        }

        public String getSsid() {
            return ssid;
        }

        public String getIpAddress() {
            return ipAddress;
        }

        public String getBssid() {
            return bssid;
        }

        public int getChannel() {
            return channel;
        }

        public NetworkConstants.WifiAuthMode getAuthMode() {
            return authMode;
        }

        public boolean isReusable() {
            return connected
                    && !TextUtils.isEmpty(ssid)
                    && !TextUtils.isEmpty(ipAddress)
                    && !"0.0.0.0".equals(ipAddress);
        }
    }

    /**
     * 仅对 Wi-Fi/旧版默认 Wi-Fi 设备执行状态查询；Thread-only 设备保持原路由。
     */
    public static boolean shouldCheckWifi(List<String> deviceCaps) {
        if (deviceCaps == null || deviceCaps.isEmpty()) {
            return true;
        }
        if (deviceCaps.contains(AppConstants.CAPABILITY_WIFI_SCAN)
                || deviceCaps.contains(AppConstants.CAPABILITY_WIFI_PROV)) {
            return true;
        }
        return !deviceCaps.contains(AppConstants.CAPABILITY_THREAD_SCAN)
                && !deviceCaps.contains(AppConstants.CAPABILITY_THREAD_PROV);
    }

    /**
     * 查询设备当前 Wi-Fi 状态。只有 CONNECTED + 有效 SSID + 有效 IPv4 才弹出复用对话框；
     * 其余任何状态/协议错误/超时都直接走 onReconfigureWifi()。
     */
    public static void queryAndAsk(
            final Activity activity,
            final ESPDevice espDevice,
            final DecisionListener listener) {

        if (activity == null || espDevice == null || listener == null) {
            if (listener != null) {
                listener.onReconfigureWifi();
            }
            return;
        }

        final Handler handler = new Handler(Looper.getMainLooper());
        final AtomicBoolean queryFinished = new AtomicBoolean(false);

        final Runnable timeoutTask = new Runnable() {
            @Override
            public void run() {
                if (queryFinished.compareAndSet(false, true)) {
                    Log.w(TAG, "Current Wi-Fi status query timed out; use normal Wi-Fi provisioning");
                    runIfActivityAlive(activity, new Runnable() {
                        @Override
                        public void run() {
                            listener.onReconfigureWifi();
                        }
                    });
                }
            }
        };
        handler.postDelayed(timeoutTask, STATUS_QUERY_TIMEOUT_MS);

        byte[] message = MessengeHelper.prepareGetWiFiConfigStatusMsg();
        espDevice.sendDataToCustomEndPoint(
                ESPConstants.HANDLER_PROV_CONFIG,
                message,
                new ResponseListener() {
                    @Override
                    public void onSuccess(byte[] returnData) {
                        if (!queryFinished.compareAndSet(false, true)) {
                            return;
                        }
                        handler.removeCallbacks(timeoutTask);

                        final CurrentWifiStatus status;
                        try {
                            status = parseStatus(returnData);
                        } catch (Exception e) {
                            Log.w(TAG, "Unable to parse current Wi-Fi status; use normal provisioning", e);
                            runIfActivityAlive(activity, new Runnable() {
                                @Override
                                public void run() {
                                    listener.onReconfigureWifi();
                                }
                            });
                            return;
                        }

                        Log.i(TAG, "Current Wi-Fi: connected=" + status.isConnected()
                                + ", reusable=" + status.isReusable()
                                + ", ssid=" + status.getSsid()
                                + ", ip=" + status.getIpAddress()
                                + ", channel=" + status.getChannel());

                        runIfActivityAlive(activity, new Runnable() {
                            @Override
                            public void run() {
                                if (status.isReusable()) {
                                    showReuseDialog(activity, status, listener);
                                } else {
                                    listener.onReconfigureWifi();
                                }
                            }
                        });
                    }

                    @Override
                    public void onFailure(final Exception e) {
                        if (!queryFinished.compareAndSet(false, true)) {
                            return;
                        }
                        handler.removeCallbacks(timeoutTask);
                        Log.w(TAG, "Current Wi-Fi status query failed; use normal provisioning", e);
                        runIfActivityAlive(activity, new Runnable() {
                            @Override
                            public void run() {
                                listener.onReconfigureWifi();
                            }
                        });
                    }
                });
    }

    static CurrentWifiStatus parseStatus(byte[] responseData) throws Exception {
        if (responseData == null || responseData.length == 0) {
            throw new IllegalArgumentException("Empty Wi-Fi status response");
        }

        NetworkConfig.NetworkConfigPayload payload =
                NetworkConfig.NetworkConfigPayload.parseFrom(responseData);
        if (!payload.hasRespGetWifiStatus()) {
            throw new IllegalArgumentException("Response does not contain Wi-Fi status");
        }

        NetworkConfig.RespGetWifiStatus response = payload.getRespGetWifiStatus();
        boolean connected = response.getStatus() == Constants.Status.Success
                && response.getWifiStaState() == NetworkConstants.WifiStationState.Connected
                && response.hasWifiConnected();

        if (!connected) {
            return new CurrentWifiStatus(false, "", "", "", 0,
                    NetworkConstants.WifiAuthMode.Open);
        }

        NetworkConstants.WifiConnectedState wifi = response.getWifiConnected();
        String ssid = wifi.getSsid().toStringUtf8().trim();
        String ip = wifi.getIp4Addr() == null ? "" : wifi.getIp4Addr().trim();
        return new CurrentWifiStatus(
                true,
                ssid,
                ip,
                formatBssid(wifi.getBssid().toByteArray()),
                wifi.getChannel(),
                wifi.getAuthMode());
    }

    private static String formatBssid(byte[] bssid) {
        if (bssid == null || bssid.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(bssid.length * 3 - 1);
        for (int i = 0; i < bssid.length; i++) {
            if (i > 0) {
                builder.append(':');
            }
            builder.append(String.format("%02X", bssid[i] & 0xFF));
        }
        return builder.toString();
    }

    private static void showReuseDialog(
            final Activity activity,
            final CurrentWifiStatus status,
            final DecisionListener listener) {

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setCancelable(false);
        builder.setTitle(R.string.current_wifi_available_title);
        builder.setMessage(activity.getString(R.string.current_wifi_available_message, status.getSsid()));
        builder.setPositiveButton(R.string.continue_current_wifi, (dialog, which) ->
                listener.onReuseCurrentWifi(status));
        builder.setNegativeButton(R.string.reconfigure_wifi, (dialog, which) ->
                listener.onReconfigureWifi());

        if (!activity.isFinishing() && !activity.isDestroyed()) {
            builder.show();
        }
    }

    private static void runIfActivityAlive(Activity activity, Runnable runnable) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    runnable.run();
                }
            }
        });
    }
}
'''


def patch_app_constants() -> None:
    path = "app/src/main/java/com/espressif/AppConstants.kt"
    source = read(path)
    if "KEY_REUSE_CURRENT_WIFI" in source:
        return
    source = replace_once(
        source,
        '        const val KEY_BLE_LOCAL_CTRL = "ble_local_ctrl"\n',
        '        const val KEY_BLE_LOCAL_CTRL = "ble_local_ctrl"\n'
        '        // 首次添加时复用设备当前已连接的 Wi-Fi；不得借此标志下发新 Wi-Fi 凭据。\n'
        '        const val KEY_REUSE_CURRENT_WIFI = "reuse_current_wifi"\n',
        "AppConstants reuse-current-WiFi key",
    )
    write(path, source)


def patch_ble_landing() -> None:
    path = "app/src/main/java/com/espressif/ui/activities/BLEProvisionLanding.java"
    source = read(path)
    if "routeWithExistingWifiCheck" in source:
        return
    source = replace_once(
        source,
        'import com.espressif.ui.models.BleDevice;\n',
        'import com.espressif.ui.models.BleDevice;\n'
        'import com.espressif.utils.ExistingWifiReuseHelper;\n',
        "BLE landing helper import",
    )
    source = replace_once(
        source,
        '''        } else if (hasClaimCap || hasCameraClaimCap) {
            goToClaimingActivity(hasCameraClaimCap);
        } else if (tryShowBleLocalControlSkipFlow(rmakerExtraCaps, deviceCaps)) {
            return;
        } else {
            routeToWifiOrThread(deviceCaps);
        }
''',
        '''        } else if (hasClaimCap || hasCameraClaimCap) {
            goToClaimingActivity(hasCameraClaimCap);
        } else {
            /* No Claim capability: Security/PoP is already ready, so query the real
             * device Wi-Fi status before asking the user for new credentials. */
            routeWithExistingWifiCheck(deviceCaps);
        }
''',
        "BLE landing post-security route",
    )
    anchor = '''    private View.OnClickListener btnScanClickListener = new View.OnClickListener() {
'''
    methods = '''    private void routeWithExistingWifiCheck(final ArrayList<String> deviceCaps) {
        if (!ExistingWifiReuseHelper.shouldCheckWifi(deviceCaps)) {
            routeToWifiOrThread(deviceCaps);
            return;
        }

        ESPDevice espDevice = provisionManager.getEspDevice();
        if (espDevice == null) {
            routeToWifiOrThread(deviceCaps);
            return;
        }

        ExistingWifiReuseHelper.queryAndAsk(this, espDevice,
                new ExistingWifiReuseHelper.DecisionListener() {
                    @Override
                    public void onReuseCurrentWifi(ExistingWifiReuseHelper.CurrentWifiStatus status) {
                        goToProvisionUsingExistingWifi(status);
                    }

                    @Override
                    public void onReconfigureWifi() {
                        routeToWifiOrThread(deviceCaps);
                    }
                });
    }

    private void goToProvisionUsingExistingWifi(ExistingWifiReuseHelper.CurrentWifiStatus status) {
        Intent provisionIntent = new Intent(getApplicationContext(), ProvisionActivity.class);
        provisionIntent.putExtras(getIntent());
        provisionIntent.removeExtra(AppConstants.KEY_PASSWORD);
        provisionIntent.putExtra(AppConstants.KEY_SSID, status.getSsid());
        provisionIntent.putExtra(AppConstants.KEY_REUSE_CURRENT_WIFI, true);
        startActivity(provisionIntent);
        finish();
    }

'''
    source = replace_once(source, anchor, methods + anchor, "BLE landing reuse route methods")
    write(path, source)


def patch_pop_activity() -> None:
    path = "app/src/main/java/com/espressif/ui/activities/ProofOfPossessionActivity.java"
    source = read(path)
    if "routeWithExistingWifiCheck" in source:
        return
    source = replace_once(
        source,
        'import com.espressif.ui.Utils;\n',
        'import com.espressif.ui.Utils;\n'
        'import com.espressif.utils.ExistingWifiReuseHelper;\n',
        "PoP helper import",
    )
    source = replace_once(
        source,
        '''                        if (hasClaimCap || hasCameraClaimCap) {
                            goToClaimingActivity(hasCameraClaimCap);
                        } else if (checkAndShowBleLocalCtrlFlow()) {
                            return;
                        } else {
                            routeToWifiOrThread(deviceCaps);
                        }
''',
        '''                        if (hasClaimCap || hasCameraClaimCap) {
                            goToClaimingActivity(hasCameraClaimCap);
                        } else {
                            /* No Claim capability: query current device Wi-Fi first. */
                            routeWithExistingWifiCheck(deviceCaps);
                        }
''',
        "PoP post-security route",
    )
    anchor = '''    private void goToClaimingActivity(boolean isCameraClaim) {
'''
    methods = '''    private void routeWithExistingWifiCheck(final ArrayList<String> deviceCaps) {
        if (!ExistingWifiReuseHelper.shouldCheckWifi(deviceCaps)) {
            routeToWifiOrThread(deviceCaps);
            return;
        }

        if (provisionManager.getEspDevice() == null) {
            routeToWifiOrThread(deviceCaps);
            return;
        }

        ExistingWifiReuseHelper.queryAndAsk(this, provisionManager.getEspDevice(),
                new ExistingWifiReuseHelper.DecisionListener() {
                    @Override
                    public void onReuseCurrentWifi(ExistingWifiReuseHelper.CurrentWifiStatus status) {
                        Intent provisionIntent = new Intent(getApplicationContext(), ProvisionActivity.class);
                        provisionIntent.putExtras(getIntent());
                        provisionIntent.removeExtra(AppConstants.KEY_PASSWORD);
                        provisionIntent.putExtra(AppConstants.KEY_SSID, status.getSsid());
                        provisionIntent.putExtra(AppConstants.KEY_REUSE_CURRENT_WIFI, true);
                        startActivity(provisionIntent);
                        finish();
                    }

                    @Override
                    public void onReconfigureWifi() {
                        routeToWifiOrThread(deviceCaps);
                    }
                });
    }

'''
    source = replace_once(source, anchor, methods + anchor, "PoP reuse route methods")
    write(path, source)


def patch_claiming_activity() -> None:
    path = "app/src/main/java/com/espressif/ui/activities/ClaimingActivity.java"
    source = read(path)
    if "routeAfterClaimWithExistingWifiCheck" in source:
        return
    source = replace_once(
        source,
        'import com.espressif.rainmaker.databinding.ActivityClaimingBinding;\n',
        'import com.espressif.rainmaker.databinding.ActivityClaimingBinding;\n'
        'import com.espressif.utils.ExistingWifiReuseHelper;\n',
        "Claiming helper import",
    )
    source = replace_once(
        source,
        '''                    Log.d(TAG, "Certificate Sent to device successfully.");
                    if (!checkAndShowBleLocalCtrlFlow()) {
                        ArrayList<String> deviceCaps = provisionManager.getEspDevice().getDeviceCapabilities();
                        routeToWifiOrThread(deviceCaps);
                    }
''',
        '''                    Log.d(TAG, "Certificate Sent to device successfully.");
                    ArrayList<String> deviceCaps = provisionManager.getEspDevice().getDeviceCapabilities();
                    /* Claim 顺序保持不变。Claim 完成后先读取设备当前标准 Wi-Fi status，
                     * 再决定复用当前网络还是进入原有 Wi-Fi 配置页面。 */
                    routeAfterClaimWithExistingWifiCheck(deviceCaps);
''',
        "Claim completion route",
    )
    anchor = '''    private void goToWiFiScanActivity() {
'''
    methods = '''    private void routeAfterClaimWithExistingWifiCheck(final ArrayList<String> deviceCaps) {
        if (!ExistingWifiReuseHelper.shouldCheckWifi(deviceCaps)) {
            routeToWifiOrThread(deviceCaps);
            return;
        }

        if (provisionManager.getEspDevice() == null) {
            routeToWifiOrThread(deviceCaps);
            return;
        }

        ExistingWifiReuseHelper.queryAndAsk(this, provisionManager.getEspDevice(),
                new ExistingWifiReuseHelper.DecisionListener() {
                    @Override
                    public void onReuseCurrentWifi(ExistingWifiReuseHelper.CurrentWifiStatus status) {
                        Intent provisionIntent = new Intent(getApplicationContext(), ProvisionActivity.class);
                        provisionIntent.putExtras(getIntent());
                        /* 继续使用当前网络：显式移除密码，且由 ProvisionActivity 的独立
                         * KEY_REUSE_CURRENT_WIFI 分支保证不调用 ESPDevice.provision()。 */
                        provisionIntent.removeExtra(AppConstants.KEY_PASSWORD);
                        provisionIntent.putExtra(AppConstants.KEY_SSID, status.getSsid());
                        provisionIntent.putExtra(AppConstants.KEY_REUSE_CURRENT_WIFI, true);
                        startActivity(provisionIntent);
                        finish();
                    }

                    @Override
                    public void onReconfigureWifi() {
                        routeToWifiOrThread(deviceCaps);
                    }
                });
    }

'''
    source = replace_once(source, anchor, methods + anchor, "Claiming reuse route methods")
    write(path, source)


def patch_provision_activity() -> None:
    path = "app/src/main/java/com/espressif/ui/activities/ProvisionActivity.java"
    source = read(path)
    if "isReuseCurrentWifi" in source:
        return

    source = replace_once(
        source,
        '''    private boolean isBleLocalCtrlFlow = false;
    private String bleLocalCtrlDeviceName = null;
''',
        '''    private boolean isBleLocalCtrlFlow = false;
    /* 首次添加时复用设备已连接 Wi-Fi。该模式与 BLE local-control skip 语义独立。 */
    private boolean isReuseCurrentWifi = false;
    private String bleLocalCtrlDeviceName = null;
''',
        "Provision reuse state field",
    )
    source = replace_once(
        source,
        '''        isBleLocalCtrlFlow = intent.getBooleanExtra(AppConstants.KEY_BLE_LOCAL_CTRL, false);
        bleLocalCtrlDeviceName = intent.getStringExtra(AppConstants.KEY_DEVICE_NAME);
''',
        '''        isBleLocalCtrlFlow = intent.getBooleanExtra(AppConstants.KEY_BLE_LOCAL_CTRL, false);
        isReuseCurrentWifi = intent.getBooleanExtra(AppConstants.KEY_REUSE_CURRENT_WIFI, false);
        bleLocalCtrlDeviceName = intent.getStringExtra(AppConstants.KEY_DEVICE_NAME);
''',
        "Provision read reuse flag",
    )
    source = replace_once(
        source,
        '''        Log.d(TAG, "BLE Local Ctrl Flow: " + isBleLocalCtrlFlow);
''',
        '''        Log.d(TAG, "BLE Local Ctrl Flow: " + isBleLocalCtrlFlow);
        Log.d(TAG, "Reuse current Wi-Fi flow: " + isReuseCurrentWifi);
''',
        "Provision reuse log",
    )
    source = replace_once(
        source,
        '''        if (!TextUtils.isEmpty(dataset)) {
            tvProvStep1.setText(R.string.thread_prov_step_1);
            tvProvStep2.setText(R.string.thread_prov_step_2);
        }
''',
        '''        if (!TextUtils.isEmpty(dataset)) {
            tvProvStep1.setText(R.string.thread_prov_step_1);
            tvProvStep2.setText(R.string.thread_prov_step_2);
        } else if (isReuseCurrentWifi) {
            tvProvStep2.setText(R.string.current_wifi_reuse_prov_step);
        }
''',
        "Provision reuse UI label",
    )
    source = replace_once(
        source,
        '''                                                    if (isBleLocalCtrlFlow) {
                                                        /* BLE local control flow - skip Wi-Fi provisioning */
                                                        startBleLocalCtrlFlow();
                                                    } else {
                                                        provision();
                                                    }
''',
        '''                                                    if (isBleLocalCtrlFlow) {
                                                        /* BLE local control flow - skip Wi-Fi provisioning */
                                                        startBleLocalCtrlFlow();
                                                    } else if (isReuseCurrentWifi) {
                                                        continueWithExistingWifiAfterAssociation();
                                                    } else {
                                                        provision();
                                                    }
''',
        "Challenge mapping reuse branch",
    )
    source = replace_once(
        source,
        '''                receivedNodeId = response.getNodeId();
                this.secretKey = secretKey;

                provision();
''',
        '''                receivedNodeId = response.getNodeId();
                this.secretKey = secretKey;

                if (isReuseCurrentWifi) {
                    continueWithExistingWifiAfterAssociation();
                } else {
                    provision();
                }
''',
        "Traditional mapping reuse branch",
    )
    anchor = '''    private void provision() {
'''
    methods = '''    /**
     * 已确认设备当前 Wi-Fi 为 CONNECTED 后的首次添加路径。
     *
     * 这里故意不调用 ESPDevice.provision()，因此不会执行 set_config/apply_config，
     * 不会重新发送 SSID/password，也不会要求设备重写 Wi-Fi 持久化配置。
     * 用户映射已完成后，直接按“Wi-Fi 已就绪”推进原有后续添加节点流程。
     */
    private void continueWithExistingWifiAfterAssociation() {
        Log.i(TAG, "Reuse current Wi-Fi: skip set/apply config and continue device addition");
        isProvisioningCompleted = true;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                doStep2();
                doStep3(true);
            }
        });
    }

'''
    source = replace_once(source, anchor, methods + anchor, "Provision reuse continuation method")
    write(path, source)


def append_strings(path: str, entries: str) -> None:
    source = read(path)
    if "current_wifi_available_title" in source:
        return
    pos = source.rfind("</resources>")
    if pos < 0:
        raise RuntimeError(f"No </resources> in {path}")
    source = source[:pos] + entries + source[pos:]
    write(path, source)


def patch_strings() -> None:
    append_strings(
        "app/src/main/res/values/strings.xml",
        '''\n    <!-- First-pairing reuse of device current Wi-Fi -->\n'
        '    <string name="current_wifi_available_title">Current device network is available</string>\n'
        '    <string name="current_wifi_available_message">Device is currently connected to: %1$s\\n\\nContinue using this network without entering the password again.</string>\n'
        '    <string name="continue_current_wifi">Continue with current network</string>\n'
        '    <string name="reconfigure_wifi">Reconfigure Wi-Fi</string>\n'
        '    <string name="current_wifi_reuse_prov_step">Using current Wi-Fi connection</string>\n\n'''.replace("'\n        '", ""),
    )

    zh = '''\n    <!-- 首次配对复用设备当前 Wi-Fi -->\n'
        '    <string name="current_wifi_available_title">设备当前网络可用</string>\n'
        '    <string name="current_wifi_available_message">设备当前已连接网络：%1$s\\n\\n继续使用当前网络，无需重新输入密码。</string>\n'
        '    <string name="continue_current_wifi">继续使用当前网络</string>\n'
        '    <string name="reconfigure_wifi">重新配置 Wi-Fi</string>\n'
        '    <string name="current_wifi_reuse_prov_step">继续使用设备当前 Wi-Fi</string>\n\n'''.replace("'\n        '", "")
    append_strings("app/src/main/res/values-zh-rCN/strings.xml", zh)
    append_strings("app/src/main/res/values-zh-rSG/strings.xml", zh)


def patch_plan_status() -> None:
    path = "docs/architecture/01_20260808_existing_wifi_reuse_first_pairing_plan.md"
    source = read(path)
    if "## 11. 实施记录" in source:
        return
    source += '''\n## 11. 实施记录\n\n实现分支：`codex/existing-wifi-reuse-first-pairing`。\n\n本轮按计划落地：\n\n- 新增标准 `prov-config / TypeCmdGetWifiStatus` 状态读取；\n- 仅 `CONNECTED + 有效 SSID + 有效 IPv4` 才允许提示复用；\n- Claim 顺序保持不变，Claim 成功后再检查当前 Wi-Fi；\n- “继续使用当前网络”通过独立 `KEY_REUSE_CURRENT_WIFI` 进入后续映射/添加流程；\n- 该分支显式不调用 `ESPDevice.provision()`，因此不会发送新 SSID/password，也不会执行 `set_config + apply_config`；\n- “重新配置 Wi-Fi”继续复用现有 `WiFiScanActivity / WiFiConfigActivity / ProvisionActivity`；\n- 查询失败、超时、无有效网络时静默回退现有 Wi-Fi 配网；\n- Thread-only 设备保持原流程；\n- 旧 BLE local-control skip 代码暂保留，但不再作为首次添加“当前 Wi-Fi 可用”的判断依据。\n\n最终实机验收仍按第 9 节 7 个场景一次性执行。\n'''
    write(path, source)


def main() -> None:
    patch_app_constants()
    write("app/src/main/java/com/espressif/utils/ExistingWifiReuseHelper.java", HELPER)
    patch_ble_landing()
    patch_pop_activity()
    patch_claiming_activity()
    patch_provision_activity()
    patch_strings()
    patch_plan_status()
    print("Existing Wi-Fi first-pairing reuse implementation applied.")


if __name__ == "__main__":
    main()
