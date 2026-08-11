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
 * - 110 版改为 Android `BluetoothLeScanner`；112 版增加 UUID 命中后的同地址连续观测；
 * - 本版进一步取消“必须先命中 UUID 才进入诊断”的门槛：二维码目标名称或目标 UUID 任一证据
 *   都可以建立诊断关联，并统计整个 fallback 窗口的 ScanResult 数量；
 * - 业务连接规则保持不变：只有目标 Provisioning UUID 候选才允许进入连接，不能仅凭名称连接。
 *
 * 生命周期：一次二维码配网 Activity 最多创建/启动一次。`start()` 后最多扫描
 * `FALLBACK_SCAN_TIMEOUT_MS`；`stop()` 可由 Activity pause/destroy/back 主动结束。对象不得跨 Activity
 * 生命周期复用，也不得在失败后递归重启 scanner。
 *
 * 所有权：本对象只持有扫描窗口内的 `BluetoothLeScanner`、`ScanCallback`、少量
 * `BluetoothDevice` 引用、目标地址和统计计数，不缓存全部原始广播包，不持有 PoP、Security2 username、
 * Wi-Fi 密码、Claim/CSR/证书等敏感数据。`ESPDevice` 以及真正的 BLE/GATT/Security2 生命周期仍由
 * ESP Provisioning 库原实现拥有。构造参数 `provisionManager` 仅为保持 108 已有调用接口稳定，
 * 本版不再通过它启动/停止扫描。
 *
 * 并发规则：系统 BLE 回调、主线程超时和 Activity stop 都可能并发进入。候选集合、诊断地址、统计状态、
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
     * - `uuid128Values`：原始 0x06/0x07 字段中成功解析出的 128-bit UUID 文本列表；只读诊断数据。
     * - `targetUuidInRaw`：原始 0x06/0x07 UUID128 字段中是否存在目标 Provisioning UUID。
     * - `malformed`：AD length 超过剩余数据时为 true；解析立即停止，禁止继续越界读取。
     *
     * 生命周期：对象由单次 `parseAdStructures()` 创建，处理完当前 ScanResult 后即可释放。
     * 所有权：列表由本对象私有持有，不引用原始 ByteArray，不跨扫描窗口缓存。
     * 并发规则：对象创建后只读，可在 `candidateLock` 外安全使用。
     */
    private data class AdObservation(
        val rawLength: Int,
        val localNameType: Int?,
        val localName: String?,
        val uuid128Values: List<String>,
        val targetUuidInRaw: Boolean,
        val malformed: Boolean,
    )

    /**
     * 一次 fallback 扫描窗口的端到端诊断统计。
     *
     * 字段含义与范围：
     * - `allResultCount`：scanActive 期间收到且具有 BluetoothDevice 的全部 ScanResult 数量。
     * - `relatedResultCount`：名称/UUID 当前命中，或地址此前已被目标证据锁定的结果数。
     * - `nameMatchCount`：Framework deviceName 或 raw Local Name 精确等于二维码目标名的结果数。
     * - `uuidMatchCount`：Framework serviceUuids 或 raw UUID128 任一命中目标 Provisioning UUID 的结果数。
     * - `rawUuidMatchCount`：其中由 raw 0x06/0x07 明确命中目标 UUID 的结果数。
     * - `nameOnlyResultCount`：当前结果名称匹配而目标 UUID 未匹配的结果数。
     * - `uuidOnlyResultCount`：当前结果目标 UUID 匹配而名称未匹配的结果数。
     * - `nameAndUuidResultCount`：当前结果名称与目标 UUID 同时匹配的结果数。
     * - `nameVisibleCount`：目标相关地址结果中 Framework 名称或 raw Local Name 任一非空的结果数。
     * - `localNameAdCount`：目标相关地址结果中 raw AD 出现 0x08/0x09 Local Name 的结果数。
     * - `scanStartElapsedMs`：本对象开始一次 fallback 的单调时间；-1 表示尚未初始化。
     * - `firstAnyElapsedMs`：首次收到任意有效 ScanResult 的单调时间；-1 表示整个窗口无结果。
     * - `firstRelatedElapsedMs`：首次出现目标相关结果的单调时间；-1 表示未识别目标相关结果。
     * - `firstUuidElapsedMs`：首次目标 UUID 命中的单调时间；-1 表示尚未命中。
     * - `firstNameElapsedMs`：首次二维码目标名称匹配的单调时间；-1 表示尚未匹配。
     * - `firstTargetAddress`：首次由名称或 UUID 建立关联的 Bluetooth 地址；null 表示尚未关联。
     *
     * 所有计数理论范围为 0..Int.MAX_VALUE；单次 6 秒扫描不存在现实溢出风险。
     * 生命周期：对象与 `BleQrServiceUuidFallback` 一一对应，`start()` 时清零，扫描结束后不再更新。
     * 所有权：仅本类持有，不向外暴露引用。
     * 并发规则：所有字段必须在 `candidateLock` 内读写；锁外只能使用结束时复制出的不可变快照值。
     */
    private data class ScanDiagnostics(
        var allResultCount: Int = 0,
        var relatedResultCount: Int = 0,
        var nameMatchCount: Int = 0,
        var uuidMatchCount: Int = 0,
        var rawUuidMatchCount: Int = 0,
        var nameOnlyResultCount: Int = 0,
        var uuidOnlyResultCount: Int = 0,
        var nameAndUuidResultCount: Int = 0,
        var nameVisibleCount: Int = 0,
        var localNameAdCount: Int = 0,
        var scanStartElapsedMs: Long = -1L,
        var firstAnyElapsedMs: Long = -1L,
        var firstRelatedElapsedMs: Long = -1L,
        var firstUuidElapsedMs: Long = -1L,
        var firstNameElapsedMs: Long = -1L,
        var firstTargetAddress: String? = null,
    )

    private val candidateLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 真正允许进入 BLE 连接的业务候选，仅由目标 Provisioning UUID 命中写入。
     * 生命周期仅限单次 fallback；所有读写必须持有 `candidateLock`。
     */
    private val uuidCandidates = LinkedHashMap<String, BluetoothDevice>()

    /**
     * 仅用于诊断关联的目标地址集合。
     *
     * 地址可由“二维码名称匹配”或“目标 UUID 匹配”任一证据加入；加入后，该地址后续即使当前
     * ScanResult 不再携带名称/UUID，也会继续被统计和详细记录。该集合绝不直接决定业务连接。
     * 生命周期不超过单次 6 秒 fallback；所有读写必须持有 `candidateLock`。
     */
    private val observedTargetAddresses = LinkedHashSet<String>()

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
        val startElapsedMs = SystemClock.elapsedRealtime()
        synchronized(candidateLock) {
            if (started) {
                return false
            }
            started = true
            uuidCandidates.clear()
            observedTargetAddresses.clear()
            exactNameCandidate = null
            resetDiagnosticsLocked(startElapsedMs)
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
             * 不设置 ScanFilter：目标是直接观察 Android 返回的原生 ScanResult。业务候选过滤和
             * 诊断关联全部在 handleNativeScanResult() 中完成，避免 Framework 预过滤掩盖证据。
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
     * 与 112 的关键区别：不再要求“目标 UUID 先命中”。二维码目标名称或目标 UUID 任一证据都能
     * 锁定诊断地址；同时所有有效 ScanResult 都会计入 `allResultCount`。业务 `uuidCandidates` 仍只
     * 由目标 UUID 命中写入，因此本函数不会因为名称匹配而放宽实际连接条件。
     */
    private fun handleNativeScanResult(callbackType: Int, result: ScanResult) {
        val device = result.device ?: return
        val scanRecord = result.scanRecord
        val advertisedName = scanRecord?.deviceName
        val address = safeAddress(device)
        val nowMs = SystemClock.elapsedRealtime()
        val rawBytes = scanRecord?.bytes ?: ByteArray(0)
        val adObservation = if (scanRecord != null) {
            parseAdStructures(rawBytes)
        } else {
            AdObservation(
                rawLength = 0,
                localNameType = null,
                localName = null,
                uuid128Values = emptyList(),
                targetUuidInRaw = false,
                malformed = false,
            )
        }

        val frameworkServiceUuids = scanRecord?.serviceUuids
            ?.map { parcelUuid -> parcelUuid.toString() }
            ?: emptyList()
        val frameworkUuidMatch = frameworkServiceUuids.any { uuidText ->
            uuidText.equals(primaryServiceUuid, ignoreCase = true)
        }
        val rawUuidMatch = adObservation.targetUuidInRaw
        val uuidMatched = frameworkUuidMatch || rawUuidMatch
        val nameMatched = advertisedName == targetDeviceName ||
            adObservation.localName == targetDeviceName
        val anyNameVisible = !advertisedName.isNullOrEmpty() ||
            !adObservation.localName.isNullOrEmpty()

        var shouldLogRelated = false
        var relatedResultIndex = 0
        var allResultCount = 0
        var relatedResultCount = 0
        var nameMatchCount = 0
        var uuidMatchCount = 0
        var rawUuidMatchCount = 0
        var nameOnlyResultCount = 0
        var uuidOnlyResultCount = 0
        var nameAndUuidResultCount = 0

        synchronized(candidateLock) {
            if (!scanActive) {
                return
            }

            diagnostics.allResultCount += 1
            if (diagnostics.firstAnyElapsedMs < 0L) {
                diagnostics.firstAnyElapsedMs = nowMs
            }

            if (uuidMatched) {
                uuidCandidates[address] = device
                diagnostics.uuidMatchCount += 1
                if (diagnostics.firstUuidElapsedMs < 0L) {
                    diagnostics.firstUuidElapsedMs = nowMs
                }
            }
            if (rawUuidMatch) {
                diagnostics.rawUuidMatchCount += 1
            }
            if (nameMatched) {
                diagnostics.nameMatchCount += 1
                if (diagnostics.firstNameElapsedMs < 0L) {
                    diagnostics.firstNameElapsedMs = nowMs
                }
            }

            when {
                nameMatched && uuidMatched -> diagnostics.nameAndUuidResultCount += 1
                nameMatched -> diagnostics.nameOnlyResultCount += 1
                uuidMatched -> diagnostics.uuidOnlyResultCount += 1
            }

            if (nameMatched || uuidMatched) {
                observedTargetAddresses.add(address)
                if (diagnostics.firstTargetAddress == null) {
                    diagnostics.firstTargetAddress = address
                }
            }

            val isRelatedAddress = nameMatched || uuidMatched || observedTargetAddresses.contains(address)
            if (!isRelatedAddress) {
                return
            }

            diagnostics.relatedResultCount += 1
            if (diagnostics.firstRelatedElapsedMs < 0L) {
                diagnostics.firstRelatedElapsedMs = nowMs
            }
            if (anyNameVisible) {
                diagnostics.nameVisibleCount += 1
            }
            if (adObservation.localNameType != null) {
                diagnostics.localNameAdCount += 1
            }

            /*
             * 保持 112 的业务选择语义：只有该地址已经是 UUID 候选，并且当前结果的名称精确匹配，
             * 才能成为 exactNameCandidate。名称单独出现只用于诊断，不允许直接进入连接。
             */
            if (uuidCandidates.containsKey(address) && nameMatched) {
                exactNameCandidate = device
            }

            shouldLogRelated = true
            relatedResultIndex = diagnostics.relatedResultCount
            allResultCount = diagnostics.allResultCount
            relatedResultCount = diagnostics.relatedResultCount
            nameMatchCount = diagnostics.nameMatchCount
            uuidMatchCount = diagnostics.uuidMatchCount
            rawUuidMatchCount = diagnostics.rawUuidMatchCount
            nameOnlyResultCount = diagnostics.nameOnlyResultCount
            uuidOnlyResultCount = diagnostics.uuidOnlyResultCount
            nameAndUuidResultCount = diagnostics.nameAndUuidResultCount
        }

        if (!shouldLogRelated) {
            return
        }

        val rawHex = rawBytes.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        val localNameTypeText = when (adObservation.localNameType) {
            AD_TYPE_SHORT_LOCAL_NAME -> "0x08"
            AD_TYPE_COMPLETE_LOCAL_NAME -> "0x09"
            else -> "none"
        }
        val frameworkUuidText = frameworkServiceUuids.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ",",
        )
        val rawUuidText = adObservation.uuid128Values.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ",",
        )

        Log.i(
            DIAG_TAG,
            "related_result index=$relatedResultIndex callback_type=$callbackType address=$address " +
                "rssi=${result.rssi} name=${advertisedName ?: "null"} " +
                "local_name=${adObservation.localName ?: "null"} local_name_ad=$localNameTypeText " +
                "name_match=$nameMatched framework_uuid_match=$frameworkUuidMatch " +
                "raw_uuid_match=$rawUuidMatch uuid_match=$uuidMatched " +
                "service_uuids=$frameworkUuidText raw_uuid128=$rawUuidText " +
                "raw_len=${adObservation.rawLength} malformed=${adObservation.malformed} " +
                "counts=all:$allResultCount,related:$relatedResultCount,name:$nameMatchCount," +
                "uuid:$uuidMatchCount,raw_uuid:$rawUuidMatchCount,name_only:$nameOnlyResultCount," +
                "uuid_only:$uuidOnlyResultCount,both:$nameAndUuidResultCount raw=$rawHex",
        )
    }

    /**
     * 解析 BLE Advertising/Scan Response 的 AD Structure。
     *
     * 每个字段依次由 length、type 和 data 组成，其中 length 包含 type 本身但不包含 length 字节。
     * 遇到 0 长度按标准结束；若声明长度超过剩余字节则标记 malformed 并立即停止，禁止越界。
     */
    private fun parseAdStructures(raw: ByteArray): AdObservation {
        var index = 0
        var localNameType: Int? = null
        var localName: String? = null
        val uuid128Values = ArrayList<String>()
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
                        uuid128Values.add(uuidText)
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
            uuid128Values = uuid128Values.toList(),
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
        val allResultCount: Int
        val relatedResultCount: Int
        val nameMatchCount: Int
        val uuidMatchCount: Int
        val rawUuidMatchCount: Int
        val nameOnlyResultCount: Int
        val uuidOnlyResultCount: Int
        val nameAndUuidResultCount: Int
        val nameVisibleCount: Int
        val localNameAdCount: Int
        val scanStartElapsedMs: Long
        val firstAnyElapsedMs: Long
        val firstRelatedElapsedMs: Long
        val firstUuidElapsedMs: Long
        val firstNameElapsedMs: Long
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

            allResultCount = diagnostics.allResultCount
            relatedResultCount = diagnostics.relatedResultCount
            nameMatchCount = diagnostics.nameMatchCount
            uuidMatchCount = diagnostics.uuidMatchCount
            rawUuidMatchCount = diagnostics.rawUuidMatchCount
            nameOnlyResultCount = diagnostics.nameOnlyResultCount
            uuidOnlyResultCount = diagnostics.uuidOnlyResultCount
            nameAndUuidResultCount = diagnostics.nameAndUuidResultCount
            nameVisibleCount = diagnostics.nameVisibleCount
            localNameAdCount = diagnostics.localNameAdCount
            scanStartElapsedMs = diagnostics.scanStartElapsedMs
            firstAnyElapsedMs = diagnostics.firstAnyElapsedMs
            firstRelatedElapsedMs = diagnostics.firstRelatedElapsedMs
            firstUuidElapsedMs = diagnostics.firstUuidElapsedMs
            firstNameElapsedMs = diagnostics.firstNameElapsedMs
            firstTargetAddress = diagnostics.firstTargetAddress
        }

        mainHandler.removeCallbacks(scanTimeoutRunnable)
        stopNativeScanner(scanner, callback, reason)

        Log.i(
            DIAG_TAG,
            "summary reason=$reason address=${firstTargetAddress ?: "none"} candidates=$candidateCount " +
                "all_results=$allResultCount related_results=$relatedResultCount " +
                "name_matches=$nameMatchCount uuid_matches=$uuidMatchCount raw_uuid_matches=$rawUuidMatchCount " +
                "name_only_results=$nameOnlyResultCount uuid_only_results=$uuidOnlyResultCount " +
                "name_and_uuid_results=$nameAndUuidResultCount name_visible=$nameVisibleCount " +
                "local_name_ad=$localNameAdCount first_any_ms=${elapsedSinceStart(scanStartElapsedMs, firstAnyElapsedMs)} " +
                "first_related_ms=${elapsedSinceStart(scanStartElapsedMs, firstRelatedElapsedMs)} " +
                "first_uuid_ms=${elapsedSinceStart(scanStartElapsedMs, firstUuidElapsedMs)} " +
                "first_name_ms=${elapsedSinceStart(scanStartElapsedMs, firstNameElapsedMs)}",
        )

        when {
            selected != null -> {
                val diagnosticMessage = if (nameMatchCount > 0) {
                    "BLE诊断：已收到二维码设备名和 Provisioning UUID，正在连接设备"
                } else if (nameVisibleCount > 0 || localNameAdCount > 0) {
                    "BLE诊断：已收到 Provisioning UUID，并看到该地址的广播名称，正在连接设备"
                } else {
                    "BLE诊断：已收到 Provisioning UUID，但扫描窗口内未看到设备名，正在按 UUID 连接"
                }
                Log.i(
                    TAG,
                    "native_selected exact_name=$exactMatch candidate_count=$candidateCount reason=$reason " +
                        "all_results=$allResultCount related_results=$relatedResultCount " +
                        "name_matches=$nameMatchCount uuid_matches=$uuidMatchCount",
                )
                onDiagnostic(diagnosticMessage)
                onSelected(selected, primaryServiceUuid)
            }

            candidateCount == 0 && allResultCount == 0 -> {
                Log.e(TAG, "native_no_scan_results reason=$reason")
                onFailed(
                    "no_scan_results",
                    IllegalStateException(
                        "BLE兼容扫描失败：6秒内未收到任何BLE扫描结果",
                    ),
                )
            }

            candidateCount == 0 && nameMatchCount > 0 -> {
                Log.e(
                    TAG,
                    "native_name_seen_uuid_missing reason=$reason name_matches=$nameMatchCount " +
                        "all_results=$allResultCount",
                )
                onFailed(
                    "name_seen_uuid_missing",
                    IllegalStateException(
                        "BLE兼容扫描已检测到二维码设备名 $targetDeviceName，但扫描结果未暴露预期的 Provisioning Service UUID",
                    ),
                )
            }

            candidateCount == 0 -> {
                Log.e(
                    TAG,
                    "native_target_not_recognized reason=$reason all_results=$allResultCount " +
                        "related_results=$relatedResultCount",
                )
                onFailed(
                    "target_not_recognized",
                    IllegalStateException(
                        "BLE兼容扫描收到 $allResultCount 条结果，但未识别到二维码设备名或预期 Provisioning Service UUID",
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

    /**
     * `start()` 时在锁内一次性清零本轮统计。参数使用单调时钟，禁止传入 wall-clock 时间。
     */
    private fun resetDiagnosticsLocked(startElapsedMs: Long) {
        diagnostics.allResultCount = 0
        diagnostics.relatedResultCount = 0
        diagnostics.nameMatchCount = 0
        diagnostics.uuidMatchCount = 0
        diagnostics.rawUuidMatchCount = 0
        diagnostics.nameOnlyResultCount = 0
        diagnostics.uuidOnlyResultCount = 0
        diagnostics.nameAndUuidResultCount = 0
        diagnostics.nameVisibleCount = 0
        diagnostics.localNameAdCount = 0
        diagnostics.scanStartElapsedMs = startElapsedMs
        diagnostics.firstAnyElapsedMs = -1L
        diagnostics.firstRelatedElapsedMs = -1L
        diagnostics.firstUuidElapsedMs = -1L
        diagnostics.firstNameElapsedMs = -1L
        diagnostics.firstTargetAddress = null
    }

    /** 将单调时间转换为相对本轮扫描起点的毫秒；无有效时间时返回 `none`。 */
    private fun elapsedSinceStart(startElapsedMs: Long, eventElapsedMs: Long): String {
        if (startElapsedMs < 0L || eventElapsedMs < startElapsedMs) {
            return "none"
        }
        return (eventElapsedMs - startElapsedMs).toString()
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
