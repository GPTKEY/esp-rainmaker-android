# 03_20260808 主机配置同步手机端代码修改记录

> 对应开发计划：`docs/architecture/02_20260808_host_configuration_sync_mobile_app_plan.md`  
> 基线分支：`codex/existing-wifi-reuse-first-pairing`  
> 实现分支：`codex/host-configuration-sync-mobile-app`

---

## 1. 修改原则

本轮严格沿用现有 ESP RainMaker Android 的 `EspNode -> Device -> Param -> ParamAdapter` 展示链路，不新增第二套设备状态仓库，不新建平行网络协议层，也不改变首次配网流程。

手机端只负责：

1. 读取设备已经发布的主机配置 Param；
2. 根据 `CloudOnline`、`RemoteControlEnabled`、`WorkMode` 派生当前 UI 是否允许编辑；
3. 把稳定协议值转换为适合用户理解的显示；
4. 在 UI 层提前避免明显非法的阈值组合；
5. 继续由主机 AppCore 作为配置最终权威和安全校验者。

本轮不把 App 变成业务状态所有者，也不在 App 中自行保存主机配置真值。

---

## 2. 系统修改记录

### APP-HOSTCFG-001：新增主机配置协议策略层

文件：

`app/src/main/java/com/espressif/ui/hostconfig/HostConfigurationPolicy.java`

新增能力：

- 识别第一阶段主机 Param：
  - `RemoteControlEnabled`
  - `CloudOnline`
  - `WorkMode`
  - `OutputState`
  - `LowThreshold`
  - `HighThreshold`
- 固化已经由主机代码确认的 `WorkMode` 稳定协议值：
  - `FILL = 1`
  - `DRAIN = 2`
  - `TIMER = 3`
  - `MANUAL = 4`
- 生成只读 `Snapshot`，派生：
  - 云是否在线；
  - 本机是否允许远程控制；
  - 当前工作模式；
  - `controlAvailable = CloudOnline && RemoteControlEnabled`。
- 状态缺失时采用 fail-closed，不猜测“允许远控”。
- 提供 `evaluateWriteRequest()` 纯逻辑预检函数，为后续统一写入口二次校验预留唯一规则来源。

安全边界：

- `RemoteControlEnabled` 在手机端始终只读；
- App 不能远程开启该授权；
- 主机仍是最终权限判断者。

---

### APP-HOSTCFG-002：Param 增加可逆的“有效 UI 投影”

文件：

`app/src/main/java/com/espressif/ui/models/Param.java`

新增字段均为 Android 侧 UI 投影，不修改 RainMaker 原始协议元数据：

- `hostWriteGateApplied`
- `hostWriteAllowed`
- `hostBoundsOverrideApplied`
- `hostMinBounds`
- `hostMaxBounds`
- `hostUiTypeOverride`

设计目的：

- 设备暂时禁止远控时，只隐藏当前 UI 的 `WRITE`，不删除 RainMaker 原始 `properties`；
- 权限重新开放后可以直接恢复，不需要重新构造协议模型；
- 液位阈值可以根据另一阈值实时收紧可编辑范围，但不覆盖设备原始 bounds；
- RecyclerView Diff 可以感知权限、范围和 UI 类型变化。

同时补齐 Parcelable 和 copy constructor，避免页面跳转/对象复制后丢失当前投影状态。

---

### APP-HOSTCFG-003：节点读取时统一刷新主机 UI 权限

文件：

`app/src/main/java/com/espressif/ui/models/EspNode.java`

调整：

- `getDevices()` 返回设备列表前重新执行 `HostConfigurationPolicy.applyEffectiveWriteGate()`；
- `setDevices()` 和 Parcel 恢复后同样执行投影。

调用关系：

```text
RainMaker/Local/BLE 更新 EspNode Param
        ↓
EspDeviceActivity 读取 node.getDevices()
        ↓
HostConfigurationPolicy 重新计算有效权限/阈值范围
        ↓
现有 ParamAdapter 继续负责显示
```

这样可以复用现有页面刷新机制，不需要新建主机专用 Activity。

---

### APP-HOSTCFG-004：全局远程写 UI 门禁

规则：

```text
CloudOnline == true
AND
RemoteControlEnabled == true
        ↓
主机可写 Param 才向现有 UI 暴露 WRITE
```

