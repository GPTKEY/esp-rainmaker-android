#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel):
    return (ROOT / rel).read_text(encoding='utf-8')


def write(rel, text):
    (ROOT / rel).write_text(text, encoding='utf-8')


def replace_once(text, old, new, label):
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f'marker not found: {label}')
    return text.replace(old, new, 1)


def add_import(text, line):
    if line in text:
        return text
    marker = '\n\nimport '
    pos = text.find(marker)
    if pos < 0:
        raise RuntimeError(f'import insertion failed: {line}')
    return text[:pos+2] + line + '\n' + text[pos+2:]


# ---------------------------------------------------------------------------
# ProvisioningLog: UI-visible status/notice/progress helpers.
# ---------------------------------------------------------------------------
p = 'app/src/main/java/com/espressif/utils/ProvisioningLog.java'
s = read(p)
s = add_import(s, 'import android.content.Context;')
if 'public static void uiStatus(Context context, String tag, int resId)' not in s:
    marker = '    private static String sanitize(String message) {'
    helpers = '''    private static String resolveText(Context context, int resId) {\n        if (context == null) return "<context-null>";\n        try { return context.getString(resId); }\n        catch (Exception e) { return "<resource:" + resId + ">"; }\n    }\n\n    public static void uiStatus(Context context, String tag, int resId) {\n        uiStatus(tag, resolveText(context, resId));\n    }\n\n    public static void uiStatus(Context context, String tag, CharSequence text) {\n        uiStatus(tag, text);\n    }\n\n    public static void uiStatus(String tag, CharSequence text) {\n        add("I", tag, "[UI状态] " + String.valueOf(text));\n    }\n\n    public static void uiNotice(Context context, String tag, int resId) {\n        uiNotice(tag, resolveText(context, resId));\n    }\n\n    public static void uiNotice(Context context, String tag, CharSequence text) {\n        uiNotice(tag, text);\n    }\n\n    public static void uiNotice(String tag, CharSequence text) {\n        add("I", tag, "[UI通知] " + String.valueOf(text));\n    }\n\n    public static void uiProgress(Context context, String tag, String state, int resId) {\n        uiProgress(tag, state, resolveText(context, resId));\n    }\n\n    public static void uiProgress(Context context, String tag, String state, CharSequence text) {\n        uiProgress(tag, state, text);\n    }\n\n    public static void uiProgress(String tag, String state, CharSequence text) {\n        add("I", tag, "[UI进度][" + String.valueOf(state) + "] " + String.valueOf(text));\n    }\n\n'''
    s = s.replace(marker, helpers + marker, 1)
write(p, s)

