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
 * RainMaker 二维码 BLE 发现失败后的 Android 原生扫描兼容回退。
 *
 * 使用目的：
 * - 正常二维码扫描仍由 ESP Provisioning Android 库负责；
 * - 只有官方路径已经返回 `device not found` 后，调用方才创建并启动本对象；
 * - 同时观测 Framework Service UUID、raw UUID、ScanRecord 名称、raw Local Name 和
 *   `BluetoothDevice.name`，避免只依赖某一种 Android BLE 名称暴露方式；
 * - UUID 候选仍为最高优先级；若 UUID 未暴露，但扫描窗口内只有一个与二维码完整名称精确匹配的
 *   设备，则允许用二维码已知的 Primary Service UUID 尝试 GATT 连接；真正的 Provisioning Service、
 *   Security2、PoP 等仍由 Espressif Provisioning 库在后续连接阶段验证；
 * - 多候选时拒绝猜测设备。
 *
 * 生命周期：一次对象只允许 `start()` 一次。官方 scanner 结束后先等待
 * `FALLBACK_SCAN_START_DELAY_MS`，再启动一次 native scan；扫描硬超时为
 * `FALLBACK_SCAN_TIMEOUT_MS`。`stop()` 可在 Activity pause/destroy/back 时幂等终止，并可取消尚未执行
 * 的延迟启动。对象不得跨 Activity 生命周期复用，也不得自动递归重试。
 *
 * 所有权：本对象仅持有当前扫描窗口内的 scanner/callback、少量 BluetoothDevice 引用、地址集合和
 * 统计计数，不缓存所有周边广播，不持有 PoP、Wi-Fi 密码、Security2 verifier、Claim/CSR/证书/token。
 * `ESPDevice`、BLETransport、GATT 和 Security2 生命周期仍归 Espressif Provisioning 库所有。
 * 构造参数 `provisionManager` 仅为保持既有调用接口兼容，本类不通过它启动扫描。
 *
 * 并发规则：BLE callback、主线程延迟启动/超时和 Activity stop 可能并发进入。所有可变候选、统计、
 * scanner 状态都必须在 `candidateLock` 的短 synchronized 区域内访问；锁内禁止 UI、GATT、
 * `startScan()`、`stopScan()` 和外部回调。
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

        /*
         * 官方 Espressif scanner 停止后不立即复用 BluetoothLeScanner。该延迟仅作为兼容性措施，
         * 不是对当前根因的假定；通过 Handler 实现，不阻塞 UI 线程。
         */
        private const val FALLBACK_SCAN_START_DELAY_MS = 800L
        private const val FALLBACK_SCAN_TIMEOUT_MS = 10_000L

        private const val AD_TYPE_SHORT_LOCAL_NAME = 0x08
        private const val AD_TYPE_COMPLETE_LOCAL_NAME = 0x09
        private const val AD_TYPE_INCOMPLETE_UUID128 = 0x06
        private const val AD_TYPE_COMPLETE_UUID128 = 0x07
    }

    /**
     * 单个 ScanRecord 的只读 AD Structure 解析摘要。
     *
     * 字段含义：
     * - `rawLength`：raw bytes 长度，范围 >= 0；
     * - `localNameType`：0x08/0x09，null 表示没有 Local Name AD；
     * - `localName`：raw Local Name，生命周期仅限本次 callback；
     * - `uuid128Values`：完整 16 字节块解析出的 UUID 文本，只用于诊断；
     * - `targetUuidInRaw`：raw 0x06/0x07 是否命中目标 Provisioning UUID；
     * - `malformed`：字段声明长度越过剩余数据时为 true，并立即停止解析。
     *
     * 所有权/并发：由 `parseAdStructures()` 每次创建，不引用原始可变数组；创建后只读，可在锁外使用。
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
     * 单次 fallback 扫描窗口的诊断统计。
     *
     * 字段含义与范围：
     * - 所有 `*Count` 均为 0..Int.MAX_VALUE；10 秒窗口不存在现实溢出风险；
     * - `allResultCount`：具有 BluetoothDevice 的全部 callback 数；
     * - `relatedResultCount`：当前或历史地址已由名称/UUID关联的 callback 数；
     * - `nameMatchCount`：三路名称任一精确匹配二维码完整名称的 callback 数；
     * - `deviceNameMatchCount`：其中 `BluetoothDevice.name` 精确匹配的 callback 数；
     * - `uuidMatchCount`：Framework/raw UUID 任一命中的 callback 数；
     * - `rawUuidMatchCount`：raw UUID 明确命中的 callback 数；
     * - `nameOnlyResultCount/uuidOnlyResultCount/nameAndUuidResultCount`：当前 callback 分类；
     * - `nameVisibleCount`：三路名称任一非空的目标相关 callback 数；
     * - `localNameAdCount`：raw 0x08/0x09 出现次数；
     * - `scanStartElapsedMs`：真正调用 startScan 前的单调时间；
     * - `firstAny/Related/Uuid/NameElapsedMs`：对应首个事件单调时间，-1 表示尚未发生；
     * - `firstTargetAddress`：首次名称或 UUID 建立关联的地址，仅用于诊断。
     *
     * 生命周期：对象与本 fallback 一一对应，真正开始 native scan 前清零，结束后不再更新。
     * 所有权：仅本类持有，不向外暴露可变引用。
     * 并发规则：所有字段只允许持有 `candidateLock` 时读写。
     */
    private data class ScanDiagnostics(
        var allResultCount: Int = 0,
        var relatedResultCount: Int = 0,
        var nameMatchCount: Int = 0,
        var deviceNameMatchCount: Int = 0,
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
     * UUID 业务候选表。
     *
     * key 为 Bluetooth 地址，value 为扫描得到的 BluetoothDevice。只有目标 Provisioning UUID 命中时
     * 才写入；生命周期仅限本次 fallback；所有访问必须持有 `candidateLock`。
     */
    private val uuidCandidates = LinkedHashMap<String, BluetoothDevice>()

    /**
     * 二维码完整名称精确匹配候选表。
     *
     * 名称来源允许为 `ScanRecord.deviceName`、raw Local Name 或 `BluetoothDevice.name`。本表只在完整
     * 名称与二维码目标完全相等时写入，不接受仅 `PROV_` 前缀匹配。若 UUID 候选为空且本表最终只有
     * 一个地址，A2-2 才允许尝试 GATT；若出现多个同名地址则拒绝猜测。
     * 生命周期仅限本次 fallback；所有访问必须持有 `candidateLock`。
     */
    private val nameCandidates = LinkedHashMap<String, BluetoothDevice>()

    /**
     * 诊断关联地址集合。名称或 UUID 任一证据可加入；后续同地址结果即使当前没有名称/UUID，也继续
     * 统计和打印。集合不直接授予连接权限，生命周期不超过本次 fallback。
     */
    private val observedTargetAddresses = LinkedHashSet<String>()

    private val diagnostics = ScanDiagnostics()

    private var started = false
    private var scanActive = false
    private var scanStarted = false
    private var nativeScanner: BluetoothLeScanner? = null
    private var nativeScanCallback: ScanCallback? = null

    private val scanStartRunnable = Runnable {
        beginNativeScan()
    }

    private val scanTimeoutRunnable = Runnable {
        finishScanAndSelect("timeout")
    }

    /**
     * 消费本对象的一次性启动机会。
     *
     * @return true 表示本次 fallback 已被消费；false 仅表示同一个对象已经启动过。
     */
    fun start(): Boolean {
        synchronized(candidateLock) {
            if (started) {
                return false
            }
            started = true
            uuidCandidates.clear()
            nameCandidates.clear()
            observedTargetAddresses.clear()
            resetDiagnosticsLocked()
        }

        Log.w(
            TAG,
            "native_prepare target=$targetDeviceName service_uuid=$primaryServiceUuid " +
                "start_delay_ms=$FALLBACK_SCAN_START_DELAY_MS timeout_ms=$FALLBACK_SCAN_TIMEOUT_MS",
        )

        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (e: SecurityException) {
            reportStartFailure("scan_permission_denied", "BLE扫描失败：缺少蓝牙权限", e)
            return true
        }

        if (adapter == null) {
            reportStartFailure("bluetooth_unavailable", "BLE扫描失败：蓝牙不可用", null)
            return true
        }

        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (e: SecurityException) {
            reportStartFailure("scan_permission_denied", "BLE扫描失败：缺少蓝牙权限", e)
            return true
        }

        if (scanner == null) {
            reportStartFailure("scanner_unavailable", "BLE扫描失败：请先开启手机蓝牙", null)
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
            scanStarted = false
        }

        mainHandler.postDelayed(scanStartRunnable, FALLBACK_SCAN_START_DELAY_MS)
        return true
    }

    /** 延迟结束后真正调用 Android scanner；外部调用和锁操作均严格分离。 */
    private fun beginNativeScan() {
        val scanner: BluetoothLeScanner
        val callback: ScanCallback

        synchronized(candidateLock) {
            if (!scanActive || scanStarted) {
                return
            }
            scanner = nativeScanner ?: return
            callback = nativeScanCallback ?: return
            scanStarted = true
            resetDiagnosticsLocked()
            diagnostics.scanStartElapsedMs = SystemClock.elapsedRealtime()
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        try {
            /*
             * 仍不使用硬件 ScanFilter：当前需要同时兼容 UUID 不暴露、ScanRecord 名称不暴露、但
             * BluetoothDevice.name 可见等设备差异。目标筛选在 callback 内完成，避免过滤器提前丢证据。
             */
            scanner.startScan(emptyList(), settings, callback)
            mainHandler.postDelayed(scanTimeoutRunnable, FALLBACK_SCAN_TIMEOUT_MS)
            Log.i(TAG, "native_start target=$targetDeviceName")
        } catch (e: SecurityException) {
            clearNativeScanState()
            reportStartFailure("scan_permission_denied", "BLE扫描失败：缺少蓝牙权限", e)
        } catch (e: Exception) {
            clearNativeScanState()
            reportStartFailure(
                "scan_start_exception",
                "BLE扫描启动失败：${e.javaClass.simpleName}",
                e,
            )
        }
    }

    /**
     * 处理一个未经过 Espressif 名称门槛过滤的原生 ScanResult。
     *
     * 名称匹配采用三路来源：Framework ScanRecord、raw AD、BluetoothDevice.name；三者只要有一个与
     * 二维码完整名称精确一致，就记录 `nameCandidates`。UUID 匹配独立记录到 `uuidCandidates`。
     */
    private fun handleNativeScanResult(callbackType: Int, result: ScanResult) {
        val device = result.device ?: return
        val scanRecord = result.scanRecord
        val frameworkName = scanRecord?.deviceName
        val cachedDeviceName = safeDeviceName(device)
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

        val frameworkNameMatch = frameworkName == targetDeviceName
        val rawNameMatch = adObservation.localName == targetDeviceName
        val cachedDeviceNameMatch = cachedDeviceName == targetDeviceName
        val nameMatched = frameworkNameMatch || rawNameMatch || cachedDeviceNameMatch
        val anyNameVisible = !frameworkName.isNullOrEmpty() ||
            !adObservation.localName.isNullOrEmpty() ||
            !cachedDeviceName.isNullOrEmpty()

        var shouldLogRelated = false
        var relatedResultIndex = 0
        var allResultCount = 0
        var relatedResultCount = 0
        var nameMatchCount = 0
        var deviceNameMatchCount = 0
        var uuidMatchCount = 0
        var rawUuidMatchCount = 0
        var nameOnlyResultCount = 0
        var uuidOnlyResultCount = 0
        var bothResultCount = 0

        synchronized(candidateLock) {
            if (!scanActive || !scanStarted) {
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
                nameCandidates[address] = device
                diagnostics.nameMatchCount += 1
                if (cachedDeviceNameMatch) {
                    diagnostics.deviceNameMatchCount += 1
                }
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

            val isRelated = nameMatched || uuidMatched || observedTargetAddresses.contains(address)
            if (!isRelated) {
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

            shouldLogRelated = true
            relatedResultIndex = diagnostics.relatedResultCount
            allResultCount = diagnostics.allResultCount
            relatedResultCount = diagnostics.relatedResultCount
            nameMatchCount = diagnostics.nameMatchCount
            deviceNameMatchCount = diagnostics.deviceNameMatchCount
            uuidMatchCount = diagnostics.uuidMatchCount
            rawUuidMatchCount = diagnostics.rawUuidMatchCount
            nameOnlyResultCount = diagnostics.nameOnlyResultCount
            uuidOnlyResultCount = diagnostics.uuidOnlyResultCount
            bothResultCount = diagnostics.nameAndUuidResultCount
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
                "rssi=${result.rssi} scan_name=${frameworkName ?: "null"} " +
                "device_name=${cachedDeviceName ?: "null"} raw_name=${adObservation.localName ?: "null"} " +
                "local_name_ad=$localNameTypeText name_match=$nameMatched " +
                "scan_name_match=$frameworkNameMatch device_name_match=$cachedDeviceNameMatch " +
                "raw_name_match=$rawNameMatch framework_uuid_match=$frameworkUuidMatch " +
                "raw_uuid_match=$rawUuidMatch uuid_match=$uuidMatched " +
                "service_uuids=$frameworkUuidText raw_uuid128=$rawUuidText " +
                "raw_len=${adObservation.rawLength} malformed=${adObservation.malformed} " +
                "counts=all:$allResultCount,related:$relatedResultCount,name:$nameMatchCount," +
                "device_name:$deviceNameMatchCount,uuid:$uuidMatchCount,raw_uuid:$rawUuidMatchCount," +
                "name_only:$nameOnlyResultCount,uuid_only:$uuidOnlyResultCount,both:$bothResultCount raw=$rawHex",
        )
    }

    /**
     * 解析 BLE Advertising/Scan Response 的 AD Structure。
     *
     * 每个字段为 length、type、data；length 包含 type 但不包含自身。遇到 0 长度结束；若声明长度
     * 超过剩余数据则标记 malformed 并立即停止，禁止越界。
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

    /** BLE 128-bit UUID 在 AD 中按 little-endian 字节序发送；仅用于诊断字符串匹配。 */
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

    /** 系统 scanner 异步失败；领取状态后在锁外报告，不自动重试。 */
    private fun handleNativeScanFailed(errorCode: Int) {
        val wasActive = synchronized(candidateLock) {
            if (!scanActive) {
                false
            } else {
                scanActive = false
                scanStarted = false
                nativeScanner = null
                nativeScanCallback = null
                true
            }
        }
        if (!wasActive) {
            return
        }

        mainHandler.removeCallbacks(scanStartRunnable)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        val description = scanFailureDescription(errorCode)
        Log.e(TAG, "native_scan_failed code=$errorCode reason=$description")
        onFailed(
            "native_scan_failed_$errorCode",
            IllegalStateException("BLE扫描失败：$description"),
        )
    }

    /**
     * 扫描结束时在锁内一次性领取候选与统计快照，锁外停止 scanner、打印汇总并调用连接/失败回调。
     */
    private fun finishScanAndSelect(reason: String) {
        val scanner: BluetoothLeScanner?
        val callback: ScanCallback?
        val scannerWasStarted: Boolean
        val selected: BluetoothDevice?
        val selectedSource: String?
        val uuidCandidateCount: Int
        val nameCandidateCount: Int
        val allResultCount: Int
        val relatedResultCount: Int
        val nameMatchCount: Int
        val deviceNameMatchCount: Int
        val uuidMatchCount: Int
        val rawUuidMatchCount: Int
        val nameOnlyResultCount: Int
        val uuidOnlyResultCount: Int
        val bothResultCount: Int
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
            scannerWasStarted = scanStarted
            scanStarted = false
            scanner = nativeScanner
            callback = nativeScanCallback
            nativeScanner = null
            nativeScanCallback = null

            uuidCandidateCount = uuidCandidates.size
            nameCandidateCount = nameCandidates.size

            val intersectionAddresses = nameCandidates.keys.filter { address ->
                uuidCandidates.containsKey(address)
            }

            when {
                intersectionAddresses.size == 1 -> {
                    selected = uuidCandidates[intersectionAddresses.first()]
                    selectedSource = "name_and_uuid"
                }

                uuidCandidateCount == 1 -> {
                    selected = uuidCandidates.values.first()
                    selectedSource = "uuid_only"
                }

                uuidCandidateCount == 0 && nameCandidateCount == 1 -> {
                    selected = nameCandidates.values.first()
                    selectedSource = "name_only"
                }

                else -> {
                    selected = null
                    selectedSource = null
                }
            }

            allResultCount = diagnostics.allResultCount
            relatedResultCount = diagnostics.relatedResultCount
            nameMatchCount = diagnostics.nameMatchCount
            deviceNameMatchCount = diagnostics.deviceNameMatchCount
            uuidMatchCount = diagnostics.uuidMatchCount
            rawUuidMatchCount = diagnostics.rawUuidMatchCount
            nameOnlyResultCount = diagnostics.nameOnlyResultCount
            uuidOnlyResultCount = diagnostics.uuidOnlyResultCount
            bothResultCount = diagnostics.nameAndUuidResultCount
            nameVisibleCount = diagnostics.nameVisibleCount
            localNameAdCount = diagnostics.localNameAdCount
            scanStartElapsedMs = diagnostics.scanStartElapsedMs
            firstAnyElapsedMs = diagnostics.firstAnyElapsedMs
            firstRelatedElapsedMs = diagnostics.firstRelatedElapsedMs
            firstUuidElapsedMs = diagnostics.firstUuidElapsedMs
            firstNameElapsedMs = diagnostics.firstNameElapsedMs
            firstTargetAddress = diagnostics.firstTargetAddress
        }

        mainHandler.removeCallbacks(scanStartRunnable)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        if (scannerWasStarted) {
            stopNativeScanner(scanner, callback, reason)
        }

        Log.i(
            DIAG_TAG,
            "summary reason=$reason address=${firstTargetAddress ?: "none"} " +
                "uuid_candidates=$uuidCandidateCount name_candidates=$nameCandidateCount " +
                "all_results=$allResultCount related_results=$relatedResultCount " +
                "name_matches=$nameMatchCount device_name_matches=$deviceNameMatchCount " +
                "uuid_matches=$uuidMatchCount raw_uuid_matches=$rawUuidMatchCount " +
                "name_only_results=$nameOnlyResultCount uuid_only_results=$uuidOnlyResultCount " +
                "name_and_uuid_results=$bothResultCount name_visible=$nameVisibleCount " +
                "local_name_ad=$localNameAdCount first_any_ms=${elapsedSinceStart(scanStartElapsedMs, firstAnyElapsedMs)} " +
                "first_related_ms=${elapsedSinceStart(scanStartElapsedMs, firstRelatedElapsedMs)} " +
                "first_uuid_ms=${elapsedSinceStart(scanStartElapsedMs, firstUuidElapsedMs)} " +
                "first_name_ms=${elapsedSinceStart(scanStartElapsedMs, firstNameElapsedMs)}",
        )

        if (selected != null && selectedSource != null) {
            val diagnosticMessage = when (selectedSource) {
                "name_only" -> "BLE诊断：二维码设备名唯一匹配，正在尝试 GATT 连接"
                "name_and_uuid" -> "BLE诊断：设备名和 Provisioning UUID 已匹配，正在连接"
                else -> "BLE诊断：Provisioning UUID 唯一匹配，正在连接"
            }
            Log.i(
                TAG,
                "native_selected source=$selectedSource uuid_candidates=$uuidCandidateCount " +
                    "name_candidates=$nameCandidateCount all_results=$allResultCount",
            )
            onDiagnostic(diagnosticMessage)
            onSelected(selected, primaryServiceUuid)
            return
        }

        when {
            allResultCount == 0 -> {
                Log.e(TAG, "native_no_scan_results reason=$reason")
                onFailed(
                    "no_scan_results",
                    IllegalStateException("BLE扫描失败：10秒内没有收到扫描结果"),
                )
            }

            uuidCandidateCount > 1 || nameCandidateCount > 1 -> {
                Log.e(
                    TAG,
                    "native_ambiguous uuid_candidates=$uuidCandidateCount name_candidates=$nameCandidateCount",
                )
                onFailed(
                    "ambiguous",
                    IllegalStateException("BLE扫描失败：发现多个候选设备，请关闭附近其他配网设备后重试"),
                )
            }

            else -> {
                Log.e(
                    TAG,
                    "native_target_not_recognized reason=$reason all_results=$allResultCount",
                )
                onFailed(
                    "target_not_recognized",
                    IllegalStateException(
                        "BLE扫描失败：未发现 $targetDeviceName（收到${allResultCount}条其他广播）",
                    ),
                )
            }
        }
    }

    /** Activity 离开时停止扫描；可取消尚未执行的延迟启动，重复调用幂等。 */
    fun stop(reason: String) {
        val scanner: BluetoothLeScanner?
        val callback: ScanCallback?
        val scannerWasStarted: Boolean

        synchronized(candidateLock) {
            if (!scanActive) {
                return
            }
            scanActive = false
            scannerWasStarted = scanStarted
            scanStarted = false
            scanner = nativeScanner
            callback = nativeScanCallback
            nativeScanner = null
            nativeScanCallback = null
        }

        mainHandler.removeCallbacks(scanStartRunnable)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        if (scannerWasStarted) {
            stopNativeScanner(scanner, callback, reason)
        }
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
            Log.w(TAG, "native_stop_failed exception=${e.javaClass.simpleName} message=${e.message}")
        }
    }

    /** startScan() 抛异常后清理状态；不调用 stopScan()。 */
    private fun clearNativeScanState() {
        synchronized(candidateLock) {
            scanActive = false
            scanStarted = false
            nativeScanner = null
            nativeScanCallback = null
        }
        mainHandler.removeCallbacks(scanStartRunnable)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
    }

    private fun reportStartFailure(
        reason: String,
        userMessage: String,
        cause: Exception?,
    ) {
        synchronized(candidateLock) {
            scanActive = false
            scanStarted = false
            nativeScanner = null
            nativeScanCallback = null
        }
        mainHandler.removeCallbacks(scanStartRunnable)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        Log.e(
            TAG,
            "native_start_failed reason=$reason exception=${cause?.javaClass?.simpleName} message=${cause?.message}",
        )
        onFailed(reason, IllegalStateException(userMessage, cause))
    }

    /** 真正开始 native scan 前清零一次统计；调用方必须持有 `candidateLock`。 */
    private fun resetDiagnosticsLocked() {
        diagnostics.allResultCount = 0
        diagnostics.relatedResultCount = 0
        diagnostics.nameMatchCount = 0
        diagnostics.deviceNameMatchCount = 0
        diagnostics.uuidMatchCount = 0
        diagnostics.rawUuidMatchCount = 0
        diagnostics.nameOnlyResultCount = 0
        diagnostics.uuidOnlyResultCount = 0
        diagnostics.nameAndUuidResultCount = 0
        diagnostics.nameVisibleCount = 0
        diagnostics.localNameAdCount = 0
        diagnostics.scanStartElapsedMs = -1L
        diagnostics.firstAnyElapsedMs = -1L
        diagnostics.firstRelatedElapsedMs = -1L
        diagnostics.firstUuidElapsedMs = -1L
        diagnostics.firstNameElapsedMs = -1L
        diagnostics.firstTargetAddress = null
    }

    /** 将单调时间转换为相对扫描起点毫秒；无有效事件返回 `none`。 */
    private fun elapsedSinceStart(startElapsedMs: Long, eventElapsedMs: Long): String {
        if (startElapsedMs < 0L || eventElapsedMs < startElapsedMs) {
            return "none"
        }
        return (eventElapsedMs - startElapsedMs).toString()
    }

    /** 读取 Bluetooth 地址；权限异常时返回稳定的本对象诊断占位，不抛出 callback。 */
    private fun safeAddress(device: BluetoothDevice): String {
        return try {
            device.address ?: "unknown-${System.identityHashCode(device)}"
        } catch (_: SecurityException) {
            "permission-denied-${System.identityHashCode(device)}"
        }
    }

    /**
     * 读取系统 BluetoothDevice 名称。
     *
     * Android 12+ 可能要求 BLUETOOTH_CONNECT；权限缺失时只返回 null，不影响 ScanRecord/raw AD 路径。
     */
    private fun safeDeviceName(device: BluetoothDevice): String? {
        return try {
            device.name
        } catch (_: SecurityException) {
            null
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