额外规则：

- `RemoteControlEnabled`：始终手机只读；
- `OutputState`：只有 `WorkMode == MANUAL(4)` 时手机端可编辑；
- 状态字段仍然显示，不把“禁止远控”错误解释为“节点离线”。

非本项目主机模型不会启用该门禁，保持 ESP RainMaker 原有设备行为。

---

### APP-HOSTCFG-005：WorkMode 本地化显示但保持整数协议

文件：

`app/src/main/java/com/espressif/ui/widgets/EspDropDown.java`

实现方式：

- 只识别名称为 `WorkMode` 的下拉框；
- Spinner 内部 `getItem()` 仍返回协议字符串 `"1"`～`"4"`；
- 仅 `getView()` / `getDropDownView()` 显示本地化文本；
- 现有 `ParamAdapter` 仍执行 `Integer.parseInt(newValue)`，因此写入主机的仍是稳定整数协议。

显示：

```text
1 -> 蓄水 / Fill
2 -> 排水 / Drain
3 -> 定时 / Timer
4 -> 手动 / Manual
```

已经核对 `item_param.xml`：`card_spinner` 与 `tv_spinner_name` 位于同一个 `rl_card_drop_down` 容器，当前识别方式可以命中，无需增加额外祖先遍历逻辑。

---

### APP-HOSTCFG-006：液位阈值改为单次确认式写入

原 RainMaker 通用 Slider 在启用 continuous update 时会产生连续中间值，不适合 `LowThreshold < HighThreshold` 这种关联配置。

本轮采用最小改动：

- `LowThreshold` / `HighThreshold` 临时投影为 `UI_TYPE_TEXT`；
- 用户点击 Edit，输入值并确认后只发送一次；
- 不再在拖动过程中连续发送中间阈值；
- 根据当前主机权威值动态收紧输入边界：

```text
LowThreshold  : 0 .. HighThreshold - 1
HighThreshold : LowThreshold + 1 .. 100
```

如果当前主机上报的阈值本身缺失或非法，App 先进入只读，等待主机重新上报有效权威值。

说明：

当前实现是“两个字段分别单次确认提交”，不是新建一个 Low/High 双字段 Draft/Apply 卡片。主机端计划已经按单 Param 写入时与另一当前值合并后原子校验，因此该方案可以完成第一阶段闭环，同时避免为了一个配置组重做通用 ParamAdapter。

如后续 UX 验收明确要求“一次同时编辑 Low+High 再 Apply”，再增加专用组合控件；当前不提前过度设计。

---

### APP-HOSTCFG-007：Boolean 权限状态读取修正

问题：

BLE 参数刷新链可能只更新 Boolean Param 的 `switchStatus`；若 App 优先读取历史 `labelValue`，存在使用旧文本覆盖新 Boolean 真值的风险。

修正：

- `bool` / `boolean` Param 始终优先使用 `switchStatus`；
- 非 Boolean 类型才尝试解析 `labelValue`；
- 去除 `android.text.TextUtils` 依赖，使策略逻辑可以在本地 JVM 单元测试直接执行。

---

### APP-HOSTCFG-008：中英文资源

新增：

- `app/src/main/res/values/strings_host_configuration.xml`
- `app/src/main/res/values-zh-rCN/strings_host_configuration.xml`

当前实际使用 WorkMode 中英文显示资源；其余权限/校验提示文案作为后续统一写请求入口接入时复用，避免散落硬编码字符串。

---

### APP-HOSTCFG-009：新增策略单元测试

文件：

`app/src/test/java/com/espressif/ui/hostconfig/HostConfigurationPolicyTest.java`

覆盖：

1. 非主机设备不受影响；
2. `RemoteControlEnabled=false` 时所有主机写 UI 关闭；
3. `CloudOnline=false` 时写 UI 关闭；
4. 两者为 true 时 WorkMode/阈值恢复可写；
5. `OutputState` 仅 MANUAL 可写；
6. Low/High 转为一次确认式编辑，并生成互相约束的有效边界；
7. 主机当前阈值非法时 fail-closed；
8. WorkMode 1～4 稳定协议值；
9. 写预检能识别非 MANUAL 输出和非法阈值；
10. Boolean `switchStatus` 优先于可能过期的 `labelValue`。

