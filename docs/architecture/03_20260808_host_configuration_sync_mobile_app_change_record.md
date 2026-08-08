# 03_20260808 主机配置同步手机端代码修改记录

> 对应开发计划：`docs/architecture/02_20260808_host_configuration_sync_mobile_app_plan.md`  
> 基线分支：`codex/existing-wifi-reuse-first-pairing`  
> 实现分支：`codex/host-configuration-sync-mobile-app`

---

## 1. 本轮实现原则

本轮继续沿用 ESP RainMaker Android 现有：

```text
EspNode -> Device -> Param -> ParamAdapter -> DeviceParamUpdates -> NetworkApiManager
```

不新建第二套设备状态仓库，不重做 Activity，不改变首次 BLE/Wi-Fi 配网流程，也不改变 Cloud / Local / BLE 路由优先级。

主机仍然是配置和安全状态的唯一权威；Android 只做展示、交互门禁、明显非法请求预检和请求串行化。

---

## 2. 系统修改记录

### APP-HOSTCFG-001：主机配置协议策略层

文件：

`app/src/main/java/com/espressif/ui/hostconfig/HostConfigurationPolicy.java`

第一阶段识别：

- `RemoteControlEnabled`
- `CloudOnline`
- `WorkMode`
- `OutputState`
- `LowThreshold`
- `HighThreshold`

已确认并固定的 `WorkMode` 协议值：

```text
FILL   = 1
DRAIN  = 2
TIMER  = 3
MANUAL = 4
```

策略层负责：

1. 从当前 Node 的全部 Device/Param 生成只读 `Snapshot`；
2. 派生 `CloudOnline && RemoteControlEnabled`；
3. 缺少关键状态时 fail-closed；
4. `RemoteControlEnabled` 始终禁止手机写入；
5. `OutputState` 仅 `MANUAL(4)` 允许手机写入；
6. Low/High 按当前另一阈值计算有效范围；
7. `evaluateWriteRequest()` 在真正发送前再次检查权限、模式、只读属性和阈值组合。

---

### APP-HOSTCFG-002：Param 增加可逆 UI 投影

文件：

`app/src/main/java/com/espressif/ui/models/Param.java`

仅增加两类临时投影：

```text
hostWriteGateApplied / hostWriteAllowed
hostBoundsOverrideApplied / hostMinBounds / hostMaxBounds
```

作用：

- 不删除 RainMaker 原始 `properties`；
- 不覆盖设备原始 `min/max`；
- 权限或阈值变化后可以重新计算并恢复；
- copy constructor、Parcelable、compareTo 均包含投影状态。

此前试验性的 `hostUiTypeOverride` 已删除。阈值仍保留原 Slider，避免为了第一阶段增加无用 UI 状态。

---

### APP-HOSTCFG-003：读取 Node 时刷新有效权限

文件：

`app/src/main/java/com/espressif/ui/models/EspNode.java`

在：

- `getDevices()`
- `setDevices()`
- Parcel 恢复后

调用：

```text
HostConfigurationPolicy.applyEffectiveWriteGate(devices)
```

因此当前 `RemoteControlEnabled / CloudOnline / WorkMode / Low / High` 变化后，现有页面下次读取 Node 即重新计算可写状态，不需要新增主机专用 Activity。

---

### APP-HOSTCFG-004：远程写 UI 门禁

规则：

```text
CloudOnline == true
AND
RemoteControlEnabled == true
```

才向现有通用 UI 暴露主机可写 Param 的 `WRITE`。

额外门禁：

```text
RemoteControlEnabled -> 永远只读
OutputState          -> 仅 MANUAL(4) 可写
```

非本项目主机模型不会启用这套门禁，普通 RainMaker 设备保持原行为。

---

### APP-HOSTCFG-005：WorkMode 本地化显示，不改变协议值

文件：

`app/src/main/java/com/espressif/ui/widgets/EspDropDown.java`

只对名称为 `WorkMode` 的 Spinner 包装显示 Adapter：

```text
1 -> 蓄水 / Fill
2 -> 排水 / Drain
3 -> 定时 / Timer
4 -> 手动 / Manual
```

Adapter 内部 `getItem()` 仍返回 `"1" ~ "4"`，因此现有 `ParamAdapter` 的 `Integer.parseInt(newValue)` 不变，发送到主机的仍是稳定整数协议。

