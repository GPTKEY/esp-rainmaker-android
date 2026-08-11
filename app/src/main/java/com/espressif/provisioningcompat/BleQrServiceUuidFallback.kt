package com.espressif.provisioningcompat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.espressif.provisioning.ESPProvisionManager
import java.nio.charset.StandardCharsets

/**
 * RainMaker 二维码 BLE 发现失败后的单次 Android 原生 Service UUID 兼容回退。
 *
 * 使用目的：
 * - 正常二维码扫描仍完全由 ESP Provisioning Android 库负责；
 * - 只有库已经返回 `device not found` 后，调用方才创建并启动本对象；
 * - 108 版曾继续调用 `ESPProvisionManager.searchBleEspDevices()`，但该 API 内部 BleScanner
 *   会先过滤掉 `ScanRecord.deviceName` 为空的结果，无法真正验证“UUID 可见但名称不可见”；
 * - 110 版改为 Android `BluetoothLeScanner`，本版继续在此基础上追踪同一目标地址后续
 *   ScanResult，并解析原始 AD Structure，用于判断 Scan Response 中 Local Name 是否真正进入 Android；
 * - 若设备名精确匹配二维码目标名则优先选中；若名称不可见但只有一个 UUID 候选，也允许安全
 *   回退连接；若现场存在多个相同 UUID 且无法用名称消歧，则拒绝猜测设备。
 *
 * 生命周期：一次二维码配网 Activity 最多创建/启动一次。`start()` 后最多扫描
 * `FALLBACK_SCAN_TIMEOUT_MS`；`stop()` 可由 Activity pause/destroy/back 主动结束。对象不得跨 Activity
 * 生命周期复用，也不得在失败后递归重启 scanner。
 *
 * 所有权：本对象只持有扫描窗口内的 `BluetoothLeScanner`、`ScanCallback`、少量
 * `BluetoothDevice` 引用和统计计数，不缓存全部原始广播包，不持有 PoP、Security2 username、
 * Wi-Fi 密码、Claim/CSR/证书等敏感数据。`ESPDevice` 以及真正的 BLE/GATT/Security2 生命周期仍由
 * ESP Provisioning 库原实现拥有。构造参数 `provisionManager` 仅为保持 108 已有调用接口稳定，
 * 本版不再通过它启动/停止扫描。
 *
 * 并发规则：系统 BLE 回调、主线程超时和 Activity stop 都可能并发进入。候选集合、统计状态、
 * started/scanActive、scanner/callback 引用均在 `candidateLock` 的短 synchronized 区域内读写；锁内
 * 禁止调用 UI、GATT、`startScan()` 或 `stopScan()`。所有外部 `onSelected/onDiagnostic/onFailed`
 * 回调均在锁外执行。
 */