# ---------------------------------------------------------------------------
# First-pairing BLE scan: log every scan result, duplicate decision and connect state.
# ---------------------------------------------------------------------------
p = 'app/src/main/java/com/espressif/ui/activities/BLEProvisionLanding.java'
s = read(p)
s = replace_once(s,
'''    private void startScan() {\n\n        ProvisioningLog.i(TAG, "BLE scan start, prefix=" + deviceNamePrefix);\n''',
'''    private void startScan() {\n\n        ProvisioningLog.i(TAG, "BLE scan start, prefix=" + deviceNamePrefix);\n        ProvisioningLog.uiProgress(this, TAG, "BLE搜索", "开始搜索配网设备，名称前缀=" + deviceNamePrefix);\n''', 'BLE start progress')
old = '''        public void onPeripheralFound(BluetoothDevice device, ScanResult scanResult) {\n\n            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {\n                if (ActivityCompat.checkSelfPermission(BLEProvisionLanding.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {\n                    ProvisioningLog.d(TAG, "====== onPeripheralFound ===== " + device.getName());\n                }\n            } else {\n                ProvisioningLog.d(TAG, "====== onPeripheralFound ===== " + device.getName());\n            }\n\n            boolean deviceExists = false;\n            String serviceUuid = "";\n\n            if (scanResult.getScanRecord().getServiceUuids() != null && scanResult.getScanRecord().getServiceUuids().size() > 0) {\n                serviceUuid = scanResult.getScanRecord().getServiceUuids().get(0).toString();\n            }\n            ProvisioningLog.d(TAG, "Add service UUID : " + serviceUuid);\n\n            if (bluetoothDevices.containsKey(device)) {\n                deviceExists = true;\n            }\n\n            if (!deviceExists) {\n                BleDevice bleDevice = new BleDevice(scanResult.getScanRecord().getDeviceName(), device);\n                rvBleDevices.setVisibility(View.VISIBLE);\n                bluetoothDevices.put(device, serviceUuid);\n                deviceList.add(bleDevice);\n                adapter.notifyDataSetChanged();\n            }\n        }\n'''
new = '''        public void onPeripheralFound(BluetoothDevice device, ScanResult scanResult) {\n\n            String scanName = scanResult.getScanRecord() != null ? scanResult.getScanRecord().getDeviceName() : null;\n            if (TextUtils.isEmpty(scanName)) {\n                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S\n                        || ActivityCompat.checkSelfPermission(BLEProvisionLanding.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {\n                    scanName = device.getName();\n                }\n            }\n            String address = "<权限不可用>";\n            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S\n                    || ActivityCompat.checkSelfPermission(BLEProvisionLanding.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {\n                address = device.getAddress();\n            }\n            String serviceUuids = scanResult.getScanRecord() != null\n                    && scanResult.getScanRecord().getServiceUuids() != null\n                    ? scanResult.getScanRecord().getServiceUuids().toString() : "[]";\n            String serviceUuid = "";\n            if (scanResult.getScanRecord() != null\n                    && scanResult.getScanRecord().getServiceUuids() != null\n                    && scanResult.getScanRecord().getServiceUuids().size() > 0) {\n                serviceUuid = scanResult.getScanRecord().getServiceUuids().get(0).toString();\n            }\n            boolean deviceExists = bluetoothDevices.containsKey(device);\n            boolean prefixMatch = !TextUtils.isEmpty(scanName)\n                    && (TextUtils.isEmpty(deviceNamePrefix) || scanName.startsWith(deviceNamePrefix));\n\n            ProvisioningLog.uiProgress(TAG, "BLE搜索结果",\n                    "name=" + scanName\n                            + ", mac=" + address\n                            + ", rssi=" + scanResult.getRssi()\n                            + " dBm, txPower=" + (scanResult.getScanRecord() != null ? scanResult.getScanRecord().getTxPowerLevel() : Integer.MIN_VALUE)\n                            + ", serviceUuids=" + serviceUuids\n                            + ", prefixMatch=" + prefixMatch\n                            + ", duplicate=" + deviceExists);\n\n            if (!deviceExists) {\n                BleDevice bleDevice = new BleDevice(scanName, device);\n                rvBleDevices.setVisibility(View.VISIBLE);\n                bluetoothDevices.put(device, serviceUuid);\n                deviceList.add(bleDevice);\n                adapter.notifyDataSetChanged();\n                ProvisioningLog.uiProgress(TAG, "BLE搜索结果", "加入候选列表：" + scanName + "，当前候选数=" + deviceList.size());\n            } else {\n                ProvisioningLog.d(TAG, "BLE duplicate scan result ignored: " + scanName + " / " + address);\n            }\n        }\n'''
s = replace_once(s, old, new, 'BLE detailed result')
s = replace_once(s,
'''        public void scanCompleted() {\n            ProvisioningLog.i(TAG, "BLE scan completed, devices=" + deviceList.size());\n''',
'''        public void scanCompleted() {\n            ProvisioningLog.i(TAG, "BLE scan completed, devices=" + deviceList.size());\n            ProvisioningLog.uiProgress(BLEProvisionLanding.this, TAG, "BLE搜索完成", "找到 " + deviceList.size() + " 个候选配网设备");\n''', 'BLE completed UI')
s = replace_once(s,
'''        BleDevice bleDevice = deviceList.get(deviceClickedPosition);\n        String uuid = bluetoothDevices.get(bleDevice.getBluetoothDevice());\n        ProvisioningLog.d(TAG, "=================== Connect to device : " + bleDevice.getName() + " UUID : " + uuid);\n''',
'''        BleDevice bleDevice = deviceList.get(deviceClickedPosition);\n        String uuid = bluetoothDevices.get(bleDevice.getBluetoothDevice());\n        ProvisioningLog.d(TAG, "=================== Connect to device : " + bleDevice.getName() + " UUID : " + uuid);\n        ProvisioningLog.uiProgress(this, TAG, "BLE连接", "正在连接设备 " + bleDevice.getName() + "，serviceUuid=" + uuid);\n''', 'BLE connect UI')
write(p, s)

