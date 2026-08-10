package com.espressif.diagnostics

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE 原始扫描诊断器。
 *
 * 目的：
 * - 只在二维码已经识别、Provisioning library 正在查找 BLE 设备的短时间窗口内启用；
 * - 旁路观察 Android 系统实际收到的 ScanResult，不参与设备选择、连接、Security2、PoP 或配网状态机；
 * - 即使设备名为空也保留日志，用于区分“空口完全收不到”和“收到广播但 Scan Response/名称不可见”。
 *
 * 生命周期：
 * - 所有权属于创建它的 Activity；
 * - 调用 [start] 后最多运行 [SCAN_TIMEOUT_MS]；
 * - Activity pause/destroy、设备发现成功或配网查找失败时均应主动调用 [stop]；
 * - 内部 Handler 只负责超时停止，不创建线程、不持有 Activity 引用，Context 会转为 applicationContext。
 *
 * 并发规则：
 * - Android BLE callback 可能与 UI/Provisioning library callback 并行；
 * - 本类只写日志和自身状态，不读写 ESPDevice、Provisioning Manager 或业务变量；
 * - [lastFingerprints] 使用 ConcurrentHashMap，避免 callback 与目标名更新之间的数据竞争；
 * - 同一地址、同一广播内容只打印一次，但当名称/UUID/raw bytes 发生变化时会再次打印，因此能观察
 *   “首包 name=null，后续 Scan Response 合并后出现 PROV_xxx”这种关键变化。
 *
 * 权限：
 * - Android 12+ 复用现有 BLUETOOTH_SCAN / BLUETOOTH_CONNECT；
 * - Android 11 及以下复用现有 ACCESS_FINE_LOCATION；
 * - 本类不申请新权限，权限不足时只打印诊断并退出。
 */
class BleRawScanDiagnostics(context: Context) {

    companion object {
        private const val TAG = "BLE_RAW_SCAN"

        /**
         * 覆盖 provisioning library 的 3 轮 6 秒扫描和轮间 500 ms 间隔，并留少量收尾裕量。
         */
        private const val SCAN_TIMEOUT_MS = 22_000L
    }

    /** Application Context，避免诊断器延长 Activity 生命周期。 */
    private val appContext = context.applicationContext

    /** 主线程 Handler，仅用于超时停止扫描。 */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 每个 BLE 地址最近一次已输出的“可观察状态指纹”。
     * key 为 Android ScanResult 中的设备地址；value 仅用于日志去重，不跨扫描会话保留。
     */
    private val lastFingerprints = ConcurrentHashMap<String, String>()

    /** 当前系统 BluetoothLeScanner；start 时取得，stop 后清空。 */
    @Volatile
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null

    /** 当前是否由本诊断器持有一个扫描 callback。 */
    @Volatile
    private var started = false

    /**
     * 二维码解析出的目标设备名，例如 PROV_e082a5。
     * 允许为空；设置后仅用于日志中的 name_match 判定，不参与扫描过滤和连接。
     */
    @Volatile
    private var targetDeviceName: String? = null