---

## 3. 当前尚未声称完成的内容

### 3.1 统一写请求二次门禁

`HostConfigurationPolicy.evaluateWriteRequest()` 已实现纯逻辑规则，但本轮尝试把它直接接入 `DeviceParamUpdates` 的统一发送入口时，GitHub 连接器对该次大范围文件写入进行了安全拦截，因此没有把该次修改强行落库，也没有绕过检查。

当前已有的保护：

1. UI 每次从 `EspNode.getDevices()` 读取时都会重算有效 WRITE；
2. 禁止远控时通用控件没有写监听/编辑入口；
3. `OutputState` 非 MANUAL 时同样关闭写入口；
4. 主机 AppCore 仍执行最终权限和参数安全校验。

待后续采用更小的独立提交把 `evaluateWriteRequest()` 接到最终发送入口，消除“UI 状态刷新与已经排队请求之间”的极小竞态窗口。

状态：**待确认 / 未完成，不记为本轮已实现。**

### 3.2 写入 ACK 后立即强制权威回读

当前继续复用应用已有的 Param 更新/轮询/EventBus 刷新链，使主机最终值回写到 UI。

计划中更严格的：

```text
写请求 -> 主机接受 -> 立即重新 getParamsValues -> UI 收敛到权威值
```

尚未独立接入。

状态：**待确认 / 后续小步实现。**

### 3.3 Low/High 双字段 Draft/Apply 组合控件

当前已经避免连续 Slider 写入，并保证单次输入范围不会主动产生明显非法组合；没有新增专用双字段卡片。

状态：**第一阶段功能可用，专用组合 UX 为可选增强。**

---

## 4. 测试顺序

建议按以下顺序验证：

### 4.1 JVM 单元测试

```bash
./gradlew :app:testDebugUnitTest --tests "com.espressif.ui.hostconfig.HostConfigurationPolicyTest"
```

### 4.2 Android 编译

```bash
./gradlew :app:assembleDebug
```

### 4.3 App + 主机联调

1. 主机 `RemoteControlEnabled=false`：
   - WorkMode 只读；
   - OutputState 只读；
   - Low/High 只读；
   - 状态仍可查看。
2. 主机本机开启远控后：
   - App 刷新后上述可写项恢复；
   - `RemoteControlEnabled` 本身仍无手机编辑入口。
3. WorkMode：
   - App 显示蓄水/排水/定时/手动；
   - 主机收到值仍为 1/2/3/4。
4. OutputState：
   - 非 MANUAL 不可编辑；
   - MANUAL 才出现写入口。
5. 阈值：
   - Low 最大值为当前 High-1；
   - High 最小值为当前 Low+1；
   - 每次 Edit/OK 只提交最终值。
6. 主机本机修改 WorkMode/阈值：
   - App 下一次参数刷新后应显示主机新值；
   - 手机本地不保留第二份配置真值。

---

## 5. 风险与回滚

本轮没有修改：

- 首次 BLE 配网流程；
- Wi-Fi 复用流程；
- RainMaker Cloud/Local/BLE 路由优先级；
- `EspDeviceActivity` 页面架构；
- `ParamAdapter` 主体；
- 主机端业务代码。

如需要回滚，删除 `HostConfigurationPolicy` 并恢复 `Param.java`、`EspNode.java`、`EspDropDown.java` 即可恢复上游通用 Param UI 行为。

---

## 6. 本轮结论

当前实现已经完成第一阶段手机端的主要展示与 UI 权限闭环：

```text
主机权威 Param
    ↓
EspNode / Device / Param
    ↓
HostConfigurationPolicy
    ├─ RemoteControl / CloudOnline 门禁
    ├─ WorkMode 稳定值 + 本地化显示
    ├─ OutputState MANUAL 门禁
    └─ Low/High 单次确认 + 关联有效范围
    ↓
现有 ParamAdapter
```

剩余工作明确收敛为两个小边界：

1. 把已有 `evaluateWriteRequest()` 接到最终发送入口做二次门禁；
2. 写成功后增加一次有超时的权威 Param 回读。

这两项不需要重做 UI 架构，也不需要新增业务状态仓库。