# ---------------------------------------------------------------------------
# PoP/Security user-visible status.
# ---------------------------------------------------------------------------
p = 'app/src/main/java/com/espressif/ui/activities/ProofOfPossessionActivity.java'
s = read(p)
s = replace_once(s,
'''        provisionManager = ESPProvisionManager.getInstance(getApplicationContext());\n        initViews();\n''',
'''        provisionManager = ESPProvisionManager.getInstance(getApplicationContext());\n        initViews();\n        ProvisioningLog.uiProgress(this, TAG, "Security", "等待输入设备 PoP / 配对码");\n''', 'PoP entry')
s = replace_once(s,
'''        ProvisioningLog.i(TAG, "PoP provided; value hidden");\n        ProvisioningLog.i(TAG, "Security session initialization started");\n''',
'''        ProvisioningLog.i(TAG, "PoP provided; value hidden");\n        ProvisioningLog.i(TAG, "Security session initialization started");\n        ProvisioningLog.uiProgress(this, TAG, "Security", "正在建立安全会话，PoP 内容已隐藏");\n''', 'PoP session start')
s = replace_once(s,
'''                ProvisioningLog.i(TAG, "Security session established successfully");\n''',
'''                ProvisioningLog.i(TAG, "Security session established successfully");\n                ProvisioningLog.uiProgress(ProofOfPossessionActivity.this, TAG, "Security完成", "安全会话建立成功");\n''', 'PoP success')
s = replace_once(s,
'''                ProvisioningLog.e(TAG, "Security session initialization failed", e);\n''',
'''                ProvisioningLog.e(TAG, "Security session initialization failed", e);\n                ProvisioningLog.uiNotice(ProofOfPossessionActivity.this, TAG, "安全会话建立失败：" + e.getClass().getSimpleName());\n''', 'PoP failure')
write(p, s)

# ---------------------------------------------------------------------------
# Claim/certificate progress + exact UI error text at displayError().
# ---------------------------------------------------------------------------
p = 'app/src/main/java/com/espressif/ui/activities/ClaimingActivity.java'
s = read(p)
s = replace_once(s,
'''    private void sendClaimStartRequest() {\n\n        ProvisioningLog.d(TAG, "Claim Start Request");\n''',
'''    private void sendClaimStartRequest() {\n\n        ProvisioningLog.uiProgress(this, TAG, "Claim 1/5", "读取设备声明信息");\n        ProvisioningLog.d(TAG, "Claim Start Request");\n''', 'claim step1')
s = replace_once(s,
'''    private void sendClaimInitRequest(String data) {\n\n        ProvisioningLog.d(TAG, "Claim Init Request");\n''',
'''    private void sendClaimInitRequest(String data) {\n\n        ProvisioningLog.uiProgress(this, TAG, "Claim 2/5", "将云端 Claim 初始化数据发送到设备");\n        ProvisioningLog.d(TAG, "Claim Init Request");\n''', 'claim step2')
s = replace_once(s,
'''    private void sendDeviceInfoToCloud(String data) {\n\n        if (isClaimingAborted) {\n''',
'''    private void sendDeviceInfoToCloud(String data) {\n\n        ProvisioningLog.uiProgress(this, TAG, "Claim 云端", "正在向 RainMaker Claim 服务提交设备信息");\n        if (isClaimingAborted) {\n''', 'claim cloud init')
s = replace_once(s,
'''    private void sendCSRToAPI(String data) {\n\n        if (isClaimingAborted) {\n''',
'''    private void sendCSRToAPI(String data) {\n\n        ProvisioningLog.uiProgress(this, TAG, "Claim 3/5", "设备 CSR 已生成，正在向 RainMaker 请求证书");\n        if (isClaimingAborted) {\n''', 'claim csr cloud')
s = replace_once(s,
'''    private void sendCertificateToDevice(final int offset) {\n\n        if (isClaimingAborted) {\n''',
'''    private void sendCertificateToDevice(final int offset) {\n\n        if (offset == 0) {\n            ProvisioningLog.uiProgress(this, TAG, "Claim 4/5", "证书已签发，正在通过 BLE 写入设备");\n        }\n        if (isClaimingAborted) {\n''', 'claim cert')
s = replace_once(s,
'''                    ProvisioningLog.i(CLAIM_DIAG_TAG, "claim_verify_complete certificate_len=" + certificateData.length());\n''',
'''                    ProvisioningLog.i(CLAIM_DIAG_TAG, "claim_verify_complete certificate_len=" + certificateData.length());\n                    ProvisioningLog.uiProgress(ClaimingActivity.this, TAG, "Claim 5/5", "设备证书写入完成，Claim 成功");\n''', 'claim complete')
s = replace_once(s,
'''        binding.layoutClaiming.tvClaimingProgress.setText(R.string.progress_claiming);\n        binding.layoutClaiming.tvClaimingError.setText(R.string.process_take_time);\n''',
'''        binding.layoutClaiming.tvClaimingProgress.setText(R.string.progress_claiming);\n        binding.layoutClaiming.tvClaimingError.setText(R.string.process_take_time);\n        ProvisioningLog.uiStatus(this, TAG, binding.layoutClaiming.tvClaimingProgress.getText());\n        ProvisioningLog.uiNotice(this, TAG, binding.layoutClaiming.tvClaimingError.getText());\n''', 'claim initial UI')
s = replace_once(s,
'''        ProvisioningLog.e(TAG, "Claiming error occurred");\n        stopClaimingProgress();\n''',
'''        ProvisioningLog.e(TAG, "Claiming error occurred");\n        ProvisioningLog.uiStatus(this, TAG, binding.layoutClaiming.tvClaimingProgress.getText());\n        ProvisioningLog.uiNotice(this, TAG, binding.layoutClaiming.tvClaimingError.getText());\n        stopClaimingProgress();\n''', 'claim display error')
write(p, s)

