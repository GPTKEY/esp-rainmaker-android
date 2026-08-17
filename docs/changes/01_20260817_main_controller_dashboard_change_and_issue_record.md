# 01_20260817 主控制器 Dashboard 修改与问题解决记录

- 日期：2026-08-17
- 仓库：`GPTKEY/esp-rainmaker-android`
- 分支：`codex/main-controller-dashboard`
- 对应架构文档：`docs/architecture/03_20260817_main_controller_dashboard_implementation.md`
- 状态：V1 已实现，`assembleDebug` 已通过

---

## 1. 修改背景

主机 RainMaker 已形成稳定的 `Host Controller + Liquid Monitor` 设备模型，但 Android 原生页面仍以通用 `EspDeviceActivity + ParamAdapter` 逐参数展示。

实际问题不是“RainMaker 没有数据”，而是：

```text
协议已经有产品语义
但 App 仍按通用参数列表展示
```

因此本次修改目标是：

- 不改变主机 RainMaker 协议；
- 不破坏普通 RainMaker Device；
- 为主控制器提供专用 Dashboard；
- 保留原高级参数页面作为兼容和调试入口。

---

# 2. 代码修改记录

## 2.1 新增主控制器专用页面

文件：

```text
app/src/main/java/com/espressif/ui/activities/MainControllerDashboardActivity.kt
```

新增能力：

- Compose 主控制器 Dashboard；
- 同时解析 `Host Controller` 和 `Liquid Monitor`；
- 水箱和液位百分比；
- 高低阈值；
- 工作模式；
- 本机输出；
- Cloud/Wi-Fi/LoRa/远控状态；
- 节点总数/在线数；
- 液位来源节点；
- 温度、电池；
- 5 秒刷新；
- 高级参数入口；
- 日志入口。

写参数继续复用：

```text
NetworkApiManager
```

没有增加另一套网络控制层。

---

## 2.2 修改设备点击路由

文件：

```text
app/src/main/java/com/espressif/ui/adapters/EspDeviceAdapter.java
```

原逻辑：

```text
点击普通 Device
-> EspDeviceActivity
```

新逻辑：

```text
点击 Device
-> 判断当前 Node 是否同时存在
   Host Controller + Liquid Monitor

是主控制器
-> MainControllerDashboardActivity

其他设备
-> EspDeviceActivity
```

避免把整个 RainMaker App 改成项目专用 App，也避免影响其他通用设备。

---

## 2.3 AndroidManifest 注册

文件：

```text
app/src/main/AndroidManifest.xml
```

新增：

```text
MainControllerDashboardActivity
```

页面保持 portrait，与当前 App 主设备页面使用方式一致。

---

## 2.4 Compose 构建依赖

文件：

```text
app/build.gradle
```

补充 Compose BOM / Material3 / Material Icons / tooling / UI test 支持。

策略不是全项目 Compose 化，而是仅新增独立专用页面。

---

## 2.5 自动实施脚本

文件：

```text
scripts/implement_main_controller_dashboard.py
```

职责：

- 确保 Dashboard 需要的代码补丁可重复应用；
- 修改设备路由；
- 注册 Manifest；
- 修正 Compose 页面需要的 `ColumnScope` 声明；
- 避免 CI 重复运行时产生重复插入。

---

## 2.6 GitHub Actions 验证流程

文件：

```text
.github/workflows/06_main_controller_dashboard.yml
```

验证：

```text
稳定 Param 契约检查
-> CI local.properties
-> Android SDK/JDK
-> :app:assembleDebug
-> 上传完整构建日志
-> 成功后提交产品源码
-> 上传 APK
```

这使本次 Android 修改不依赖本地 Android Studio 才能验证。

---

# 3. 问题一：原通用页面无法表达主控制器产品语义

## 3.1 现象

主机已经上报完整参数，但 App 页面仍是一项一项的通用参数控件：

```text
LevelPercent
LowThreshold
HighThreshold
WorkMode
OutputState
...
```

