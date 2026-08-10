package com.espressif.provisioningcompat

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.util.Log
import com.espressif.provisioning.ESPProvisionManager
import com.espressif.provisioning.listeners.BleScanListener

/**
 * RainMaker 二维码 BLE 发现失败后的单次 Service UUID 兼容回退。
 *
 * 使用目的：
 * - 正常二维码扫描仍完全由 ESP Provisioning Android 库负责；
 * - 只有库已经返回 `device not found` 后，调用方才创建并启动本对象；
 * - 本对象使用库公开的无名称过滤 BLE 扫描接口，只接受固定 Provisioning Primary Service UUID；
 * - 若 Android 没有把 Scan Response 中的设备名合并到 `ScanRecord.deviceName`，仍可在 UUID 唯一时
 *   找到目标设备；若现场存在多个同 UUID 且又没有二维码目标名可消歧，则安全失败，绝不猜测连接。
 *
 * 生命周期：一次二维码配网 Activity 最多创建/启动一次。`start()` 后由 ESPProvisionManager 的
 * BleScanner 决定扫描时长；`stop()` 可由 Activity pause/destroy/back 主动结束。对象不得跨 Activity
 * 生命周期复用。
 *
 * 所有权：本对象只持有扫描期间的 BluetoothDevice 引用和地址键，不持有 PoP、Security2 username、
 * Wi-Fi 密码、Claim/CSR/证书等敏感数据。ESPDevice 以及真正的 BLE/GATT/Security2 生命周期仍由
 * ESPProvisionManager/ESPDevice 原实现拥有。
 *
 * 并发规则：BLE 回调可能来自非 UI 线程，候选集合和状态都在 `candidateLock` 的短 synchronized 区域
 * 内读写；锁内不调用 UI、不连接 GATT、不启动/停止扫描。选中/失败回调在锁外执行，由调用方自行
 * 切换到 UI 线程。
 */
class BleQrServiceUuidFallback(
    private val provisionManager: ESPProvisionManager,
    private val targetDeviceName: String,
    private val primaryServiceUuid: String,
    private val onSelected: (BluetoothDevice, String) -> Unit,
    private val onFailed: (String, Exception?) -> Unit,
) {

    companion object {
        private const val TAG = "BLE_QR_FALLBACK"
    }

    private val candidateLock = Any()
    private val uuidCandidates = LinkedHashMap<String, BluetoothDevice>()
    private var exactNameCandidate: BluetoothDevice? = null
    private var started = false
    private var scanActive = false

    /**
     * 启动一次无名称过滤的 BLE 扫描。
     *
     * @return `true` 表示本次成功发起扫描；`false` 表示对象已启动过，调用方不得再次重试。
     */
    fun start(): Boolean {
        synchronized(candidateLock) {
            if (started) {
                return false
            }
            started = true
            scanActive = true
            uuidCandidates.clear()
            exactNameCandidate = null
        }

        Log.w(
            TAG,
            "start target=$targetDeviceName service_uuid=$primaryServiceUuid",
        )

        try {
            provisionManager.searchBleEspDevices(object : BleScanListener {
                override fun scanStartFailed() {
                    markScanInactive()
                    Log.e(TAG, "scan_start_failed")
                    onFailed("scan_start_failed", null)
                }

                override fun onPeripheralFound(device: BluetoothDevice, scanResult: ScanResult) {
                    val scanRecord = scanResult.scanRecord ?: return
                    val matchedUuid = scanRecord.serviceUuids
                        ?.firstOrNull { parcelUuid ->
                            parcelUuid.toString().equals(primaryServiceUuid, ignoreCase = true)
                        }
                        ?.toString()
                        ?: return

                    val address = try {
                        device.address ?: "unknown"
                    } catch (_: SecurityException) {
                        "permission-denied"
                    }
                    val advertisedName = scanRecord.deviceName

                    synchronized(candidateLock) {
                        uuidCandidates[address] = device
                        if (!advertisedName.isNullOrEmpty() && advertisedName == targetDeviceName) {
                            exactNameCandidate = device
                        }
                    }

                    Log.i(
                        TAG,
                        "candidate address=$address rssi=${scanResult.rssi} " +
                            "name_visible=${!advertisedName.isNullOrEmpty()} " +
                            "name_match=${advertisedName == targetDeviceName} uuid=$matchedUuid",
                    )
                }

                override fun scanCompleted() {
                    val selected: BluetoothDevice?
                    val candidateCount: Int
                    val exactMatch: Boolean

                    synchronized(candidateLock) {
                        scanActive = false
                        candidateCount = uuidCandidates.size
                        exactMatch = exactNameCandidate != null
                        selected = exactNameCandidate
                            ?: if (candidateCount == 1) uuidCandidates.values.first() else null
                    }

                    when {
                        selected != null -> {
                            Log.i(
                                TAG,
                                "selected exact_name=$exactMatch candidate_count=$candidateCount",
                            )
                            onSelected(selected, primaryServiceUuid)
                        }

                        candidateCount == 0 -> {
                            Log.e(TAG, "no_service_uuid_match")
                            onFailed("no_service_uuid_match", null)
                        }

                        else -> {
                            Log.e(TAG, "ambiguous candidate_count=$candidateCount")
                            onFailed("ambiguous", null)
                        }
                    }
                }

                override fun onFailure(e: Exception) {
                    markScanInactive()
                    Log.e(
                        TAG,
                        "scan_failed exception=${e.javaClass.simpleName} message=${e.message}",
                    )
                    onFailed("scan_failed", e)
                }
            })
        } catch (e: Exception) {
            markScanInactive()
            Log.e(
                TAG,
                "scan_start_exception exception=${e.javaClass.simpleName} message=${e.message}",
            )
            onFailed("scan_start_exception", e)
        }
        return true
    }

    /** Activity 离开时停止仍在运行的 fallback scan；重复调用幂等。 */
    fun stop(reason: String) {
        val needStop = synchronized(candidateLock) {
            if (!scanActive) {
                false
            } else {
                scanActive = false
                true
            }
        }
        if (!needStop) {
            return
        }

        Log.i(TAG, "stop reason=$reason")
        try {
            provisionManager.stopBleScan()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "stop_failed exception=${e.javaClass.simpleName} message=${e.message}",
            )
        }
    }

    private fun markScanInactive() {
        synchronized(candidateLock) {
            scanActive = false
        }
    }
}
