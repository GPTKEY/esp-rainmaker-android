package com.espressif.provisioningcompat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.espressif.provisioning.ESPProvisionManager

/**
 * RainMaker 二维码 BLE 发现失败后的单次 Android 原生 Service UUID 兼容回退。
 *
 * 使用目的：
 * - 正常二维码扫描仍完全由 ESP Provisioning Android 库负责；
 * - 只有库已经返回 `device not found` 后，调用方才创建并启动本对象；
 * - 108 版曾继续调用 `ESPProvisionManager.searchBleEspDevices()`，但该 API 内部 BleScanner
 *   会先过滤掉 `ScanRecord.deviceName` 为空的结果，无法真正验证“UUID 可见但名称不可见”；
 * - 本版直接使用 Android `BluetoothLeScanner` 接收原生 `ScanResult`，设备名允许为空，只接受
 *   固定 Provisioning Primary Service UUID；
 * - 若设备名精确匹配二维码目标名则优先选中；若名称不可见但只有一个 UUID 候选，也允许安全
 *   回退连接；若现场存在多个相同 UUID 且无法用名称消歧，则拒绝猜测设备。
 *
 * 生命周期：一次二维码配网 Activity 最多创建/启动一次。`start()` 后最多扫描
 * `FALLBACK_SCAN_TIMEOUT_MS`；`stop()` 可由 Activity pause/destroy/back 主动结束。对象不得跨 Activity
 * 生命周期复用，也不得在失败后递归重启 scanner。
 *
 * 所有权：本对象只持有扫描窗口内的 `BluetoothLeScanner`、`ScanCallback` 和少量
 * `BluetoothDevice` 引用，不持有 PoP、Security2 username、Wi-Fi 密码、Claim/CSR/证书等敏感数据。
 * `ESPDevice` 以及真正的 BLE/GATT/Security2 生命周期仍由 ESP Provisioning 库原实现拥有。
 * 构造参数 `provisionManager` 仅为保持 108 已有调用接口稳定，本版不再通过它启动/停止扫描。
 *
 * 并发规则：系统 BLE 回调、主线程超时和 Activity stop 都可能并发进入。候选集合、started、
 * scanActive、scanner/callback 引用均在 `candidateLock` 的短 synchronized 区域内读写；锁内禁止
 * 调用 UI、GATT、`startScan()` 或 `stopScan()`。所有外部 `onSelected/onFailed` 回调均在锁外执行。
 */
