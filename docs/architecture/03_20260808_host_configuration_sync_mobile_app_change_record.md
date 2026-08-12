# 03_20260808 主机配置同步手机端代码修改记录

> 对应开发计划：`docs/architecture/02_20260808_host_configuration_sync_mobile_app_plan.md`  
> 基线分支：`codex/existing-wifi-reuse-first-pairing`  
> 实现分支：`codex/host-configuration-sync-mobile-app`

---

## 1. 本轮实现原则

本轮继续沿用 ESP RainMaker Android 现有链路：

```text
EspNode -> Device -> Param -> ParamAdapter -> DeviceParamUpdates -> NetworkApiManager
```

不新建第二套设备状态仓库，不重做 Activity，不改变首次 BLE/Wi-Fi 配网流程，也不改变 Cloud / Local / BLE 路由优先级。

主机仍然是配置和安全状态的唯一权威；Android 只负责：

1. 展示主机发布的 Param；
2. 根据主机状态派生当前可编辑性；
3. 在网络发送前再次做明显非法请求预检；
4. 串行化 Param 写请求；
5. 写 ACK 后回读主机权威值并刷新现有页面。

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

已经从主机代码确认并固定的 WorkMode 协议值：

```text
FILL   = 1
DRAIN  = 2
TIMER  = 3
MANUAL = 4
```

策略层负责：

- 生成当前 Node 的只读 `Snapshot`；
- 派生 `CloudOnline && RemoteControlEnabled`；
- 关键状态缺失时 fail-closed；
- `RemoteControlEnabled` 永远禁止手机写入；
- `OutputState` 仅 `MANUAL(4)` 允许手机写入；
- Low/High 根据当前另一阈值计算有效范围；
- `evaluateWriteRequest()` 在真正发送前复核权限、模式、只读属性和阈值组合。

主机 AppCore 仍执行最终业务和安全校验，Android 预检不能替代主机校验。

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
- 权限或阈值变化后可重新计算并恢复；
- copy constructor、Parcelable、compareTo 均包含投影状态。

此前试验性的 `hostUiTypeOverride` 已删除。阈值继续使用现有 Slider，避免引入没有必要的第二种 UI 状态。

---

### APP-HOSTCFG-003：读取 Node 时刷新有效权限

文件：

`app/src/main/java/com/espressif/ui/models/EspNode.java`

在以下路径统一执行：

```text
HostConfigurationPolicy.applyEffectiveWriteGate(devices)
```

调用位置：

- `getDevices()`；
- `setDevices()`；
- Parcel 恢复后。

因此 `RemoteControlEnabled / CloudOnline / WorkMode / Low / High` 变化后，现有页面下次读取 Node 即重新计算有效写权限和阈值范围，不需要新增主机专用 Activity。

---

### APP-HOSTCFG-004：远程写 UI 门禁

基础规则：

```text
CloudOnline == true
AND
RemoteControlEnabled == true
```

主机可写 Param 才向现有通用 UI 暴露 `WRITE`。

额外规则：

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

Adapter 内部 `getItem()` 仍返回 `"1" ~ "4"`，现有 `ParamAdapter` 的 `Integer.parseInt(newValue)` 保持不变，因此发给主机的仍是稳定整数协议。

已经核对 `item_param.xml`：`card_spinner` 与 `tv_spinner_name` 位于同一个 `rl_card_drop_down` 容器，无需增加额外祖先遍历。

---

### APP-HOSTCFG-006：Low/High 保留 Slider，但每次只提交最终值

文件：

`app/src/main/java/com/espressif/ui/adapters/DeviceParamUpdates.java`

最终方案继续复用原 Slider：

- `onSeeking()` 产生的 `LowThreshold / HighThreshold` 中间值在 `processSliderChange()` 中直接忽略；
- `onStopTrackingTouch()` 原有 `clearQueueAndSendLastValue()` 保留；
- 每次手指释放只发送最终值。

有效范围由主机当前权威值动态收紧：

```text
LowThreshold  : max(设备原始 min, 0) .. min(设备原始 max, High - 1)
HighThreshold : max(设备原始 min, Low + 1) .. min(设备原始 max, 100)
```

若当前主机阈值缺失或本身非法，则 Android 先只读，等待权威值恢复。

这个方案比新增 Low/High 双字段 Draft/Apply 卡片改动更小，不为第一阶段过度设计。

---

### APP-HOSTCFG-007：最终发送前二次门禁

文件：

`app/src/main/java/com/espressif/ui/adapters/DeviceParamUpdates.java`

所有现有非 Matter Param 最终继续汇聚到：

```text
sendParamUpdates()
```

在调用 `NetworkApiManager.updateParamValue()` 前，重新读取：

```text
espApp.nodeMap[nodeId].getDevices()
```

并执行：

```text
HostConfigurationPolicy.evaluateWriteRequest(...)
```

因此即使 UI 曾经显示可写，但请求排队期间主机刚好关闭远控、掉云、切出 MANUAL 或修改另一阈值，旧请求也会在真正网络发送前再次被拦截。

---

### APP-HOSTCFG-008：写队列串行化和并发修复

文件：

`app/src/main/java/com/espressif/ui/adapters/DeviceParamUpdates.java`

修复内容：

1. 在提交 Executor 前先预约 `isWait=true`；
2. Param 请求预约后立即返回，同一轮不再继续调度 Slider；
3. Slider 只有成功预约后才从队列 `poll()`；
4. 已有 in-flight 请求时不提前 poll Slider，避免丢值；
5. 预检拦截、响应失败、网络失败都会释放 `isWait` 并继续下一项；
6. Iterator 删除空 Slider 队列使用 `iterator.remove()`，避免遍历期间直接修改 Map。

没有新增工作线程、任务或第二个发送队列，继续复用原单线程 Executor。