    private val stopRunnable = Runnable {
        stop("timeout")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            logResult("single", result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { logResult("batch", it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan_failed code=$errorCode")
            stop("scan_failed_$errorCode")
        }
    }

    /**
     * 更新二维码目标设备名。
     *
     * 该值只用于日志比对；清空去重表后，下一次收到相同广播仍会重新打印一次，确保日志里能看到
     * `target=... name_match=...`，不会因为目标名是在扫描启动后才由 AAR 创建 ESPDevice 而漏掉判据。
     */
    fun setTargetDeviceName(name: String?) {
        val normalized = name?.trim()?.takeIf { it.isNotEmpty() }
        targetDeviceName = normalized
        lastFingerprints.clear()
        Log.i(TAG, "target_updated name=${normalized ?: "<null>"}")
    }

    /**
     * 启动无过滤、BALANCED 模式的只读扫描。
     *
     * 不调用 stopBleScan、不修改 provisioning library scanner；若系统拒绝额外 scanner，会通过
     * onScanFailed 或异常日志体现，原有配网流程仍继续运行。
     */
    fun start() {
        if (started) {
            Log.d(TAG, "start ignored: already_started")
            return
        }
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "start skipped: required BLE/location permission missing")
            return
        }

        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            Log.e(TAG, "start skipped: bluetooth adapter unavailable")
            return
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "start skipped: bluetooth disabled")
            return
        }

        val localScanner = adapter.bluetoothLeScanner
        if (localScanner == null) {
            Log.e(TAG, "start skipped: BluetoothLeScanner unavailable")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        try {
            lastFingerprints.clear()
            scanner = localScanner
            started = true
            localScanner.startScan(emptyList(), settings, scanCallback)
            handler.removeCallbacks(stopRunnable)
            handler.postDelayed(stopRunnable, SCAN_TIMEOUT_MS)
            Log.i(
                TAG,
                "scan_started timeout_ms=$SCAN_TIMEOUT_MS target=${targetDeviceName ?: "<null>"}"
            )
        } catch (securityException: SecurityException) {
            scanner = null
            started = false
            Log.e(TAG, "scan start SecurityException", securityException)
        } catch (exception: RuntimeException) {
            scanner = null
            started = false
            Log.e(TAG, "scan start RuntimeException", exception)
        }
    }

    /**
     * 停止诊断扫描。
     *
     * 可重复调用；reason 只用于日志，不影响业务状态。
     */
    fun stop(reason: String) {
        handler.removeCallbacks(stopRunnable)

        val localScanner = scanner
        scanner = null
        val wasStarted = started
        started = false

        if (localScanner != null && wasStarted && hasRequiredPermissions()) {
            try {
                localScanner.stopScan(scanCallback)
            } catch (securityException: SecurityException) {
                Log.e(TAG, "scan stop SecurityException reason=$reason", securityException)
            } catch (exception: RuntimeException) {
                Log.e(TAG, "scan stop RuntimeException reason=$reason", exception)
            }
        }

        if (wasStarted) {
            Log.i(TAG, "scan_stopped reason=$reason unique_states=${lastFingerprints.size}")
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun logResult(source: String, result: ScanResult) {
        val record = result.scanRecord
        val address = try {
            result.device.address ?: "<null>"
        } catch (securityException: SecurityException) {
            "<permission-denied>"
        }
        val name = record?.deviceName
        val serviceUuids = record?.serviceUuids
            ?.joinToString(prefix = "[", postfix = "]") { it.toString() }
            ?: "[]"
        val manufacturerData = sparseArrayToString(record?.manufacturerSpecificData)
        val serviceData = record?.serviceData
            ?.entries
            ?.joinToString(prefix = "{", postfix = "}") {
                "${it.key}=${bytesToHex(it.value)}"
            }
            ?: "{}"
        val raw = bytesToHex(record?.bytes)
        val target = targetDeviceName
        val nameMatch = target != null && target == name

        val fingerprint = listOf(
            name ?: "<null>",
            serviceUuids,
            manufacturerData,
            serviceData,
            raw
        ).joinToString("|")

        val old = lastFingerprints.put(address, fingerprint)
        if (old == fingerprint) {
            return
        }

        Log.i(
            TAG,
            "result source=$source addr=$address rssi=${result.rssi} " +
                "name=${name ?: "<null>"} target=${target ?: "<null>"} " +
                "name_match=$nameMatch uuids=$serviceUuids mfg=$manufacturerData " +
                "service_data=$serviceData raw=$raw"
        )
    }

    private fun sparseArrayToString(data: android.util.SparseArray<ByteArray>?): String {
        if (data == null || data.size() == 0) {
            return "{}"
        }

        return buildString {
            append('{')
            for (index in 0 until data.size()) {
                if (index > 0) {
                    append(',')
                }
                append(data.keyAt(index))
                append('=')
                append(bytesToHex(data.valueAt(index)))
            }
            append('}')
        }
    }

    private fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) {
            return "<empty>"
        }
        return bytes.joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }
}
