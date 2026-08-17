# 03_20260817 主控制器专用仪表盘实现说明

- 日期：2026-08-17
- 仓库：`GPTKEY/esp-rainmaker-android`
- 实现分支：`codex/main-controller-dashboard`
- 前置设计：`docs/architecture/02_20260808_host_configuration_sync_mobile_app_plan.md`
- 对应主机：`GPTKEY/uart_echo`
- 当前阶段：主控制器 Dashboard V1 已实现，Android `assembleDebug` 已通过

---

## 1. 本次实现目标

原 RainMaker Android App 的 `EspDeviceActivity + ParamAdapter` 是通用设备参数页面，适合展示单个 Device 的通用 Param，但不能直接表达本项目主控制器的产品语义。

本次实现不修改 RainMaker 云协议，不复制第二套控制协议，而是在现有 App 中增加一个**主控制器专用 Dashboard**：

```text
RainMaker Node
├ Host Controller
│  ├ OutputState
│  ├ WorkMode
│  ├ LoRaAvailable
│  ├ NodeCount
│  ├ OnlineNodeCount
│  ├ WiFiRSSI
│  ├ CloudOnline
│  ├ OfflineMode
│  └ RemoteControlEnabled
│
└ Liquid Monitor
   ├ LevelPercent
   ├ LevelValid
   ├ SourceBound
   ├ SourceOnline
   ├ SourceAddress
   ├ Temperature
   ├ TemperatureValid
   ├ BatteryVoltage
   ├ BatteryValid
   ├ ThresholdsValid
   ├ LowThreshold
   └ HighThreshold
```

Android 仍以主机 RainMaker 上报为权威状态。

---

## 2. 核心架构

### 2.1 新增专用 Activity

新增：

```text
app/src/main/java/com/espressif/ui/activities/MainControllerDashboardActivity.kt
```

职责：

- 识别主控制器稳定 Device/Param 契约；
- 聚合 `Host Controller` 与 `Liquid Monitor` 两个 Device 的状态；
- 以 Compose 构建主控制器专用 UI；
- 使用现有 `NetworkApiManager` 读取和写入 RainMaker Param；
- 不直接访问主机内部 NVS/EEZ 变量；
- 不复制主机业务状态机。

### 2.2 页面路由

原有普通设备仍进入：

```text
EspDeviceActivity
```

只有满足以下条件时，才进入：

```text
MainControllerDashboardActivity
```

识别条件：

1. 用户点击的 Device 名称是 `Host Controller` 或 `Liquid Monitor`；
2. 同一个 RainMaker Node 中同时存在 `Host Controller` 和 `Liquid Monitor`。

这样可以避免名称相似的普通 RainMaker Device 被错误路由到主控制器专用页面。

相关修改：

```text
app/src/main/java/com/espressif/ui/adapters/EspDeviceAdapter.java
app/src/main/AndroidManifest.xml
```

---

## 3. V1 页面能力

### 3.1 水位总览

Dashboard 显示：

- 当前液位百分比；
- 大型动态水箱视觉；
- 低液位阈值；
- 高液位阈值；
- 液位有效性；
- 液位过低/正常/过高状态。

阈值写入仍使用稳定 RainMaker 参数：

```text
LowThreshold
HighThreshold
```

客户端先校验：

```text
0 <= low < high <= 100
```

主机仍做最终合法性判断。

### 3.2 工作模式

V1 使用主机稳定整数协议：

```text
1 -> 蓄水 FILL
2 -> 排水 DRAIN
3 -> 定时 TIMER
4 -> 手动 MANUAL
```

写入参数：

```text
WorkMode
```

Android 只负责本地化显示，不重新定义协议值。

### 3.3 本机输出

控制参数：

```text
OutputState
```

最终状态必须以后续主机上报为准，不能把 Android 本地点击状态作为永久真值。

### 3.4 主机连接与系统状态

展示：

```text
CloudOnline
RemoteControlEnabled
WiFiRSSI
LoRaAvailable
NodeCount
OnlineNodeCount
OfflineMode
```

其中：

- `RemoteControlEnabled` 只读；
- App 不提供远程开启该权限的入口；
- 云在线与远控授权分别显示，避免把“在线但禁止远控”误判为离线。

### 3.5 当前液位源节点

展示：

```text
SourceBound
SourceOnline
SourceAddress
BatteryVoltage
Temperature
```

当前 RainMaker 稳定契约并没有提供“每一个 LoRa 节点的 RSSI 列表”，因此 V1 **不伪造逐节点 RSSI 数据**。

Dashboard 只展示协议真实提供的：

