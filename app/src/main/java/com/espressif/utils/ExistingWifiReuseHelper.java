// Copyright 2026
package com.espressif.utils;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import com.espressif.utils.ProvisioningLog;

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

        ProvisioningLog.uiProgress(activity, TAG, "Wi-Fi状态", "读取设备当前 Wi-Fi 连接状态");
        final Handler handler = new Handler(Looper.getMainLooper());
        final AtomicBoolean queryFinished = new AtomicBoolean(false);

        final Runnable timeoutTask = new Runnable() {
            @Override
            public void run() {
                if (queryFinished.compareAndSet(false, true)) {
                    ProvisioningLog.w(TAG, "Current Wi-Fi status query timed out; use normal Wi-Fi provisioning");
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
                            ProvisioningLog.w(TAG, "Unable to parse current Wi-Fi status; use normal provisioning", e);
                            runIfActivityAlive(activity, new Runnable() {
                                @Override
                                public void run() {
                                    listener.onReconfigureWifi();
                                }
                            });
                            return;
                        }

                        ProvisioningLog.i(TAG, "Current Wi-Fi: connected=" + status.isConnected()
                                + ", reusable=" + status.isReusable()
                                + ", ssid=" + status.getSsid()
                                + ", ip=" + status.getIpAddress()
                                + ", channel=" + status.getChannel());

                        runIfActivityAlive(activity, new Runnable() {
                            @Override
                            public void run() {
                                if (status.isReusable()) {
                                    ProvisioningLog.uiProgress(activity, TAG, "Wi-Fi状态", "设备当前已联网：" + status.getSsid() + "，IP=" + status.getIpAddress());
                                    showReuseDialog(activity, status, listener);
                                } else {
                                    ProvisioningLog.uiProgress(activity, TAG, "Wi-Fi状态", "设备当前没有可复用的有效 Wi-Fi，进入重新配置流程");
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
                        ProvisioningLog.w(TAG, "Current Wi-Fi status query failed; use normal provisioning", e);
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
        builder.setPositiveButton(R.string.continue_current_wifi, (dialog, which) -> {
            ProvisioningLog.uiNotice(activity, TAG, "用户选择：继续使用当前网络 " + status.getSsid());
            listener.onReuseCurrentWifi(status);
        });
        builder.setNegativeButton(R.string.reconfigure_wifi, (dialog, which) -> {
            ProvisioningLog.uiNotice(activity, TAG, "用户选择：重新配置 Wi-Fi");
            listener.onReconfigureWifi();
        });

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
