package com.espressif.ui.activities

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espressif.AppConstants
import com.espressif.EspApplication
import com.espressif.NetworkApiManager
import com.espressif.cloudapi.ApiResponseListener
import com.espressif.rainmaker.BuildConfig
import com.espressif.ui.models.Device
import com.espressif.ui.models.EspNode
import com.espressif.ui.models.Param
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 主控制器专用仪表盘。
 *
 * 数据只来自 uart_echo 的稳定 RainMaker contract v2：
 * - Host Controller
 * - Liquid Monitor
 *
 * 不修改 RainMaker 协议，不复制一套云控制逻辑。写参数仍统一走 NetworkApiManager，
 * 因而继续复用原 App 的 Cloud / Local / BLE Local Control 路由。
 */
class MainControllerDashboardActivity : AppCompatActivity() {

    companion object {
        const val HOST_DEVICE_NAME = "Host Controller"
        const val LIQUID_DEVICE_NAME = "Liquid Monitor"

        const val PARAM_LEVEL_PERCENT = "LevelPercent"
        const val PARAM_LEVEL_VALID = "LevelValid"
        const val PARAM_SOURCE_BOUND = "SourceBound"
        const val PARAM_SOURCE_ONLINE = "SourceOnline"
        const val PARAM_SOURCE_ADDRESS = "SourceAddress"
        const val PARAM_TEMPERATURE = "Temperature"
        const val PARAM_TEMPERATURE_VALID = "TemperatureValid"
        const val PARAM_BATTERY_VOLTAGE = "BatteryVoltage"
        const val PARAM_BATTERY_VALID = "BatteryValid"
        const val PARAM_THRESHOLDS_VALID = "ThresholdsValid"
        const val PARAM_LOW_THRESHOLD = "LowThreshold"
        const val PARAM_HIGH_THRESHOLD = "HighThreshold"

        const val PARAM_OUTPUT_STATE = "OutputState"
        const val PARAM_WORK_MODE = "WorkMode"
        const val PARAM_LORA_AVAILABLE = "LoRaAvailable"
        const val PARAM_NODE_COUNT = "NodeCount"
        const val PARAM_ONLINE_NODE_COUNT = "OnlineNodeCount"
        const val PARAM_WIFI_RSSI = "WiFiRSSI"
        const val PARAM_CLOUD_ONLINE = "CloudOnline"
        const val PARAM_OFFLINE_MODE = "OfflineMode"
        const val PARAM_REMOTE_CONTROL_ENABLED = "RemoteControlEnabled"

        const val WORK_MODE_FILL = 1
        const val WORK_MODE_DRAIN = 2
        const val WORK_MODE_TIMER = 3
        const val WORK_MODE_MANUAL = 4

        /**
         * 只在同一个 Node 中同时存在稳定的 Host/Liquid 两个 Device 时启用专用页面，
         * 避免名称相似的普通 RainMaker 设备被误识别。
         */
        @JvmStatic
        fun isMainControllerDevice(device: Device?, node: EspNode?): Boolean {
            if (device == null || node == null) return false
            val tappedName = device.deviceName
            if (tappedName != HOST_DEVICE_NAME && tappedName != LIQUID_DEVICE_NAME) return false
            val names = node.devices?.mapNotNull { it?.deviceName }?.toSet().orEmpty()
            return names.contains(HOST_DEVICE_NAME) && names.contains(LIQUID_DEVICE_NAME)
        }
    }

    private lateinit var espApp: EspApplication
    private lateinit var networkApiManager: NetworkApiManager
    private lateinit var nodeId: String
    private var originalDevice: Device? = null
    private var hostDevice: Device? = null
    private var liquidDevice: Device? = null