@Suppress("DEPRECATION", "UNUSED_PARAMETER")
class BleQrServiceUuidFallback(
    provisionManager: ESPProvisionManager,
    private val targetDeviceName: String,
    private val primaryServiceUuid: String,
    private val onSelected: (BluetoothDevice, String) -> Unit,
    private val onDiagnostic: (String) -> Unit = {},
    private val onFailed: (String, Exception?) -> Unit,
) {

    companion object {
        private const val TAG = "BLE_QR_FALLBACK"
        private const val DIAG_TAG = "BLE_SCAN_E2E"
        private const val FALLBACK_SCAN_TIMEOUT_MS = 6000L

        private const val AD_TYPE_SHORT_LOCAL_NAME = 0x08
        private const val AD_TYPE_COMPLETE_LOCAL_NAME = 0x09
        private const val AD_TYPE_INCOMPLETE_UUID128 = 0x06
        private const val AD_TYPE_COMPLETE_UUID128 = 0x07
    }

    /**
     * 单个 ScanRecord 的 AD Structure 解析摘要。
     *
     * 字段含义与取值：
     * - `rawLength`：Android 返回的 `ScanRecord.bytes` 长度，非负整数；仅用于诊断长度，不作为协议判断。
     * - `localNameType`：0x08/0x09 表示发现 Shortened/Complete Local Name；null 表示当前记录没有名称 AD。
     * - `localName`：当前记录中解析到的广播名称；生命周期仅限本次扫描窗口，不跨 Activity 保存。
     * - `targetUuidInRaw`：原始 0x06/0x07 UUID128 字段中是否存在目标 Provisioning UUID。
     * - `malformed`：AD length 超过剩余数据时为 true；解析立即停止，禁止继续越界读取。
     *
     * 所有权/并发：对象由单次 `parseAdStructures()` 创建后只读，不共享可变数组，可在锁外安全使用。
     */
    private data class AdObservation(
        val rawLength: Int,
        val localNameType: Int?,
        val localName: String?,
        val targetUuidInRaw: Boolean,
        val malformed: Boolean,
    )

    /**
     * 一次 fallback 扫描窗口的目标设备统计。
     *
     * 字段含义与范围：
     * - `targetResultCount`：首次锁定目标 UUID 后，目标地址相关 ScanResult 总数，范围 0..Int.MAX_VALUE。
     * - `uuidMatchCount`：明确含目标 Service UUID 的结果数，范围 0..targetResultCount。
     * - `nameVisibleCount`：`ScanRecord.deviceName` 非空的目标结果数。
     * - `localNameAdCount`：原始 AD 中出现 0x08/0x09 Local Name 的目标结果数。
     * - `firstUuidElapsedMs`：首次 UUID 命中的 `SystemClock.elapsedRealtime()`；-1 表示尚未命中。
     * - `firstNameElapsedMs`：首次 deviceName 或 Local Name AD 可见时间；-1 表示整个窗口未见名称。
     * - `firstTargetAddress`：首次 UUID 命中的 Bluetooth 地址，仅用于本轮日志关联；null 表示未命中。
     *
     * 生命周期：对象与 `BleQrServiceUuidFallback` 一一对应，`start()` 时清零，扫描结束后不再更新。
     * 所有权：仅本类持有，不向外暴露引用。
     * 并发规则：所有字段必须在 `candidateLock` 内读写；锁外只能使用结束时复制出的不可变快照值。
     */
    private data class ScanDiagnostics(
        var targetResultCount: Int = 0,
        var uuidMatchCount: Int = 0,
        var nameVisibleCount: Int = 0,
        var localNameAdCount: Int = 0,
        var firstUuidElapsedMs: Long = -1L,
        var firstNameElapsedMs: Long = -1L,
        var firstTargetAddress: String? = null,
    )

    private val candidateLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uuidCandidates = LinkedHashMap<String, BluetoothDevice>()
    private val diagnostics = ScanDiagnostics()

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
            diagnostics.targetResultCount = 0
            diagnostics.uuidMatchCount = 0
            diagnostics.nameVisibleCount = 0
            diagnostics.localNameAdCount = 0
            diagnostics.firstUuidElapsedMs = -1L
            diagnostics.firstNameElapsedMs = -1L
            diagnostics.firstTargetAddress = null
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
                handleNativeScanResult(callbackType, result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (result in results) {
                    handleNativeScanResult(-1, result)
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
     * 首次 UUID 命中后把 Bluetooth 地址加入目标候选；此后即使某条结果本身不再携带 UUID，
     * 只要地址相同仍会继续解析和统计，用于捕获后续独立/合并到来的 Scan Response 名称数据。
     */
    private fun handleNativeScanResult(callbackType: Int, result: ScanResult) {
        val scanRecord = result.scanRecord ?: return
        val device = result.device ?: return
        val advertisedName = scanRecord.deviceName
        val address = safeAddress(device)
        val nowMs = SystemClock.elapsedRealtime()
        val adObservation = parseAdStructures(scanRecord.bytes)

        val parsedUuidMatch = scanRecord.serviceUuids
            ?.any { parcelUuid ->
                parcelUuid.toString().equals(primaryServiceUuid, ignoreCase = true)
            }
            ?: false
        val uuidMatched = parsedUuidMatch || adObservation.targetUuidInRaw

        var shouldLogTarget = false
        var targetResultIndex = 0
        var uuidMatchCount = 0
        var nameVisibleCount = 0
        var localNameAdCount = 0

        synchronized(candidateLock) {
            if (!scanActive) {
                return
            }

            if (uuidMatched) {
                uuidCandidates[address] = device
                diagnostics.uuidMatchCount += 1
                if (diagnostics.firstUuidElapsedMs < 0L) {
                    diagnostics.firstUuidElapsedMs = nowMs
                    diagnostics.firstTargetAddress = address
                }
            }

            val isTargetAddress = uuidMatched || uuidCandidates.containsKey(address)
            if (!isTargetAddress) {
                return
            }

            diagnostics.targetResultCount += 1
            if (!advertisedName.isNullOrEmpty()) {
                diagnostics.nameVisibleCount += 1
            }
            if (adObservation.localNameType != null) {
                diagnostics.localNameAdCount += 1
            }
            if (diagnostics.firstNameElapsedMs < 0L &&
                (!advertisedName.isNullOrEmpty() || adObservation.localNameType != null)
            ) {
                diagnostics.firstNameElapsedMs = nowMs
            }

            if ((!advertisedName.isNullOrEmpty() && advertisedName == targetDeviceName) ||
                (!adObservation.localName.isNullOrEmpty() && adObservation.localName == targetDeviceName)
            ) {
                exactNameCandidate = device
            }

            shouldLogTarget = true
            targetResultIndex = diagnostics.targetResultCount
            uuidMatchCount = diagnostics.uuidMatchCount
            nameVisibleCount = diagnostics.nameVisibleCount
            localNameAdCount = diagnostics.localNameAdCount
        }

        if (!shouldLogTarget) {
            return
        }

        val rawHex = scanRecord.bytes.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        val localNameTypeText = when (adObservation.localNameType) {
            AD_TYPE_SHORT_LOCAL_NAME -> "0x08"
            AD_TYPE_COMPLETE_LOCAL_NAME -> "0x09"
            else -> "none"
        }

        Log.i(
            DIAG_TAG,
            "target_result index=$targetResultIndex callback_type=$callbackType address=$address " +
                "rssi=${result.rssi} uuid_match=$uuidMatched name_visible=${!advertisedName.isNullOrEmpty()} " +
                "name_match=${advertisedName == targetDeviceName || adObservation.localName == targetDeviceName} " +
                "local_name_ad=$localNameTypeText local_name=${adObservation.localName ?: "null"} " +
                "raw_len=${adObservation.rawLength} malformed=${adObservation.malformed} " +
                "counts=uuid:$uuidMatchCount,name:$nameVisibleCount,local_ad:$localNameAdCount raw=$rawHex",
        )
    }

    /**
     * 解析 BLE Advertising/Scan Response 的 AD Structure。
     *
     * 每个字段格式为 `[length][type][data...]`，其中 length 包含 type 本身但不包含 length 字节。
     * 遇到 0 长度按标准结束；若声明长度超过剩余字节则标记 malformed 并立即停止，禁止越界。
     */
    private fun parseAdStructures(raw: ByteArray): AdObservation {
        var index = 0
        var localNameType: Int? = null
        var localName: String? = null
        var targetUuidInRaw = false
        var malformed = false

        while (index < raw.size) {
            val fieldLength = raw[index].toInt() and 0xff
            if (fieldLength == 0) {
                break
            }

            val fieldEndExclusive = index + 1 + fieldLength
            if (fieldLength < 1 || fieldEndExclusive > raw.size) {
                malformed = true
                break
            }

            val type = raw[index + 1].toInt() and 0xff
            val dataStart = index + 2
            val dataEndExclusive = fieldEndExclusive

            when (type) {
                AD_TYPE_SHORT_LOCAL_NAME,
                AD_TYPE_COMPLETE_LOCAL_NAME,
                -> {
                    if (dataEndExclusive > dataStart) {
                        val decoded = String(
                            raw,
                            dataStart,
                            dataEndExclusive - dataStart,
                            StandardCharsets.UTF_8,
                        ).trimEnd('\u0000')
                        localNameType = type
                        localName = decoded.ifEmpty { null }
                    }
                }

                AD_TYPE_INCOMPLETE_UUID128,
                AD_TYPE_COMPLETE_UUID128,
                -> {
                    var uuidOffset = dataStart
                    while (uuidOffset + 16 <= dataEndExclusive) {
                        val uuidText = uuid128LittleEndianToString(raw, uuidOffset)
                        if (uuidText.equals(primaryServiceUuid, ignoreCase = true)) {
                            targetUuidInRaw = true
                        }
                        uuidOffset += 16
                    }
                }
            }

            index = fieldEndExclusive
        }

        return AdObservation(
            rawLength = raw.size,
            localNameType = localNameType,
            localName = localName,
            targetUuidInRaw = targetUuidInRaw,
            malformed = malformed,
        )
    }

    /**
     * BLE 128-bit UUID 在 AD 数据中按 little-endian 字节序发送；这里反转 16 字节并格式化成
     * Android `ParcelUuid.toString()` 使用的 canonical 8-4-4-4-12 形式，仅用于诊断匹配。
     */
    private fun uuid128LittleEndianToString(raw: ByteArray, offset: Int): String {
        val canonical = ByteArray(16)
        for (i in 0 until 16) {
            canonical[i] = raw[offset + 15 - i]
        }
        val hex = canonical.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        return buildString(36) {
            append(hex, 0, 8)
            append('-')
            append(hex, 8, 12)
            append('-')
            append(hex, 12, 16)
            append('-')
            append(hex, 16, 20)
            append('-')
            append(hex, 20, 32)
        }
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
     * 超时后只在锁内领取候选和统计快照，在锁外停止 scanner 并执行连接/诊断/失败回调。
     */
    private fun finishScanAndSelect(reason: String) {
        val scanner: BluetoothLeScanner?
        val callback: ScanCallback?
        val selected: BluetoothDevice?
        val candidateCount: Int
        val exactMatch: Boolean
        val targetResultCount: Int
        val uuidMatchCount: Int
        val nameVisibleCount: Int
        val localNameAdCount: Int
        val nameAfterUuidMs: Long?
        val firstTargetAddress: String?

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

            targetResultCount = diagnostics.targetResultCount
            uuidMatchCount = diagnostics.uuidMatchCount
            nameVisibleCount = diagnostics.nameVisibleCount
            localNameAdCount = diagnostics.localNameAdCount
            firstTargetAddress = diagnostics.firstTargetAddress
            nameAfterUuidMs = if (
                diagnostics.firstUuidElapsedMs >= 0L && diagnostics.firstNameElapsedMs >= 0L
            ) {
                diagnostics.firstNameElapsedMs - diagnostics.firstUuidElapsedMs
            } else {
                null
            }
        }

        mainHandler.removeCallbacks(scanTimeoutRunnable)
        stopNativeScanner(scanner, callback, reason)

        Log.i(
            DIAG_TAG,
            "summary reason=$reason address=${firstTargetAddress ?: "none"} candidates=$candidateCount " +
                "target_results=$targetResultCount uuid_matches=$uuidMatchCount " +
                "name_visible=$nameVisibleCount local_name_ad=$localNameAdCount " +
                "name_after_uuid_ms=${nameAfterUuidMs?.toString() ?: "none"}",
        )

        when {
            selected != null -> {
                val diagnosticMessage = if (localNameAdCount > 0 || nameVisibleCount > 0) {
                    "BLE诊断：已收到 Provisioning UUID 和设备名，正在连接设备"
                } else {
                    "BLE诊断：已收到 Provisioning UUID，但扫描窗口内未收到设备名 Scan Response，正在按 UUID 连接"
                }
                Log.i(
                    TAG,
                    "native_selected exact_name=$exactMatch candidate_count=$candidateCount reason=$reason " +
                        "local_name_ad=$localNameAdCount name_visible=$nameVisibleCount",
                )
                onDiagnostic(diagnosticMessage)
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

    private fun safeAddress(device: BluetoothDevice): String {
        return try {
            device.address ?: "unknown-${System.identityHashCode(device)}"
        } catch (_: SecurityException) {
            "permission-denied-${System.identityHashCode(device)}"
        }
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