# ---------------------------------------------------------------------------
# Existing Wi-Fi reuse decision status.
# ---------------------------------------------------------------------------
p = 'app/src/main/java/com/espressif/utils/ExistingWifiReuseHelper.java'
s = read(p)
s = replace_once(s,
'''        final Handler handler = new Handler(Looper.getMainLooper());\n''',
'''        ProvisioningLog.uiProgress(activity, TAG, "Wi-Fi状态", "读取设备当前 Wi-Fi 连接状态");\n        final Handler handler = new Handler(Looper.getMainLooper());\n''', 'reuse query')
s = replace_once(s,
'''                                if (status.isReusable()) {\n                                    showReuseDialog(activity, status, listener);\n                                } else {\n                                    listener.onReconfigureWifi();\n                                }\n''',
'''                                if (status.isReusable()) {\n                                    ProvisioningLog.uiProgress(activity, TAG, "Wi-Fi状态", "设备当前已联网：" + status.getSsid() + "，IP=" + status.getIpAddress());\n                                    showReuseDialog(activity, status, listener);\n                                } else {\n                                    ProvisioningLog.uiProgress(activity, TAG, "Wi-Fi状态", "设备当前没有可复用的有效 Wi-Fi，进入重新配置流程");\n                                    listener.onReconfigureWifi();\n                                }\n''', 'reuse result')
s = replace_once(s,
'''        builder.setPositiveButton(R.string.continue_current_wifi, (dialog, which) ->\n                listener.onReuseCurrentWifi(status));\n        builder.setNegativeButton(R.string.reconfigure_wifi, (dialog, which) ->\n                listener.onReconfigureWifi());\n''',
'''        builder.setPositiveButton(R.string.continue_current_wifi, (dialog, which) -> {\n            ProvisioningLog.uiNotice(activity, TAG, "用户选择：继续使用当前网络 " + status.getSsid());\n            listener.onReuseCurrentWifi(status);\n        });\n        builder.setNegativeButton(R.string.reconfigure_wifi, (dialog, which) -> {\n            ProvisioningLog.uiNotice(activity, TAG, "用户选择：重新配置 Wi-Fi");\n            listener.onReconfigureWifi();\n        });\n''', 'reuse choice')
write(p, s)