    private var state by mutableStateOf(MainControllerState())
    private val handler = Handler(Looper.getMainLooper())
    private var refreshInFlight = false

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshFromNode(showError = false)
            handler.postDelayed(this, 5000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        espApp = applicationContext as EspApplication
        networkApiManager = NetworkApiManager(applicationContext)
        originalDevice = intent.getParcelableExtra(AppConstants.KEY_ESP_DEVICE)

        val initial = originalDevice
        if (initial == null || initial.nodeId.isNullOrBlank()) {
            finish()
            return
        }
        nodeId = initial.nodeId
        resolveDevices()
        rebuildState()

        setContentView(ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    MainControllerDashboard(
                        state = state,
                        onBack = { finish() },
                        onRefresh = { refreshFromNode(showError = true) },
                        onOutputChanged = { updateOutput(it) },
                        onWorkModeChanged = { updateWorkMode(it) },
                        onThresholdsChanged = { low, high -> updateThresholds(low, high) },
                        onAdvancedParams = { openAdvancedParams() },
                        onDeviceInfo = { openDeviceInfo() },
                        onNetworkConfig = { openDeviceInfo() },
                        onLogs = { startActivity(Intent(this@MainControllerDashboardActivity, ProvisioningLogActivity::class.java)) },
                        onScenes = { Toast.makeText(this@MainControllerDashboardActivity, "场景仍由 RainMaker 主页面统一管理", Toast.LENGTH_SHORT).show() },
                        onSchedules = { Toast.makeText(this@MainControllerDashboardActivity, "定时仍由 RainMaker 主页面统一管理", Toast.LENGTH_SHORT).show() },
                    )
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshTask)
        refreshFromNode(showError = false)
        handler.postDelayed(refreshTask, 5000L)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun resolveDevices() {
        val node = espApp.nodeMap[nodeId]
        val devices = node?.devices.orEmpty()
        hostDevice = devices.firstOrNull { it.deviceName == HOST_DEVICE_NAME }
        liquidDevice = devices.firstOrNull { it.deviceName == LIQUID_DEVICE_NAME }
        if (hostDevice == null && originalDevice?.deviceName == HOST_DEVICE_NAME) hostDevice = originalDevice
        if (liquidDevice == null && originalDevice?.deviceName == LIQUID_DEVICE_NAME) liquidDevice = originalDevice
    }

    private fun refreshFromNode(showError: Boolean) {
        if (refreshInFlight) return
        refreshInFlight = true
        networkApiManager.getParamsValues(nodeId, object : ApiResponseListener {
            override fun onSuccess(data: Bundle?) {
                refreshInFlight = false
                runOnUiThread {
                    resolveDevices()
                    rebuildState()
                }
            }

            override fun onResponseFailure(exception: Exception) {
                refreshInFlight = false
                if (showError) runOnUiThread {
                    Toast.makeText(this@MainControllerDashboardActivity, "刷新失败：${exception.message ?: "服务器返回错误"}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onNetworkFailure(exception: Exception) {
                refreshInFlight = false
                if (showError) runOnUiThread {
                    Toast.makeText(this@MainControllerDashboardActivity, "刷新失败：网络不可用", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun rebuildState() {
        val node = espApp.nodeMap[nodeId]
        state = MainControllerState(
            title = hostDevice?.userVisibleName?.takeIf { !it.isNullOrBlank() }
                ?: node?.nodeName?.takeIf { !it.isNullOrBlank() }
                ?: "主控制器",
            nodeOnline = node?.isOnline == true,
            levelPercent = liquidDevice.intParam(PARAM_LEVEL_PERCENT, 0).coerceIn(0, 100),
            levelValid = liquidDevice.boolParam(PARAM_LEVEL_VALID, false),
            sourceBound = liquidDevice.boolParam(PARAM_SOURCE_BOUND, false),
            sourceOnline = liquidDevice.boolParam(PARAM_SOURCE_ONLINE, false),
            sourceAddress = liquidDevice.intParam(PARAM_SOURCE_ADDRESS, 0),
            temperature = liquidDevice.floatParam(PARAM_TEMPERATURE),
            temperatureValid = liquidDevice.boolParam(PARAM_TEMPERATURE_VALID, false),
            batteryVoltage = liquidDevice.floatParam(PARAM_BATTERY_VOLTAGE),
            batteryValid = liquidDevice.boolParam(PARAM_BATTERY_VALID, false),
            thresholdsValid = liquidDevice.boolParam(PARAM_THRESHOLDS_VALID, false),
            lowThreshold = liquidDevice.intParam(PARAM_LOW_THRESHOLD, 20).coerceIn(0, 99),
            highThreshold = liquidDevice.intParam(PARAM_HIGH_THRESHOLD, 90).coerceIn(1, 100),
            outputState = hostDevice.boolParam(PARAM_OUTPUT_STATE, false),
            workMode = hostDevice.intParam(PARAM_WORK_MODE, WORK_MODE_MANUAL).coerceIn(WORK_MODE_FILL, WORK_MODE_MANUAL),
            loraAvailable = hostDevice.boolParam(PARAM_LORA_AVAILABLE, false),
            nodeCount = hostDevice.intParam(PARAM_NODE_COUNT, 0).coerceAtLeast(0),
            onlineNodeCount = hostDevice.intParam(PARAM_ONLINE_NODE_COUNT, 0).coerceAtLeast(0),
            wifiRssi = hostDevice.intParam(PARAM_WIFI_RSSI, -127),
            cloudOnline = hostDevice.boolParam(PARAM_CLOUD_ONLINE, false),
            offlineMode = hostDevice.boolParam(PARAM_OFFLINE_MODE, false),
            remoteControlEnabled = hostDevice.boolParam(PARAM_REMOTE_CONTROL_ENABLED, false),
            lastUpdatedMs = System.currentTimeMillis(),
        )
    }

    private fun updateOutput(enabled: Boolean) {
        val previous = state.outputState
        state = state.copy(outputState = enabled)
        writeParams(hostDevice, mapOf(PARAM_OUTPUT_STATE to enabled)) {
            if (!it) state = state.copy(outputState = previous)
        }
    }

    private fun updateWorkMode(mode: Int) {
        if (mode !in WORK_MODE_FILL..WORK_MODE_MANUAL) return
        val previous = state.workMode
        state = state.copy(workMode = mode)
        writeParams(hostDevice, mapOf(PARAM_WORK_MODE to mode)) {
            if (!it) state = state.copy(workMode = previous)
        }
    }

    private fun updateThresholds(low: Int, high: Int) {
        if (low !in 0..99 || high !in 1..100 || low >= high) {
            Toast.makeText(this, "阈值无效：低阈值必须小于高阈值", Toast.LENGTH_SHORT).show()
            return
        }
        val previousLow = state.lowThreshold
        val previousHigh = state.highThreshold
        state = state.copy(lowThreshold = low, highThreshold = high, thresholdsValid = true)
        writeParams(liquidDevice, mapOf(PARAM_LOW_THRESHOLD to low, PARAM_HIGH_THRESHOLD to high)) {
            if (!it) state = state.copy(lowThreshold = previousLow, highThreshold = previousHigh)
        }
    }

    private fun writeParams(device: Device?, values: Map<String, Any>, result: (Boolean) -> Unit) {
        if (device == null) {
            result(false)
            Toast.makeText(this, "设备模型尚未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val params = JsonObject()
        values.forEach { (name, value) ->
            when (value) {
                is Boolean -> params.addProperty(name, value)
                is Int -> params.addProperty(name, value)
                is Long -> params.addProperty(name, value)
                is Float -> params.addProperty(name, value)
                is Double -> params.addProperty(name, value)
                is String -> params.addProperty(name, value)
            }
        }
        val body = JsonObject().apply { add(device.deviceName, params) }
        networkApiManager.updateParamValue(nodeId, body, object : ApiResponseListener {
            override fun onSuccess(data: Bundle?) {
                runOnUiThread {
                    result(true)
                    refreshFromNode(showError = false)
                }
            }

            override fun onResponseFailure(exception: Exception) {
                runOnUiThread {
                    result(false)
                    Toast.makeText(this@MainControllerDashboardActivity, "控制失败：${exception.message ?: "设备拒绝请求"}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onNetworkFailure(exception: Exception) {
                runOnUiThread {
                    result(false)
                    Toast.makeText(this@MainControllerDashboardActivity, "控制失败：网络不可用", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun openAdvancedParams() {
        val target = hostDevice ?: originalDevice ?: return
        startActivity(Intent(this, EspDeviceActivity::class.java).apply {
            putExtra(AppConstants.KEY_ESP_DEVICE, target)
        })
    }

    private fun openDeviceInfo() {
        startActivity(Intent(this, NodeDetailsActivity::class.java).apply {
            putExtra(AppConstants.KEY_NODE_ID, nodeId)
        })
    }
}

private data class MainControllerState(
    val title: String = "主控制器",
    val nodeOnline: Boolean = false,
    val levelPercent: Int = 0,
    val levelValid: Boolean = false,
    val sourceBound: Boolean = false,
    val sourceOnline: Boolean = false,
    val sourceAddress: Int = 0,
    val temperature: Float? = null,
    val temperatureValid: Boolean = false,
    val batteryVoltage: Float? = null,
    val batteryValid: Boolean = false,
    val thresholdsValid: Boolean = false,
    val lowThreshold: Int = 20,
    val highThreshold: Int = 90,
    val outputState: Boolean = false,
    val workMode: Int = MainControllerDashboardActivity.WORK_MODE_MANUAL,
    val loraAvailable: Boolean = false,
    val nodeCount: Int = 0,
    val onlineNodeCount: Int = 0,
    val wifiRssi: Int = -127,
    val cloudOnline: Boolean = false,
    val offlineMode: Boolean = false,
    val remoteControlEnabled: Boolean = false,
    val lastUpdatedMs: Long = 0L,
)

private fun Device?.findParam(name: String): Param? = this?.params?.firstOrNull { it.name == name }

private fun Device?.boolParam(name: String, default: Boolean): Boolean {
    val p = findParam(name) ?: return default
    val label = p.labelValue?.trim()?.lowercase(Locale.US)
    return when (label) {
        "true", "1", "on" -> true
        "false", "0", "off" -> false
        else -> p.switchStatus
    }
}

private fun Device?.intParam(name: String, default: Int): Int {
    val p = findParam(name) ?: return default
    return p.labelValue?.trim()?.toDoubleOrNull()?.roundToInt()
        ?: p.value.roundToInt()
}

private fun Device?.floatParam(name: String): Float? {
    val p = findParam(name) ?: return null
    return p.labelValue?.trim()?.toFloatOrNull() ?: p.value.toFloat()
}

private val DashboardBackground = Color(0xFFF4F8FC)
private val DashboardBlue = Color(0xFF1677FF)
private val DashboardCyan = Color(0xFF17A7E8)
private val DashboardGreen = Color(0xFF11A66A)
private val DashboardText = Color(0xFF11223B)
private val DashboardMuted = Color(0xFF6C7D92)
private val DashboardWarning = Color(0xFFFFA000)
private val DashboardDanger = Color(0xFFE64A4A)

@Composable
private fun MainControllerDashboard(
    state: MainControllerState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOutputChanged: (Boolean) -> Unit,
    onWorkModeChanged: (Int) -> Unit,
    onThresholdsChanged: (Int, Int) -> Unit,
    onAdvancedParams: () -> Unit,
    onDeviceInfo: () -> Unit,
    onNetworkConfig: () -> Unit,
    onLogs: () -> Unit,
    onScenes: () -> Unit,
    onSchedules: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var thresholdDialog by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = DashboardBackground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(state.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = DashboardText)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (state.nodeOnline) DashboardGreen else DashboardDanger)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (state.nodeOnline) "智能水位控制器 · 在线" else "智能水位控制器 · 离线",
                                color = DashboardMuted,
                                fontSize = 14.sp,
                            )
                        }
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = DashboardBlue)
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = DashboardText)
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("高级参数") },
                                leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                                onClick = { menuExpanded = false; onAdvancedParams() },
                            )
                            DropdownMenuItem(
                                text = { Text("设备信息") },
                                leadingIcon = { Icon(Icons.Rounded.Info, null) },
                                onClick = { menuExpanded = false; onDeviceInfo() },
                            )
                            DropdownMenuItem(
                                text = { Text("返回") },
                                onClick = { menuExpanded = false; onBack() },
                            )
                        }
                    }
                }
            }

            item { StatusOverviewCard(state) }
            item { WaterLevelCard(state, onEditThresholds = { thresholdDialog = true }) }
            item { WorkModeCard(state.workMode, onWorkModeChanged) }
            item { OutputCard(state.outputState, state.nodeOnline, onOutputChanged) }
            item { NodeStatusCard(state) }
            item {
                QuickActionsCard(
                    onScenes = onScenes,
                    onSchedules = onSchedules,
                    onNetworkConfig = onNetworkConfig,
                    onLogs = onLogs,
                )
            }
            item { FooterCard(state) }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (thresholdDialog) {
        ThresholdDialog(
            initialLow = state.lowThreshold,
            initialHigh = state.highThreshold,
            onDismiss = { thresholdDialog = false },
            onSave = { low, high ->
                thresholdDialog = false
                onThresholdsChanged(low, high)
            },
        )
    }
}

@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun StatusOverviewCard(state: MainControllerState) {
    DashboardCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusItem(
                Icons.Rounded.CloudDone,
                "云连接",
                if (state.cloudOnline) "已连接" else "未连接",
                state.cloudOnline,
            )
            StatusItem(
                Icons.Rounded.Wifi,
                "Wi-Fi",
                wifiSignalText(state.wifiRssi),
                state.wifiRssi > -100,
            )
            StatusItem(
                Icons.Rounded.Hub,
                "设备状态",
                if (state.nodeOnline) "在线" else "离线",
                state.nodeOnline,
            )
            StatusItem(
                Icons.Rounded.Settings,
                "远程控制",
                if (state.remoteControlEnabled) "已启用" else "未启用",
                state.remoteControlEnabled,
            )
        }
        if (state.offlineMode) {
            Spacer(Modifier.height(12.dp))
            Text("当前处于离线工作模式，云端状态可能延迟。", color = DashboardWarning, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StatusItem(icon: ImageVector, title: String, value: String, good: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Icon(icon, null, tint = if (good) DashboardGreen else DashboardMuted, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(6.dp))
        Text(title, color = DashboardText, fontSize = 12.sp, maxLines = 1)
        Text(value, color = if (good) DashboardGreen else DashboardMuted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun WaterLevelCard(state: MainControllerState, onEditThresholds: () -> Unit) {
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(0.36f)) {
                Text("当前水位", color = DashboardMuted, fontSize = 15.sp)
                Text(
                    if (state.levelValid) "${state.levelPercent}%" else "--%",
                    color = DashboardBlue,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    waterStateText(state),
                    color = waterStateColor(state),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(16.dp))
                ThresholdLegend("高阈值", state.highThreshold, DashboardDanger)
                Spacer(Modifier.height(7.dp))
                ThresholdLegend("低阈值", state.lowThreshold, DashboardWarning)
            }
            Spacer(Modifier.width(12.dp))
            WaterTank(
                level = if (state.levelValid) state.levelPercent else 0,
                low = state.lowThreshold,
                high = state.highThreshold,
                modifier = Modifier
                    .weight(0.64f)
                    .height(220.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFF5F9FF))
                .clickable(onClick = onEditThresholds)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Tune, null, tint = DashboardBlue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("阈值设置", color = DashboardText, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("${state.lowThreshold}% － ${state.highThreshold}%", color = DashboardBlue)
        }
    }
}

@Composable
private fun ThresholdLegend(label: String, value: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 24.dp, height = 2.dp).background(color))
        Spacer(Modifier.width(8.dp))
        Text("$label  $value%", color = DashboardMuted, fontSize = 12.sp)
    }
}

@Composable
private fun WaterTank(level: Int, low: Int, high: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.padding(8.dp)) {
        val strokeWidth = 3.dp.toPx()
        val radius = 26.dp.toPx()
        val tankLeft = size.width * 0.08f
        val tankRight = size.width * 0.92f
        val tankTop = size.height * 0.08f
        val tankBottom = size.height * 0.92f
        val tankWidth = tankRight - tankLeft
        val tankHeight = tankBottom - tankTop

        drawRoundRect(
            color = Color(0xFFE8F2FB),
            topLeft = Offset(tankLeft, tankTop),
            size = Size(tankWidth, tankHeight),
            cornerRadius = CornerRadius(radius, radius),
        )

        val waterFraction = level.coerceIn(0, 100) / 100f
        val waterTop = tankBottom - tankHeight * waterFraction
        if (waterFraction > 0f) {
            drawRoundRect(
                color = Color(0xFF35A9F4),
                topLeft = Offset(tankLeft + strokeWidth, waterTop),
                size = Size(tankWidth - strokeWidth * 2, tankBottom - waterTop - strokeWidth),
                cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
            )
            drawRect(
                color = Color(0xFF1687E8).copy(alpha = 0.28f),
                topLeft = Offset(tankLeft + strokeWidth, (waterTop + tankBottom) / 2f),
                size = Size(tankWidth - strokeWidth * 2, (tankBottom - waterTop) / 2f - strokeWidth),
            )
        }

        drawRoundRect(
            color = Color(0xFF9BC3E5),
            topLeft = Offset(tankLeft, tankTop),
            size = Size(tankWidth, tankHeight),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = strokeWidth),
        )

        fun yForPercent(percent: Int): Float = tankBottom - tankHeight * (percent.coerceIn(0, 100) / 100f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()), 0f)
        drawLine(
            DashboardDanger,
            Offset(tankLeft - 8.dp.toPx(), yForPercent(high)),
            Offset(tankRight + 8.dp.toPx(), yForPercent(high)),
            strokeWidth = 2.dp.toPx(),
            pathEffect = dash,
        )
        drawLine(
            DashboardWarning,
            Offset(tankLeft - 8.dp.toPx(), yForPercent(low)),
            Offset(tankRight + 8.dp.toPx(), yForPercent(low)),
            strokeWidth = 2.dp.toPx(),
            pathEffect = dash,
        )
        if (level in 0..100) {
            drawLine(
                DashboardBlue,
                Offset(tankRight + 5.dp.toPx(), yForPercent(level)),
                Offset(tankRight + 23.dp.toPx(), yForPercent(level)),
                strokeWidth = 3.dp.toPx(),
            )
        }
    }
}

@Composable
private fun WorkModeCard(mode: Int, onModeChanged: (Int) -> Unit) {
    DashboardCard {
        Text("工作模式", color = DashboardText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton("蓄水", Icons.Rounded.ArrowDownward, mode == MainControllerDashboardActivity.WORK_MODE_FILL, Modifier.weight(1f)) {
                onModeChanged(MainControllerDashboardActivity.WORK_MODE_FILL)
            }
            ModeButton("排水", Icons.Rounded.ArrowUpward, mode == MainControllerDashboardActivity.WORK_MODE_DRAIN, Modifier.weight(1f)) {
                onModeChanged(MainControllerDashboardActivity.WORK_MODE_DRAIN)
            }
            ModeButton("定时", Icons.Rounded.Schedule, mode == MainControllerDashboardActivity.WORK_MODE_TIMER, Modifier.weight(1f)) {
                onModeChanged(MainControllerDashboardActivity.WORK_MODE_TIMER)
            }
            ModeButton("手动", Icons.Rounded.PanTool, mode == MainControllerDashboardActivity.WORK_MODE_MANUAL, Modifier.weight(1f)) {
                onModeChanged(MainControllerDashboardActivity.WORK_MODE_MANUAL)
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFFEAF3FF) else Color(0xFFF7F9FC))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (selected) DashboardBlue else DashboardText, modifier = Modifier.size(27.dp))
        Spacer(Modifier.height(7.dp))
        Text(label, color = if (selected) DashboardBlue else DashboardText, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun OutputCard(enabled: Boolean, nodeOnline: Boolean, onChanged: (Boolean) -> Unit) {
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFEAF3FF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Settings, null, tint = DashboardBlue)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("水泵输出", color = DashboardText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (enabled) "主输出 · 已开启" else "主输出 · 已关闭", color = if (enabled) DashboardGreen else DashboardMuted, fontSize = 13.sp)
            }
            Switch(checked = enabled, onCheckedChange = onChanged, enabled = nodeOnline)
        }
    }
}

@Composable
private fun NodeStatusCard(state: MainControllerState) {
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("节点状态", color = DashboardText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("  (LoRa)", color = DashboardMuted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.SignalCellularAlt, null, tint = if (state.loraAvailable) DashboardGreen else DashboardMuted)
        }
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NodeMetric("LoRa", if (state.loraAvailable) "可用" else "不可用", state.loraAvailable, Modifier.weight(1f))
            NodeMetric("节点总数", state.nodeCount.toString(), state.nodeCount > 0, Modifier.weight(1f))
            NodeMetric("在线节点", "${state.onlineNodeCount}/${state.nodeCount}", state.onlineNodeCount > 0, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF7F9FC))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (state.sourceOnline) Color(0xFFE6F7EF) else Color(0xFFF0F2F5)),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (state.sourceAddress > 0) "%02d".format(state.sourceAddress) else "--", color = if (state.sourceOnline) DashboardGreen else DashboardMuted, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("液位源节点", color = DashboardText, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !state.sourceBound -> "未绑定"
                        state.sourceOnline -> "已绑定 · 在线"
                        else -> "已绑定 · 离线"
                    },
                    color = if (state.sourceOnline) DashboardGreen else DashboardMuted,
                    fontSize = 13.sp,
                )
            }
            if (state.batteryValid && state.batteryVoltage != null) {
                Text("%.2f V".format(state.batteryVoltage), color = DashboardMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun NodeMetric(label: String, value: String, good: Boolean, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF7F9FC))
            .padding(vertical = 12.dp, horizontal = 10.dp),
    ) {
        Text(label, color = DashboardMuted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = if (good) DashboardGreen else DashboardText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun QuickActionsCard(onScenes: () -> Unit, onSchedules: () -> Unit, onNetworkConfig: () -> Unit, onLogs: () -> Unit) {
    DashboardCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            QuickAction("场景", Icons.Rounded.WaterDrop, Color(0xFF0FA99A), onScenes)
            QuickAction("定时", Icons.Rounded.Schedule, DashboardBlue, onSchedules)
            QuickAction("网络配置", Icons.Rounded.Router, Color(0xFF7267E9), onNetworkConfig)
            QuickAction("日志", Icons.Rounded.ReceiptLong, Color(0xFFFF8A3D), onLogs)
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(color.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color)
        }
        Spacer(Modifier.height(7.dp))
        Text(label, color = DashboardText, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun FooterCard(state: MainControllerState) {
    DashboardCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FooterInfo(Icons.Rounded.Thermostat, "环境温度", if (state.temperatureValid && state.temperature != null) "%.1f ℃".format(state.temperature) else "-- ℃", Modifier.weight(1f))
            FooterInfo(Icons.Rounded.Refresh, "最后更新", formatTime(state.lastUpdatedMs), Modifier.weight(1.25f))
            FooterInfo(Icons.Rounded.Info, "系统", if (state.nodeOnline) "运行正常" else "设备离线", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text("App ${BuildConfig.VERSION_NAME}", color = DashboardMuted, fontSize = 11.sp)
    }
}

@Composable
private fun FooterInfo(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = DashboardCyan, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(7.dp))
        Column {
            Text(value, color = DashboardText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, color = DashboardMuted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ThresholdDialog(initialLow: Int, initialHigh: Int, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var low by remember(initialLow) { mutableStateOf(initialLow.toFloat()) }
    var high by remember(initialHigh) { mutableStateOf(initialHigh.toFloat()) }
    val valid = low.roundToInt() < high.roundToInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("水位阈值设置") },
        text = {
            Column {
                Text("低阈值 ${low.roundToInt()}%", fontWeight = FontWeight.Medium)
                Slider(value = low, onValueChange = { low = it }, valueRange = 0f..99f, steps = 98)
                Spacer(Modifier.height(8.dp))
                Text("高阈值 ${high.roundToInt()}%", fontWeight = FontWeight.Medium)
                Slider(value = high, onValueChange = { high = it }, valueRange = 1f..100f, steps = 98)
                if (!valid) {
                    Text("低阈值必须小于高阈值", color = DashboardDanger, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(low.roundToInt(), high.roundToInt()) }, enabled = valid) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun waterStateText(state: MainControllerState): String {
    if (!state.levelValid) return "水位数据无效"
    return when {
        state.levelPercent >= state.highThreshold -> "高水位"
        state.levelPercent <= state.lowThreshold -> "低水位"
        else -> "水位正常"
    }
}

private fun waterStateColor(state: MainControllerState): Color {
    if (!state.levelValid) return DashboardMuted
    return when {
        state.levelPercent >= state.highThreshold -> DashboardDanger
        state.levelPercent <= state.lowThreshold -> DashboardWarning
        else -> DashboardGreen
    }
}

private fun wifiSignalText(rssi: Int): String = when {
    rssi >= -55 -> "强信号"
    rssi >= -67 -> "良好"
    rssi >= -78 -> "一般"
    rssi >= -95 -> "较弱"
    else -> "不可用"
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return "--:--:--"
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