- LoRa 总体可用状态；
- 节点总数；
- 在线节点数；
- 当前液位来源节点及其状态。

后续若主机增加 RM9 动态节点模型，再扩展真正的逐节点卡片。

---

## 4. 数据读写链路

### 4.1 读取

进入页面和周期刷新时：

```text
MainControllerDashboardActivity
    -> NetworkApiManager.getParamsValues(nodeId)
    -> 更新 EspNode / Device / Param 权威状态
    -> rebuild MainControllerState
    -> Compose 重绘
```

V1 周期刷新间隔：

```text
5 s
```

该刷新是手机 UI 的状态同步，不改变主机业务逻辑。

### 4.2 写入

工作模式、输出、阈值均继续通过现有：

```text
NetworkApiManager
```

发送 RainMaker Param 更新。

原则：

```text
用户操作
-> 发出 RainMaker 参数请求
-> 主机接收并执行/拒绝
-> 主机重新上报 authoritative state
-> Android 页面最终收敛
```

没有新增平行 HTTP/MQTT/私有控制协议。

---

## 5. 通用设备兼容策略

本次修改必须满足：

```text
主控制器设备 -> 专用 Dashboard
其他 RainMaker Device -> 原 EspDeviceActivity
```

因此没有删除：

```text
EspDeviceActivity
ParamAdapter
```

同时 Dashboard 保留“高级参数”入口，可以重新进入原通用参数页面，用于：

- 查看未来新增但 Dashboard 尚未专门适配的 Param；
- 调试协议；
- 保留 RainMaker App 原生能力。

---

## 6. Compose 引入范围

项目原主体仍是传统 Android View/RecyclerView 架构。

本次只把主控制器专用页作为独立 Compose 页面接入，没有要求全项目迁移 Compose。

`app/build.gradle` 已补充 Compose Material3、icons、tooling 等依赖。

这样可以：

- 快速构建主控制器信息密集型 Dashboard；
- 避免大范围改动原 RainMaker App；
- 保持普通页面兼容性。

---

## 7. V1 已完成 / 暂未完成

### 已完成

- 主控制器精确识别与路由；
- Host/Liquid 两 Device 聚合；
- 水位大卡片；
- 水位阈值显示与编辑；
- 工作模式控制；
- OutputState 控制；
- Cloud/Remote/Wi-Fi/LoRa 状态；
- 节点数量和液位来源节点；
- 温度、电池；
- 5 秒状态刷新；
- 高级参数回退入口；
- 配网日志入口；
- Android Debug APK 云端编译通过。

### 暂未完成

- Dashboard 内完整 Scene 编辑器；
- Dashboard 内完整 Schedule 编辑器；
- 网络配置独立专用页；
- 真正的动态 LoRa 多节点列表；
- 每个 LoRa 节点独立 RSSI/电量/历史数据；
- 主控制器专用趋势图和历史记录。

Scene/Schedule V1 仍由 RainMaker 原有页面统一管理。

---

## 8. 当前验证结果

GitHub Actions 工作流：

```text
.github/workflows/06_main_controller_dashboard.yml
```

执行内容包括：

1. 校验主控制器关键 Param 契约；
2. 创建 CI Android 构建配置；
3. 执行 `:app:assembleDebug`；
4. 上传编译日志；
5. 成功后提交生成的产品源码修改；
6. 上传 Debug APK。

2026-08-12 的最终构建结果：

```text
Build debug APK       SUCCESS
Upload build log      SUCCESS
Commit integration    SUCCESS
Upload debug APK      SUCCESS
```

实现源码落地提交：

```text
dc8d96686177e60da41722de0eba60386f1bea2b
```

---

## 9. 后续建议顺序

建议后续按以下顺序继续：

```text
DASH-V1 实机 UI/参数联调
    -> 修复手机分辨率/滚动/交互问题
    -> RemoteControlEnabled 完整门禁验收
    -> 阈值双参数权威回写验收
    -> WorkMode / OutputState 双向同步验收

DASH-V2
    -> 独立网络状态/配置页
    -> Schedule / Scene 正式入口

RM9
    -> 主机扩展动态 LoRa 节点 RainMaker 模型
    -> Android 动态节点列表
    -> 单节点详情 / RSSI / 电池 / 最近消息
```

在 RM9 协议落地前，不应在 Android 端制造不存在的逐节点数据。

---

## 10. 结论

主控制器页面已经从“通用 Param 列表”升级为“产品语义 Dashboard”，但底层仍坚持：

```text
RainMaker 稳定契约
+ 主机 authoritative state
+ Android 只表达用户操作
```

这保证了主控制器专用体验与 RainMaker 原有通用设备兼容性可以同时保留。