用户需要自己理解参数关系，水位、控制模式、连接状态、节点状态没有形成完整控制面板。

## 3.2 原因

原 `EspDeviceActivity + ParamAdapter` 的设计目标是：

```text
任何 RainMaker Device 都能显示
```

它不知道本项目中的：

```text
Host Controller
+
Liquid Monitor
=
同一个主控制器产品
```

因此这不是 ParamAdapter 的 bug，而是**通用组件与产品专用页面职责不同**。

## 3.3 解决方法

新增 `MainControllerDashboardActivity`，将两个 Device 聚合为一个产品页面。

同时保留原页面：

```text
专用页面负责用户体验
通用页面负责兼容/调试/未知参数
```

---

# 4. 问题二：如何避免误识别普通 RainMaker Device

## 4.1 风险

如果只用：

```text
deviceName == "Host Controller"
```

判断，未来名称相同或相近设备可能进入错误页面。

## 4.2 原因

单 Device 名称不足以证明整个 Node 是本项目的主控制器。

## 4.3 解决方法

采用组合识别：

```text
当前点击 Device 名称属于：
Host Controller / Liquid Monitor

并且同一个 Node 同时存在：
Host Controller + Liquid Monitor
```

只有同时满足才进入专用 Dashboard。

普通 Device 继续走 `EspDeviceActivity`。

---

# 5. 问题三：不能为了样图伪造逐 LoRa 节点数据

## 5.1 现象/需求冲突

Dashboard 设计上希望展示多个 LoRa 节点卡片和 RSSI，但当前主机稳定 RainMaker contract 只有：

```text
LoRaAvailable
NodeCount
OnlineNodeCount
SourceAddress
SourceOnline
BatteryVoltage
Temperature
```

并没有逐节点 RSSI 列表。

## 5.2 原因

当前 RainMaker Device 模型仍以主机总体状态和当前液位来源节点为主，还没有 RM9 动态节点模型。

## 5.3 解决方法

V1 只显示真实存在的数据：

- LoRa 总体可用；
- 节点总数；
- 在线节点数；
- 当前液位源地址；
- 当前源在线状态；
- 当前源电池/温度。

明确不显示伪造的“节点 1/2/3 RSSI”。

后续等主机增加动态节点协议后再实现。

---

# 6. 问题四：Android 本地状态不能成为控制权威

## 6.1 风险

如果用户点击 Switch 后直接永久修改本地 UI：

```text
手机显示 ON
```

但主机可能因为：

- 未授权远控；
- 当前工作模式不允许；
- 参数非法；
- 请求失败；
- 持久化失败；

最终仍然保持 OFF。

## 6.2 原因

Android 是远程控制客户端，不是主机业务状态的 owner。

## 6.3 解决方法

统一链路：

```text
Android 用户意图
-> NetworkApiManager
-> RainMaker
-> 主机执行/拒绝
-> 主机重新上报
-> Android 最终刷新
```

`RemoteControlEnabled` 继续是只读门禁，App 不允许远程开启。

V1 页面每 5 秒重新同步一次状态，用于保证显示逐步收敛到主机权威值。

后续实机联调还要继续验证“写入后立即刷新/回报”的交互体验。

---

# 7. 问题五：Compose Canvas `drawLine()` 编译失败

## 7.1 失败现象

GitHub Actions 执行：

```text
:app:compileDebugKotlin
```

失败位置：

```text
MainControllerDashboardActivity.kt:703
MainControllerDashboardActivity.kt:710
MainControllerDashboardActivity.kt:718
```

编译器报告 `drawLine()` 没有匹配的重载。

## 7.2 根本原因

代码使用了错误的命名参数：

```kotlin
width = ...
```

Compose `DrawScope.drawLine()` 对应参数名实际是：

```kotlin
strokeWidth = ...
```

因此虽然值类型本身正确，Kotlin 的命名参数匹配仍会直接失败。

## 7.3 修复

将三处：

```kotlin
width = ...
```

改为：

```kotlin
strokeWidth = ...
```