# ---------------------------------------------------------------------------
# Wi-Fi scan page: every AP from device + UI selection.
# ---------------------------------------------------------------------------
p = 'app/src/main/java/com/espressif/ui/activities/WiFiScanActivity.java'
s = read(p)
s = replace_once(s,
'''        ProvisioningLog.i(TAG, "Wi-Fi scan started, source=" + BuildConfig.WIFI_SCAN_SRC);\n''',
'''        ProvisioningLog.i(TAG, "Wi-Fi scan started, source=" + BuildConfig.WIFI_SCAN_SRC);\n        ProvisioningLog.uiProgress(this, TAG, "Wi-Fi搜索", "开始搜索可用 Wi-Fi，来源=" + BuildConfig.WIFI_SCAN_SRC);\n''', 'wifi scan ui')
s = replace_once(s,
'''                        wifiAPList.addAll(wifiList);\n                        ProvisioningLog.i(TAG, "Device Wi-Fi scan results received, count=" + wifiList.size());\n                        displayWifiList();\n''',
'''                        wifiAPList.addAll(wifiList);\n                        ProvisioningLog.i(TAG, "Device Wi-Fi scan results received, count=" + wifiList.size());\n                        ProvisioningLog.uiProgress(WiFiScanActivity.this, TAG, "Wi-Fi搜索结果", "设备返回 " + wifiList.size() + " 个热点");\n                        for (WiFiAccessPoint ap : wifiList) {\n                            ProvisioningLog.i(TAG, "[Wi-Fi搜索结果] ssid=" + ap.getWifiName()\n                                    + ", rssi=" + ap.getRssi()\n                                    + ", security=" + ap.getSecurity());\n                        }\n                        displayWifiList();\n''', 'device wifi list')
s = replace_once(s,
'''            ProvisioningLog.i(TAG, "Wi-Fi selected for provisioning, ssid=" + ssid + "; password hidden");\n''',
'''            ProvisioningLog.i(TAG, "Wi-Fi selected for provisioning, ssid=" + ssid + "; password hidden");\n            ProvisioningLog.uiNotice(this, TAG, "已选择 Wi-Fi：" + ssid + "，密码内容已隐藏");\n''', 'wifi selection')
write(p, s)

# Manual Wi-Fi page.
p = 'app/src/main/java/com/espressif/ui/activities/WiFiConfigActivity.java'
s = read(p)
s = replace_once(s,
'''        provisionManager = ESPProvisionManager.getInstance(getApplicationContext());\n        initViews();\n''',
'''        provisionManager = ESPProvisionManager.getInstance(getApplicationContext());\n        initViews();\n        ProvisioningLog.uiProgress(this, TAG, "Wi-Fi配置", "等待手动输入 Wi-Fi 名称和密码");\n''', 'manual wifi entry')
s = replace_once(s,
'''            ProvisioningLog.i(TAG, "Manual Wi-Fi provisioning requested, ssid=" + ssid + "; password hidden");\n''',
'''            ProvisioningLog.i(TAG, "Manual Wi-Fi provisioning requested, ssid=" + ssid + "; password hidden");\n            ProvisioningLog.uiNotice(this, TAG, "手动配置 Wi-Fi：" + ssid + "，密码内容已隐藏");\n''', 'manual wifi chosen')
write(p, s)