---

### APP-HOSTCFG-009：Boolean 权限状态读取修正

`bool/boolean` Param 优先使用现有解析链维护的 `switchStatus`。

原因：BLE 参数刷新可能只更新 `switchStatus`，历史 `labelValue` 可能滞后；若优先读取字符串，会把新 Boolean 真值覆盖掉。

非 Boolean 类型才回退解析 `labelValue`。

---

### APP-HOSTCFG-010：中英文资源

新增：

- `app/src/main/res/values/strings_host_configuration.xml`
- `app/src/main/res/values-zh-rCN/strings_host_configuration.xml`

当前用于 WorkMode 本地化显示，并预留统一权限/错误提示文案。

---

### APP-HOSTCFG-011：策略单元测试

文件：

`app/src/test/java/com/espressif/ui/hostconfig/HostConfigurationPolicyTest.java`

覆盖：

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

只监听：

`codex/host-configuration-sync-mobile-app`

不会改变基线分支原有 CI。

---

### APP-HOSTCFG-013：写 ACK 后权威 Param 回读

文件：

`app/src/main/java/com/espressif/ui/adapters/DeviceParamUpdates.java`

主机配置写入成功后的顺序调整为：

```text
write ACK
-> 先调用原控件 listener.onSuccess() 完成 loading/optimistic UI 收尾
-> 再调用 NetworkApiManager.getParamsValues(nodeId)
-> 现有解析链覆盖 espApp.nodeMap 中的 Param
-> EspDeviceActivity.updateViewTask 立即刷新当前页面
-> 释放 isWait
-> 继续下一条写请求
```

这样可以保证原控件即使先显示了手机请求值，后续权威回读仍会最后覆盖成主机实际接受的值。

回读失败不会把已经成功的写请求伪装成“写失败”；只记录告警、释放队列，并由项目原有周期刷新继续收敛。

页面退出后使用 `isFinishing()/isDestroyed()` 防护，不对已经销毁的 Activity 投递 UI 刷新。

---

### APP-HOSTCFG-014：BLE proxy 上报与权威回读串行化

文件：

`app/src/main/java/com/espressif/ui/adapters/DeviceParamUpdates.java`

已核对现有 BLE 流程：

```text
BLE set_params 成功
-> NetworkApiManager 回调写成功
-> reportParamsToProxy()
-> BleLocalControlManager.getParamsWithTimestamp()
```

`BleLocalControlManager` 已用 `proxyReadInProgress` 防止 `getParamsWithTimestamp()` 与普通 `queryParams()` 并发，因此手机端不能在 BLE ACK 回调内无条件立刻再读一次。

本轮采用有限、非阻塞等待：

```text
BLE write ACK
-> 延迟 100 ms，让 proxy read 有机会启动
-> 每 250 ms 检查 isProxyReadInProgress(nodeId)
-> BLE 空闲后立即执行权威回读
-> 最多检查 12 次，约 3 秒
```

如果约 3 秒后 BLE proxy 仍忙：

- 不继续占用 Param 写队列；
- 不并发抢占 BLE 读通道；
- 跳过本次立即回读；
- 依靠现有周期刷新继续收敛。

如果等待期间 BLE 断开，则交回 `NetworkApiManager` 按项目原有策略选择可用读取路径。

该等待使用主线程 `Handler.postDelayed()`，不阻塞线程。

---

## 3. 当前未实现 / 待确认项

### 3.1 Low/High 双字段 Draft/Apply 专用卡片

当前两个阈值继续使用原 Slider，各自在手指释放时单次提交，并由另一当前权威阈值限制范围。

如果后续 UX 明确要求：

```text
同时修改 Low + High
-> 本地预览
-> 一次 Apply
```

再新增专用组合控件。

状态：**可选 UX 增强，不阻塞第一阶段配置闭环。**

除此以外，开发计划第一阶段核心链路已落地；是否继续增加组合控件应以真机使用体验决定，当前不提前扩展。

---

## 4. 当前调用顺序

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
DeviceParamUpdates 单线程队列
        ↓
HostConfigurationPolicy.evaluateWriteRequest()
        ↓
NetworkApiManager.updateParamValue()
        ↓
主机 AppCore 最终校验
        ↓
写 ACK
        ↓
NetworkApiManager.getParamsValues()
        ↓
主机权威 Param 覆盖 espApp.nodeMap
        ↓
EspDeviceActivity.updateViewTask
```

BLE 路径在写 ACK 与权威回读之间额外串行等待已有 proxy read 完成。

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
8. 写成功但主机对值进行了校正时，App 应在权威回读后显示主机最终值；
9. BLE 本地控制写入时，不应出现 proxy read 与普通 get_params 并发；
10. 主机本机修改 WorkMode/阈值后，App 参数刷新必须回到主机权威值。

---

## 6. 风险与边界

- Android 预检只是体验与竞态保护，主机 AppCore 必须继续做最终校验；
- BLE 立即回读有约 3 秒等待上限，超时后主动降级为原周期刷新；
- 本轮没有修改首次 BLE 配网、已有 Wi-Fi 复用流程和 Cloud / Local / BLE 路由优先级；
- 没有新建业务线程、第二写队列或第二配置状态仓库；
- 未修改主机端业务代码。

---

## 7. 当前结论

第一阶段已经形成完整基础闭环：

```text
UI 权限门禁
+ 最终发送二次门禁
+ WorkMode 稳定协议本地化
+ OutputState MANUAL 门禁
+ Low/High 关联范围
+ 阈值只发送最终值
+ Param 写请求严格串行
+ 写 ACK 后权威回读
+ BLE proxy/readback 串行保护
```

后续不应继续扩大架构；下一步应以 CI、真机联调结果为依据，只修复实际暴露的问题。