修复提交：

```text
ec34b2b137ff248df8b099df805e8737a2959942
```

随后 `:app:assembleDebug` 成功。

## 7.4 经验

Compose API 使用命名参数时，参数名本身是编译契约的一部分。

后续 Canvas/Modifier API 修改应优先通过真实 Gradle 构建验证，不只依靠静态阅读。

---

# 8. 问题六：失败构建日志不便获取

## 8.1 现象

第一次云端构建失败后，通过 GitHub job logs 接口读取时出现日志内容为空/临时不可读取，导致不能可靠定位 Kotlin 行号。

## 8.2 原因

GitHub Actions 运行中和刚结束时，job log 下载接口的可用性存在时间窗口；仅依赖 API 即时读取不够稳定。

## 8.3 解决方法

修改 Dashboard CI：

```text
无论 Build 成功或失败
-> 将 Gradle 输出保存为文件
-> Upload build log artifact
```

对应 CI 提交：

```text
b72cabfeb9f94da9b6a390980dad76561c8db038
```

这样后续失败可以直接下载完整构建日志，不再依赖 job logs 即时接口。

---

# 9. 最终构建验证

最终 GitHub Actions run：

```text
Main Controller Dashboard
run #3
```

结果：

```text
Apply dashboard integration      SUCCESS
Verify RainMaker contract        SUCCESS
Build debug APK                  SUCCESS
Upload build log                 SUCCESS
Commit compiled integration      SUCCESS
Upload debug APK                 SUCCESS
```

最终产品源码提交：

```text
dc8d96686177e60da41722de0eba60386f1bea2b
```

APK artifact 已成功生成。

---

# 10. 本次问题分类总结

| 问题 | 类型 | 根因 | 解决 |
|---|---|---|---|
| 通用参数页不适合主控制器 | 架构/UI | 通用 Device 页面没有产品聚合语义 | 新增专用 Dashboard，保留通用页 |
| 可能误识别设备 | 路由 | 仅凭单一 Device 名称不够 | 同 Node 双 Device 组合识别 |
| 样图需要逐节点 RSSI，但协议没有 | 协议边界 | 主机尚无动态节点模型 | V1 只显示真实 contract 数据 |
| 手机状态可能与主机不一致 | 状态所有权 | Android 不是业务权威 | NetworkApiManager + 主机回报收敛 |
| Compose `drawLine` 编译失败 | 编译/API | 命名参数写成 `width` | 改为 `strokeWidth` |
| CI 失败日志不易拿到 | 工程化 | 即时 job log 接口不稳定 | 构建日志作为 artifact 强制上传 |

---

# 11. 当前待实机验证项

虽然 Android 编译已经通过，但以下仍必须以真机 + 主机联调为准：

```text
1. 点击 Host Controller / Liquid Monitor 是否稳定进入专用页
2. 各手机分辨率下水箱/卡片/滚动是否正常
3. LevelPercent 是否实时正确刷新
4. Low/High Threshold 写入后是否以主机权威值收敛
5. WorkMode 双向修改是否同步
6. OutputState 是否遵守主机业务门禁
7. RemoteControlEnabled=false 时所有受控项是否真正不可写
8. CloudOnline=false 时页面错误提示是否清晰
9. 主机本机修改后 Android 是否更新
10. Android 修改后主机 EEZ 页面是否更新
```

这些属于 V1 实机验收，不应仅以 `assembleDebug` 成功替代。

---

# 12. 后续问题处理原则

后续 Dashboard 若出现问题，优先按以下层次定位：

```text
UI 显示问题
-> MainControllerState / Compose

参数值错误
-> EspNode / Device / Param 当前缓存

写请求失败
-> NetworkApiManager / RainMaker response

请求成功但设备未变化
-> 主机 RainMaker adapter / AppCore 门禁

多节点信息不足
-> 先检查 RainMaker contract 是否提供，不在 Android 伪造
```

保持 Android、RainMaker adapter、主机 AppCore 三层职责边界不混淆。