# ---------------------------------------------------------------------------
# Main provisioning progress screen: mirror state transitions and user-visible errors.
# ---------------------------------------------------------------------------
p = 'app/src/main/java/com/espressif/ui/activities/ProvisionActivity.java'
s = read(p)
s = replace_once(s,
'''    private void doStep1() {\n\n        tick1.setVisibility(View.GONE);\n''',
'''    private void doStep1() {\n\n        ProvisioningLog.uiProgress(this, TAG, "步骤1", tvProvStep1.getText());\n        tick1.setVisibility(View.GONE);\n''', 'prov step1')
s = replace_once(s,
'''    private void doStep2() {\n\n        tick1.setImageResource(R.drawable.ic_checkbox_on);\n''',
'''    private void doStep2() {\n\n        ProvisioningLog.uiProgress(this, TAG, "步骤1完成", tvProvStep1.getText());\n        ProvisioningLog.uiProgress(this, TAG, "步骤2", tvProvStep2.getText());\n        tick1.setImageResource(R.drawable.ic_checkbox_on);\n''', 'prov step2')
s = replace_once(s,
'''    private void doStep3(boolean isSuccessInStep2) {\n\n        if (isSuccessInStep2) {\n''',
'''    private void doStep3(boolean isSuccessInStep2) {\n\n        ProvisioningLog.uiProgress(this, TAG, "步骤2结果", tvProvStep2.getText() + "，success=" + isSuccessInStep2);\n        if (isSuccessInStep2) {\n''', 'prov step3')
s = replace_once(s,
'''    private void doStep4() {\n\n        hideLoading();\n''',
'''    private void doStep4() {\n\n        ProvisioningLog.uiProgress(this, TAG, "步骤4", getString(R.string.prov_step_4));\n        hideLoading();\n''', 'prov step4')
s = replace_once(s,
'''    private void doStep5() {\n\n        ProvisioningLog.d(TAG, "================= Do step 5 =================");\n''',
'''    private void doStep5() {\n\n        ProvisioningLog.uiProgress(this, TAG, "步骤5", getString(R.string.prov_step_5));\n        ProvisioningLog.d(TAG, "================= Do step 5 =================");\n''', 'prov step5')
s = replace_once(s,
'''    private void continueWithExistingWifiAfterAssociation() {\n        ProvisioningLog.i(TAG, "Reuse current Wi-Fi: skip set/apply config and continue device addition");\n''',
'''    private void continueWithExistingWifiAfterAssociation() {\n        ProvisioningLog.i(TAG, "Reuse current Wi-Fi: skip set/apply config and continue device addition");\n        ProvisioningLog.uiProgress(this, TAG, "复用Wi-Fi", "设备已联网，跳过 SSID/password 下发，继续添加节点");\n''', 'prov reuse')
s = replace_once(s,
'''    private void provision() {\n\n        ProvisioningLog.d(TAG, "+++++++++++++++++++++++++++++ PROVISION +++++++++++++++++++++++++++++");\n''',
'''    private void provision() {\n\n        ProvisioningLog.uiProgress(this, TAG, "Wi-Fi下发", "开始发送 Wi-Fi 配置，SSID=" + ssidValue + "，密码已隐藏");\n        ProvisioningLog.d(TAG, "+++++++++++++++++++++++++++++ PROVISION +++++++++++++++++++++++++++++");\n''', 'prov send wifi')
s = replace_once(s,
'''    private void displayFailureAtStep2() {\n\n        tick2.setImageResource(R.drawable.ic_error);\n''',
'''    private void displayFailureAtStep2() {\n\n        ProvisioningLog.uiNotice(this, TAG, tvErrAtStep2.getText());\n        tick2.setImageResource(R.drawable.ic_error);\n''', 'prov failure text')
s = replace_once(s,
'''    private void showMappingError() {\n        runOnUiThread(() -> {\n''',
'''    private void showMappingError() {\n        ProvisioningLog.uiNotice(this, TAG, getString(R.string.error_node_association_msg));\n        runOnUiThread(() -> {\n''', 'mapping failure')
s = replace_once(s,
'''            ProvisioningLog.e(TAG, "WiFi connection confirmation timed out");\n''',
'''            ProvisioningLog.e(TAG, "WiFi connection confirmation timed out");\n            ProvisioningLog.uiNotice(ProvisionActivity.this, TAG, getString(R.string.error_wifi_connection_failed));\n''', 'wifi timeout')
# Log successful final UI at both common success sites.
s = s.replace('tvProvSuccess.setVisibility(View.VISIBLE);',
              'tvProvSuccess.setVisibility(View.VISIBLE);\n                                            ProvisioningLog.uiNotice(ProvisionActivity.this, TAG, tvProvSuccess.getText());')
write(p, s)