@Suppress("DEPRECATION", "UNUSED_PARAMETER")
class BleQrServiceUuidFallback(
    provisionManager: ESPProvisionManager,
    private val targetDeviceName: String,
    private val primaryServiceUuid: String,
    private val onSelected: (BluetoothDevice, String) -> Unit,
    private val onFailed: (String, Exception?) -> Unit,
) {

    companion object {
        private const val TAG = "BLE_QR_FALLBACK"
        private const val FALLBACK_SCAN_TIMEOUT_MS = 6000L
    }

    private val candidateLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uuidCandidates = LinkedHashMap<String, BluetoothDevice>()

    private var exactNameCandidate: BluetoothDevice? = null
    private var started = false
    private var scanActive = false
    private var nativeScanner: BluetoothLeScanner? = null
    private var nativeScanCallback: ScanCallback? = null

    private val scanTimeoutRunnable = Runnable {
        finishScanAndSelect("timeout")
    }

    /**
     * 启动一次真正不依赖设备名的 Android 原生 BLE 扫描。
     *
     * @return `true` 表示本次 fallback 已经被消费（包括 scanner 无法启动时的明确失败）；
     * `false` 仅表示同一对象已经启动过，调用方不得再次重试。
     */
    fun start(): Boolean {
        synchronized(candidateLock) {
            if (started) {
                return false
            }
            started = true
            uuidCandidates.clear()
            exactNameCandidate = null
        }

        Log.w(
            TAG,
            "native_start target=$targetDeviceName service_uuid=$primaryServiceUuid timeout_ms=$FALLBACK_SCAN_TIMEOUT_MS",
        )

        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (e: SecurityException) {
            reportStartFailure(
                "scan_permission_denied",
                "BLE兼容扫描无法启动：缺少蓝牙扫描权限",
                e,
            )
            return true
        }

        if (adapter == null) {
            reportStartFailure(
                "bluetooth_unavailable",
                "BLE兼容扫描无法启动：手机不支持蓝牙或蓝牙适配器不可用",
                null,
            )
            return true
        }

        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (e: SecurityException) {
            reportStartFailure(
                "scan_permission_denied",
                "BLE兼容扫描无法启动：缺少蓝牙扫描权限",
                e,
            )
            return true
        }

        if (scanner == null) {
            reportStartFailure(
                "scanner_unavailable",
                "BLE兼容扫描无法启动：请确认手机蓝牙已经开启",
                null,
            )
            return true
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleNativeScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (result in results) {
                    handleNativeScanResult(result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                handleNativeScanFailed(errorCode)
            }
        }

        synchronized(candidateLock) {
            nativeScanner = scanner
            nativeScanCallback = callback
            scanActive = true
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return try {
            /*
             * 不设置 ScanFilter：目标是直接观察 Android 返回的原生 ScanResult，尤其允许
             * `deviceName == null`。业务候选过滤全部在 handleNativeScanResult() 中完成。
             */
            scanner.startScan(emptyList(), settings, callback)
            mainHandler.postDelayed(scanTimeoutRunnable, FALLBACK_SCAN_TIMEOUT_MS)
            true
        } catch (e: SecurityException) {
            clearNativeScanState()
            reportStartFailure(
                "scan_permission_denied",
                "BLE兼容扫描无法启动：缺少蓝牙扫描权限",
                e,
            )
            true
        } catch (e: Exception) {
            clearNativeScanState()
            reportStartFailure(
                "scan_start_exception",
                "BLE兼容扫描启动失败：${e.javaClass.simpleName}",
                e,
            )
            true
        }
    }

    /**
     * 消费一个未经 Espressif BleScanner 名称门槛过滤的原生 ScanResult。
     *
     * 只保存目标 Provisioning UUID 候选。即使 `deviceName` 为空，只要 UUID 匹配仍会进入候选表。
     */
    private fun handleNativeScanResult(result: ScanResult) {
        val scanRecord = result.scanRecord ?: return
        val matchedUuid = scanRecord.serviceUuids
            ?.firstOrNull { parcelUuid ->
                parcelUuid.toString().equals(primaryServiceUuid, ignoreCase = true)
            }
            ?.toString()
            ?: return

        val device = result.device ?: return
        val advertisedName = scanRecord.deviceName
        val address = try {
            device.address ?: "unknown"
        } catch (_: SecurityException) {
            "permission-denied"
        }

        synchronized(candidateLock) {
            if (!scanActive) {
                return
            }
            uuidCandidates[address] = device
            if (!advertisedName.isNullOrEmpty() && advertisedName == targetDeviceName) {
                exactNameCandidate = device
            }
        }

        Log.i(
            TAG,
            "native_candidate address=$address rssi=${result.rssi} " +
                "name_visible=${!advertisedName.isNullOrEmpty()} " +
                "name_match=${advertisedName == targetDeviceName} uuid=$matchedUuid",
        )
    }

    /** 系统 BLE scanner 启动失败；错误码直接转成用户可见诊断，不进行自动重试。 */
    private fun handleNativeScanFailed(errorCode: Int) {
        val wasActive = synchronized(candidateLock) {
            if (!scanActive) {
                false
            } else {
                scanActive = false
                nativeScanner = null
                nativeScanCallback = null
                true
            }
        }
        if (!wasActive) {
            return
        }

        mainHandler.removeCallbacks(scanTimeoutRunnable)
        val description = scanFailureDescription(errorCode)
        Log.e(TAG, "native_scan_failed code=$errorCode reason=$description")
        onFailed(
            "native_scan_failed_$errorCode",
            IllegalStateException("BLE兼容扫描启动失败：$description"),
        )
    }

    /**
     * 超时后只在锁内领取候选快照，在锁外停止 scanner 并执行连接/失败回调。
     */
    private fun finishScanAndSelect(reason: String) {
        val scanner: BluetoothLeScanner?
        val callback: ScanCallback?
        val selected: BluetoothDevice?
        val candidateCount: Int
        val exactMatch: Boolean

        synchronized(candidateLock) {
            if (!scanActive) {
                return
            }

            scanActive = false
            scanner = nativeScanner
            callback = nativeScanCallback
            nativeScanner = null
            nativeScanCallback = null

            candidateCount = uuidCandidates.size
            exactMatch = exactNameCandidate != null
            selected = exactNameCandidate
                ?: if (candidateCount == 1) uuidCandidates.values.first() else null
        }

        mainHandler.removeCallbacks(scanTimeoutRunnable)
        stopNativeScanner(scanner, callback, reason)

        when {
            selected != null -> {
                Log.i(
                    TAG,
                    "native_selected exact_name=$exactMatch candidate_count=$candidateCount reason=$reason",
                )
                onSelected(selected, primaryServiceUuid)
            }

            candidateCount == 0 -> {
                Log.e(TAG, "native_no_service_uuid_match reason=$reason")
                onFailed(
                    "no_service_uuid_match",
                    IllegalStateException(
                        "BLE兼容扫描失败：手机未检测到设备的 Provisioning Service UUID",
                    ),
                )
            }

            else -> {
                Log.e(TAG, "native_ambiguous candidate_count=$candidateCount reason=$reason")
                onFailed(
                    "ambiguous",
                    IllegalStateException(
                        "BLE兼容扫描失败：检测到多个 Provisioning 候选，无法安全确认目标设备",
                    ),
                )
            }
        }
    }

    /** Activity 离开时停止仍在运行的 native fallback scan；重复调用幂等且不触发失败 UI。 */
    fun stop(reason: String) {
        val scanner: BluetoothLeScanner?
        val callback: ScanCallback?

        synchronized(candidateLock) {
            if (!scanActive) {
                return
            }
            scanActive = false
            scanner = nativeScanner
            callback = nativeScanCallback
            nativeScanner = null
            nativeScanCallback = null
        }

        mainHandler.removeCallbacks(scanTimeoutRunnable)
        stopNativeScanner(scanner, callback, reason)
    }

    private fun stopNativeScanner(
        scanner: BluetoothLeScanner?,
        callback: ScanCallback?,
        reason: String,
    ) {
        if (scanner == null || callback == null) {
            return
        }

        Log.i(TAG, "native_stop reason=$reason")
        try {
            scanner.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "native_stop_permission_denied message=${e.message}")
        } catch (e: Exception) {
            Log.w(
                TAG,
                "native_stop_failed exception=${e.javaClass.simpleName} message=${e.message}",
            )
        }
    }

    /** startScan() 抛异常后清理本对象持有的 scanner 状态，不调用 stopScan()。 */
    private fun clearNativeScanState() {
        synchronized(candidateLock) {
            scanActive = false
            nativeScanner = null
            nativeScanCallback = null
        }
        mainHandler.removeCallbacks(scanTimeoutRunnable)
    }

    private fun reportStartFailure(
        reason: String,
        userMessage: String,
        cause: Exception?,
    ) {
        synchronized(candidateLock) {
            scanActive = false
            nativeScanner = null
            nativeScanCallback = null
        }
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        Log.e(
            TAG,
            "native_start_failed reason=$reason exception=${cause?.javaClass?.simpleName} message=${cause?.message}",
        )
        onFailed(reason, IllegalStateException(userMessage, cause))
    }

    private fun scanFailureDescription(errorCode: Int): String {
        return when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "BLE扫描已经启动"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "BLE扫描器注册失败"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Android BLE扫描内部错误"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "手机不支持所需BLE扫描功能"
            5 -> "Android BLE扫描硬件资源不足"
            6 -> "Android限制了过于频繁的BLE扫描"
            else -> "Android BLE扫描失败（错误码 $errorCode）"
        }
    }
}