`item_param.xml` 已核对：`card_spinner` 与 `tv_spinner_name` 位于同一 `rl_card_drop_down` 容器，当前识别方式可以直接命中，不增加额外层级遍历。

---

### APP-HOSTCFG-006：Low/High 保留 Slider，但每次只提交最终值

文件：

`app/src/main/java/com/espressif/ui/adapters/DeviceParamUpdates.java`

最终采用的方案不是 TEXT 编辑框，而是继续使用原 Slider：

- `onSeeking()` 产生的 `LowThreshold / HighThreshold` 中间值在 `processSliderChange()` 中直接忽略；
- `onStopTrackingTouch()` 原有 `clearQueueAndSendLastValue()` 保留；
- 每次手指释放只发送最终值。

有效范围由主机当前权威值动态收紧：

```text
LowThreshold  : max(设备原始 min, 0) .. min(设备原始 max, High - 1)
HighThreshold : max(设备原始 min, Low + 1) .. min(设备原始 max, 100)
```

若当前主机阈值缺失或本身非法，则 Android 先只读，等待权威值恢复。

这个方案比新建 Low/High 双字段 Draft/Apply 卡片改动更小，第一阶段不做过度设计。

---

### APP-HOSTCFG-007：最终发送前二次门禁

文件：

`app/src/main/java/com/espressif/ui/adapters/DeviceParamUpdates.java`

所有现有非 Matter Param 最终仍汇聚到：

```text
sendParamUpdates()
```

在调用 `NetworkApiManager.updateParamValue()` 前，重新读取当前：

```text
espApp.nodeMap[nodeId].getDevices()
```

并执行：

```text
HostConfigurationPolicy.evaluateWriteRequest(...)
```

因此即使 UI 已显示可写，但请求排队期间主机刚好：

- 关闭远控；
- 云状态变为离线；
- 从 MANUAL 切到其它模式；
- 修改了另一液位阈值；

旧请求也会在真正网络发送前再次被拦截。

主机 AppCore 仍保留最终校验，Android 预检不能替代主机安全逻辑。

---

### APP-HOSTCFG-008：写队列串行化和并发修复

文件：

`app/src/main/java/com/espressif/ui/adapters/DeviceParamUpdates.java`

原实现存在一个小的并发窗口：请求提交到单线程 Executor 后，要等 worker 真正执行才设置 `isWait`，UI 线程可能在这段时间再次提交另一个网络请求。

本轮修正：

1. 在提交 Executor 前先预约 `isWait=true`；
2. Param 请求预约后立即 `return`，同一轮不再继续调度 Slider；
3. Slider 只有成功预约后才从队列 `poll()`；
4. 已有 in-flight 请求时不提前 poll Slider，避免丢值；
5. 预检拦截、响应失败、网络失败都会释放 `isWait` 并继续下一项；
6. Iterator 删除空 Slider 队列使用 `iterator.remove()`，避免遍历时直接修改 Map。

没有新增线程、任务或队列，继续复用原单线程 Executor。

---

### APP-HOSTCFG-009：Boolean 权限状态读取修正

`bool/boolean` Param 优先使用现有解析链维护的 `switchStatus`。

原因：BLE 参数刷新可能只更新 `switchStatus`，历史 `labelValue` 可能滞后；若优先读字符串会把新 Boolean 真值覆盖掉。

非 Boolean 类型才回退解析 `labelValue`。

---

### APP-HOSTCFG-010：中英文资源

新增：

- `app/src/main/res/values/strings_host_configuration.xml`
- `app/src/main/res/values-zh-rCN/strings_host_configuration.xml`

当前用于 WorkMode 显示，并预留统一权限/错误提示文案。

---

### APP-HOSTCFG-011：策略单元测试

文件：

`app/src/test/java/com/espressif/ui/hostconfig/HostConfigurationPolicyTest.java`

覆盖至少：

1. 非主机设备不受影响；
2. 远控关闭时主机写 UI fail-closed；
3. CloudOffline 时主机写 UI fail-closed；
4. 远控+云在线时 WorkMode/阈值恢复可写；
5. OutputState 仅 MANUAL 可写；
6. Low/High 关联有效范围；
7. 权威阈值非法时 fail-closed；
8. WorkMode 1/2/3/4 稳定协议；
9. 最终写预检拒绝非 MANUAL 输出和非法阈值；
10. Boolean `switchStatus` 优先于可能过期的 `labelValue`。