# ---------------------------------------------------------------------------
# Already-bound BLE discovery and connection manager.
# ---------------------------------------------------------------------------
p = 'app/src/main/java/com/espressif/ble/BleLocalControlManager.kt'
s = read(p)
s = s.replace('import android.util.Log\n', 'import com.espressif.utils.ProvisioningLog\n')
s = s.replace('Log.', 'ProvisioningLog.')
s = replace_once(s,
'''        val bleDevices = collectBleDevices()\n        if (bleDevices.isEmpty()) {\n''',
'''        val bleDevices = collectBleDevices()\n        ProvisioningLog.uiProgress(TAG, "已绑定设备发现", "已绑定节点中包含 BLE 本地控制信息的设备数=${bleDevices.size}")\n        for ((nodeId, bleInfo) in bleDevices) {\n            ProvisioningLog.i(TAG, "[已绑定设备] nodeId=$nodeId, bleName=${bleInfo.name}, pop=<hidden>")\n        }\n        if (bleDevices.isEmpty()) {\n''', 'bound candidates')
s = replace_once(s,
'''        isBleScanning = true\n        ProvisioningLog.d(TAG, "Starting broad BLE scan with prefix '$BLE_DEVICE_PREFIX' (attempt ${scanRetryCount + 1})")\n''',
'''        isBleScanning = true\n        ProvisioningLog.d(TAG, "Starting broad BLE scan with prefix '$BLE_DEVICE_PREFIX' (attempt ${scanRetryCount + 1})")\n        ProvisioningLog.uiProgress(TAG, "已绑定BLE搜索", "开始扫描，prefix=$BLE_DEVICE_PREFIX，attempt=${scanRetryCount + 1}")\n''', 'bound scan start')
old = '''        override fun onPeripheralFound(device: BluetoothDevice, scanResult: ScanResult) {\n            val deviceName = scanResult.scanRecord?.deviceName ?: return\n\n            for (conn in connectionMap.values) {\n                if (conn.state == ConnectionState.DISCONNECTED\n                    && conn.bluetoothDevice == null\n                    && deviceName == conn.bleInfo.name\n                ) {\n                    val serviceUuid = if (scanResult.scanRecord?.serviceUuids?.isNotEmpty() == true) {\n                        scanResult.scanRecord!!.serviceUuids!![0].toString()\n                    } else {\n                        ""\n                    }\n                    conn.bluetoothDevice = device\n                    conn.serviceUuid = serviceUuid\n                    ProvisioningLog.d(TAG, "Scan matched: $deviceName -> node ${conn.nodeId}")\n                    break\n                }\n            }\n        }\n'''
new = '''        override fun onPeripheralFound(device: BluetoothDevice, scanResult: ScanResult) {\n            val deviceName = scanResult.scanRecord?.deviceName ?: device.name ?: "<unknown>"\n            val serviceUuids = scanResult.scanRecord?.serviceUuids?.joinToString(",") ?: ""\n            val address = try { device.address ?: "<unknown>" } catch (_: SecurityException) { "<permission-denied>" }\n            val matchingNodes = connectionMap.values.filter { it.bleInfo.name == deviceName }.map { it.nodeId }\n            ProvisioningLog.uiProgress(TAG, "已绑定BLE搜索结果",\n                "name=$deviceName, mac=$address, rssi=${scanResult.rssi} dBm, serviceUuids=[$serviceUuids], matchedNodes=${matchingNodes.joinToString(",")}")\n\n            for (conn in connectionMap.values) {\n                if (conn.state == ConnectionState.DISCONNECTED\n                    && conn.bluetoothDevice == null\n                    && deviceName == conn.bleInfo.name\n                ) {\n                    val serviceUuid = if (scanResult.scanRecord?.serviceUuids?.isNotEmpty() == true) {\n                        scanResult.scanRecord!!.serviceUuids!![0].toString()\n                    } else {\n                        ""\n                    }\n                    conn.bluetoothDevice = device\n                    conn.serviceUuid = serviceUuid\n                    ProvisioningLog.uiProgress(TAG, "已绑定BLE匹配", "设备 $deviceName 匹配 nodeId=${conn.nodeId}，serviceUuid=$serviceUuid")\n                    break\n                }\n            }\n        }\n'''
s = replace_once(s, old, new, 'bound detailed scan')
s = replace_once(s,
'''        override fun scanCompleted() {\n            ProvisioningLog.d(TAG, "BLE scan completed")\n''',
'''        override fun scanCompleted() {\n            ProvisioningLog.d(TAG, "BLE scan completed")\n            ProvisioningLog.uiProgress(TAG, "已绑定BLE搜索完成", "BLE broad scan completed")\n''', 'bound scan complete')
s = replace_once(s,
'''        ProvisioningLog.d(TAG, "Connecting to BLE device: ${bluetoothDevice.name} for node ${conn.nodeId}")\n''',
'''        ProvisioningLog.d(TAG, "Connecting to BLE device: ${bluetoothDevice.name} for node ${conn.nodeId}")\n        ProvisioningLog.uiProgress(TAG, "已绑定BLE连接", "正在连接 ${bluetoothDevice.name} / nodeId=${conn.nodeId}")\n''', 'bound connect')
s = replace_once(s,
'''                ProvisioningLog.d(TAG, "BLE session success for $nodeId")\n''',
'''                ProvisioningLog.d(TAG, "BLE session success for $nodeId")\n                ProvisioningLog.uiProgress(TAG, "已绑定BLE会话", "nodeId=$nodeId 安全会话建立成功，可进行本地控制/重配网")\n''', 'bound session success')
write(p, s)

# ---------------------------------------------------------------------------
# Trigger point after cloud node data is loaded.
# ---------------------------------------------------------------------------
p = 'app/src/main/java/com/espressif/ui/activities/EspMainActivity.java'
s = read(p)
s = add_import(s, 'import com.espressif.utils.ProvisioningLog;')
s = replace_once(s,
'''                if (espApp.getAppState() == EspApplication.AppState.GET_DATA_SUCCESS) {\n                    BleLocalControlManager bleManager = BleLocalControlManager.getInstance(this);\n''',
'''                if (espApp.getAppState() == EspApplication.AppState.GET_DATA_SUCCESS) {\n                    ProvisioningLog.uiProgress(this, TAG, "已绑定设备发现", "RainMaker 节点列表加载完成，开始发现附近已绑定 BLE 设备");\n                    BleLocalControlManager bleManager = BleLocalControlManager.getInstance(this);\n''', 'main bound discovery trigger')
write(p, s)

# ---------------------------------------------------------------------------
# Already-bound Wi-Fi re-provisioning activity.
# ---------------------------------------------------------------------------
p = 'app/src/main/java/com/espressif/ui/activities/BleWifiProvisionActivity.java'
s = read(p)
s = replace_once(s,
'''        initViews();\n        startWifiScan();\n''',
'''        initViews();\n        ProvisioningLog.uiProgress(this, TAG, "已绑定重配网", "已进入已绑定设备 Wi-Fi 重新配置流程，nodeId=" + nodeId);\n        startWifiScan();\n''', 'reprov entry')
s = replace_once(s,
'''    private void startWifiScan() {\n        showScanLoading();\n''',
'''    private void startWifiScan() {\n        ProvisioningLog.uiProgress(this, TAG, "已绑定重配网-Wi-Fi搜索", "通过现有 BLE 会话扫描设备可见的 Wi-Fi");\n        showScanLoading();\n''', 'reprov scan')
s = replace_once(s,
'''                        wifiAPList.addAll(wifiList);\n                        wiFiListAdapter.notifyDataSetChanged();\n''',
'''                        wifiAPList.addAll(wifiList);\n                        ProvisioningLog.uiProgress(BleWifiProvisionActivity.this, TAG, "已绑定重配网-Wi-Fi结果", "扫描到 " + wifiList.size() + " 个热点");\n                        for (WiFiAccessPoint ap : wifiList) {\n                            ProvisioningLog.i(TAG, "[重配网Wi-Fi结果] ssid=" + ap.getWifiName()\n                                    + ", rssi=" + ap.getRssi()\n                                    + ", security=" + ap.getSecurity());\n                        }\n                        wiFiListAdapter.notifyDataSetChanged();\n''', 'reprov wifi results')
s = replace_once(s,
'''                selectedWiFi = wifiAPList.get(pos);\n                ssid = selectedWiFi.getWifiName();\n''',
'''                selectedWiFi = wifiAPList.get(pos);\n                ssid = selectedWiFi.getWifiName();\n                ProvisioningLog.uiNotice(BleWifiProvisionActivity.this, TAG, "重配网选择 Wi-Fi：" + ssid);\n''', 'reprov selection')
s = replace_once(s,
'''    private void startProvisioning(String ssidValue, String password) {\n        layoutWifiSelect.setVisibility(View.GONE);\n''',
'''    private void startProvisioning(String ssidValue, String password) {\n        ProvisioningLog.uiProgress(this, TAG, "已绑定重配网 1/3", "发送新 Wi-Fi 凭据，SSID=" + ssidValue + "，密码已隐藏");\n        layoutWifiSelect.setVisibility(View.GONE);\n''', 'reprov send')
s = replace_once(s,
'''            public void wifiConfigSent() {\n                runOnUiThread(() -> {\n''',
'''            public void wifiConfigSent() {\n                ProvisioningLog.uiProgress(BleWifiProvisionActivity.this, TAG, "已绑定重配网 1/3", "Wi-Fi 凭据发送成功");\n                runOnUiThread(() -> {\n''', 'reprov sent')
s = replace_once(s,
'''            public void wifiConfigApplied() {\n                runOnUiThread(() -> {\n''',
'''            public void wifiConfigApplied() {\n                ProvisioningLog.uiProgress(BleWifiProvisionActivity.this, TAG, "已绑定重配网 2/3", "设备已应用新 Wi-Fi 配置，等待联网确认");\n                runOnUiThread(() -> {\n''', 'reprov applied')
s = replace_once(s,
'''            public void deviceProvisioningSuccess() {\n                runOnUiThread(() -> {\n''',
'''            public void deviceProvisioningSuccess() {\n                ProvisioningLog.uiProgress(BleWifiProvisionActivity.this, TAG, "已绑定重配网 3/3", "设备已连接新 Wi-Fi，重配网成功");\n                runOnUiThread(() -> {\n''', 'reprov success')
s = replace_once(s,
'''    private void showProvisionError(String detail) {\n        ProvisioningLog.e(TAG, detail);\n''',
'''    private void showProvisionError(String detail) {\n        ProvisioningLog.e(TAG, detail);\n        ProvisioningLog.uiNotice(this, TAG, "已绑定设备重配网失败：" + detail);\n''', 'reprov error')
write(p, s)

print('Pairing UI/notification/BLE-search logging implementation applied.')