---

### APP-HOSTCFG-012：当前分支专用 GitHub Actions

新增：

`.github/workflows/03_host_configuration_sync_mobile_app.yml`

自动执行：

```text
实现边界静态检查
-> HostConfigurationPolicyTest
-> :app:assembleDebug
-> 上传 debug APK artifact
```

仅监听：

`codex/host-configuration-sync-mobile-app`

不会改变基线分支原有 CI。

---

## 3. 当前明确未完成 / 待确认

### 3.1 写 ACK 后立即权威回读

现有应用已经每约 5 秒通过 `getParamsValues()` 刷新设备 Param，且 Cloud/Local/BLE 的读取链都会更新 `espApp.nodeMap`。

开发计划中更严格的：

```text
write ACK
-> 立即 getParamsValues()
-> 用主机返回值覆盖手机临时值
-> UI 立即收敛
```

当前尚未单独接入。

原因：现有 `ParamAdapter` 在 write success callback 内还会写入一次请求值；若简单把 `getParamsValues()` 插在 callback 前面，会被后续 optimistic update 再覆盖，必须明确调整回调顺序或增加一个很小的权威刷新入口后再做。

状态：**待确认，不能宣称已完成。**

### 3.2 Low/High 双字段 Draft/Apply 组合控件

当前是两个 Slider，各自在释放时单次提交，并由另一当前阈值限制可选范围。

如果后续 UX 明确要求“一次同时修改两个阈值，再统一 Apply”，再增加专用组合控件；第一阶段不提前实现。

状态：**可选增强，不阻塞当前基础闭环。**

---

## 4. 调用顺序

```text
主机 RainMaker Param
        ↓
Cloud / Local / BLE 更新 EspNode
        ↓
EspNode.getDevices()
        ↓
HostConfigurationPolicy.applyEffectiveWriteGate()
        ↓
现有 ParamAdapter 显示/交互
        ↓
DeviceParamUpdates 队列
        ↓
HostConfigurationPolicy.evaluateWriteRequest()
        ↓
NetworkApiManager
        ↓
主机 AppCore 最终校验
```

---

## 5. 验证顺序

### 5.1 JVM 策略测试

```bash
./gradlew :app:testDebugUnitTest --tests "com.espressif.ui.hostconfig.HostConfigurationPolicyTest"
```

### 5.2 Android 编译

```bash
./gradlew :app:assembleDebug
```

### 5.3 真机联调

1. `RemoteControlEnabled=false`：WorkMode / OutputState / Low / High 均不可写，状态仍可见；
2. 主机本机开启远控：App 刷新后写入口恢复，但 `RemoteControlEnabled` 本身仍不可写；
3. WorkMode 显示中文/英文名称，主机实际收到 1/2/3/4；
4. OutputState 非 MANUAL 不可写，MANUAL 可写；
5. Low 最大值始终不超过 High-1；High 最小值始终不低于 Low+1；
6. 拖动 Low/High 过程中不连续发请求，释放时只发一次最终值；
7. 请求排队后立即在主机关闭远控或切出 MANUAL，旧请求应在 Android 最终发送门禁被拒绝；
8. 主机本机修改 WorkMode/阈值，App 后续参数刷新必须回到主机权威值。

---

## 6. 回滚边界

本轮没有修改：

- 首次 BLE 配网；
- 已有 Wi-Fi 复用流程；
- Cloud / Local / BLE 路由优先级；
- `EspDeviceActivity` 页面架构；
- 主机端业务代码。

主要回滚文件：

- `HostConfigurationPolicy.java`
- `Param.java`
- `EspNode.java`
- `EspDropDown.java`
- `DeviceParamUpdates.java`
- 两组 strings 资源
- 本轮测试与 CI 文件

---

## 7. 当前结论

第一阶段已经形成：

```text
UI 门禁
+ 最终发送二次门禁
+ WorkMode 稳定协议本地化
+ OutputState MANUAL 门禁
+ Low/High 关联范围
+ 阈值只发送最终值
+ 写请求串行化
```

剩余最主要的功能缺口只有一个：**写 ACK 后立即进行权威 Param 回读并主动刷新 UI**。该项应作为下一小步单独实现，不需要重构现有页面或网络架构。
